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
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.ledgerflow.app.data.local.AccountEntity
import com.ledgerflow.app.data.local.CategoryEntity
import com.ledgerflow.app.data.local.LedgerDatabase
import com.ledgerflow.app.data.local.SeedData
import com.ledgerflow.app.data.local.TransactionEntity
import com.ledgerflow.app.domain.AccountTypeCatalog
import com.ledgerflow.app.domain.CategoryTree
import com.ledgerflow.app.domain.FxConverter
import com.ledgerflow.app.domain.MoneyFormatter
import com.ledgerflow.app.domain.WidgetMetricsCalculator
import com.ledgerflow.app.security.VaultStore
import com.ledgerflow.app.ui.components.HierarchicalCategoryPickerSheet
import com.ledgerflow.app.ui.theme.LedgerFlowTheme
import com.ledgerflow.app.ui.theme.LedgerPalette
import com.ledgerflow.app.ui.theme.TabularStyle
import com.ledgerflow.app.ui.theme.resolveLedgerPalette
import com.ledgerflow.app.work.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Floating transparent overlay activity launched from LedgerFlow widgets for
 * instant Expense/Income logging, optional Voice Expense parsing, and full hierarchical category selection.
 */
class QuickTransactionOverlayActivity : ComponentActivity() {
    private val saveInFlight = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )

        val initialType = normalizeTxType(intent?.getStringExtra(EXTRA_TX_TYPE))
        val prefillMinor = intent?.getLongExtra(EXTRA_PREFILL_AMOUNT_MINOR, 0L) ?: 0L
        val prefillCurrency = intent?.getStringExtra(EXTRA_PREFILL_CURRENCY)?.takeIf { it.length == 3 }
        val prefillMerchant = intent?.getStringExtra(EXTRA_PREFILL_MERCHANT).orEmpty()
        val prefillNote = intent?.getStringExtra(EXTRA_PREFILL_NOTE).orEmpty()
        val prefillSourceTag = intent?.getStringExtra(EXTRA_PREFILL_SOURCE_TAG)?.takeIf { it.isNotBlank() } ?: "MANUAL"
        val startVoice = intent?.getBooleanExtra(EXTRA_START_VOICE, false) ?: false

        setContent {
            val appCtx = applicationContext
            val vaultStore = remember { VaultStore(appCtx) }
            val vault = remember { vaultStore.read() }
            val baseCurrency = remember { (prefillCurrency ?: vault.baseCurrency).uppercase() }
            val palette = resolveLedgerPalette(
                themeMode = vault.themeMode,
                systemDark = LedgerWidgetCoordinator.isSystemDarkTheme(this),
            )

            LedgerFlowTheme(palette = palette) {
                QuickTransactionOverlayCard(
                    palette = palette,
                    initialType = initialType,
                    baseCurrency = vault.baseCurrency,
                    entryCurrency = baseCurrency,
                    prefillMinor = prefillMinor,
                    prefillMerchant = prefillMerchant,
                    prefillNote = prefillNote,
                    startVoiceOnOpen = startVoice,
                    onDismiss = { finishWithoutAnimation() },
                    onSave = { selectedType, account, category, amountMinor, noteInput ->
                        if (!saveInFlight.compareAndSet(false, true)) return@QuickTransactionOverlayCard
                        val now = System.currentTimeMillis()
                        val signedMinor = if (selectedType == "INCOME") amountMinor else -amountMinor
                        val convertedMinor = if (!account.currency.equals(vault.baseCurrency, ignoreCase = true)) {
                            FxConverter.convertMinor(signedMinor, account.currency, vault.baseCurrency)
                        } else null

                        val cleanNote = noteInput.trim()
                        val merchantVal = prefillMerchant.trim().ifBlank { cleanNote }
                        val noteVal = if (prefillMerchant.isNotBlank()) cleanNote else ""
                        val reviewStatus = if (category != null) {
                            TransactionEntity.STATUS_CONFIRMED
                        } else {
                            TransactionEntity.STATUS_PENDING_REVIEW
                        }

                        val tx = TransactionEntity(
                            id = UUID.randomUUID().toString(),
                            accountId = account.id,
                            categoryId = category?.id,
                            categoryIdsCsv = category?.id.orEmpty(),
                            txType = selectedType,
                            amountMinor = signedMinor,
                            currency = account.currency,
                            convertedMinor = convertedMinor,
                            convertedCurrency = if (convertedMinor != null) vault.baseCurrency else null,
                            merchant = merchantVal,
                            note = noteVal,
                            sourceTag = prefillSourceTag,
                            occurredAtEpochMs = now,
                            createdAtEpochMs = now,
                            updatedAtEpochMs = now,
                            pendingSync = true,
                            reviewStatus = reviewStatus,
                        )

                        val db = LedgerDatabase.getInstance(appCtx)
                        CoroutineScopeSaveHelper.launchSave(
                            db = db,
                            appContext = appCtx,
                            tx = tx,
                            toastMessage = buildSavedToast(
                                amountMinor = amountMinor,
                                currency = account.currency,
                                categoryName = category?.name,
                            ),
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

    private fun buildSavedToast(amountMinor: Long, currency: String, categoryName: String?): String {
        val amountStr = MoneyFormatter.formatMinor(amountMinor, currency, masked = false)
        val suffix = categoryName?.takeIf { it.isNotBlank() } ?: "Pending Review"
        return "Saved $amountStr • $suffix"
    }

    companion object {
        const val EXTRA_TX_TYPE = "extra_tx_type"
        const val EXTRA_PREFILL_AMOUNT_MINOR = "extra_prefill_amount_minor"
        const val EXTRA_PREFILL_CURRENCY = "extra_prefill_currency"
        const val EXTRA_PREFILL_MERCHANT = "extra_prefill_merchant"
        const val EXTRA_PREFILL_NOTE = "extra_prefill_note"
        const val EXTRA_PREFILL_SOURCE_TAG = "extra_prefill_source_tag"
        const val EXTRA_START_VOICE = "extra_start_voice"

        fun normalizeTxType(raw: String?): String =
            if (raw.equals("INCOME", ignoreCase = true)) "INCOME" else "EXPENSE"

        fun newIntent(
            context: Context,
            txType: String = "EXPENSE",
            prefillAmountMinor: Long = 0L,
            prefillCurrency: String? = null,
            prefillMerchant: String? = null,
            prefillNote: String? = null,
            prefillSourceTag: String = "MANUAL",
            startVoice: Boolean = false,
        ): Intent = Intent(context, QuickTransactionOverlayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            putExtra(EXTRA_TX_TYPE, normalizeTxType(txType))
            if (prefillAmountMinor > 0L) putExtra(EXTRA_PREFILL_AMOUNT_MINOR, prefillAmountMinor)
            if (!prefillCurrency.isNullOrBlank()) putExtra(EXTRA_PREFILL_CURRENCY, prefillCurrency)
            if (!prefillMerchant.isNullOrBlank()) putExtra(EXTRA_PREFILL_MERCHANT, prefillMerchant)
            if (!prefillNote.isNullOrBlank()) putExtra(EXTRA_PREFILL_NOTE, prefillNote)
            putExtra(EXTRA_PREFILL_SOURCE_TAG, prefillSourceTag)
            putExtra(EXTRA_START_VOICE, startVoice)
        }
    }
}

private object CoroutineScopeSaveHelper {
    fun launchSave(
        db: LedgerDatabase,
        appContext: Context,
        tx: TransactionEntity,
        toastMessage: String,
        onComplete: () -> Unit,
    ) {
        kotlinx.coroutines.MainScope().launch {
            withContext(Dispatchers.IO) {
                db.recordTransactionAtomic(tx)
                runCatching { SyncWorker.enqueueWhenOnline(appContext) }
                LedgerWidgetCoordinator.refreshAllWidgetsNow(appContext)
            }
            Toast.makeText(appContext, toastMessage, Toast.LENGTH_SHORT).show()
            onComplete()
        }
    }
}

private fun parseSpokenTransaction(spoken: String): Pair<String, String> {
    val clean = spoken.trim()
    if (clean.isBlank()) return "" to ""
    val numMatch = Regex("""(?:₹|rs\.?|inr|\$)?\s*(\d+(?:,\d{3})*(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE).find(clean)
    val amountStr = numMatch?.groupValues?.getOrNull(1)?.replace(",", "").orEmpty()
    val noteStr = if (numMatch != null) {
        clean.removeRange(numMatch.range)
            .replace(Regex("""^(?:for|on|at|to|spent|paid|got|received)\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+(?:rupees?|bucks?|inr)\b""", RegexOption.IGNORE_CASE), "")
            .trim()
            .trim(',', '-', ' ')
    } else clean
    return amountStr to noteStr
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickTransactionOverlayCard(
    palette: LedgerPalette,
    initialType: String,
    baseCurrency: String,
    entryCurrency: String,
    prefillMinor: Long,
    prefillMerchant: String,
    prefillNote: String,
    startVoiceOnOpen: Boolean,
    onDismiss: () -> Unit,
    onSave: (txType: String, account: AccountEntity, category: CategoryEntity?, amountMinor: Long, note: String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    var isLoadingData by remember { mutableStateOf(true) }
    var accounts by remember { mutableStateOf<List<AccountEntity>>(emptyList()) }
    var allCategories by remember { mutableStateOf<List<CategoryEntity>>(emptyList()) }
    var allTransactions by remember { mutableStateOf<List<TransactionEntity>>(emptyList()) }

    var txType by remember { mutableStateOf(initialType) }
    var amountInput by remember {
        mutableStateOf(
            if (prefillMinor > 0L) MoneyFormatter.formatEditableDecimal(prefillMinor, entryCurrency) else ""
        )
    }
    var selectedAccountId by remember { mutableStateOf<String?>(null) }
    var selectedCategoryId by remember { mutableStateOf<String?>(null) }
    var noteInput by remember {
        mutableStateOf(
            listOf(prefillMerchant.trim(), prefillNote.trim()).filter { it.isNotBlank() }.joinToString(" • ")
        )
    }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var showCategoryTreePicker by remember { mutableStateOf(false) }

    var isListeningVoice by remember { mutableStateOf(false) }
    var voiceStatus by remember { mutableStateOf<String?>(null) }
    val recognizerHolder = remember { arrayOfNulls<SpeechRecognizer>(1) }
    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val startVoiceCapture: () -> Unit = {
        val sr = recognizerHolder[0]
        val avail = runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)
        if (sr != null && avail && hasAudioPermission) {
            val speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            }
            runCatching {
                sr.cancel()
                sr.startListening(speechIntent)
                isListeningVoice = true
                voiceStatus = "Listening... Say e.g. \"250 Swiggy dinner\""
            }.onFailure {
                isListeningVoice = false
                voiceStatus = "Voice unavailable right now — enter amount below"
            }
        } else if (!avail) {
            voiceStatus = "Voice service unavailable — type amount below"
        }
    }

    val audioPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasAudioPermission = granted
        if (granted) startVoiceCapture() else voiceStatus = "Mic permission denied — enter amount below"
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val db = LedgerDatabase.getInstance(context.applicationContext)
            SeedData.ensureMinimalSetup(db, defaultCurrency = baseCurrency)
            val loadedAccounts = db.accountDao().getAllActive()
                .filter { it.deletedAtEpochMs == null }
                .sortedWith(
                    compareBy<AccountEntity> { AccountTypeCatalog.isLiability(it.accountType) }
                        .thenBy { it.createdAtEpochMs }
                )
            val loadedCategories = db.categoryDao().getAllActive()
            val loadedTxs = db.transactionDao().getAllActive()
            withContext(Dispatchers.Main) {
                accounts = loadedAccounts
                allCategories = loadedCategories
                allTransactions = loadedTxs
                selectedAccountId = loadedAccounts.firstOrNull {
                    it.currency.equals(entryCurrency, ignoreCase = true)
                }?.id ?: loadedAccounts.firstOrNull()?.id
                isLoadingData = false
            }
        }

        if (runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)) {
            val sr = SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListeningVoice = true
                    voiceStatus = "Listening... Say amount & note"
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { isListeningVoice = false }
                override fun onError(error: Int) {
                    isListeningVoice = false
                    voiceStatus = "Didn't catch that — tap Mic to retry"
                }
                override fun onResults(results: Bundle?) {
                    isListeningVoice = false
                    val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    val (amt, note) = parseSpokenTransaction(spoken)
                    if (amt.isNotBlank()) amountInput = amt
                    if (note.isNotBlank()) noteInput = note
                    voiceStatus = if (spoken.isNotBlank()) "Heard: \"$spoken\"" else null
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (partial.isNotBlank()) voiceStatus = "\"$partial\""
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            recognizerHolder[0] = sr
            if (startVoiceOnOpen) {
                if (hasAudioPermission) startVoiceCapture() else audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        } else if (startVoiceOnOpen) {
            voiceStatus = "Voice service unavailable — enter amount below"
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { recognizerHolder[0]?.destroy() }
            recognizerHolder[0] = null
        }
    }

    LaunchedEffect(isLoadingData) {
        if (!isLoadingData && !startVoiceOnOpen) {
            runCatching { focusRequester.requestFocus() }
        }
    }

    val selectedAccount = accounts.firstOrNull { it.id == selectedAccountId } ?: accounts.firstOrNull()
    val effectiveCurrency = selectedAccount?.currency ?: entryCurrency
    val topCategories = remember(allCategories, allTransactions, txType) {
        WidgetMetricsCalculator.rankTopCategories(
            categories = allCategories,
            transactions = allTransactions,
            txType = txType,
            limit = 6,
        )
    }
    val catMap = remember(allCategories) { allCategories.associateBy { it.id } }
    val selectedCategory = selectedCategoryId?.let { catMap[it] }
    val selectedBreadcrumb = remember(selectedCategoryId, catMap) {
        selectedCategoryId?.let { CategoryTree.breadcrumbPath(it, catMap) }.orEmpty()
    }

    val trySave: () -> Unit = {
        if (!saving && selectedAccount != null) {
            val minor = MoneyFormatter.parseDecimalToMinor(amountInput, effectiveCurrency)
            if (minor == null || minor <= 0L) {
                errorMsg = "Enter a valid amount above zero"
            } else {
                saving = true
                errorMsg = null
                scope.launch {
                    onSave(txType, selectedAccount, selectedCategory, minor, noteInput)
                }
            }
        } else if (!isLoadingData && selectedAccount == null) {
            errorMsg = "Create an account in LedgerFlow first"
        }
    }

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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("EXPENSE" to "Expense", "INCOME" to "Income").forEach { (key, label) ->
                            val active = txType == key
                            FilterChip(
                                selected = active,
                                onClick = {
                                    txType = key
                                    selectedCategoryId = null
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
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Surface(
                            onClick = {
                                if (!hasAudioPermission) {
                                    audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                } else if (isListeningVoice) {
                                    runCatching { recognizerHolder[0]?.stopListening() }
                                    isListeningVoice = false
                                } else {
                                    startVoiceCapture()
                                }
                            },
                            shape = CircleShape,
                            color = if (isListeningVoice) palette.emeraldPrimary else palette.tint,
                            border = BorderStroke(1.dp, if (isListeningVoice) palette.emeraldPrimary else palette.border),
                            modifier = Modifier.size(34.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.Mic,
                                    contentDescription = "Voice Expense",
                                    tint = if (isListeningVoice) Color.White else palette.emeraldPrimary,
                                    modifier = Modifier.size(17.dp),
                                )
                            }
                        }
                        IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Outlined.Close, contentDescription = "Cancel", tint = palette.muted, modifier = Modifier.size(18.dp))
                        }
                    }
                }

                voiceStatus?.let { vMsg ->
                    Text(
                        text = vMsg,
                        fontSize = 11.sp,
                        color = if (isListeningVoice) palette.emeraldPrimary else palette.muted,
                        fontWeight = FontWeight.Medium,
                    )
                }

                OutlinedTextField(
                    value = amountInput,
                    onValueChange = { raw ->
                        amountInput = MoneyFormatter.sanitizeDecimalInput(raw, effectiveCurrency, allowNegative = false)
                        errorMsg = null
                    },
                    label = { Text("Amount (${MoneyFormatter.currencySymbol(effectiveCurrency)})") },
                    placeholder = { Text("0.00") },
                    singleLine = true,
                    textStyle = TabularStyle.copy(
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.ink,
                    ),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { trySave() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    shape = RoundedCornerShape(14.dp),
                )

                if (accounts.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("ACCOUNT", fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, color = palette.muted)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            accounts.forEach { acc ->
                                val selected = acc.id == selectedAccount?.id
                                FilterChip(
                                    selected = selected,
                                    onClick = { selectedAccountId = acc.id },
                                    label = { Text(acc.name, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = palette.emeraldSoft,
                                        selectedLabelColor = palette.emeraldPrimary,
                                        labelColor = palette.ink,
                                    ),
                                )
                            }
                        }
                    }
                }

                if (allCategories.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("CATEGORY (OPTIONAL)", fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, color = palette.muted)
                            Surface(
                                onClick = { showCategoryTreePicker = true },
                                shape = RoundedCornerShape(999.dp),
                                color = palette.tint,
                                border = BorderStroke(1.dp, palette.border),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(Icons.Outlined.AccountTree, contentDescription = null, tint = palette.emeraldPrimary, modifier = Modifier.size(12.dp))
                                    Text("Browse Tree", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = palette.emeraldPrimary)
                                }
                            }
                        }
                        if (selectedBreadcrumb.isNotBlank() && selectedBreadcrumb.contains("›")) {
                            Text(
                                text = "Selected: $selectedBreadcrumb",
                                fontSize = 11.sp,
                                color = palette.emeraldPrimary,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        val activeSameKind = remember(allCategories, txType) {
                            allCategories.filter { it.kind == txType && it.deletedAtEpochMs == null }
                        }
                        val duplicateNames = remember(activeSameKind) {
                            activeSameKind.groupingBy { it.name.trim().lowercase() }.eachCount()
                        }
                        fun chipLabel(cat: CategoryEntity): String {
                            val hasDupName = (duplicateNames[cat.name.trim().lowercase()] ?: 0) > 1
                            if (!hasDupName) return cat.name
                            val parentName = cat.parentCategoryId?.let { catMap[it]?.name }?.takeIf { it.isNotBlank() }
                            return if (parentName != null) "${cat.name} ($parentName)" else cat.name
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            topCategories.forEach { cat ->
                                val selected = cat.id == selectedCategoryId
                                FilterChip(
                                    selected = selected,
                                    onClick = { selectedCategoryId = if (selected) null else cat.id },
                                    label = { Text(chipLabel(cat), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = palette.emeraldSoft,
                                        selectedLabelColor = palette.emeraldPrimary,
                                        labelColor = palette.ink,
                                    ),
                                )
                            }
                            if (selectedCategory != null && topCategories.none { it.id == selectedCategory.id }) {
                                FilterChip(
                                    selected = true,
                                    onClick = { selectedCategoryId = null },
                                    label = { Text(chipLabel(selectedCategory), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = palette.emeraldSoft,
                                        selectedLabelColor = palette.emeraldPrimary,
                                    ),
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = noteInput,
                    onValueChange = { noteInput = it },
                    label = { Text("Merchant or Note (optional)", fontSize = 12.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { trySave() }),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                )

                errorMsg?.let { msg ->
                    Text(text = msg, fontSize = 12.sp, color = palette.roseOutflow)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel", color = palette.muted) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = trySave,
                        enabled = !saving && !isLoadingData,
                        colors = ButtonDefaults.buttonColors(containerColor = palette.emeraldPrimary),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(text = if (saving) "Saving..." else "Save", fontWeight = FontWeight.SemiBold, color = Color.White)
                    }
                }
            }
        }
    }

    if (showCategoryTreePicker) {
        HierarchicalCategoryPickerSheet(
            title = "Select Category",
            categories = allCategories,
            kindFilter = txType,
            selectedIds = listOfNotNull(selectedCategoryId).toSet(),
            multiSelect = false,
            nullOptionLabel = "Pending Review (No Category)",
            nullSelected = selectedCategoryId == null,
            onSelectNull = { selectedCategoryId = null },
            onSelectSingle = { selectedCategoryId = it.id },
            onDismiss = { showCategoryTreePicker = false },
        )
    }
}
