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
import com.ledgerflow.app.data.local.DebtEntity
import com.ledgerflow.app.data.local.HoldingEntity
import com.ledgerflow.app.data.local.LedgerDatabase
import com.ledgerflow.app.data.local.MagicRuleEntity
import com.ledgerflow.app.data.local.PlannedPaymentEntity
import com.ledgerflow.app.data.local.SavingsGoalEntity
import com.ledgerflow.app.data.local.ShoppingItemEntity
import com.ledgerflow.app.data.local.ShoppingListEntity
import com.ledgerflow.app.data.local.TransactionEntity
import com.ledgerflow.app.data.remote.SupabaseSyncClient
import com.ledgerflow.app.data.sync.LedgerSyncRepository
import com.ledgerflow.app.data.sync.SyncOutcome
import com.ledgerflow.app.domain.AccountTypeCatalog
import com.ledgerflow.app.domain.AnalyticsCalculator
import com.ledgerflow.app.domain.CalculatorEngine
import com.ledgerflow.app.domain.FxConverter
import com.ledgerflow.app.domain.MagicRuleEngine
import com.ledgerflow.app.domain.MoneyFormatter
import com.ledgerflow.app.domain.UpdateManifest
import com.ledgerflow.app.security.VaultSessionState
import com.ledgerflow.app.service.PayAndTrackCapture
import com.ledgerflow.app.ui.theme.ThemeModes
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

    val debts: StateFlow<List<DebtEntity>> = db.debtDao().observeActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val savingsGoals: StateFlow<List<SavingsGoalEntity>> = db.savingsGoalDao().observeActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val shoppingLists: StateFlow<List<ShoppingListEntity>> = db.shoppingListDao().observeActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val shoppingItems: StateFlow<List<ShoppingItemEntity>> = db.shoppingItemDao().observeActive()
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
            db.processDueAutoPayPlannedPayments()
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

    fun currentMonthEndEpochMs(): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH))
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
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

    fun setThemeMode(mode: String) {
        val clean = ThemeModes.normalize(mode)
        _vaultState.value = vault.update { it.copy(themeMode = clean) }
    }

    fun toggleDarkLightMode(currentlyDark: Boolean) {
        val current = ThemeModes.normalize(_vaultState.value.themeMode)
        val next = when {
            !currentlyDark -> ThemeModes.FLOW_DARK
            current == ThemeModes.FLOW_DARK -> ThemeModes.PREMIUM_DARK
            else -> ThemeModes.LIGHT
        }
        setThemeMode(next)
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
        val clean = MoneyFormatter.toTitleCaseName(name)
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
        val cleanName = MoneyFormatter.toTitleCaseName(name)
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
        fallbackCategoryId: String? = null,
        selectedCategoryIds: List<String> = emptyList(),
    ): Boolean {
        val magnitudeMinor = CalculatorEngine.evaluateToMinor(amountExpr, currency)
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
            val cleanExtraIds = selectedCategoryIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            val primaryFromList = cleanExtraIds.firstOrNull()
            val effectiveCategoryId = manualCategoryId ?: primaryFromList ?: ruleMatch.categoryId ?: fallbackCategoryId
            val allCategoryIds = buildList {
                effectiveCategoryId?.let { add(it) }
                cleanExtraIds.forEach { if (!contains(it)) add(it) }
            }
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
                categoryIdsCsv = if (allCategoryIds.size > 1) allCategoryIds.joinToString(",") else "",
                txType = txType,
                amountMinor = signedMinor,
                currency = currency,
                convertedMinor = converted,
                convertedCurrency = if (converted != null) baseCurrency else null,
                merchant = cleanMerchant,
                note = note.trim(),
                labelsCsv = mergedLabels,
                sourceTag = "MANUAL",
                autoRuleApplied = manualCategoryId == null && primaryFromList == null && ruleMatch.matched,
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
        val cleanName = name.trim()
        val cleanCur = currency.trim().uppercase()
        if (cleanName.isBlank() || limitMinor <= 0L || cleanCur.length != 3) {
            _statusMessage.value = "Enter a valid budget name and positive limit"
            return
        }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            db.budgetDao().upsert(
                BudgetEntity(
                    id = UUID.randomUUID().toString(),
                    categoryId = categoryId,
                    name = cleanName,
                    limitMinor = limitMinor,
                    currency = cleanCur,
                    period = "MONTHLY",
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now,
                    pendingSync = true,
                )
            )
            SyncWorker.enqueueWhenOnline(app)
            _statusMessage.value = "Budget \"$cleanName\" added"
        }
    }

    fun addPlannedPayment(
        title: String,
        amountMinor: Long,
        currency: String,
        accountId: String?,
        categoryId: String?,
        cadence: String,
        txType: String = "EXPENSE",
        nextDueAtEpochMs: Long? = null,
        autoPay: Boolean = false,
    ) {
        val cleanTitle = title.trim()
        val cleanCur = currency.trim().uppercase()
        if (cleanTitle.isBlank() || amountMinor <= 0L || cleanCur.length != 3) {
            _statusMessage.value = "Enter valid planned payment title & amount"
            return
        }
        val normCadence = when (cadence.trim().uppercase()) {
            "WEEKLY" -> "WEEKLY"
            "YEARLY" -> "YEARLY"
            else -> "MONTHLY"
        }
        val normType = if (txType.trim().equals("INCOME", ignoreCase = true)) "INCOME" else "EXPENSE"
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val dueAt = nextDueAtEpochMs ?: LedgerDatabase.advanceDueDateEpochMs(now, normCadence)
            val resolvedAccountId = accountId
                ?.takeIf { id -> accounts.value.any { it.id == id && it.deletedAtEpochMs == null } }
                ?: accounts.value.firstOrNull { !AccountTypeCatalog.isLiability(it.accountType) }?.id
                ?: accounts.value.firstOrNull()?.id
            db.plannedPaymentDao().upsert(
                PlannedPaymentEntity(
                    id = UUID.randomUUID().toString(),
                    accountId = resolvedAccountId,
                    categoryId = categoryId,
                    title = cleanTitle,
                    amountMinor = abs(amountMinor),
                    currency = cleanCur,
                    txType = normType,
                    cadence = normCadence,
                    nextDueAtEpochMs = dueAt,
                    autoPay = autoPay,
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now,
                    pendingSync = true,
                )
            )
            if (autoPay && dueAt <= now) {
                db.processDueAutoPayPlannedPayments(now)
            }
            SyncWorker.enqueueWhenOnline(app)
            _statusMessage.value = if (normType == "INCOME") "Scheduled income added" else "Planned payment added"
        }
    }

    fun payPlannedNow(plannedId: String) {
        viewModelScope.launch {
            val fallback = accounts.value.firstOrNull { !AccountTypeCatalog.isLiability(it.accountType) }?.id
                ?: accounts.value.firstOrNull()?.id
            val recorded = db.executePlannedPaymentAtomic(plannedId, fallback)
            if (recorded) {
                SyncWorker.enqueueWhenOnline(app)
                _statusMessage.value = "Planned payment recorded"
            } else {
                _statusMessage.value = "Add an active account first to record planned payments"
            }
        }
    }

    fun skipPlannedPayment(plannedId: String) {
        viewModelScope.launch {
            if (db.skipPlannedPaymentAtomic(plannedId)) {
                SyncWorker.enqueueWhenOnline(app)
                _statusMessage.value = "Skipped to next due date"
            }
        }
    }

    fun togglePlannedAutoPay(plannedId: String, enabled: Boolean) {
        viewModelScope.launch {
            val existing = db.plannedPaymentDao().getById(plannedId) ?: return@launch
            if (existing.deletedAtEpochMs != null) return@launch
            val now = System.currentTimeMillis()
            db.plannedPaymentDao().upsert(
                existing.copy(
                    autoPay = enabled,
                    updatedAtEpochMs = now,
                    pendingSync = true,
                )
            )
            if (enabled && existing.nextDueAtEpochMs <= now) {
                db.processDueAutoPayPlannedPayments(now)
            }
            SyncWorker.enqueueWhenOnline(app)
            _statusMessage.value = if (enabled) "Auto-Pay enabled" else "Auto-Pay paused"
        }
    }

    fun addAccount(
        name: String,
        institution: String,
        type: String,
        currency: String,
        balanceMinor: Long,
        lastFour: String,
        creditLimitMinor: Long = 0L,
    ) {
        val normType = AccountTypeCatalog.normalize(type)
        val cleanName = if (normType == "CASH") name.trim().ifBlank { "Cash Wallet" } else name.trim()
        if (cleanName.isBlank()) {
            _statusMessage.value = "Account name required"
            return
        }
        val cleanCur = currency.trim().uppercase()
        if (cleanCur.length != 3) return
        val cleanLastFour = if (AccountTypeCatalog.supportsLastFour(normType)) {
            lastFour.filter(Char::isDigit).take(4)
        } else {
            ""
        }
        val normalizedMinor = AccountTypeCatalog.normalizeOpeningBalanceMinor(normType, balanceMinor)
        val safeLimit = if (normType == "CREDIT_CARD" || normType == "CARD") creditLimitMinor.coerceAtLeast(0L) else 0L
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            db.accountDao().upsert(
                AccountEntity(
                    id = UUID.randomUUID().toString(),
                    name = cleanName,
                    institution = institution.trim(),
                    accountType = normType,
                    currency = cleanCur,
                    balanceMinor = normalizedMinor,
                    lastFour = cleanLastFour,
                    creditLimitMinor = safeLimit,
                    sourceType = "MANUAL",
                    syncStatus = "OK",
                    lastSyncedAtEpochMs = now,
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now,
                    pendingSync = true,
                )
            )
            SyncWorker.enqueueWhenOnline(app)
            _statusMessage.value = "${AccountTypeCatalog.displayLabel(normType)} \"$cleanName\" added"
        }
    }

    fun updateAccountCreditLimit(accountId: String, creditLimitMinor: Long) {
        val safeLimit = creditLimitMinor.coerceAtLeast(0L)
        viewModelScope.launch {
            val existing = db.accountDao().getById(accountId) ?: return@launch
            if (existing.deletedAtEpochMs != null) return@launch
            val now = System.currentTimeMillis()
            db.accountDao().upsert(
                existing.copy(
                    creditLimitMinor = safeLimit,
                    updatedAtEpochMs = now,
                    pendingSync = true,
                )
            )
            SyncWorker.enqueueWhenOnline(app)
            _statusMessage.value = "Credit limit updated for \"${existing.name}\""
        }
    }

    fun updateAccount(
        accountId: String,
        name: String,
        institution: String,
        lastFour: String,
        creditLimitMinor: Long,
    ) {
        val cleanName = name.trim()
        if (cleanName.isBlank()) {
            _statusMessage.value = "Account name required"
            return
        }
        viewModelScope.launch {
            val existing = db.accountDao().getById(accountId) ?: return@launch
            if (existing.deletedAtEpochMs != null) return@launch
            val normType = AccountTypeCatalog.normalize(existing.accountType)
            val cleanLastFour = if (AccountTypeCatalog.supportsLastFour(normType)) {
                lastFour.filter(Char::isDigit).take(4)
            } else {
                ""
            }
            val safeLimit = if (normType == "CREDIT_CARD" || normType == "CARD") creditLimitMinor.coerceAtLeast(0L) else 0L
            val now = System.currentTimeMillis()
            db.accountDao().upsert(
                existing.copy(
                    name = cleanName,
                    institution = if (normType == "CASH") "" else institution.trim(),
                    lastFour = cleanLastFour,
                    creditLimitMinor = safeLimit,
                    updatedAtEpochMs = now,
                    pendingSync = true,
                )
            )
            SyncWorker.enqueueWhenOnline(app)
            _statusMessage.value = "Account \"$cleanName\" updated"
        }
    }

    fun addCategory(
        name: String,
        kind: String,
        parentCategoryId: String?,
        iconKey: String,
        colorHex: String,
        onCreated: (CategoryEntity) -> Unit = {},
    ) {
        val cleanName = name.trim()
        if (cleanName.isBlank()) {
            _statusMessage.value = "Enter a category name"
            return
        }
        val cleanKind = if (kind.trim().uppercase() == "INCOME") "INCOME" else "EXPENSE"
        val cleanIcon = iconKey.trim().ifBlank { "category" }
        val cleanColor = colorHex.trim().ifBlank { "#059669" }
        viewModelScope.launch {
            val existingParent = parentCategoryId?.let { db.categoryDao().getById(it) }?.takeIf { it.deletedAtEpochMs == null }
            val resolvedKind = existingParent?.kind ?: cleanKind
            val now = System.currentTimeMillis()
            val created = CategoryEntity(
                id = UUID.randomUUID().toString(),
                name = cleanName,
                kind = resolvedKind,
                iconKey = existingParent?.iconKey ?: cleanIcon,
                colorHex = existingParent?.colorHex ?: cleanColor,
                parentCategoryId = existingParent?.id,
                createdAtEpochMs = now,
                updatedAtEpochMs = now,
                pendingSync = true,
            )
            db.categoryDao().upsert(created)
            SyncWorker.enqueueWhenOnline(app)
            onCreated(created)
            _statusMessage.value = if (existingParent != null) {
                "Subcategory \"$cleanName\" added under ${existingParent.name}"
            } else {
                "Category \"$cleanName\" added"
            }
        }
    }

    fun updateCategory(
        categoryId: String,
        name: String,
        kind: String,
        parentCategoryId: String?,
        iconKey: String,
        colorHex: String,
    ) {
        val cleanName = name.trim()
        if (cleanName.isBlank()) {
            _statusMessage.value = "Enter a category name"
            return
        }
        val cleanKind = if (kind.trim().uppercase() == "INCOME") "INCOME" else "EXPENSE"
        val cleanIcon = iconKey.trim().ifBlank { "category" }
        val cleanColor = colorHex.trim().ifBlank { "#059669" }
        viewModelScope.launch {
            val existing = db.categoryDao().getById(categoryId) ?: return@launch
            if (existing.deletedAtEpochMs != null) return@launch
            val safeParentId = parentCategoryId?.takeIf { it != categoryId }
            val resolvedParent = safeParentId?.let { db.categoryDao().getById(it) }
                ?.takeIf { it.deletedAtEpochMs == null && it.parentCategoryId == null }
            val resolvedKind = resolvedParent?.kind ?: cleanKind
            val now = System.currentTimeMillis()
            val updated = existing.copy(
                name = cleanName,
                kind = resolvedKind,
                iconKey = cleanIcon,
                colorHex = cleanColor,
                parentCategoryId = resolvedParent?.id,
                updatedAtEpochMs = now,
                pendingSync = true,
            )
            val allActive = db.categoryDao().getAllActive()
            val updates = buildList {
                add(updated)
                if (updated.parentCategoryId == null && updated.kind != existing.kind) {
                    allActive
                        .filter { it.parentCategoryId == updated.id && it.kind != updated.kind }
                        .forEach { child ->
                            add(child.copy(kind = updated.kind, updatedAtEpochMs = now, pendingSync = true))
                        }
                }
            }
            db.categoryDao().upsertAll(updates)
            SyncWorker.enqueueWhenOnline(app)
            _statusMessage.value = "\"$cleanName\" updated"
        }
    }

    fun deleteCategories(ids: Set<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val all = db.categoryDao().getAllActive()
            val toDeleteIds = HashSet(ids)
            // Also delete subcategories whose parent is being deleted
            all.filter { it.parentCategoryId != null && it.parentCategoryId in ids }.forEach { toDeleteIds.add(it.id) }
            val updated = all
                .filter { it.id in toDeleteIds }
                .map { it.copy(deletedAtEpochMs = now, updatedAtEpochMs = now, pendingSync = true) }
            if (updated.isNotEmpty()) {
                db.categoryDao().upsertAll(updated)
                SyncWorker.enqueueWhenOnline(app)
                _statusMessage.value = "Removed ${updated.size} category item(s)"
            }
        }
    }

    fun shareTextReport(title: String, fileName: String, contents: String, mimeType: String = "text/csv", onShareIntent: (Intent) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val dir = File(app.cacheDir, "exports").apply { mkdirs() }
                val file = File(dir, fileName)
                file.writeText(contents, Charsets.UTF_8)
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, title)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(intent, title)
                withContext(Dispatchers.Main) { onShareIntent(chooser) }
            }.onFailure { err ->
                withContext(Dispatchers.Main) {
                    _statusMessage.value = "Failed to export report: ${err.message ?: "error"}"
                }
            }
        }
    }

    fun saveReportToUri(uri: Uri, contents: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                app.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(contents.toByteArray(Charsets.UTF_8))
                } ?: error("Cannot open destination file")
            }.onSuccess {
                withContext(Dispatchers.Main) {
                    _statusMessage.value = "Report saved to Downloads / Drive"
                }
            }.onFailure { err ->
                withContext(Dispatchers.Main) {
                    _statusMessage.value = "Save failed: ${err.message ?: "error"}"
                }
            }
        }
    }

    fun repayDebtAccount(
        fromAccountId: String,
        debtAccountId: String,
        amountMinor: Long,
        note: String = "",
    ): Boolean {
        if (amountMinor <= 0L || fromAccountId == debtAccountId) {
            _statusMessage.value = "Select a source account and positive repayment amount"
            return false
        }
        val fromAcc = accounts.value.firstOrNull { it.id == fromAccountId } ?: return false
        val debtAcc = accounts.value.firstOrNull { it.id == debtAccountId } ?: return false
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val tx = TransactionEntity(
                id = UUID.randomUUID().toString(),
                accountId = fromAcc.id,
                transferAccountId = debtAcc.id,
                categoryId = null,
                txType = "TRANSFER",
                amountMinor = abs(amountMinor),
                currency = fromAcc.currency,
                merchant = "Repay ${debtAcc.name}",
                note = note.trim().ifBlank { "${AccountTypeCatalog.displayLabel(debtAcc.accountType)} repayment" },
                labelsCsv = "repayment",
                sourceTag = "MANUAL",
                occurredAtEpochMs = now,
                createdAtEpochMs = now,
                updatedAtEpochMs = now,
                pendingSync = true,
            )
            db.recordTransactionAtomic(tx)
            SyncWorker.enqueueWhenOnline(app)
            _statusMessage.value = "Repayment recorded for \"${debtAcc.name}\""
        }
        return true
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
        if (symbol.isBlank() || !quantity.isFinite() || quantity <= 0.0 || quantity > 1_000_000_000.0 || currentPriceMinor <= 0L) {
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

    // ---- Debts (Lent & Borrowed) ----

    fun addDebt(
        personName: String,
        direction: String,
        amountMinor: Long,
        currency: String,
        accountId: String?,
        dueAtEpochMs: Long?,
        note: String,
        createInitialTransaction: Boolean,
    ) {
        val cleanPerson = personName.trim()
        val cleanCur = currency.trim().uppercase()
        if (cleanPerson.isBlank() || amountMinor <= 0L || cleanCur.length != 3) {
            _statusMessage.value = "Enter a valid person name and positive amount"
            return
        }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val debt = DebtEntity(
                id = UUID.randomUUID().toString(),
                personName = cleanPerson,
                direction = if (direction.equals("BORROWED", ignoreCase = true)) "BORROWED" else "LENT",
                totalAmountMinor = abs(amountMinor),
                remainingAmountMinor = abs(amountMinor),
                currency = cleanCur,
                accountId = accountId,
                dueAtEpochMs = dueAtEpochMs,
                note = note.trim(),
                status = "ACTIVE",
                createdAtEpochMs = now,
                updatedAtEpochMs = now,
                pendingSync = true,
            )
            if (db.createDebtAtomic(debt, createInitialTransaction = createInitialTransaction)) {
                SyncWorker.enqueueWhenOnline(app)
                _statusMessage.value = if (debt.direction == "LENT") {
                    "Logged amount lent to $cleanPerson"
                } else {
                    "Logged amount borrowed from $cleanPerson"
                }
            }
        }
    }

    fun updateDebt(
        debtId: String,
        personName: String,
        direction: String,
        totalAmountMinor: Long,
        accountId: String?,
        dueAtEpochMs: Long?,
        note: String,
    ) {
        val cleanPerson = personName.trim()
        if (cleanPerson.isBlank() || totalAmountMinor <= 0L) {
            _statusMessage.value = "Enter a valid name and positive amount"
            return
        }
        viewModelScope.launch {
            val existing = db.debtDao().getById(debtId) ?: return@launch
            if (existing.deletedAtEpochMs != null) return@launch
            val now = System.currentTimeMillis()
            val cleanTotal = abs(totalAmountMinor)
            val paidSoFar = (existing.totalAmountMinor - existing.remainingAmountMinor).coerceAtLeast(0L)
            val newRemaining = (cleanTotal - paidSoFar).coerceAtLeast(0L)
            val newStatus = if (newRemaining == 0L) "CLOSED" else "ACTIVE"
            db.debtDao().upsert(
                existing.copy(
                    personName = cleanPerson,
                    direction = if (direction.equals("BORROWED", ignoreCase = true)) "BORROWED" else "LENT",
                    totalAmountMinor = cleanTotal,
                    remainingAmountMinor = newRemaining,
                    accountId = accountId,
                    dueAtEpochMs = dueAtEpochMs,
                    note = note.trim(),
                    status = newStatus,
                    updatedAtEpochMs = now,
                    pendingSync = true,
                )
            )
            _statusMessage.value = "Debt record updated"
        }
    }

    fun recordDebtRepayment(
        debtId: String,
        amountMinor: Long,
        accountId: String?,
        recordTransaction: Boolean,
    ) {
        if (amountMinor <= 0L) {
            _statusMessage.value = "Enter a positive repayment amount"
            return
        }
        viewModelScope.launch {
            val ok = db.recordDebtRepaymentAtomic(
                debtId = debtId,
                amountMinor = amountMinor,
                accountId = accountId,
                recordTransaction = recordTransaction,
            )
            if (ok) {
                SyncWorker.enqueueWhenOnline(app)
                val updated = db.debtDao().getById(debtId)
                _statusMessage.value = if (updated?.status == "CLOSED") {
                    "Debt settled and moved to Closed"
                } else {
                    "Repayment recorded"
                }
            } else {
                _statusMessage.value = "Unable to record repayment"
            }
        }
    }

    fun deleteDebt(debtId: String) {
        viewModelScope.launch {
            val existing = db.debtDao().getById(debtId) ?: return@launch
            val now = System.currentTimeMillis()
            db.debtDao().upsert(existing.copy(deletedAtEpochMs = now, updatedAtEpochMs = now, pendingSync = true))
            _statusMessage.value = "Debt deleted"
        }
    }

    // ---- Savings Goals ----

    fun addSavingsGoal(
        name: String,
        targetAmountMinor: Long,
        alreadySavedMinor: Long,
        currency: String,
        targetDateEpochMs: Long?,
        iconKey: String,
        colorHex: String,
        linkedAccountId: String?,
    ) {
        val cleanName = name.trim()
        val cleanCur = currency.trim().uppercase()
        if (cleanName.isBlank() || targetAmountMinor <= 0L || cleanCur.length != 3) {
            _statusMessage.value = "Enter a valid goal name and positive target"
            return
        }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val target = abs(targetAmountMinor)
            val saved = abs(alreadySavedMinor)
            val status = if (saved >= target) "REACHED" else "ACTIVE"
            db.savingsGoalDao().upsert(
                SavingsGoalEntity(
                    id = UUID.randomUUID().toString(),
                    name = cleanName,
                    targetAmountMinor = target,
                    savedAmountMinor = saved,
                    currency = cleanCur,
                    targetDateEpochMs = targetDateEpochMs,
                    iconKey = iconKey.ifBlank { "savings" },
                    colorHex = colorHex.ifBlank { "#059669" },
                    linkedAccountId = linkedAccountId,
                    status = status,
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now,
                    pendingSync = true,
                )
            )
            _statusMessage.value = "Goal \"$cleanName\" created"
        }
    }

    fun updateSavingsGoal(
        goalId: String,
        name: String,
        targetAmountMinor: Long,
        alreadySavedMinor: Long,
        targetDateEpochMs: Long?,
        iconKey: String,
        colorHex: String,
        linkedAccountId: String?,
    ) {
        val cleanName = name.trim()
        if (cleanName.isBlank() || targetAmountMinor <= 0L) {
            _statusMessage.value = "Enter a valid goal name and target"
            return
        }
        viewModelScope.launch {
            val existing = db.savingsGoalDao().getById(goalId) ?: return@launch
            if (existing.deletedAtEpochMs != null) return@launch
            val now = System.currentTimeMillis()
            val target = abs(targetAmountMinor)
            val saved = abs(alreadySavedMinor)
            val status = when {
                saved >= target -> "REACHED"
                existing.status == "PAUSED" -> "PAUSED"
                else -> "ACTIVE"
            }
            db.savingsGoalDao().upsert(
                existing.copy(
                    name = cleanName,
                    targetAmountMinor = target,
                    savedAmountMinor = saved,
                    targetDateEpochMs = targetDateEpochMs,
                    iconKey = iconKey.ifBlank { existing.iconKey },
                    colorHex = colorHex.ifBlank { existing.colorHex },
                    linkedAccountId = linkedAccountId,
                    status = status,
                    updatedAtEpochMs = now,
                    pendingSync = true,
                )
            )
            _statusMessage.value = "Goal updated"
        }
    }

    fun addGoalFunds(
        goalId: String,
        amountMinor: Long,
        accountId: String?,
        deductFromAccount: Boolean,
    ) {
        if (amountMinor <= 0L) {
            _statusMessage.value = "Enter a positive contribution amount"
            return
        }
        viewModelScope.launch {
            val ok = db.addGoalContributionAtomic(
                goalId = goalId,
                amountMinor = amountMinor,
                accountId = accountId,
                deductFromAccount = deductFromAccount,
            )
            if (ok) {
                if (deductFromAccount && accountId != null) SyncWorker.enqueueWhenOnline(app)
                val updated = db.savingsGoalDao().getById(goalId)
                _statusMessage.value = if (updated?.status == "REACHED") {
                    "Goal reached!"
                } else {
                    "Contribution added to goal"
                }
            }
        }
    }

    fun setGoalPaused(goalId: String, paused: Boolean) {
        viewModelScope.launch {
            val existing = db.savingsGoalDao().getById(goalId) ?: return@launch
            if (existing.deletedAtEpochMs != null) return@launch
            val now = System.currentTimeMillis()
            val nextStatus = when {
                existing.savedAmountMinor >= existing.targetAmountMinor -> "REACHED"
                paused -> "PAUSED"
                else -> "ACTIVE"
            }
            db.savingsGoalDao().upsert(
                existing.copy(
                    status = nextStatus,
                    updatedAtEpochMs = now,
                    pendingSync = true,
                )
            )
            _statusMessage.value = if (nextStatus == "PAUSED") "Goal paused" else "Goal resumed"
        }
    }

    fun deleteSavingsGoal(goalId: String) {
        viewModelScope.launch {
            val existing = db.savingsGoalDao().getById(goalId) ?: return@launch
            val now = System.currentTimeMillis()
            db.savingsGoalDao().upsert(existing.copy(deletedAtEpochMs = now, updatedAtEpochMs = now, pendingSync = true))
            _statusMessage.value = "Goal deleted"
        }
    }

    // ---- Shopping Lists ----

    fun createShoppingList(
        title: String,
        defaultCategoryId: String?,
        onCreated: (String) -> Unit = {},
    ) {
        val cleanTitle = title.trim()
        if (cleanTitle.isBlank()) {
            _statusMessage.value = "Enter a checklist name"
            return
        }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val id = UUID.randomUUID().toString()
            db.shoppingListDao().upsert(
                ShoppingListEntity(
                    id = id,
                    title = cleanTitle,
                    defaultCategoryId = defaultCategoryId,
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now,
                    pendingSync = true,
                )
            )
            _statusMessage.value = "Checklist \"$cleanTitle\" created"
            onCreated(id)
        }
    }

    fun deleteShoppingList(listId: String) {
        viewModelScope.launch {
            val existing = db.shoppingListDao().getById(listId) ?: return@launch
            val now = System.currentTimeMillis()
            db.shoppingListDao().upsert(existing.copy(deletedAtEpochMs = now, updatedAtEpochMs = now, pendingSync = true))
            val items = db.shoppingItemDao().getActiveForList(listId)
            if (items.isNotEmpty()) {
                db.shoppingItemDao().upsertAll(
                    items.map { it.copy(deletedAtEpochMs = now, updatedAtEpochMs = now, pendingSync = true) }
                )
            }
            _statusMessage.value = "Checklist deleted"
        }
    }

    fun addShoppingItem(
        listId: String,
        name: String,
        estimatedPriceMinor: Long,
    ) {
        val cleanName = name.trim()
        if (cleanName.isBlank()) {
            _statusMessage.value = "Enter an item name"
            return
        }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            db.shoppingItemDao().upsert(
                ShoppingItemEntity(
                    id = UUID.randomUUID().toString(),
                    listId = listId,
                    name = cleanName,
                    estimatedPriceMinor = abs(estimatedPriceMinor),
                    checked = false,
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now,
                    pendingSync = true,
                )
            )
        }
    }

    fun toggleShoppingItem(itemId: String) {
        viewModelScope.launch {
            val existing = db.shoppingItemDao().getById(itemId) ?: return@launch
            if (existing.deletedAtEpochMs != null) return@launch
            val now = System.currentTimeMillis()
            db.shoppingItemDao().upsert(
                existing.copy(
                    checked = !existing.checked,
                    updatedAtEpochMs = now,
                    pendingSync = true,
                )
            )
        }
    }

    fun deleteShoppingItem(itemId: String) {
        viewModelScope.launch {
            val existing = db.shoppingItemDao().getById(itemId) ?: return@launch
            val now = System.currentTimeMillis()
            db.shoppingItemDao().upsert(existing.copy(deletedAtEpochMs = now, updatedAtEpochMs = now, pendingSync = true))
        }
    }

    fun convertCheckedShoppingToExpense(
        listId: String,
        itemIds: List<String>,
        accountId: String,
        categoryId: String?,
        totalMinor: Long,
        currency: String,
        merchantTitle: String,
        removeConvertedItems: Boolean = true,
        onSuccess: () -> Unit = {},
    ) {
        if (totalMinor <= 0L || itemIds.isEmpty()) {
            _statusMessage.value = "Enter a valid positive total to log as expense"
            return
        }
        viewModelScope.launch {
            val ok = db.convertShoppingItemsToExpenseAtomic(
                listId = listId,
                itemIds = itemIds,
                accountId = accountId,
                categoryId = categoryId,
                totalMinor = totalMinor,
                currency = currency,
                merchantTitle = merchantTitle,
                removeConvertedItems = removeConvertedItems,
            )
            if (ok) {
                SyncWorker.enqueueWhenOnline(app)
                val formatted = MoneyFormatter.formatMinor(totalMinor, currency)
                _statusMessage.value = "Logged $formatted expense from checklist"
                onSuccess()
            } else {
                _statusMessage.value = "Select an active account first"
            }
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
        viewModelScope.launch {
            db.processDueAutoPayPlannedPayments()
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
