package com.ledgerflow.app.widget

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.ledgerflow.app.data.local.LedgerDatabase
import com.ledgerflow.app.data.local.ShoppingListEntity
import com.ledgerflow.app.domain.MoneyFormatter
import com.ledgerflow.app.domain.ParsedShoppingItem
import com.ledgerflow.app.domain.ShoppingUnitCatalog
import com.ledgerflow.app.domain.VoiceShoppingParser
import com.ledgerflow.app.security.VaultStore
import com.ledgerflow.app.ui.theme.LedgerFlowTheme
import com.ledgerflow.app.ui.theme.LedgerPalette
import com.ledgerflow.app.ui.theme.resolveLedgerPalette
import com.ledgerflow.app.work.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Floating transparent overlay launched from the Shopping List widget for structured
 * Quick Item addition (Name, Qty, Unit, Est. Price) OR multi-item Voice List capture.
 */
class VoiceShoppingOverlayActivity : ComponentActivity() {
    private val saveInFlight = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )

        val targetListId = intent?.getStringExtra(EXTRA_LIST_ID)?.trim()?.takeIf { it.isNotEmpty() }
        val initialMode = intent?.getStringExtra(EXTRA_INITIAL_MODE)?.takeIf { it == MODE_MANUAL } ?: MODE_VOICE

        setContent {
            val appCtx = applicationContext
            val vault = remember { VaultStore(appCtx).read() }
            val palette = resolveLedgerPalette(
                themeMode = vault.themeMode,
                systemDark = LedgerWidgetCoordinator.isSystemDarkTheme(this),
            )

            LedgerFlowTheme(palette = palette) {
                ShoppingQuickOverlayCard(
                    palette = palette,
                    baseCurrency = vault.baseCurrency,
                    preferredListId = targetListId,
                    initialMode = initialMode,
                    onDismiss = { finishWithoutAnimation() },
                    onSaveVoiceItems = { parsedItems, resolvedListId ->
                        if (!saveInFlight.compareAndSet(false, true)) return@ShoppingQuickOverlayCard
                        VoiceSaveHelper.launchSaveVoice(
                            appContext = appCtx,
                            targetListId = resolvedListId,
                            items = parsedItems,
                            onComplete = { finishWithoutAnimation() },
                        )
                    },
                    onSaveManualItem = { resolvedListId, name, qtyVal, qtyUnit, priceMinor ->
                        if (!saveInFlight.compareAndSet(false, true)) return@ShoppingQuickOverlayCard
                        VoiceSaveHelper.launchSaveManual(
                            appContext = appCtx,
                            targetListId = resolvedListId,
                            name = name,
                            qtyVal = qtyVal,
                            qtyUnit = qtyUnit,
                            priceMinor = priceMinor,
                            onComplete = { finishWithoutAnimation() },
                        )
                    },
                )
            }
        }
    }

    private fun finishWithoutAnimation() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        const val EXTRA_LIST_ID = "extra_list_id"
        const val EXTRA_INITIAL_MODE = "extra_initial_mode"
        const val MODE_VOICE = "VOICE"
        const val MODE_MANUAL = "MANUAL"

        fun newIntent(
            context: Context,
            listId: String? = null,
            initialMode: String = MODE_VOICE,
        ): Intent = Intent(context, VoiceShoppingOverlayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            if (!listId.isNullOrBlank()) putExtra(EXTRA_LIST_ID, listId)
            putExtra(EXTRA_INITIAL_MODE, initialMode)
        }
    }
}

private object VoiceSaveHelper {
    fun launchSaveVoice(
        appContext: Context,
        targetListId: String?,
        items: List<ParsedShoppingItem>,
        onComplete: () -> Unit,
    ) {
        kotlinx.coroutines.MainScope().launch {
            val outcome = withContext(Dispatchers.IO) {
                val db = LedgerDatabase.getInstance(appContext)
                val saved = db.addParsedShoppingItemsAtomic(
                    listId = targetListId,
                    items = items,
                    defaultListTitle = "Shopping List",
                )
                runCatching { SyncWorker.enqueueWhenOnline(appContext) }
                LedgerWidgetCoordinator.refreshAllWidgetsNow(appContext)
                saved
            }
            val msg = when {
                outcome.addedCount > 0 -> "Added ${outcome.addedCount} item(s) to ${outcome.listTitle}"
                outcome.skippedDuplicateCount > 0 -> "Items already exist in ${outcome.listTitle}"
                else -> "No new items added"
            }
            Toast.makeText(appContext, msg, Toast.LENGTH_SHORT).show()
            onComplete()
        }
    }

    fun launchSaveManual(
        appContext: Context,
        targetListId: String?,
        name: String,
        qtyVal: Double?,
        qtyUnit: String,
        priceMinor: Long,
        onComplete: () -> Unit,
    ) {
        kotlinx.coroutines.MainScope().launch {
            val addedTitle = withContext(Dispatchers.IO) {
                val db = LedgerDatabase.getInstance(appContext)
                val now = System.currentTimeMillis()
                val resolvedList = targetListId?.let { id -> db.shoppingListDao().getById(id)?.takeIf { it.deletedAtEpochMs == null } }
                    ?: db.shoppingListDao().getMostRecentActive()
                    ?: ShoppingListEntity(
                        id = UUID.randomUUID().toString(),
                        title = "Shopping List",
                        createdAtEpochMs = now,
                        updatedAtEpochMs = now,
                        pendingSync = true,
                    ).also { db.shoppingListDao().upsert(it) }
                db.addShoppingItemAtomic(
                    listId = resolvedList.id,
                    name = name,
                    quantityValue = qtyVal,
                    quantityUnit = qtyUnit,
                    estimatedPriceMinor = priceMinor,
                    nowMs = now,
                )
                runCatching { SyncWorker.enqueueWhenOnline(appContext) }
                LedgerWidgetCoordinator.refreshAllWidgetsNow(appContext)
                resolvedList.title
            }
            Toast.makeText(appContext, "Added \"$name\" to $addedTitle", Toast.LENGTH_SHORT).show()
            onComplete()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShoppingQuickOverlayCard(
    palette: LedgerPalette,
    baseCurrency: String,
    preferredListId: String?,
    initialMode: String,
    onDismiss: () -> Unit,
    onSaveVoiceItems: (items: List<ParsedShoppingItem>, targetListId: String?) -> Unit,
    onSaveManualItem: (targetListId: String?, name: String, qtyVal: Double?, qtyUnit: String, priceMinor: Long) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf(initialMode) }
    var activeListTitle by remember { mutableStateOf("Shopping List") }
    var activeListId by remember { mutableStateOf<String?>(preferredListId) }
    var willCreateNewList by remember { mutableStateOf(false) }

    // Manual Item State
    var itemName by remember { mutableStateOf("") }
    var qtyText by remember { mutableStateOf("") }
    var selectedUnit by remember { mutableStateOf("") }
    var customUnitText by remember { mutableStateOf("") }
    var priceText by remember { mutableStateOf("") }

    val (parsedQty, qtyError) = remember(qtyText) { ShoppingUnitCatalog.validateQuantityInput(qtyText) }
    val resolvedUnit = when (selectedUnit) {
        ShoppingUnitCatalog.UNIT_CUSTOM_SENTINEL -> customUnitText.trim()
        else -> selectedUnit.trim()
    }
    val parsedPriceMinor = remember(priceText, baseCurrency) {
        if (priceText.isBlank()) 0L else (MoneyFormatter.parseDecimalToMinor(priceText, baseCurrency) ?: -1L)
    }

    // Voice State
    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var speechAvailable by remember { mutableStateOf(false) }
    var isListening by remember { mutableStateOf(false) }
    var transcript by remember { mutableStateOf("") }
    var statusLabel by remember { mutableStateOf("Tap Mic to speak items like \"2 kg atta, milk\"") }
    var saving by remember { mutableStateOf(false) }
    val recognizerHolder = remember { arrayOfNulls<SpeechRecognizer>(1) }

    val startListening: () -> Unit = {
        val sr = recognizerHolder[0]
        if (sr != null && speechAvailable && hasAudioPermission) {
            val speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            }
            runCatching {
                sr.cancel()
                sr.startListening(speechIntent)
                isListening = true
                statusLabel = "Listening... Say items naturally"
            }.onFailure {
                isListening = false
                statusLabel = "Voice engine unavailable right now — type below"
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasAudioPermission = granted
        if (granted && speechAvailable && mode == VoiceShoppingOverlayActivity.MODE_VOICE) {
            startListening()
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val db = LedgerDatabase.getInstance(context.applicationContext)
            val resolved = preferredListId?.let { id -> db.shoppingListDao().getById(id)?.takeIf { it.deletedAtEpochMs == null } }
                ?: db.shoppingListDao().getMostRecentActive()
            withContext(Dispatchers.Main) {
                if (resolved != null) {
                    activeListId = resolved.id
                    activeListTitle = resolved.title
                    willCreateNewList = false
                } else {
                    activeListId = null
                    activeListTitle = "Shopping List"
                    willCreateNewList = true
                }
            }
        }

        val available = runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)
        speechAvailable = available
        if (available) {
            val sr = SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListening = true
                    statusLabel = "Listening... Speak naturally"
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    isListening = false
                    statusLabel = "Processing speech..."
                }
                override fun onError(error: Int) {
                    isListening = false
                    statusLabel = "Tap Mic to retry or edit items below."
                }
                override fun onResults(results: Bundle?) {
                    isListening = false
                    val top = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                    if (top.isNotBlank()) {
                        transcript = if (transcript.isBlank()) top else "$transcript, $top"
                        statusLabel = "Ready — tap Add Items to List"
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                    if (partial.isNotBlank()) statusLabel = "\"$partial\""
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            recognizerHolder[0] = sr
        }
        if (initialMode == VoiceShoppingOverlayActivity.MODE_VOICE) {
            if (!hasAudioPermission) permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            else if (available) startListening()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { recognizerHolder[0]?.destroy() }
            recognizerHolder[0] = null
        }
    }

    val parsedVoiceItems = remember(transcript) { VoiceShoppingParser.parse(transcript) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.44f))
            .imePadding()
            .navigationBarsPadding()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 14.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            shape = RoundedCornerShape(24.dp),
            color = palette.surface,
            border = BorderStroke(1.dp, palette.border),
            shadowElevation = 12.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Quick Checklist Entry", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = palette.ink)
                        Text(
                            text = if (willCreateNewList) "Creates list: $activeListTitle" else "List: $activeListTitle",
                            fontSize = 11.sp,
                            color = palette.emeraldPrimary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close", tint = palette.muted, modifier = Modifier.size(18.dp))
                    }
                }

                // Mode Switch: Quick Item vs Voice List
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        VoiceShoppingOverlayActivity.MODE_MANUAL to "Quick Item",
                        VoiceShoppingOverlayActivity.MODE_VOICE to "Voice List",
                    ).forEach { (key, label) ->
                        val active = mode == key
                        FilterChip(
                            selected = active,
                            onClick = {
                                mode = key
                                if (key == VoiceShoppingOverlayActivity.MODE_VOICE && hasAudioPermission && speechAvailable && !isListening) {
                                    startListening()
                                }
                            },
                            label = { Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = palette.emeraldSoft,
                                selectedLabelColor = palette.emeraldPrimary,
                                labelColor = palette.muted,
                            ),
                        )
                    }
                }

                if (mode == VoiceShoppingOverlayActivity.MODE_MANUAL) {
                    OutlinedTextField(
                        value = itemName,
                        onValueChange = { itemName = it },
                        label = { Text("Item Name") },
                        placeholder = { Text("Milk, Bread, Rice...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = qtyText,
                            onValueChange = { qtyText = it.filter { ch -> ch.isDigit() || ch == '.' } },
                            label = { Text("Qty") },
                            placeholder = { Text("2") },
                            isError = qtyError != null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(0.9f),
                            shape = RoundedCornerShape(12.dp),
                        )
                        OutlinedTextField(
                            value = priceText,
                            onValueChange = { priceText = MoneyFormatter.sanitizeDecimalInput(it, baseCurrency, allowNegative = false) },
                            label = { Text("Est. Price ($baseCurrency)") },
                            placeholder = { Text("0") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1.3f),
                            shape = RoundedCornerShape(12.dp),
                        )
                    }
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf("kg", "g", "litre", "ml", "piece", "packet", "dozen", ShoppingUnitCatalog.UNIT_CUSTOM_SENTINEL).forEach { unit ->
                            val active = selectedUnit == unit
                            FilterChip(
                                selected = active,
                                onClick = { selectedUnit = if (active && unit != ShoppingUnitCatalog.UNIT_CUSTOM_SENTINEL) "" else unit },
                                label = { Text(if (unit == ShoppingUnitCatalog.UNIT_CUSTOM_SENTINEL) "Custom" else unit, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = palette.emeraldSoft,
                                    selectedLabelColor = palette.emeraldPrimary,
                                ),
                            )
                        }
                    }
                    AnimatedVisibility(visible = selectedUnit == ShoppingUnitCatalog.UNIT_CUSTOM_SENTINEL) {
                        OutlinedTextField(
                            value = customUnitText,
                            onValueChange = { customUnitText = it },
                            label = { Text("Custom Unit") },
                            placeholder = { Text("jar, tray, pack...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                        )
                    }
                    if (qtyError != null) {
                        Text(qtyError, fontSize = 11.sp, color = palette.roseOutflow)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text("Cancel", color = palette.muted) }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            enabled = !saving && itemName.isNotBlank() && qtyError == null && parsedPriceMinor >= 0L,
                            onClick = {
                                saving = true
                                scope.launch {
                                    onSaveManualItem(activeListId, itemName.trim(), parsedQty, resolvedUnit, parsedPriceMinor.coerceAtLeast(0L))
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = palette.emeraldPrimary),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("Add Item", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = palette.tint,
                        border = BorderStroke(1.dp, if (isListening) palette.emeraldPrimary else palette.border),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Surface(
                                onClick = {
                                    if (!hasAudioPermission) permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    else if (isListening) {
                                        runCatching { recognizerHolder[0]?.stopListening() }
                                        isListening = false
                                    } else if (speechAvailable) startListening()
                                },
                                shape = CircleShape,
                                color = if (isListening) palette.emeraldPrimary else palette.emeraldSoft,
                                modifier = Modifier.size(42.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (hasAudioPermission) Icons.Outlined.Mic else Icons.Outlined.MicOff,
                                        contentDescription = "Microphone",
                                        tint = if (isListening) Color.White else palette.emeraldPrimary,
                                    )
                                }
                            }
                            Text(text = statusLabel, fontSize = 12.sp, color = palette.ink, modifier = Modifier.weight(1f))
                        }
                    }

                    OutlinedTextField(
                        value = transcript,
                        onValueChange = { transcript = it },
                        label = { Text("Items (comma or 'and' separated)", fontSize = 12.sp) },
                        placeholder = { Text("2 kg atta, milk, 500g paneer") },
                        maxLines = 3,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            if (!saving && parsedVoiceItems.isNotEmpty()) {
                                saving = true
                                scope.launch { onSaveVoiceItems(parsedVoiceItems, activeListId) }
                            }
                        }),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    )

                    if (parsedVoiceItems.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            parsedVoiceItems.forEach { item ->
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = palette.emeraldSoft,
                                    border = BorderStroke(1.dp, palette.emeraldPrimary.copy(alpha = 0.3f)),
                                ) {
                                    Text(
                                        text = "${item.name} — ${item.quantityLabel}",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = palette.emeraldPrimary,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    )
                                }
                            }
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        OutlinedButton(
                            onClick = {
                                if (!hasAudioPermission) permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                else if (speechAvailable) startListening()
                            },
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("Mic", fontSize = 12.sp, color = palette.ink)
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            enabled = !saving && parsedVoiceItems.isNotEmpty(),
                            onClick = {
                                saving = true
                                runCatching { recognizerHolder[0]?.stopListening() }
                                scope.launch { onSaveVoiceItems(parsedVoiceItems, activeListId) }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = palette.emeraldPrimary),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("Add to List", fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            }
        }
    }
}
