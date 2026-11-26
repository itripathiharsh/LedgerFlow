package com.ledgerflow.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.app.LedgerFlowApplication
import com.ledgerflow.app.data.local.AccountEntity
import com.ledgerflow.app.data.local.BudgetEntity
import com.ledgerflow.app.data.local.CategoryEntity
import com.ledgerflow.app.data.local.HoldingEntity
import com.ledgerflow.app.data.local.LedgerDatabase
import com.ledgerflow.app.data.local.MagicRuleEntity
import com.ledgerflow.app.data.local.PlannedPaymentEntity
import com.ledgerflow.app.data.local.TransactionEntity
import com.ledgerflow.app.data.remote.SupabaseSyncClient
import com.ledgerflow.app.data.sync.LedgerSyncRepository
import com.ledgerflow.app.data.sync.SyncOutcome
import com.ledgerflow.app.domain.AnalyticsCalculator
import com.ledgerflow.app.domain.CalculatorEngine
import com.ledgerflow.app.domain.FxConverter
import com.ledgerflow.app.domain.MagicRuleEngine
import com.ledgerflow.app.domain.MoneyFormatter
import com.ledgerflow.app.domain.UpdateManifest
import com.ledgerflow.app.security.VaultSessionState
import com.ledgerflow.app.service.PayAndTrackCapture
import com.ledgerflow.app.update.AppUpdateManager
import com.ledgerflow.app.update.UpdateCheckOutcome
import com.ledgerflow.app.update.UpdateEventBus
import com.ledgerflow.app.update.UpdateInstallEvent
import com.ledgerflow.app.update.UpdateInstallProgress
import com.ledgerflow.app.util.NetworkMonitor
import com.ledgerflow.app.work.SyncWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar
import java.util.UUID
import kotlin.math.abs

@OptIn(ExperimentalCoroutinesApi::class)
class LedgerViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as LedgerFlowApplication
    private val db = app.database
    val vault = app.vaultStore
    private val remote = app.remoteClient
    private val repo = app.syncRepository
    private val updater = app.updateManager

    private val _vaultState = MutableStateFlow(vault.read())
    val vaultState: StateFlow<VaultSessionState> = _vaultState.asStateFlow()

    private val _isLocked = MutableStateFlow(vault.read().appLockEnabled)
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    private var lastBackgroundedAtMs: Long = 0L

    private val _availableUpdate = MutableStateFlow<UpdateManifest?>(null)
    val availableUpdate: StateFlow<UpdateManifest?> = _availableUpdate.asStateFlow()

    private val _updateCheckInProgress = MutableStateFlow(false)
    val updateCheckInProgress: StateFlow<Boolean> = _updateCheckInProgress.asStateFlow()

    private val _updateInstallState = MutableStateFlow<UpdateInstallProgress?>(null)
    val updateInstallState: StateFlow<UpdateInstallProgress?> = _updateInstallState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _recordsFilter = MutableStateFlow("ALL") // ALL, MANUAL, GPAY_AUTO, PLANNED, IMPORT, MULTIFX
    val recordsFilter: StateFlow<String> = _recordsFilter.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _syncInProgress = MutableStateFlow(false)
    val syncInProgress: StateFlow<Boolean> = _syncInProgress.asStateFlow()

    private val _importInProgress = MutableStateFlow(false)
    val importInProgress: StateFlow<Boolean> = _importInProgress.asStateFlow()

    val isOnline: StateFlow<Boolean> = NetworkMonitor.observeOnline(app)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NetworkMonitor.isOnline(app))

    val accounts: StateFlow<List<AccountEntity>> = db.accountDao().observeActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val categories: StateFlow<List<CategoryEntity>> = db.categoryDao().observeActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allTransactions: StateFlow<List<TransactionEntity>> = db.transactionDao().observeActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val filteredTransactions: StateFlow<List<TransactionEntity>> = combine(
        _searchQuery.flatMapLatest { q ->
            if (q.isBlank()) {
                db.transactionDao().observeActive()
            } else {
                combine(db.transactionDao().observeActive(), flow { emit(q) }) { _, raw ->
                    val fts = MagicRuleEngine.sanitizeFtsQuery(raw)
                    if (fts.isBlank()) emptyList() else db.transactionDao().searchFts(fts)
                }
            }
        },
        _recordsFilter,
        _vaultState,
    ) { list, filter, v ->
        when (filter) {
            "MANUAL" -> list.filter { it.sourceTag == "MANUAL" }
            "GPAY_AUTO" -> list.filter { it.sourceTag == "GPAY_AUTO" }
            "PLANNED" -> list.filter { it.sourceTag == "PLANNED" }
            "IMPORT" -> list.filter { it.sourceTag == "IMPORT" }
            "MULTIFX" -> list.filter { it.currency != v.baseCurrency }
            else -> list
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val budgets: StateFlow<List<BudgetEntity>> = db.budgetDao().observeActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val plannedPayments: StateFlow<List<PlannedPaymentEntity>> = db.plannedPaymentDao().observeActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val holdings: StateFlow<List<HoldingEntity>> = db.holdingDao().observeActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val magicRules: StateFlow<List<MagicRuleEntity>> = db.magicRuleDao().observeActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val pendingSyncCount: StateFlow<Int> = combine(
        accounts,
        allTransactions,
        budgets,
        plannedPayments,
        holdings,
    ) { acc, txs, bdg, pln, hld ->
        acc.count { it.pendingSync } + txs.count { it.pendingSync } +
            bdg.count { it.pendingSync } + pln.count { it.pendingSync } + hld.count { it.pendingSync }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    init {
        val currentVault = _vaultState.value
        currentVault.justUpdatedVersionName?.let { newVersion ->
            _statusMessage.value = "Updated to LedgerFlow v$newVersion"
            _vaultState.value = vault.update { it.copy(justUpdatedVersionName = null) }
        }

        viewModelScope.launch {
            repo.refreshFxRatesIfOnline()
        }

        viewModelScope.launch {
            UpdateEventBus.events.collect { event ->
                when (event) {
                    is UpdateInstallEvent.UserConfirmationRequired -> {
                        _updateInstallState.value = UpdateInstallProgress.AwaitingUserConfirmation(event.intent)
                    }
                    is UpdateInstallEvent.Success -> {
                        _updateInstallState.value = UpdateInstallProgress.Installing
                    }
                    is UpdateInstallEvent.Failed -> {
                        _updateInstallState.value = UpdateInstallProgress.Failed(event.message)
                    }
                }
            }
        }

        viewModelScope.launch {
            isOnline.collect { online ->
                if (!online) {
                    val activeTransfer = _updateInstallState.value is UpdateInstallProgress.Downloading ||
                        _updateInstallState.value is UpdateInstallProgress.VerifyingSignature ||
                        _updateInstallState.value is UpdateInstallProgress.Installing
                    if (!activeTransfer) {
                        // Offline-first UX: never show update popups while offline.
                        _availableUpdate.value = null
                    }
                } else {
                    if (_availableUpdate.value == null) {
                        updater.loadCachedEligibleManifest(ignoreDismissed = false)?.let { cached ->
                            _availableUpdate.value = cached
                        }
                    }
                    maybeCheckForUpdatesSilently()
                }
            }
        }
    }

    fun currentMonthStartEpochMs(): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    fun currentMonthFraction(): Float {
        val cal = Calendar.getInstance()
        val day = cal.get(Calendar.DAY_OF_MONTH).toFloat()
        val max = cal.getActualMaximum(Calendar.DAY_OF_MONTH).toFloat().coerceAtLeast(1f)
        return (day / max).coerceIn(0f, 1f)
    }

    fun daysRemainingInMonth(): Int {
        val cal = Calendar.getInstance()
        val max = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val day = cal.get(Calendar.DAY_OF_MONTH)
        return (max - day).coerceAtLeast(1)
    }

    fun clearMessage() {
        _statusMessage.value = null
    }

    fun showMessage(message: String) {
        if (message.isNotBlank()) {
            _statusMessage.value = message
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setRecordsFilter(filter: String) {
        _recordsFilter.value = filter
    }

    fun toggleHideBalances() {
        _vaultState.value = vault.update { it.copy(hideBalances = !it.hideBalances) }
    }

    fun setBaseCurrency(code: String) {
        val clean = code.trim().uppercase()
        if (clean.length != 3) return
        _vaultState.value = vault.update { it.copy(baseCurrency = clean) }
        viewModelScope.launch {
            db.alignStarterWalletCurrencyIfEmpty(clean)
            SyncWorker.enqueueWhenOnline(app)
        }
        _statusMessage.value = "Default currency set to $clean"
    }

    fun updateProfileName(name: String) {
        val clean = name.trim()
        if (clean.isBlank()) {
            _statusMessage.value = "Name cannot be empty"
            return
        }
        _vaultState.value = vault.update { it.copy(userName = clean) }
        _statusMessage.value = "Profile updated"
    }

    fun completeOnboarding(
        name: String,
        email: String,
        password: String,
        currency: String,
    ) {
        val cleanName = name.trim()
        val cleanEmail = email.trim()
        val cleanCur = currency.trim().uppercase()
        if (cleanName.isBlank()) {
            _statusMessage.value = "Enter your name"
            return
        }
        if (!cleanEmail.contains("@") || !cleanEmail.contains(".")) {
            _statusMessage.value = "Enter a valid email address"
            return
        }
        if (password.length < 6) {
            _statusMessage.value = "Password must be at least 6 characters"
            return
        }
        if (cleanCur.length != 3) {
            _statusMessage.value = "Choose your currency"
            return
        }

        _vaultState.value = vault.update {
            it.copy(
                onboardingCompleted = true,
                userName = cleanName,
                userEmail = cleanEmail,
                baseCurrency = cleanCur,
            )
        }

        viewModelScope.launch {
            db.alignStarterWalletCurrencyIfEmpty(cleanCur)
            if (isOnline.value) {
                var authRes = remote.signUp(cleanEmail, password)
                if (!authRes.success || authRes.accessToken.isNullOrBlank()) {
                    val signInAttempt = remote.signIn(cleanEmail, password)
                    if (signInAttempt.success && !signInAttempt.accessToken.isNullOrBlank()) {
                        authRes = signInAttempt
                    }
                }
                if (authRes.success && !authRes.accessToken.isNullOrBlank()) {
                    _vaultState.value = vault.update {
                        it.copy(
                            userId = authRes.userId,
                            userEmail = authRes.email ?: cleanEmail,
                            accessToken = authRes.accessToken,
                            refreshToken = authRes.refreshToken,
                        )
                    }
                    syncNow()
                } else if (authRes.success) {
                    _statusMessage.value = "Welcome, $cleanName! Verify your email to enable cloud sync."
                } else {
                    _statusMessage.value = "Welcome, $cleanName! Setup saved locally."
                }
            } else {
                _statusMessage.value = "Welcome, $cleanName! Setup saved offline."
            }
        }
    }

    fun addTransaction(
        txType: String,
        amountExpr: String,
        currency: String,
        accountId: String,
        transferAccountId: String?,
        manualCategoryId: String?,
        merchant: String,
        note: String,
        labelsCsv: String,
        recurringMonthly: Boolean,
    ): Boolean {
        val magnitudeMinor = CalculatorEngine.evaluateToMinor(amountExpr)
        if (magnitudeMinor == null || magnitudeMinor <= 0L) {
            _statusMessage.value = "Enter a valid non-zero amount"
            return false
        }
        if (txType == "TRANSFER" && (transferAccountId.isNullOrBlank() || transferAccountId == accountId)) {
            _statusMessage.value = "Choose a different destination account for transfer"
            return false
        }

        val cleanMerchant = merchant.trim().ifBlank {
            when (txType) {
                "INCOME" -> "Income Entry"
                "TRANSFER" -> "Account Transfer"
                else -> "Expense Entry"
            }
        }
        val baseCurrency = _vaultState.value.baseCurrency

        viewModelScope.launch {
            val rules = db.magicRuleDao().getAllActive()
            val ruleMatch = MagicRuleEngine.apply(cleanMerchant, note, rules)
            val effectiveCategoryId = manualCategoryId ?: ruleMatch.categoryId
            val mergedLabels = listOfNotNull(
                labelsCsv.trim().ifBlank { null },
                ruleMatch.label?.takeIf { !labelsCsv.contains(it, ignoreCase = true) },
            ).joinToString(",")

            val signedMinor = when (txType) {
                "EXPENSE" -> -abs(magnitudeMinor)
                else -> abs(magnitudeMinor)
            }
            val converted = if (currency != baseCurrency) {
                FxConverter.convertMinor(signedMinor, currency, baseCurrency)
            } else null

            val now = System.currentTimeMillis()
            val tx = TransactionEntity(
                id = UUID.randomUUID().toString(),
                accountId = accountId,
                transferAccountId = if (txType == "TRANSFER") transferAccountId else null,
                categoryId = effectiveCategoryId,
                txType = txType,
                amountMinor = signedMinor,
                currency = currency,
                convertedMinor = converted,
                convertedCurrency = if (converted != null) baseCurrency else null,
                merchant = cleanMerchant,
                note = note.trim(),
                labelsCsv = mergedLabels,
                sourceTag = "MANUAL",
                autoRuleApplied = manualCategoryId == null && ruleMatch.matched,
                occurredAtEpochMs = now,
                createdAtEpochMs = now,
                updatedAtEpochMs = now,
                pendingSync = true,
            )
            db.recordTransactionAtomic(tx)

            if (recurringMonthly && txType != "TRANSFER") {
                db.plannedPaymentDao().upsert(
                    PlannedPaymentEntity(
                        id = UUID.randomUUID().toString(),
                        accountId = accountId,
                        categoryId = effectiveCategoryId,
                        title = cleanMerchant,
                        amountMinor = abs(magnitudeMinor),
                        currency = currency,
                        txType = txType,
                        cadence = "MONTHLY",
                        nextDueAtEpochMs = LedgerDatabase.advanceDueDateEpochMs(now, "MONTHLY"),
                        autoPay = false,
                        createdAtEpochMs = now,
                        updatedAtEpochMs = now,
                        pendingSync = true,
                    )
                )
            }

            SyncWorker.enqueueWhenOnline(app)
            _statusMessage.value = "Saved locally to SQLite vault"
        }
        return true
    }

    fun deleteTransaction(txId: String) {
        viewModelScope.launch {
            db.softDeleteTransactionAtomic(txId)
            SyncWorker.enqueueWhenOnline(app)
            _statusMessage.value = "Transaction deleted and balance updated"
        }
    }

    fun addBudget(name: String, categoryId: String?, limitMinor: Long, currency: String) {
        if (name.isBlank() || limitMinor <= 0) {
            _statusMessage.value = "Enter a valid budget name and limit"
            return
        }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            db.budgetDao().upsert(
                BudgetEntity(
                    id = UUID.randomUUID().toString(),
                    categoryId = categoryId,
                    name = name.trim(),
                    limitMinor = limitMinor,
                    currency = currency,
                    period = "MONTHLY",
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now,
                    pendingSync = true,
                )
            )
            SyncWorker.enqueueWhenOnline(app)
            _statusMessage.value = "Budget \"$name\" added"
        }
    }

    fun addPlannedPayment(title: String, amountMinor: Long, currency: String, accountId: String?, categoryId: String?, cadence: String) {
        if (title.isBlank() || amountMinor <= 0) {
            _statusMessage.value = "Enter valid bill title & amount"
            return
        }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            db.plannedPaymentDao().upsert(
                PlannedPaymentEntity(
                    id = UUID.randomUUID().toString(),
                    accountId = accountId,
                    categoryId = categoryId,
                    title = title.trim(),
                    amountMinor = amountMinor,
                    currency = currency,
                    txType = "EXPENSE",
                    cadence = cadence,
                    nextDueAtEpochMs = now + 7L * 86_400_000L,
                    autoPay = false,
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now,
                    pendingSync = true,
                )
            )
            SyncWorker.enqueueWhenOnline(app)
            _statusMessage.value = "Planned payment added"
        }
    }

    fun payPlannedNow(plannedId: String) {
        viewModelScope.launch {
            val fallback = accounts.value.firstOrNull()?.id
            db.executePlannedPaymentAtomic(plannedId, fallback)
            SyncWorker.enqueueWhenOnline(app)
            _statusMessage.value = "Planned payment recorded"
        }
    }

    fun addAccount(name: String, institution: String, type: String, currency: String, balanceMinor: Long, lastFour: String) {
        if (name.isBlank()) {
            _statusMessage.value = "Account name required"
            return
        }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            db.accountDao().upsert(
                AccountEntity(
                    id = UUID.randomUUID().toString(),
                    name = name.trim(),
                    institution = institution.trim(),
                    accountType = type,
                    currency = currency.uppercase(),
                    balanceMinor = balanceMinor,
                    lastFour = lastFour.take(4),
                    sourceType = "MANUAL",
                    syncStatus = "OK",
                    lastSyncedAtEpochMs = now,
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now,
                    pendingSync = true,
                )
            )
            SyncWorker.enqueueWhenOnline(app)
            _statusMessage.value = "Account \"$name\" created"
        }
    }

    fun deleteAccount(accountId: String) {
        viewModelScope.launch {
            val acc = db.accountDao().getById(accountId) ?: return@launch
            if (acc.deletedAtEpochMs != null) return@launch
            val now = System.currentTimeMillis()
            db.accountDao().upsert(acc.copy(deletedAtEpochMs = now, updatedAtEpochMs = now, pendingSync = true))
            SyncWorker.enqueueWhenOnline(app)
            _statusMessage.value = "Account \"${acc.name}\" removed"
        }
    }

    fun deleteBudget(budgetId: String) {
        viewModelScope.launch {
            val existing = budgets.value.firstOrNull { it.id == budgetId } ?: return@launch
            val now = System.currentTimeMillis()
            db.budgetDao().upsert(existing.copy(deletedAtEpochMs = now, updatedAtEpochMs = now, pendingSync = true))
            SyncWorker.enqueueWhenOnline(app)
            _statusMessage.value = "Budget deleted"
        }
    }

    fun deletePlannedPayment(plannedId: String) {
        viewModelScope.launch {
            val existing = db.plannedPaymentDao().getById(plannedId) ?: return@launch
            val now = System.currentTimeMillis()
            db.plannedPaymentDao().upsert(existing.copy(deletedAtEpochMs = now, updatedAtEpochMs = now, pendingSync = true))
            SyncWorker.enqueueWhenOnline(app)
            _statusMessage.value = "Planned bill deleted"
        }
    }

    fun addHolding(
        symbol: String,
        name: String,
        assetClass: String,
        exchange: String,
        quantity: Double,
        avgCostMinor: Long,
        currentPriceMinor: Long,
        currency: String,
    ) {
        if (symbol.isBlank() || quantity <= 0.0 || currentPriceMinor <= 0L) {
            _statusMessage.value = "Enter valid asset symbol, quantity, and price"
            return
        }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            db.holdingDao().upsert(
                HoldingEntity(
                    id = UUID.randomUUID().toString(),
                    symbol = symbol.trim().uppercase(),
                    name = name.trim().ifBlank { symbol.trim().uppercase() },
                    assetClass = assetClass,
                    exchange = exchange.trim().uppercase(),
                    quantity = quantity,
                    avgCostMinor = avgCostMinor.coerceAtLeast(1L),
                    currentPriceMinor = currentPriceMinor,
                    currency = currency.uppercase(),
                    dayChangeBps = 0,
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now,
                    pendingSync = true,
                )
            )
            SyncWorker.enqueueWhenOnline(app)
            _statusMessage.value = "Holding ${symbol.trim().uppercase()} saved"
        }
    }

    fun updateHoldingPrice(holdingId: String, newPriceMinor: Long) {
        if (newPriceMinor <= 0L) {
            _statusMessage.value = "Enter a valid positive unit price"
            return
        }
        viewModelScope.launch {
            val existing = holdings.value.firstOrNull { it.id == holdingId } ?: return@launch
            val now = System.currentTimeMillis()
            db.holdingDao().upsert(
                existing.copy(
                    currentPriceMinor = newPriceMinor,
                    updatedAtEpochMs = now,
                    pendingSync = true,
                )
            )
            SyncWorker.enqueueWhenOnline(app)
            _statusMessage.value = "${existing.symbol} price updated"
        }
    }

    fun deleteHolding(holdingId: String) {
        viewModelScope.launch {
            val existing = holdings.value.firstOrNull { it.id == holdingId } ?: return@launch
            val now = System.currentTimeMillis()
            db.holdingDao().upsert(existing.copy(deletedAtEpochMs = now, updatedAtEpochMs = now, pendingSync = true))
            SyncWorker.enqueueWhenOnline(app)
            _statusMessage.value = "${existing.symbol} deleted"
        }
    }

    fun addMagicRule(pattern: String, categoryId: String?, label: String) {
        if (pattern.isBlank()) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            db.magicRuleDao().upsert(
                MagicRuleEntity(
                    id = UUID.randomUUID().toString(),
                    matchPattern = pattern.trim().lowercase(),
                    targetCategoryId = categoryId,
                    targetLabel = label.trim(),
                    enabled = true,
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now,
                    pendingSync = true,
                )
            )
            _statusMessage.value = "Auto-rule saved"
        }
    }

    fun deleteMagicRule(ruleId: String) {
        viewModelScope.launch {
            val existing = magicRules.value.firstOrNull { it.id == ruleId } ?: return@launch
            val now = System.currentTimeMillis()
            db.magicRuleDao().upsert(existing.copy(deletedAtEpochMs = now, updatedAtEpochMs = now, pendingSync = true))
            SyncWorker.enqueueWhenOnline(app)
            _statusMessage.value = "Auto-rule deleted"
        }
    }

    fun capturePaymentAlertText(rawText: String, originNote: String = "Captured via Smart Paste") {
        val cleaned = rawText.trim()
        if (cleaned.isBlank()) {
            _statusMessage.value = "Paste a payment SMS or alert containing amount and merchant"
            return
        }
        viewModelScope.launch {
            val lines = cleaned.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val title = lines.firstOrNull().orEmpty()
            val body = lines.drop(1).joinToString(" ").ifBlank { cleaned }
            val outcome = PayAndTrackCapture.capturePaymentDetailed(
                context = app,
                title = title,
                text = body,
                manualEntry = true,
                noteText = originNote,
            )
            _statusMessage.value = if (outcome.captured && outcome.merchant != null) {
                val formatted = MoneyFormatter.formatMinor(outcome.amountMinor, outcome.currency)
                "Captured $formatted at ${outcome.merchant}"
            } else {
                "Could not extract an amount and merchant from that text"
            }
        }
    }

    fun importCsvUri(uri: Uri) {
        if (_importInProgress.value) return
        _importInProgress.value = true
        viewModelScope.launch {
            try {
                val baseCur = _vaultState.value.baseCurrency
                val preferredAccId = accounts.value.firstOrNull()?.id
                val outcomeMsg = withContext(Dispatchers.IO) {
                    var readError: String? = null
                    var exceededLimit = false
                    val rawBytes = try {
                        val stream = app.contentResolver.openInputStream(uri)
                        if (stream == null) {
                            readError = "Unable to open selected file."
                            null
                        } else {
                            stream.use { input ->
                                val maxBytes = 10 * 1024 * 1024
                                val buf = ByteArray(8192)
                                val out = java.io.ByteArrayOutputStream()
                                var total = 0
                                while (true) {
                                    val n = input.read(buf)
                                    if (n <= 0) break
                                    total += n
                                    if (total > maxBytes) {
                                        exceededLimit = true
                                        return@use null
                                    }
                                    out.write(buf, 0, n)
                                }
                                out.toByteArray()
                            }
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        readError = "Unable to read selected file (${t.message ?: "access error"})"
                        null
                    }

                    if (exceededLimit) {
                        return@withContext "Selected file exceeds 10 MB import limit."
                    }
                    if (rawBytes == null) {
                        return@withContext readError ?: "Unable to read selected file."
                    }

                    val outcome = repo.importExternalDataOffline(
                        rawBytes = rawBytes,
                        preferredAccountId = preferredAccId,
                        defaultCurrency = baseCur,
                    )
                    if (outcome.importedTransactions > 0 || outcome.importedAccounts > 0 || outcome.importedOtherItems > 0) {
                        runCatching { SyncWorker.enqueueWhenOnline(app) }
                    }
                    outcome.message
                }
                _statusMessage.value = outcomeMsg
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _statusMessage.value = "Import failed: ${t.message ?: "Unsupported file"}"
            } finally {
                _importInProgress.value = false
            }
        }
    }

    fun exportAndShare(asCsv: Boolean, onUriReady: (Intent) -> Unit) {
        viewModelScope.launch {
            try {
                val intent = withContext(Dispatchers.IO) {
                    val exportDir = File(app.cacheDir, "exports").apply { mkdirs() }
                    val file = File(exportDir, if (asCsv) "ledgerflow_export.csv" else "ledgerflow_vault_backup.json")
                    val body = if (asCsv) repo.exportLedgerCsv() else repo.exportVaultJson()
                    file.writeText(body)
                    val uri: Uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
                    Intent(Intent.ACTION_SEND).apply {
                        type = if (asCsv) "text/csv" else "application/json"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
                onUriReady(Intent.createChooser(intent, if (asCsv) "Export Ledger CSV" else "Export Vault Backup"))
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _statusMessage.value = "Export failed: ${t.message ?: "Unable to write file"}"
            }
        }
    }

    fun resetAllData() {
        viewModelScope.launch {
            val baseCur = _vaultState.value.baseCurrency
            db.resetAllDataFresh(defaultCurrency = baseCur)
            _vaultState.value = vault.resetSyncAndCloudState()
            _searchQuery.value = ""
            _recordsFilter.value = "ALL"
            _statusMessage.value = "Deleted all data — started fresh (${MoneyFormatter.formatMinor(0L, baseCur)})"
        }
    }

    fun syncNow() {
        if (_syncInProgress.value) return
        viewModelScope.launch {
            _syncInProgress.value = true
            when (val outcome = repo.syncNow()) {
                is SyncOutcome.Success -> {
                    _vaultState.value = vault.read()
                    _statusMessage.value = "Synced (${outcome.pushedCount} up, ${outcome.pulledCount} down)"
                }
                is SyncOutcome.LocalOnly -> {
                    _vaultState.value = vault.read()
                    _statusMessage.value = outcome.reason
                }
                is SyncOutcome.Error -> {
                    _statusMessage.value = outcome.message
                }
            }
            _syncInProgress.value = false
        }
    }

    fun signInSupabase(email: String, password: String) {
        if (email.isBlank() || password.length < 6) {
            _statusMessage.value = "Enter email and 6+ character password"
            return
        }
        viewModelScope.launch {
            if (!isOnline.value) {
                _statusMessage.value = "Internet required for cloud sign-in (local mode still works offline)"
                return@launch
            }
            _syncInProgress.value = true
            val res = remote.signIn(email.trim(), password)
            _syncInProgress.value = false
            if (res.success && !res.accessToken.isNullOrBlank()) {
                _vaultState.value = vault.update {
                    it.copy(
                        userId = res.userId,
                        userEmail = res.email ?: email.trim(),
                        accessToken = res.accessToken,
                        refreshToken = res.refreshToken,
                    )
                }
                syncNow()
            } else {
                _statusMessage.value = res.message ?: "Sign-in failed"
            }
        }
    }

    fun signUpSupabase(email: String, password: String) {
        if (email.isBlank() || password.length < 6) {
            _statusMessage.value = "Enter email and 6+ character password"
            return
        }
        viewModelScope.launch {
            if (!isOnline.value) {
                _statusMessage.value = "Internet required to register cloud account"
                return@launch
            }
            _syncInProgress.value = true
            val res = remote.signUp(email.trim(), password)
            _syncInProgress.value = false
            if (res.success && !res.accessToken.isNullOrBlank()) {
                _vaultState.value = vault.update {
                    it.copy(
                        userId = res.userId,
                        userEmail = res.email ?: email.trim(),
                        accessToken = res.accessToken,
                        refreshToken = res.refreshToken,
                    )
                }
                syncNow()
            } else {
                _statusMessage.value = res.message ?: "Sign-up failed"
            }
        }
    }

    fun signOutSupabase() {
        _vaultState.value = vault.clearAuth()
        _statusMessage.value = "Signed out of cloud sync (local Room data preserved)"
    }

    fun setPayAndTrackEnabled(enabled: Boolean) {
        _vaultState.value = vault.update { it.copy(payAndTrackEnabled = enabled) }
    }

    fun setBlurInSwitcher(enabled: Boolean) {
        _vaultState.value = vault.update { it.copy(blurInSwitcher = enabled) }
    }

    fun setAutoLockSeconds(seconds: Int) {
        _vaultState.value = vault.update { it.copy(autoLockSeconds = seconds.coerceAtLeast(0)) }
    }

    fun setTelemetryOptIn(enabled: Boolean) {
        _vaultState.value = vault.update { it.copy(telemetryOptIn = enabled) }
    }

    fun configurePinLock(pin: String) {
        if (pin.length < 4) {
            _statusMessage.value = "PIN must be at least 4 digits"
            return
        }
        _vaultState.value = vault.setPin(pin)
        _statusMessage.value = "Biometric & PIN lock enabled"
    }

    fun disablePinLock() {
        _vaultState.value = vault.update {
            it.copy(appLockEnabled = false, pinHashBase64 = null, pinSaltBase64 = null)
        }
        _isLocked.value = false
        _statusMessage.value = "App Lock disabled"
    }

    fun unlockWithPin(pin: String): Boolean {
        val ok = vault.checkPin(pin)
        if (ok) _isLocked.value = false
        return ok
    }

    fun unlockViaBiometric() {
        _isLocked.value = false
    }

    fun onAppBackgrounded() {
        lastBackgroundedAtMs = System.currentTimeMillis()
    }

    fun onAppForegrounded() {
        val state = _vaultState.value
        if (state.appLockEnabled && lastBackgroundedAtMs > 0L) {
            val elapsedMs = System.currentTimeMillis() - lastBackgroundedAtMs
            if (elapsedMs >= state.autoLockSeconds * 1000L) {
                _isLocked.value = true
            }
        }
        resumePendingInstallIfPermitted()
        maybeCheckForUpdatesSilently()
    }

    fun installedVersionName(): String = AppUpdateManager.installedVersionName(app)

    fun installedVersionCode(): Long = AppUpdateManager.installedVersionCode(app)

    fun maybeCheckForUpdatesSilently() {
        if (!NetworkMonitor.isOnline(app)) return
        if (!updater.shouldCheckSilently(_vaultState.value.lastUpdateCheckEpochMs)) {
            return
        }
        checkForUpdates(manual = false)
    }

    fun checkForUpdates(manual: Boolean) {
        if (_updateCheckInProgress.value) return
        if (!NetworkMonitor.isOnline(app)) {
            if (manual) {
                _statusMessage.value = "Offline — will check automatically when online"
            }
            return
        }
        viewModelScope.launch {
            _updateCheckInProgress.value = true
            val outcome = updater.checkForUpdate(ignoreDismissed = manual)
            _vaultState.value = vault.read()
            when (outcome) {
                is UpdateCheckOutcome.UpdateAvailable -> {
                    _updateInstallState.value = null
                    _availableUpdate.value = outcome.manifest
                }
                is UpdateCheckOutcome.UpToDate -> {
                    _availableUpdate.value = null
                    if (manual) {
                        _statusMessage.value = "You are on the latest version (v${outcome.versionName})"
                    }
                }
                is UpdateCheckOutcome.Snoozed -> {
                    if (!manual) _availableUpdate.value = null
                }
                is UpdateCheckOutcome.Offline -> {
                    if (manual) _statusMessage.value = "Offline — will check automatically when online"
                }
                is UpdateCheckOutcome.Error -> {
                    if (manual) _statusMessage.value = outcome.message
                }
            }
            _updateCheckInProgress.value = false
        }
    }

    fun startUpdateNow() {
        val manifest = _availableUpdate.value ?: return
        val currentState = _updateInstallState.value
        if (currentState is UpdateInstallProgress.CheckingPermission ||
            currentState is UpdateInstallProgress.Downloading ||
            currentState is UpdateInstallProgress.VerifyingSignature ||
            currentState is UpdateInstallProgress.Installing ||
            currentState is UpdateInstallProgress.AwaitingUserConfirmation
        ) {
            return
        }
        _updateInstallState.value = UpdateInstallProgress.CheckingPermission
        viewModelScope.launch {
            updater.downloadVerifyAndInstall(manifest) { progress ->
                _updateInstallState.value = progress
            }
        }
    }

    fun dismissUpdateForLater() {
        val current = _availableUpdate.value ?: return
        val s = _updateInstallState.value
        if (s is UpdateInstallProgress.CheckingPermission ||
            s is UpdateInstallProgress.Downloading ||
            s is UpdateInstallProgress.VerifyingSignature ||
            s is UpdateInstallProgress.AwaitingUserConfirmation
        ) {
            return
        }
        _vaultState.value = vault.update {
            it.copy(
                dismissedUpdateVersionCode = maxOf(it.dismissedUpdateVersionCode, current.versionCode),
                pendingInstallResumeVersionCode = 0L,
            )
        }
        _availableUpdate.value = null
        _updateInstallState.value = null
    }

    fun markInstallPermissionLaunched() {
        val pendingCode = _availableUpdate.value?.versionCode ?: 0L
        if (pendingCode > 0L) {
            _vaultState.value = vault.update {
                it.copy(pendingInstallResumeVersionCode = pendingCode)
            }
        }
        _updateInstallState.value = null
    }

    fun onInstallPermissionLaunchFailed(message: String) {
        _vaultState.value = vault.update { it.copy(pendingInstallResumeVersionCode = 0L) }
        _updateInstallState.value = UpdateInstallProgress.Failed(message)
    }

    fun resumePendingInstallIfPermitted() {
        val pendingCode = _vaultState.value.pendingInstallResumeVersionCode
        if (pendingCode <= 0L || pendingCode <= installedVersionCode()) return
        if (!AppUpdateManager.canRequestPackageInstalls(app)) return
        _vaultState.value = vault.update { it.copy(pendingInstallResumeVersionCode = 0L) }
        if (_availableUpdate.value == null) {
            _availableUpdate.value = updater.loadCachedEligibleManifest(ignoreDismissed = true)
        }
        if (_availableUpdate.value != null) {
            startUpdateNow()
        }
    }

    fun onInstallPermissionResult(granted: Boolean) {
        if (granted) {
            _vaultState.value = vault.update { it.copy(pendingInstallResumeVersionCode = 0L) }
            startUpdateNow()
        } else {
            _vaultState.value = vault.update { it.copy(pendingInstallResumeVersionCode = 0L) }
            _updateInstallState.value = UpdateInstallProgress.Failed(
                "Enable 'Install unknown apps' for LedgerFlow in Android Settings, then tap Retry."
            )
        }
    }

    fun consumeAwaitingConfirmationIntent() {
        if (_updateInstallState.value is UpdateInstallProgress.AwaitingUserConfirmation) {
            _updateInstallState.value = UpdateInstallProgress.Installing
        }
    }

    fun fallbackSystemInstallerIntent(): Intent? =
        _availableUpdate.value?.let { updater.buildVerifiedFallbackInstallIntent(it) }

    fun onInstallLaunchFailed(message: String) {
        _vaultState.value = vault.update {
            it.copy(pendingUpdateRelaunch = false, pendingUpdateVersionName = null)
        }
        _updateInstallState.value = UpdateInstallProgress.Failed(message)
    }
}
