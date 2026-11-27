package com.ledgerflow.app.data.sync

import android.content.Context
import androidx.room.withTransaction
import com.ledgerflow.app.data.local.AccountEntity
import com.ledgerflow.app.data.local.BudgetEntity
import com.ledgerflow.app.data.local.CategoryEntity
import com.ledgerflow.app.data.local.DebtEntity
import com.ledgerflow.app.data.local.HoldingEntity
import com.ledgerflow.app.data.local.LedgerDatabase
import com.ledgerflow.app.data.local.MagicRuleEntity
import com.ledgerflow.app.data.local.PlannedPaymentEntity
import com.ledgerflow.app.data.local.SavingsGoalEntity
import com.ledgerflow.app.data.local.SeedData
import com.ledgerflow.app.data.local.ShoppingItemEntity
import com.ledgerflow.app.data.local.ShoppingListEntity
import com.ledgerflow.app.data.local.TransactionEntity
import com.ledgerflow.app.data.remote.IsoTime
import com.ledgerflow.app.data.remote.SupabaseAccountDto
import com.ledgerflow.app.data.remote.SupabaseBudgetDto
import com.ledgerflow.app.data.remote.SupabaseCategoryDto
import com.ledgerflow.app.data.remote.SupabaseHoldingDto
import com.ledgerflow.app.data.remote.SupabaseMagicRuleDto
import com.ledgerflow.app.data.remote.SupabasePlannedDto
import com.ledgerflow.app.data.remote.SupabaseSyncClient
import com.ledgerflow.app.data.remote.SupabaseTransactionDto
import com.ledgerflow.app.domain.AccountTypeCatalog
import com.ledgerflow.app.domain.FxConverter
import com.ledgerflow.app.domain.ImportParser
import com.ledgerflow.app.domain.MagicRuleEngine
import com.ledgerflow.app.domain.ParsedImportResult
import com.ledgerflow.app.security.VaultStore
import com.ledgerflow.app.util.NetworkMonitor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

sealed class SyncOutcome {
    data class Success(val pushedCount: Int, val pulledCount: Int) : SyncOutcome()
    data class LocalOnly(val reason: String, val pendingCount: Int) : SyncOutcome()
    data class Error(val message: String) : SyncOutcome()
}

data class DataImportOutcome(
    val importedTransactions: Int,
    val importedAccounts: Int = 0,
    val importedOtherItems: Int = 0,
    val formatLabel: String = "CSV",
    val message: String,
)

@Serializable
data class VaultDebtDto(
    val id: String,
    val personName: String,
    val direction: String,
    val totalAmountMinor: Long,
    val remainingAmountMinor: Long,
    val currency: String,
    val accountId: String? = null,
    val dueAtEpochMs: Long? = null,
    val note: String = "",
    val status: String = "ACTIVE",
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val deletedAtEpochMs: Long? = null,
)

@Serializable
data class VaultSavingsGoalDto(
    val id: String,
    val name: String,
    val targetAmountMinor: Long,
    val savedAmountMinor: Long = 0L,
    val currency: String,
    val targetDateEpochMs: Long? = null,
    val iconKey: String = "savings",
    val colorHex: String = "#059669",
    val linkedAccountId: String? = null,
    val status: String = "ACTIVE",
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val deletedAtEpochMs: Long? = null,
)

@Serializable
data class VaultShoppingListDto(
    val id: String,
    val title: String,
    val defaultCategoryId: String? = null,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val deletedAtEpochMs: Long? = null,
)

@Serializable
data class VaultShoppingItemDto(
    val id: String,
    val listId: String,
    val name: String,
    val quantityLabel: String = "",
    val estimatedPriceMinor: Long = 0L,
    val checked: Boolean = false,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val deletedAtEpochMs: Long? = null,
)

@Serializable
data class LedgerVaultBackup(
    val exportedAt: String,
    val baseCurrency: String,
    val accounts: List<SupabaseAccountDto>,
    val categories: List<SupabaseCategoryDto>,
    val transactions: List<SupabaseTransactionDto>,
    val budgets: List<SupabaseBudgetDto>,
    val plannedPayments: List<SupabasePlannedDto>,
    val holdings: List<SupabaseHoldingDto>,
    val magicRules: List<SupabaseMagicRuleDto>,
    val debts: List<VaultDebtDto> = emptyList(),
    val savingsGoals: List<VaultSavingsGoalDto> = emptyList(),
    val shoppingLists: List<VaultShoppingListDto> = emptyList(),
    val shoppingItems: List<VaultShoppingItemDto> = emptyList(),
)

class LedgerSyncRepository(
    private val context: Context,
    private val db: LedgerDatabase,
    private val vault: VaultStore,
    private val remote: SupabaseSyncClient = SupabaseSyncClient(),
) {
    private val syncMutex = Mutex()
    private val exportJson = Json { prettyPrint = true; encodeDefaults = true }
    private val importJson = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun countPendingChanges(): Int =
        db.accountDao().getDirty().size +
            db.categoryDao().getDirty().size +
            db.transactionDao().getDirty().count {
                it.reviewStatus != TransactionEntity.STATUS_PENDING_REVIEW && it.accountId.isNotBlank()
            } +
            db.budgetDao().getDirty().size +
            db.plannedPaymentDao().getDirty().size +
            db.holdingDao().getDirty().size +
            db.magicRuleDao().getDirty().size

    fun restoreCachedFxRates() {
        val state = vault.read()
        val raw = state.cachedFxRatesJson ?: return
        val map = runCatching {
            remote.json.decodeFromString(
                kotlinx.serialization.builtins.MapSerializer(
                    kotlinx.serialization.serializer<String>(),
                    kotlinx.serialization.serializer<Double>(),
                ),
                raw,
            )
        }.getOrNull() ?: return
        val label = state.cachedFxDate?.takeIf { it.isNotBlank() }?.let { "ECB ($it)" } ?: "Cached ECB"
        FxConverter.updateRatesFromUsdBase(map, sourceLabel = label)
    }

    suspend fun refreshFxRatesIfOnline(): Boolean {
        restoreCachedFxRates()
        if (!NetworkMonitor.isOnline(context)) return false
        val fx = remote.fetchLiveFxRatesOrNull("USD") ?: return false
        if (fx.rates.isEmpty()) return false
        val label = fx.date.takeIf { it.isNotBlank() }?.let { "ECB ($it)" } ?: "Live ECB"
        FxConverter.updateRatesFromUsdBase(fx.rates, sourceLabel = label)
        val serialized = runCatching {
            remote.json.encodeToString(
                kotlinx.serialization.builtins.MapSerializer(
                    kotlinx.serialization.serializer<String>(),
                    kotlinx.serialization.serializer<Double>(),
                ),
                fx.rates,
            )
        }.getOrNull()
        if (!serialized.isNullOrBlank()) {
            vault.update {
                it.copy(
                    cachedFxRatesJson = serialized,
                    cachedFxDate = fx.date.ifBlank { it.cachedFxDate },
                )
            }
        }
        return true
    }

    suspend fun syncNow(): SyncOutcome = syncMutex.withLock {
        val pending = countPendingChanges()
        if (!NetworkMonitor.isOnline(context)) {
            return SyncOutcome.LocalOnly("Offline — changes saved locally", pending)
        }

        // Refresh public ECB FX rates quietly in background if available.
        refreshFxRatesIfOnline()

        var state = vault.read()
        var token = state.accessToken
        val userId = state.userId
        if (token.isNullOrBlank() || userId.isNullOrBlank()) {
            return SyncOutcome.LocalOnly("Local-only vault (Sign in via Privacy & Vault for cloud sync)", pending)
        }

        try {
            // Isolate local DB if user explicitly switched to a different cloud account.
            if (!state.lastCloudUserId.isNullOrBlank() && state.lastCloudUserId != userId) {
                db.clearAllTables()
                state = vault.update { it.copy(lastCloudUserId = userId, lastSyncWatermarkIso = null, lastSyncEpochMs = 0L) }
            }

            // Validate/refresh session on 401 automatically
            val pushCode = pushAllDirty(userId, token)
            if (pushCode == 401) {
                token = refreshAccessTokenOrNull(state.refreshToken) ?: return SyncOutcome.Error("Session expired; sign in again")
                val retryPush = pushAllDirty(userId, token)
                if (retryPush !in 200..299) {
                    return SyncOutcome.Error("Cloud push failed (HTTP $retryPush)")
                }
            } else if (pushCode !in 200..299) {
                return SyncOutcome.Error("Cloud push failed (HTTP $pushCode)")
            }

            val watermark = vault.read().lastSyncWatermarkIso
            var pullOutcome = pullAllDelta(watermark, token)
            if (pullOutcome.statusCode == 401) {
                token = refreshAccessTokenOrNull(vault.read().refreshToken) ?: return SyncOutcome.Error("Session expired; sign in again")
                pullOutcome = pullAllDelta(watermark, token)
            }
            if (pullOutcome.statusCode !in 200..299) {
                return SyncOutcome.Error("Cloud pull failed (HTTP ${pullOutcome.statusCode})")
            }

            val nowMs = System.currentTimeMillis()
            val nextWatermark = pullOutcome.maxServerUpdatedAtMs
                ?.let { maxMs ->
                    val existingMs = watermark?.let { IsoTime.parseOr(it, 0L) } ?: 0L
                    IsoTime.format(maxOf(existingMs, maxMs))
                }
                ?: watermark

            vault.update {
                it.copy(
                    lastCloudUserId = userId,
                    lastSyncEpochMs = nowMs,
                    lastSyncWatermarkIso = nextWatermark,
                )
            }
            SyncOutcome.Success(pushedCount = pending, pulledCount = pullOutcome.pulledCount)
        } catch (e: Exception) {
            SyncOutcome.LocalOnly(e.message ?: "Cloud unreachable — offline cache active", countPendingChanges())
        }
    }

    private suspend fun refreshAccessTokenOrNull(refreshToken: String?): String? {
        if (refreshToken.isNullOrBlank()) return null
        val refreshed = remote.refreshSession(refreshToken)
        if (!refreshed.success || refreshed.accessToken.isNullOrBlank()) return null
        vault.update {
            it.copy(
                accessToken = refreshed.accessToken,
                refreshToken = refreshed.refreshToken ?: it.refreshToken,
            )
        }
        return refreshed.accessToken
    }

    private data class PullResult(
        val statusCode: Int,
        val pulledCount: Int,
        val maxServerUpdatedAtMs: Long?,
    )

    private suspend fun <E, D> pushDirtyTable(
        table: String,
        dirty: List<E>,
        serializer: kotlinx.serialization.KSerializer<D>,
        token: String,
        idOf: (E) -> String,
        toDto: (E) -> D,
        markSynced: suspend (List<String>) -> Unit,
    ): Int {
        if (dirty.isEmpty()) return 200
        val code = remote.upsertTable(table, dirty.map(toDto), serializer, token)
        if (code !in 200..299) return code
        markSynced(dirty.map(idOf))
        return 200
    }

    private suspend fun pushAllDirty(userId: String, token: String): Int {
        // Only confirmed, account-assigned transactions may leave device; raw notification metadata stays local.
        val allDirtyTx = db.transactionDao().getDirty()
        val deletedUnreviewed = allDirtyTx.filter {
            it.deletedAtEpochMs != null && it.reviewStatus == TransactionEntity.STATUS_PENDING_REVIEW
        }
        if (deletedUnreviewed.isNotEmpty()) {
            db.transactionDao().markSynced(deletedUnreviewed.map { it.id })
        }
        val syncableTx = allDirtyTx.filter {
            it.reviewStatus != TransactionEntity.STATUS_PENDING_REVIEW && it.accountId.isNotBlank()
        }

        val codes = listOf(
            pushDirtyTable("accounts", db.accountDao().getDirty(), SupabaseAccountDto.serializer(), token, { it.id }, { it.toRemoteDto(userId) }, db.accountDao()::markSynced),
            pushDirtyTable("categories", db.categoryDao().getDirty(), SupabaseCategoryDto.serializer(), token, { it.id }, { it.toDto(userId) }, db.categoryDao()::markSynced),
            pushDirtyTable("transactions", syncableTx, SupabaseTransactionDto.serializer(), token, { it.id }, { it.toRemoteDto(userId) }, db.transactionDao()::markSynced),
            pushDirtyTable("budgets", db.budgetDao().getDirty(), SupabaseBudgetDto.serializer(), token, { it.id }, { it.toDto(userId) }, db.budgetDao()::markSynced),
            pushDirtyTable("planned_payments", db.plannedPaymentDao().getDirty(), SupabasePlannedDto.serializer(), token, { it.id }, { it.toDto(userId) }, db.plannedPaymentDao()::markSynced),
            pushDirtyTable("holdings", db.holdingDao().getDirty(), SupabaseHoldingDto.serializer(), token, { it.id }, { it.toDto(userId) }, db.holdingDao()::markSynced),
            pushDirtyTable("magic_rules", db.magicRuleDao().getDirty(), SupabaseMagicRuleDto.serializer(), token, { it.id }, { it.toDto(userId) }, db.magicRuleDao()::markSynced),
        )
        return codes.firstOrNull { it !in 200..299 } ?: 200
    }

    private suspend fun pullAllDelta(sinceIso: String?, token: String): PullResult {
        val now = System.currentTimeMillis()
        val (accCode, accDtos) = remote.pullTableSince("accounts", sinceIso, SupabaseAccountDto.serializer(), token)
        if (accCode !in 200..299) return PullResult(accCode, 0, null)
        val (catCode, catDtos) = remote.pullTableSince("categories", sinceIso, SupabaseCategoryDto.serializer(), token)
        if (catCode !in 200..299) return PullResult(catCode, 0, null)
        val (txCode, txDtos) = remote.pullTableSince("transactions", sinceIso, SupabaseTransactionDto.serializer(), token)
        if (txCode !in 200..299) return PullResult(txCode, 0, null)
        val (bdgCode, bdgDtos) = remote.pullTableSince("budgets", sinceIso, SupabaseBudgetDto.serializer(), token)
        if (bdgCode !in 200..299) return PullResult(bdgCode, 0, null)
        val (plnCode, plnDtos) = remote.pullTableSince("planned_payments", sinceIso, SupabasePlannedDto.serializer(), token)
        if (plnCode !in 200..299) return PullResult(plnCode, 0, null)
        val (hldCode, hldDtos) = remote.pullTableSince("holdings", sinceIso, SupabaseHoldingDto.serializer(), token)
        if (hldCode !in 200..299) return PullResult(hldCode, 0, null)
        val (rleCode, rleDtos) = remote.pullTableSince("magic_rules", sinceIso, SupabaseMagicRuleDto.serializer(), token)
        if (rleCode !in 200..299) return PullResult(rleCode, 0, null)

        var total = 0
        var maxUpdatedAtMs: Long? = null
        fun noteServerTimestamp(ms: Long) {
            maxUpdatedAtMs = maxOf(maxUpdatedAtMs ?: 0L, ms)
        }

        db.withTransaction {
            total += mergePulledEntities(
                remotes = accDtos.map { it.toEntity(now) },
                idOf = { it.id },
                updatedAtOf = { it.updatedAtEpochMs },
                fetchExistingChunk = { db.accountDao().getByIds(it) },
                localPendingOf = { it.pendingSync },
                upsertAll = { db.accountDao().upsertAll(it) },
                onServerTimestamp = ::noteServerTimestamp,
            )
            total += mergePulledEntities(
                remotes = catDtos.map { it.toEntity(now) },
                idOf = { it.id },
                updatedAtOf = { it.updatedAtEpochMs },
                fetchExistingChunk = { db.categoryDao().getByIds(it) },
                localPendingOf = { it.pendingSync },
                upsertAll = { db.categoryDao().upsertAll(it) },
                onServerTimestamp = ::noteServerTimestamp,
            )
            total += mergePulledEntities(
                remotes = txDtos.map { it.toEntity(now) },
                idOf = { it.id },
                updatedAtOf = { it.updatedAtEpochMs },
                fetchExistingChunk = { db.transactionDao().getByIds(it) },
                localPendingOf = { it.pendingSync },
                upsertAll = { db.transactionDao().upsertAll(it) },
                onServerTimestamp = ::noteServerTimestamp,
            )
            total += mergePulledEntities(
                remotes = bdgDtos.map { it.toEntity(now) },
                idOf = { it.id },
                updatedAtOf = { it.updatedAtEpochMs },
                fetchExistingChunk = { db.budgetDao().getByIds(it) },
                localPendingOf = { it.pendingSync },
                upsertAll = { db.budgetDao().upsertAll(it) },
                onServerTimestamp = ::noteServerTimestamp,
            )
            total += mergePulledEntities(
                remotes = plnDtos.map { it.toEntity(now) },
                idOf = { it.id },
                updatedAtOf = { it.updatedAtEpochMs },
                fetchExistingChunk = { db.plannedPaymentDao().getByIds(it) },
                localPendingOf = { it.pendingSync },
                upsertAll = { db.plannedPaymentDao().upsertAll(it) },
                onServerTimestamp = ::noteServerTimestamp,
            )
            total += mergePulledEntities(
                remotes = hldDtos.map { it.toEntity(now) },
                idOf = { it.id },
                updatedAtOf = { it.updatedAtEpochMs },
                fetchExistingChunk = { db.holdingDao().getByIds(it) },
                localPendingOf = { it.pendingSync },
                upsertAll = { db.holdingDao().upsertAll(it) },
                onServerTimestamp = ::noteServerTimestamp,
            )
            total += mergePulledEntities(
                remotes = rleDtos.map { it.toEntity(now) },
                idOf = { it.id },
                updatedAtOf = { it.updatedAtEpochMs },
                fetchExistingChunk = { db.magicRuleDao().getByIds(it) },
                localPendingOf = { it.pendingSync },
                upsertAll = { db.magicRuleDao().upsertAll(it) },
                onServerTimestamp = ::noteServerTimestamp,
            )
        }
        return PullResult(statusCode = 200, pulledCount = total, maxServerUpdatedAtMs = maxUpdatedAtMs)
    }

    private fun shouldApplyRemote(
        localPendingSync: Boolean,
        localUpdatedAtMs: Long,
        remoteUpdatedAtMs: Long,
    ): Boolean {
        if (remoteUpdatedAtMs > localUpdatedAtMs) return true
        if (remoteUpdatedAtMs < localUpdatedAtMs) return false
        return !localPendingSync
    }

    private suspend inline fun <E> mergePulledEntities(
        remotes: List<E>,
        idOf: (E) -> String,
        updatedAtOf: (E) -> Long,
        fetchExistingChunk: suspend (List<String>) -> List<E>,
        localPendingOf: (E) -> Boolean,
        upsertAll: suspend (List<E>) -> Unit,
        onServerTimestamp: (Long) -> Unit,
    ): Int {
        if (remotes.isEmpty()) return 0
        remotes.forEach { onServerTimestamp(updatedAtOf(it)) }
        val existing = remotes.map(idOf).chunked(400).flatMap { fetchExistingChunk(it) }.associateBy(idOf)
        val winners = remotes.filter { remoteEntity ->
            val local = existing[idOf(remoteEntity)]
            local == null || shouldApplyRemote(
                localPendingSync = localPendingOf(local),
                localUpdatedAtMs = updatedAtOf(local),
                remoteUpdatedAtMs = updatedAtOf(remoteEntity),
            )
        }
        if (winners.isNotEmpty()) upsertAll(winners)
        return winners.size
    }

    suspend fun importCsvOffline(csvText: String, targetAccountId: String, defaultCurrency: String = "USD"): Int =
        importExternalDataOffline(
            rawBytes = csvText.toByteArray(Charsets.UTF_8),
            preferredAccountId = targetAccountId,
            defaultCurrency = defaultCurrency,
        ).importedTransactions

    suspend fun importExternalDataOffline(
        rawBytes: ByteArray,
        preferredAccountId: String? = null,
        defaultCurrency: String = "USD",
    ): DataImportOutcome {
        ImportParser.detectBinaryFormatMessage(rawBytes)?.let { reason ->
            return DataImportOutcome(0, message = reason)
        }
        val text = ImportParser.decodeTextBytes(rawBytes)
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return DataImportOutcome(0, message = "Selected file is empty.")
        }

        val now = System.currentTimeMillis()
        if (trimmed.startsWith("{") && trimmed.contains("\"transactions\"") && trimmed.contains("\"accounts\"")) {
            val backup = runCatching { importJson.decodeFromString(LedgerVaultBackup.serializer(), trimmed) }.getOrNull()
            if (backup != null && (backup.accounts.isNotEmpty() || backup.transactions.isNotEmpty())) {
                return restoreVaultBackupOffline(backup, defaultCurrency, now)
            }
        }

        return when (val parsed = ImportParser.parseTransactionsCsvOrJson(text, defaultCurrency, now)) {
            is ParsedImportResult.Error -> DataImportOutcome(0, message = parsed.message)
            is ParsedImportResult.Transactions -> {
                SeedData.ensureMinimalSetup(db, defaultCurrency = defaultCurrency, nowMs = now)
                val activeAccounts = db.accountDao().getAllActive()
                val fallbackAccountId = preferredAccountId?.takeIf { id -> activeAccounts.any { it.id == id } }
                    ?: activeAccounts.firstOrNull()?.id
                    ?: return DataImportOutcome(0, formatLabel = parsed.formatLabel, message = "Create an account first before importing.")

                val categories = db.categoryDao().getAllActive()
                val rules = db.magicRuleDao().getAllActive()
                var imported = 0

                db.withTransaction {
                    for (draft in parsed.drafts) {
                        val accountId = resolveAccountId(draft.accountHint, activeAccounts) ?: fallbackAccountId
                        val ruleMatch = MagicRuleEngine.apply(draft.merchant, draft.note, rules)
                        val hintCatId = resolveCategoryId(draft.categoryHint, draft.txType, categories)
                        val categoryId = ruleMatch.categoryId ?: hintCatId
                        val labels = listOfNotNull(
                            draft.labelsCsv.takeIf { it.isNotBlank() },
                            ruleMatch.label?.takeIf { it.isNotBlank() },
                        ).distinct().joinToString(",")

                        db.recordTransactionAtomic(
                            TransactionEntity(
                                id = UUID.randomUUID().toString(),
                                accountId = accountId,
                                categoryId = categoryId,
                                txType = draft.txType,
                                amountMinor = draft.amountMinor,
                                currency = draft.currency,
                                merchant = draft.merchant,
                                note = draft.note,
                                labelsCsv = labels,
                                sourceTag = "IMPORT",
                                autoRuleApplied = ruleMatch.matched,
                                occurredAtEpochMs = draft.occurredAtEpochMs,
                                createdAtEpochMs = now,
                                updatedAtEpochMs = now,
                                pendingSync = true,
                            )
                        )
                        imported++
                    }
                }
                DataImportOutcome(
                    importedTransactions = imported,
                    formatLabel = parsed.formatLabel,
                    message = "Imported $imported transactions (${parsed.formatLabel})",
                )
            }
        }
    }

    private suspend fun restoreVaultBackupOffline(
        backup: LedgerVaultBackup,
        defaultCurrency: String,
        nowMs: Long,
    ): DataImportOutcome {
        val cats = backup.categories.map { it.toEntity(nowMs).copy(pendingSync = true) }
        val accs = backup.accounts.map { it.toEntity(nowMs).copy(pendingSync = true) }
        val txs = backup.transactions.map { it.toEntity(nowMs).copy(pendingSync = true) }
        val budgets = backup.budgets.map { it.toEntity(nowMs).copy(pendingSync = true) }
        val planned = backup.plannedPayments.map { it.toEntity(nowMs).copy(pendingSync = true) }
        val holdings = backup.holdings.map { it.toEntity(nowMs).copy(pendingSync = true) }
        val rules = backup.magicRules.map { it.toEntity(nowMs).copy(pendingSync = true) }
        val debts = backup.debts.map { it.toEntity(nowMs) }
        val goals = backup.savingsGoals.map { it.toEntity(nowMs) }
        val lists = backup.shoppingLists.map { it.toEntity(nowMs) }
        val items = backup.shoppingItems.map { it.toEntity(nowMs) }

        db.withTransaction {
            if (cats.isNotEmpty()) db.categoryDao().upsertAll(cats)
            if (accs.isNotEmpty()) db.accountDao().upsertAll(accs)
            if (budgets.isNotEmpty()) db.budgetDao().upsertAll(budgets)
            if (planned.isNotEmpty()) db.plannedPaymentDao().upsertAll(planned)
            if (holdings.isNotEmpty()) db.holdingDao().upsertAll(holdings)
            if (rules.isNotEmpty()) db.magicRuleDao().upsertAll(rules)
            if (debts.isNotEmpty()) db.debtDao().upsertAll(debts)
            if (goals.isNotEmpty()) db.savingsGoalDao().upsertAll(goals)
            if (lists.isNotEmpty()) db.shoppingListDao().upsertAll(lists)
            if (items.isNotEmpty()) db.shoppingItemDao().upsertAll(items)
            if (txs.isNotEmpty()) db.transactionDao().upsertAll(txs)
            if (db.accountDao().getAllActive().isEmpty() || db.categoryDao().getAllActive().isEmpty()) {
                SeedData.ensureMinimalSetup(db, defaultCurrency = backup.baseCurrency.ifBlank { defaultCurrency }, nowMs = nowMs)
            }
        }

        val activeTxCount = txs.count { it.deletedAtEpochMs == null }
        val activeAccCount = accs.count { it.deletedAtEpochMs == null }
        val otherCount = budgets.count { it.deletedAtEpochMs == null } +
            planned.count { it.deletedAtEpochMs == null } +
            holdings.count { it.deletedAtEpochMs == null } +
            rules.count { it.deletedAtEpochMs == null } +
            debts.count { it.deletedAtEpochMs == null } +
            goals.count { it.deletedAtEpochMs == null } +
            lists.count { it.deletedAtEpochMs == null } +
            items.count { it.deletedAtEpochMs == null }

        return DataImportOutcome(
            importedTransactions = activeTxCount,
            importedAccounts = activeAccCount,
            importedOtherItems = otherCount,
            formatLabel = "JSON",
            message = "Imported $activeTxCount transactions across $activeAccCount accounts (JSON backup)",
        )
    }

    private fun resolveAccountId(hint: String?, accounts: List<AccountEntity>): String? {
        val clean = hint?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return accounts.firstOrNull { it.name.equals(clean, ignoreCase = true) }?.id
            ?: accounts.firstOrNull { it.name.contains(clean, ignoreCase = true) || clean.contains(it.name, ignoreCase = true) }?.id
    }

    private fun resolveCategoryId(hint: String?, txType: String, categories: List<CategoryEntity>): String? {
        val clean = hint?.trim()?.takeIf { it.isNotEmpty() }
        if (clean != null) {
            categories.firstOrNull { it.name.equals(clean, ignoreCase = true) }?.let { return it.id }
            categories.firstOrNull { it.name.contains(clean, ignoreCase = true) || clean.contains(it.name, ignoreCase = true) }?.let { return it.id }
        }
        return when (txType) {
            "INCOME" -> categories.firstOrNull { it.kind == "INCOME" }?.id
            else -> categories.firstOrNull { it.name.equals("General", ignoreCase = true) }?.id
        }
    }

    suspend fun exportVaultJson(): String {
        val state = vault.read()
        val uid = state.userId ?: "local-offline"
        val backup = LedgerVaultBackup(
            exportedAt = IsoTime.format(System.currentTimeMillis()),
            baseCurrency = state.baseCurrency,
            accounts = db.accountDao().getAllActive().map { it.toDto(uid) },
            categories = db.categoryDao().getAllActive().map { it.toDto(uid) },
            transactions = db.transactionDao().getAllActive().map { it.toDto(uid) },
            budgets = db.budgetDao().getAllActive().map { it.toDto(uid) },
            plannedPayments = db.plannedPaymentDao().getAllActive().map { it.toDto(uid) },
            holdings = db.holdingDao().getAllActive().map { it.toDto(uid) },
            magicRules = db.magicRuleDao().getAllActive().map { it.toDto(uid) },
            debts = db.debtDao().getAllActive().map { it.toBackupDto() },
            savingsGoals = db.savingsGoalDao().getAllActive().map { it.toBackupDto() },
            shoppingLists = db.shoppingListDao().getAllActive().map { it.toBackupDto() },
            shoppingItems = db.shoppingItemDao().getAllActive().map { it.toBackupDto() },
        )
        return exportJson.encodeToString(LedgerVaultBackup.serializer(), backup)
    }

    suspend fun exportLedgerCsv(): String {
        val txs = db.transactionDao().getAllActive()
        val accounts = db.accountDao().getAllActive().associateBy { it.id }
        val cats = db.categoryDao().getAllActive().associateBy { it.id }
        val sb = StringBuilder("Date,Merchant,Type,Amount,Currency,Category,Account,Source,Note,Labels\n")
        for (tx in txs) {
            val accName = accounts[tx.accountId]?.name ?: tx.accountId
            val catName = cats[tx.categoryId]?.name ?: "General"
            val amountStr = String.format(Locale.US, "%.2f", tx.amountMinor / 100.0)
            sb.append(csvCell(IsoTime.format(tx.occurredAtEpochMs))).append(',')
                .append(csvCell(tx.merchant)).append(',')
                .append(csvCell(tx.txType)).append(',')
                .append(amountStr).append(',')
                .append(csvCell(tx.currency)).append(',')
                .append(csvCell(catName)).append(',')
                .append(csvCell(accName)).append(',')
                .append(csvCell(tx.sourceTag)).append(',')
                .append(csvCell(tx.note)).append(',')
                .append(csvCell(tx.labelsCsv)).append('\n')
        }
        return sb.toString()
    }

    private fun csvCell(v: String): String = "\"${v.replace("\"", "\"\"")}\""
}

private fun AccountEntity.toDto(userId: String) = SupabaseAccountDto(
    id = id, userId = userId, name = name, institution = institution, accountType = accountType,
    currency = currency, balanceMinor = balanceMinor, lastFour = lastFour, sourceType = sourceType,
    syncStatus = syncStatus, lastSyncedAt = lastSyncedAtEpochMs?.let(IsoTime::format),
    createdAt = IsoTime.format(createdAtEpochMs), updatedAt = IsoTime.format(updatedAtEpochMs),
    deletedAt = deletedAtEpochMs?.let(IsoTime::format),
)

private fun AccountEntity.toRemoteDto(userId: String) = toDto(userId).copy(
    accountType = AccountTypeCatalog.toSupabaseSafeType(accountType),
)

private fun SupabaseAccountDto.toEntity(now: Long) = AccountEntity(
    id = id, name = name, institution = institution, accountType = accountType,
    currency = currency, balanceMinor = balanceMinor, lastFour = lastFour, sourceType = sourceType,
    syncStatus = syncStatus, lastSyncedAtEpochMs = lastSyncedAt?.let { IsoTime.parseOr(it, now) },
    createdAtEpochMs = IsoTime.parseOr(createdAt, now), updatedAtEpochMs = IsoTime.parseOr(updatedAt, now),
    deletedAtEpochMs = deletedAt?.let { IsoTime.parseOr(it, now) }, pendingSync = false,
)

private fun CategoryEntity.toDto(userId: String) = SupabaseCategoryDto(
    id = id, userId = userId, name = name, kind = kind, iconKey = iconKey, colorHex = colorHex,
    createdAt = IsoTime.format(createdAtEpochMs), updatedAt = IsoTime.format(updatedAtEpochMs),
    deletedAt = deletedAtEpochMs?.let(IsoTime::format),
)

private fun SupabaseCategoryDto.toEntity(now: Long) = CategoryEntity(
    id = id, name = name, kind = kind, iconKey = iconKey, colorHex = colorHex,
    createdAtEpochMs = IsoTime.parseOr(createdAt, now), updatedAtEpochMs = IsoTime.parseOr(updatedAt, now),
    deletedAtEpochMs = deletedAt?.let { IsoTime.parseOr(it, now) }, pendingSync = false,
)

private fun TransactionEntity.toDto(userId: String) = SupabaseTransactionDto(
    id = id, userId = userId, accountId = accountId, transferAccountId = transferAccountId, categoryId = categoryId,
    txType = txType, amountMinor = amountMinor, currency = currency, convertedMinor = convertedMinor,
    convertedCurrency = convertedCurrency, merchant = merchant, note = note, labelsCsv = labelsCsv,
    sourceTag = sourceTag, autoRuleApplied = autoRuleApplied, occurredAt = IsoTime.format(occurredAtEpochMs),
    createdAt = IsoTime.format(createdAtEpochMs), updatedAt = IsoTime.format(updatedAtEpochMs),
    deletedAt = deletedAtEpochMs?.let(IsoTime::format),
)

private fun TransactionEntity.toRemoteDto(userId: String): SupabaseTransactionDto {
    val safeSource = when (sourceTag) {
        "GPAY_AUTO", "BANK_SYNC", "PLANNED", "IMPORT" -> sourceTag
        else -> "MANUAL"
    }
    val combinedUserNote = listOfNotNull(
        spendingPurpose.trim().takeIf { it.isNotEmpty() },
        note.trim().takeIf { it.isNotEmpty() },
    ).distinct().joinToString(" • ")
    return toDto(userId).copy(
        note = combinedUserNote,
        sourceTag = safeSource,
    )
}

private fun SupabaseTransactionDto.toEntity(now: Long) = TransactionEntity(
    id = id, accountId = accountId, transferAccountId = transferAccountId, categoryId = categoryId,
    txType = txType, amountMinor = amountMinor, currency = currency, convertedMinor = convertedMinor,
    convertedCurrency = convertedCurrency, merchant = merchant, note = note, labelsCsv = labelsCsv,
    sourceTag = sourceTag, autoRuleApplied = autoRuleApplied, occurredAtEpochMs = IsoTime.parseOr(occurredAt, now),
    createdAtEpochMs = IsoTime.parseOr(createdAt, now), updatedAtEpochMs = IsoTime.parseOr(updatedAt, now),
    deletedAtEpochMs = deletedAt?.let { IsoTime.parseOr(it, now) }, pendingSync = false,
    reviewStatus = TransactionEntity.STATUS_CONFIRMED,
)

private fun BudgetEntity.toDto(userId: String) = SupabaseBudgetDto(
    id = id, userId = userId, categoryId = categoryId, name = name, limitMinor = limitMinor,
    currency = currency, period = period, createdAt = IsoTime.format(createdAtEpochMs),
    updatedAt = IsoTime.format(updatedAtEpochMs), deletedAt = deletedAtEpochMs?.let(IsoTime::format),
)

private fun SupabaseBudgetDto.toEntity(now: Long) = BudgetEntity(
    id = id, categoryId = categoryId, name = name, limitMinor = limitMinor, currency = currency,
    period = period, createdAtEpochMs = IsoTime.parseOr(createdAt, now),
    updatedAtEpochMs = IsoTime.parseOr(updatedAt, now), deletedAtEpochMs = deletedAt?.let { IsoTime.parseOr(it, now) },
    pendingSync = false,
)

private fun PlannedPaymentEntity.toDto(userId: String) = SupabasePlannedDto(
    id = id, userId = userId, accountId = accountId, categoryId = categoryId, title = title,
    amountMinor = amountMinor, currency = currency, txType = txType, cadence = cadence,
    nextDueAt = IsoTime.format(nextDueAtEpochMs), autoPay = autoPay,
    createdAt = IsoTime.format(createdAtEpochMs), updatedAt = IsoTime.format(updatedAtEpochMs),
    deletedAt = deletedAtEpochMs?.let(IsoTime::format),
)

private fun SupabasePlannedDto.toEntity(now: Long) = PlannedPaymentEntity(
    id = id, accountId = accountId, categoryId = categoryId, title = title,
    amountMinor = amountMinor, currency = currency, txType = txType, cadence = cadence,
    nextDueAtEpochMs = IsoTime.parseOr(nextDueAt, now), autoPay = autoPay,
    createdAtEpochMs = IsoTime.parseOr(createdAt, now), updatedAtEpochMs = IsoTime.parseOr(updatedAt, now),
    deletedAtEpochMs = deletedAt?.let { IsoTime.parseOr(it, now) }, pendingSync = false,
)

private fun HoldingEntity.toDto(userId: String) = SupabaseHoldingDto(
    id = id, userId = userId, symbol = symbol, name = name, assetClass = assetClass,
    exchange = exchange, quantity = quantity, avgCostMinor = avgCostMinor, currentPriceMinor = currentPriceMinor,
    currency = currency, dayChangeBps = dayChangeBps,
    createdAt = IsoTime.format(createdAtEpochMs), updatedAt = IsoTime.format(updatedAtEpochMs),
    deletedAt = deletedAtEpochMs?.let(IsoTime::format),
)

private fun SupabaseHoldingDto.toEntity(now: Long) = HoldingEntity(
    id = id, symbol = symbol, name = name, assetClass = assetClass, exchange = exchange,
    quantity = quantity, avgCostMinor = avgCostMinor, currentPriceMinor = currentPriceMinor,
    currency = currency, dayChangeBps = dayChangeBps,
    createdAtEpochMs = IsoTime.parseOr(createdAt, now), updatedAtEpochMs = IsoTime.parseOr(updatedAt, now),
    deletedAtEpochMs = deletedAt?.let { IsoTime.parseOr(it, now) }, pendingSync = false,
)

private fun MagicRuleEntity.toDto(userId: String) = SupabaseMagicRuleDto(
    id = id, userId = userId, matchPattern = matchPattern, targetCategoryId = targetCategoryId,
    targetLabel = targetLabel, enabled = enabled,
    createdAt = IsoTime.format(createdAtEpochMs), updatedAt = IsoTime.format(updatedAtEpochMs),
    deletedAt = deletedAtEpochMs?.let(IsoTime::format),
)

private fun SupabaseMagicRuleDto.toEntity(now: Long) = MagicRuleEntity(
    id = id, matchPattern = matchPattern, targetCategoryId = targetCategoryId,
    targetLabel = targetLabel, enabled = enabled,
    createdAtEpochMs = IsoTime.parseOr(createdAt, now), updatedAtEpochMs = IsoTime.parseOr(updatedAt, now),
    deletedAtEpochMs = deletedAt?.let { IsoTime.parseOr(it, now) }, pendingSync = false,
)

private fun DebtEntity.toBackupDto() = VaultDebtDto(
    id = id, personName = personName, direction = direction, totalAmountMinor = totalAmountMinor,
    remainingAmountMinor = remainingAmountMinor, currency = currency, accountId = accountId,
    dueAtEpochMs = dueAtEpochMs, note = note, status = status,
    createdAtEpochMs = createdAtEpochMs, updatedAtEpochMs = updatedAtEpochMs, deletedAtEpochMs = deletedAtEpochMs,
)

private fun VaultDebtDto.toEntity(now: Long) = DebtEntity(
    id = id, personName = personName, direction = direction, totalAmountMinor = totalAmountMinor,
    remainingAmountMinor = remainingAmountMinor, currency = currency, accountId = accountId,
    dueAtEpochMs = dueAtEpochMs, note = note, status = status,
    createdAtEpochMs = createdAtEpochMs.takeIf { it > 0L } ?: now,
    updatedAtEpochMs = updatedAtEpochMs.takeIf { it > 0L } ?: now,
    deletedAtEpochMs = deletedAtEpochMs,
    pendingSync = true,
)

private fun SavingsGoalEntity.toBackupDto() = VaultSavingsGoalDto(
    id = id, name = name, targetAmountMinor = targetAmountMinor, savedAmountMinor = savedAmountMinor,
    currency = currency, targetDateEpochMs = targetDateEpochMs, iconKey = iconKey, colorHex = colorHex,
    linkedAccountId = linkedAccountId, status = status,
    createdAtEpochMs = createdAtEpochMs, updatedAtEpochMs = updatedAtEpochMs, deletedAtEpochMs = deletedAtEpochMs,
)

private fun VaultSavingsGoalDto.toEntity(now: Long) = SavingsGoalEntity(
    id = id, name = name, targetAmountMinor = targetAmountMinor, savedAmountMinor = savedAmountMinor,
    currency = currency, targetDateEpochMs = targetDateEpochMs, iconKey = iconKey, colorHex = colorHex,
    linkedAccountId = linkedAccountId, status = status,
    createdAtEpochMs = createdAtEpochMs.takeIf { it > 0L } ?: now,
    updatedAtEpochMs = updatedAtEpochMs.takeIf { it > 0L } ?: now,
    deletedAtEpochMs = deletedAtEpochMs,
    pendingSync = true,
)

private fun ShoppingListEntity.toBackupDto() = VaultShoppingListDto(
    id = id, title = title, defaultCategoryId = defaultCategoryId,
    createdAtEpochMs = createdAtEpochMs, updatedAtEpochMs = updatedAtEpochMs, deletedAtEpochMs = deletedAtEpochMs,
)

private fun VaultShoppingListDto.toEntity(now: Long) = ShoppingListEntity(
    id = id, title = title, defaultCategoryId = defaultCategoryId,
    createdAtEpochMs = createdAtEpochMs.takeIf { it > 0L } ?: now,
    updatedAtEpochMs = updatedAtEpochMs.takeIf { it > 0L } ?: now,
    deletedAtEpochMs = deletedAtEpochMs,
    pendingSync = true,
)

private fun ShoppingItemEntity.toBackupDto() = VaultShoppingItemDto(
    id = id, listId = listId, name = name, quantityLabel = quantityLabel, estimatedPriceMinor = estimatedPriceMinor, checked = checked,
    createdAtEpochMs = createdAtEpochMs, updatedAtEpochMs = updatedAtEpochMs, deletedAtEpochMs = deletedAtEpochMs,
)

private fun VaultShoppingItemDto.toEntity(now: Long) = ShoppingItemEntity(
    id = id, listId = listId, name = name, quantityLabel = quantityLabel, estimatedPriceMinor = estimatedPriceMinor, checked = checked,
    createdAtEpochMs = createdAtEpochMs.takeIf { it > 0L } ?: now,
    updatedAtEpochMs = updatedAtEpochMs.takeIf { it > 0L } ?: now,
    deletedAtEpochMs = deletedAtEpochMs,
    pendingSync = true,
)
