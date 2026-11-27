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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.ledgerflow.app.data.local.LedgerDatabase
import com.ledgerflow.app.domain.ParsedShoppingItem
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Floating transparent overlay activity launched from Widget A/B to capture
 * spoken or typed shopping list items directly into the active Room checklist.
 */
class VoiceShoppingOverlayActivity : ComponentActivity() {
    private val saveInFlight = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )

        setContent {
            val appCtx = applicationContext
            val vault = remember { VaultStore(appCtx).read() }
            val palette = resolveLedgerPalette(
                themeMode = vault.themeMode,
                systemDark = LedgerWidgetCoordinator.isSystemDarkTheme(this),
            )

            LedgerFlowTheme(palette = palette) {
                VoiceShoppingOverlayCard(
                    palette = palette,
                    onDismiss = { finishWithoutAnimation() },
                    onSaveItems = { parsedItems, targetListId ->
                        if (!saveInFlight.compareAndSet(false, true)) return@VoiceShoppingOverlayCard
                        VoiceSaveHelper.launchSave(
                            appContext = appCtx,
                            targetListId = targetListId,
                            items = parsedItems,
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
        fun newIntent(context: Context): Intent =
            Intent(context, VoiceShoppingOverlayActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
    }
}

private object VoiceSaveHelper {
    fun launchSave(
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
                outcome.addedCount > 0 && outcome.skippedDuplicateCount > 0 ->
                    "Added ${outcome.addedCount} item(s) to ${outcome.listTitle} (${outcome.skippedDuplicateCount} already in list)"
                outcome.addedCount > 0 ->
                    "Added ${outcome.addedCount} item(s) to ${outcome.listTitle}"
                outcome.skippedDuplicateCount > 0 ->
                    "Items already exist in ${outcome.listTitle}"
                else ->
                    "No new items to add"
            }
            Toast.makeText(appContext, msg, Toast.LENGTH_SHORT).show()
            onComplete()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoiceShoppingOverlayCard(
    palette: LedgerPalette,
    onDismiss: () -> Unit,
    onSaveItems: (items: List<ParsedShoppingItem>, targetListId: String?) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var activeListTitle by remember { mutableStateOf("Shopping List") }
    var activeListId by remember { mutableStateOf<String?>(null) }
    var willCreateNewList by remember { mutableStateOf(false) }

    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var speechAvailable by remember { mutableStateOf(false) }
    var isListening by remember { mutableStateOf(false) }
    var transcript by remember { mutableStateOf("") }
    var statusLabel by remember { mutableStateOf("Preparing microphone...") }
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
                statusLabel = "Listening... Say items like \"2 kg atta, milk, 500g paneer\""
            }.onFailure {
                isListening = false
                statusLabel = "Voice engine unavailable right now — type items below"
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasAudioPermission = granted
        if (granted) {
            if (speechAvailable) {
                startListening()
            } else {
                statusLabel = "Voice engine unavailable on device — type items below"
            }
        } else {
            statusLabel = "Microphone denied — grant permission or type items below"
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val db = LedgerDatabase.getInstance(context.applicationContext)
            val activeList = db.shoppingListDao().getMostRecentActive()
            withContext(Dispatchers.Main) {
                if (activeList != null) {
                    activeListId = activeList.id
                    activeListTitle = activeList.title
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
                    statusLabel = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that. Tap Mic to retry or edit text below."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required. Tap Grant Mic or type below."
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Voice network unavailable. Type items below instead."
                        else -> "Voice capture stopped. Tap Mic to retry or edit text below."
                    }
                }

                override fun onResults(results: Bundle?) {
                    isListening = false
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val top = matches?.firstOrNull()?.trim().orEmpty()
                    if (top.isNotBlank()) {
                        transcript = if (transcript.isBlank()) top else "$transcript, $top"
                        statusLabel = "Parsed ready — tap Add to List or Mic to append more"
                    } else {
                        statusLabel = "No speech detected. Tap Mic to retry or type below."
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val partial = matches?.firstOrNull()?.trim().orEmpty()
                    if (partial.isNotBlank()) {
                        statusLabel = "\"$partial\""
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            recognizerHolder[0] = sr
        }

        if (!hasAudioPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else if (available) {
            startListening()
        } else {
            statusLabel = "Voice service unavailable — type items below"
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { recognizerHolder[0]?.destroy() }
            recognizerHolder[0] = null
        }
    }

    val parsedItems = remember(transcript) { VoiceShoppingParser.parse(transcript) }
    val trySave: () -> Unit = {
        if (!saving && parsedItems.isNotEmpty()) {
            saving = true
            runCatching { recognizerHolder[0]?.stopListening() }
            scope.launch {
                onSaveItems(parsedItems, activeListId)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.42f))
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
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Voice Shopping List",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = palette.ink,
                        )
                        Text(
                            text = if (willCreateNewList) {
                                "Will create new list: $activeListTitle"
                            } else {
                                "Adding to: $activeListTitle"
                            },
                            fontSize = 11.sp,
                            color = palette.emeraldPrimary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Close",
                            tint = palette.muted,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                // Mic status strip
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = palette.tint,
                    border = BorderStroke(
                        1.dp,
                        if (isListening) palette.emeraldPrimary else palette.border,
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Surface(
                            onClick = {
                                if (!hasAudioPermission) {
                                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                } else if (isListening) {
                                    runCatching { recognizerHolder[0]?.stopListening() }
                                    isListening = false
                                    statusLabel = "Paused — edit items or tap Mic again"
                                } else if (speechAvailable) {
                                    startListening()
                                }
                            },
                            shape = CircleShape,
                            color = if (isListening) palette.emeraldPrimary else palette.emeraldSoft,
                            modifier = Modifier.size(44.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (hasAudioPermission) Icons.Outlined.Mic else Icons.Outlined.MicOff,
                                    contentDescription = "Microphone",
                                    tint = if (isListening) Color.White else palette.emeraldPrimary,
                                )
                            }
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = statusLabel,
                                fontSize = 12.sp,
                                color = palette.ink,
                            )
                            if (!hasAudioPermission) {
                                TextButton(
                                    onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                                ) {
                                    Text("Grant Microphone", fontSize = 11.sp, color = palette.emeraldPrimary)
                                }
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = transcript,
                    onValueChange = { transcript = it },
                    label = { Text("Items (comma or 'and' separated)", fontSize = 12.sp) },
                    placeholder = { Text("2 kg atta, milk, 500g paneer") },
                    maxLines = 3,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { trySave() }),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                )

                if (parsedItems.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "PARSED ITEMS (${parsedItems.size})",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.6.sp,
                            color = palette.muted,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            parsedItems.forEach { item ->
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
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("Cancel", color = palette.muted)
                    }
                    Spacer(Modifier.size(8.dp))
                    Button(
                        onClick = trySave,
                        enabled = !saving && parsedItems.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.emeraldPrimary),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            text = if (saving) "Adding..." else "Add to List (${parsedItems.size})",
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        )
                    }
                }
            }
        }
    }
}
