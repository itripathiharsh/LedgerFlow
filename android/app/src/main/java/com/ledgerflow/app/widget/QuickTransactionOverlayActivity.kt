package com.ledgerflow.app.widget

import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledgerflow.app.data.local.AccountEntity
import com.ledgerflow.app.data.local.CategoryEntity
import com.ledgerflow.app.data.local.LedgerDatabase
import com.ledgerflow.app.data.local.SeedData
import com.ledgerflow.app.data.local.TransactionEntity
import com.ledgerflow.app.domain.AccountTypeCatalog
import com.ledgerflow.app.domain.FxConverter
import com.ledgerflow.app.domain.MoneyFormatter
import com.ledgerflow.app.domain.WidgetMetricsCalculator
import com.ledgerflow.app.security.VaultStore
import com.ledgerflow.app.ui.theme.LedgerFlowTheme
import com.ledgerflow.app.ui.theme.LedgerPalette
import com.ledgerflow.app.ui.theme.TabularStyle
import com.ledgerflow.app.ui.theme.resolveLedgerPalette
import com.ledgerflow.app.work.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Floating transparent overlay activity launched directly from Widget A/B for
 * 1-tap Expense or Income entry over the launcher without opening MainActivity.
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
        ): Intent = Intent(context, QuickTransactionOverlayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            putExtra(EXTRA_TX_TYPE, normalizeTxType(txType))
            if (prefillAmountMinor > 0L) putExtra(EXTRA_PREFILL_AMOUNT_MINOR, prefillAmountMinor)
            if (!prefillCurrency.isNullOrBlank()) putExtra(EXTRA_PREFILL_CURRENCY, prefillCurrency)
            if (!prefillMerchant.isNullOrBlank()) putExtra(EXTRA_PREFILL_MERCHANT, prefillMerchant)
            if (!prefillNote.isNullOrBlank()) putExtra(EXTRA_PREFILL_NOTE, prefillNote)
            putExtra(EXTRA_PREFILL_SOURCE_TAG, prefillSourceTag)
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
    onDismiss: () -> Unit,
    onSave: (txType: String, account: AccountEntity, category: CategoryEntity?, amountMinor: Long, note: String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    var txType by remember { mutableStateOf(initialType) }
    var accounts by remember { mutableStateOf<List<AccountEntity>>(emptyList()) }
    var allCategories by remember { mutableStateOf<List<CategoryEntity>>(emptyList()) }
    var allTransactions by remember { mutableStateOf<List<TransactionEntity>>(emptyList()) }
    var isLoadingData by remember { mutableStateOf(true) }

    var selectedAccountId by remember { mutableStateOf<String?>(null) }
    var selectedCategoryId by remember { mutableStateOf<String?>(null) }
    var amountInput by remember {
        mutableStateOf(
            if (prefillMinor > 0L) {
                val div = if (entryCurrency.equals("JPY", ignoreCase = true)) 1.0 else 100.0
                val raw = prefillMinor / div
                if (raw % 1.0 == 0.0) raw.toLong().toString() else raw.toString()
            } else ""
        )
    }
    var noteInput by remember { mutableStateOf(prefillNote.ifBlank { prefillMerchant }) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

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
                .filter { it.deletedAtEpochMs == null }
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
    }

    LaunchedEffect(isLoadingData) {
        if (!isLoadingData) {
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
    val selectedCategory = topCategories.firstOrNull { it.id == selectedCategoryId }

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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("EXPENSE" to "Expense", "INCOME" to "Income").forEach { (key, label) ->
                            val active = txType == key
                            FilterChip(
                                selected = active,
                                onClick = {
                                    txType = key
                                    selectedCategoryId = null
                                },
                                label = {
                                    Text(
                                        text = label,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = palette.emeraldSoft,
                                    selectedLabelColor = palette.emeraldPrimary,
                                    labelColor = palette.muted,
                                ),
                            )
                        }
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Cancel",
                            tint = palette.muted,
                            modifier = Modifier.size(18.dp),
                        )
                    }
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
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "ACCOUNT",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.6.sp,
                            color = palette.muted,
                        )
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
                                    label = {
                                        Text(
                                            text = acc.name,
                                            fontSize = 12.sp,
                                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                        )
                                    },
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

                if (topCategories.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "CATEGORY (OPTIONAL)",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.6.sp,
                            color = palette.muted,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            topCategories.forEach { cat ->
                                val selected = cat.id == selectedCategoryId
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        selectedCategoryId = if (selected) null else cat.id
                                    },
                                    label = { Text(cat.name, fontSize = 11.sp) },
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
                    Text(
                        text = msg,
                        fontSize = 12.sp,
                        color = palette.roseOutflow,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = palette.muted)
                    }
                    Spacer(Modifier.size(8.dp))
                    Button(
                        onClick = trySave,
                        enabled = !saving && !isLoadingData,
                        colors = ButtonDefaults.buttonColors(containerColor = palette.emeraldPrimary),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            text = if (saving) "Saving..." else "Save",
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        )
                    }
                }
            }
        }
    }
}
