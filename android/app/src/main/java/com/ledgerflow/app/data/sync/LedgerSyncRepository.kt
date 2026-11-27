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
import com.ledgerflow.app.domain.MoneyFormatter
import com.ledgerflow.app.domain.ParsedImportResult
import com.ledgerflow.app.security.VaultStore
import com.ledgerflow.app.util.NetworkMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    val lastConvertedTxId: String? = null,
    val lastConvertedSignature: String = "",
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
    val quantityValue: Double? = null,
    val quantityUnit: String = "",
    val estimatedPriceMinor: Long = 0L,
    val checked: Boolean = false,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val deletedAtEpochMs: Long? = null,
)

@Serializable
data class VaultCategoryHierarchyDto(
    val id: String,
    val parentCategoryId: String? = null,
    val description: String = "",
)

/** Transaction fields that never leave the device through cloud sync but belong in a local backup. */
@Serializable
data class VaultTransactionDetailDto(
    val id: String,
    val reviewStatus: String = TransactionEntity.STATUS_CONFIRMED,
    val paymentAppName: String = "",
    val paymentAppPackage: String = "",
    val referenceId: String = "",
    val accountHint: String = "",
    val notificationKey: String = "",
    val notificationFingerprint: String = "",
    val originalParsedAmountMinor: Long? = null,
    val isRefundOrReversal: Boolean = false,
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
    val categoryHierarchy: List<VaultCategoryHierarchyDto> = emptyList(),
    val transactionDetails: List<VaultTransactionDetailDto> = emptyList(),
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
                withContext(Dispatchers.IO) { db.clearAllTables() }
                SeedData.ensureMinimalSetup(db, defaultCurrency = state.baseCurrency)
                state = vault.update {
                    it.copy(
                        lastCloudUserId = userId,
                        lastSyncWatermarkIso = null,
                        lastSyncEpochMs = 0L,
                        cloudDetailColumnsMissing = false,
                    )
                }
            }

            val ids = CloudIdMapper(userId)
            val watermark = state.lastSyncWatermarkIso
            // Until a first pull has returned data, untouched starter rows are held back so that a
            // fresh install can never overwrite the copy already in the cloud.
            val firstSync = watermark.isNullOrBlank()

            // Validate/refresh session on 401 automatically
            var push = pushAllDirty(userId, token, ids, holdUntouchedStarters = firstSync)
            if (push.code == 401) {
                token = refreshAccessTokenOrNull(state.refreshToken) ?: return SyncOutcome.Error("Session expired; sign in again")
                push = pushAllDirty(userId, token, ids, holdUntouchedStarters = firstSync)
            }
            if (push.code !in 200..299) {
                return SyncOutcome.Error("Cloud push failed (HTTP ${push.code})")
            }

            var pullOutcome = pullAllDelta(watermark, token, ids, replaceUntouchedStarters = firstSync)
            if (pullOutcome.statusCode == 401) {
                token = refreshAccessTokenOrNull(vault.read().refreshToken) ?: return SyncOutcome.Error("Session expired; sign in again")
                pullOutcome = pullAllDelta(watermark, token, ids, replaceUntouchedStarters = firstSync)
            }
            if (pullOutcome.statusCode !in 200..299) {
                return SyncOutcome.Error("Cloud pull failed (HTTP ${pullOutcome.statusCode})")
            }

            // The cloud schema has gained the detail columns since an earlier upload had to omit them.
            val backfill = push.sentDetail && !push.droppedDetail && vault.read().cloudDetailColumnsMissing
            if (backfill) {
                markRowsWithDetailDirty()
                vault.update { it.copy(cloudDetailColumnsMissing = false) }
            }
            if (firstSync || backfill) {
                // Sends starter rows the cloud turned out not to have, plus any backfilled rows.
                val followUp = pushAllDirty(userId, token, ids, holdUntouchedStarters = false)
                if (followUp.code !in 200..299) {
                    if (backfill) vault.update { it.copy(cloudDetailColumnsMissing = true) }
                    return SyncOutcome.Error("Cloud push failed (HTTP ${followUp.code})")
                }
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

    /** Payload for a cloud schema that does not yet have a table's detail columns. */
    private class CorePayload<E, D>(val toDto: (E) -> D, val omitKeys: Set<String>)

    private class PushReport {
        var code = 200
        var sentDetail = false
        var droppedDetail = false
    }

    private suspend fun <E, D> pushDirtyTable(
        report: PushReport,
        table: String,
        dirty: List<E>,
        serializer: kotlinx.serialization.KSerializer<D>,
        token: String,
        idOf: (E) -> String,
        toDto: (E) -> D,
        markSynced: suspend (List<String>) -> Unit,
        core: CorePayload<E, D>? = null,
    ) {
        if (dirty.isEmpty()) return
        var result = remote.upsertTable(table, dirty.map(toDto), serializer, token)
        if (result.missingColumn && core != null) {
            result = remote.upsertTable(table, dirty.map(core.toDto), serializer, token, core.omitKeys)
            if (result.code in 200..299) {
                report.droppedDetail = true
                vault.update { it.copy(cloudDetailColumnsMissing = true) }
            }
        } else if (result.code in 200..299 && core != null) {
            report.sentDetail = true
        }
        if (result.code !in 200..299) {
            if (report.code in 200..299) report.code = result.code
            return
        }
        dirty.map(idOf).chunked(400).forEach { markSynced(it) }
    }

    private suspend fun pushAllDirty(
        userId: String,
        token: String,
        ids: CloudIdMapper,
        holdUntouchedStarters: Boolean,
    ): PushReport {
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
        var accounts = db.accountDao().getDirty()
        var categories = db.categoryDao().getDirty()
        if (holdUntouchedStarters) {
            val usedAccountIds = accountIdsWithTransactions()
            accounts = accounts.filterNot { SeedData.isUntouchedStarterWallet(it, it.id in usedAccountIds) }
            categories = categories.filterNot(SeedData::isUntouchedStarterCategory)
        }

        val report = PushReport()
        val accountDto = { it: AccountEntity -> it.toRemoteDto(userId).withIds(ids::toCloud) }
        pushDirtyTable(
            report, "accounts", accounts, SupabaseAccountDto.serializer(), token, { it.id },
            accountDto, db.accountDao()::markSynced,
            core = CorePayload(accountDto, ACCOUNT_DETAIL_KEYS),
        )
        val categoryDto = { it: CategoryEntity -> it.toDto(userId).withIds(ids::toCloud) }
        pushDirtyTable(
            report, "categories", categories, SupabaseCategoryDto.serializer(), token, { it.id },
            categoryDto, db.categoryDao()::markSynced,
            core = CorePayload(categoryDto, CATEGORY_DETAIL_KEYS),
        )
        pushDirtyTable(
            report, "transactions", syncableTx, SupabaseTransactionDto.serializer(), token, { it.id },
            { it.toRemoteDto(userId).withIds(ids) }, db.transactionDao()::markSynced,
            core = CorePayload({ it.toCoreRemoteDto(userId).withIds(ids) }, TRANSACTION_DETAIL_KEYS),
        )
        pushDirtyTable(report, "budgets", db.budgetDao().getDirty(), SupabaseBudgetDto.serializer(), token, { it.id }, { it.toDto(userId).withIds(ids::toCloud) }, db.budgetDao()::markSynced)
        pushDirtyTable(report, "planned_payments", db.plannedPaymentDao().getDirty(), SupabasePlannedDto.serializer(), token, { it.id }, { it.toDto(userId).withIds(ids::toCloud) }, db.plannedPaymentDao()::markSynced)
        pushDirtyTable(report, "holdings", db.holdingDao().getDirty(), SupabaseHoldingDto.serializer(), token, { it.id }, { it.toDto(userId).withIds(ids::toCloud) }, db.holdingDao()::markSynced)
        pushDirtyTable(report, "magic_rules", db.magicRuleDao().getDirty(), SupabaseMagicRuleDto.serializer(), token, { it.id }, { it.toDto(userId).withIds(ids::toCloud) }, db.magicRuleDao()::markSynced)
        return report
    }

    private suspend fun accountIdsWithTransactions(): Set<String> =
        db.transactionDao().getAllActive()
            .flatMap { listOfNotNull(it.accountId, it.transferAccountId) }
            .toSet()

    /**
     * Queues the rows that hold something in a detail column. Rows without any are left alone, so a
     * device that only ever saw the coarse cloud copy cannot overwrite fuller data from another one.
     */
    private suspend fun markRowsWithDetailDirty() {
        db.withTransaction {
            val accounts = db.accountDao().getAllIncludingDeleted().filter {
                it.creditLimitMinor != 0L || AccountTypeCatalog.toSupabaseSafeType(it.accountType) != it.accountType
            }
            db.accountDao().upsertAll(accounts.map { it.copy(pendingSync = true) })
            val categories = db.categoryDao().getAllIncludingDeleted().filter {
                it.parentCategoryId != null || it.description.isNotBlank()
            }
            db.categoryDao().upsertAll(categories.map { it.copy(pendingSync = true) })
            val transactions = db.transactionDao().getAllActive().filter {
                it.reviewStatus != TransactionEntity.STATUS_PENDING_REVIEW &&
                    (it.spendingPurpose.isNotBlank() || it.categoryIdsCsv.isNotBlank())
            }
            transactions.map { it.id }.chunked(400).forEach { db.transactionDao().markDirty(it) }
        }
    }

    private suspend fun pullAllDelta(
        sinceIso: String?,
        token: String,
        ids: CloudIdMapper,
        replaceUntouchedStarters: Boolean,
    ): PullResult {
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
            // Read before any pulled transaction lands, so it reflects only what was done on this device.
            val usedAccountIds = if (replaceUntouchedStarters) accountIdsWithTransactions() else emptySet()
            total += mergePulledRows(
                remotes = accDtos.map { it.withIds(ids::toLocal) },
                idOf = { it.id },
                remoteUpdatedAtOf = { IsoTime.parseOr(it.updatedAt, now) },
                fetchExistingChunk = { db.accountDao().getByIds(it) },
                localIdOf = { it.id },
                localUpdatedAtOf = { it.updatedAtEpochMs },
                localPendingOf = { it.pendingSync },
                toEntity = { dto, local -> dto.toEntity(now, local) },
                upsertAll = { db.accountDao().upsertAll(it) },
                onServerTimestamp = ::noteServerTimestamp,
                isReplaceable = { replaceUntouchedStarters && SeedData.isUntouchedStarterWallet(it, it.id in usedAccountIds) },
            )
            total += mergePulledRows(
                remotes = catDtos.map { it.withIds(ids::toLocal) },
                idOf = { it.id },
                remoteUpdatedAtOf = { IsoTime.parseOr(it.updatedAt, now) },
                fetchExistingChunk = { db.categoryDao().getByIds(it) },
                localIdOf = { it.id },
                localUpdatedAtOf = { it.updatedAtEpochMs },
                localPendingOf = { it.pendingSync },
                toEntity = { dto, local -> dto.toEntity(now, local) },
                upsertAll = { db.categoryDao().upsertAll(it) },
                onServerTimestamp = ::noteServerTimestamp,
                isReplaceable = { replaceUntouchedStarters && SeedData.isUntouchedStarterCategory(it) },
            )
            total += mergePulledRows(
                remotes = txDtos.map { it.withIds(ids, toLocal = true) },
                idOf = { it.id },
                remoteUpdatedAtOf = { IsoTime.parseOr(it.updatedAt, now) },
                fetchExistingChunk = { db.transactionDao().getByIds(it) },
                localIdOf = { it.id },
                localUpdatedAtOf = { it.updatedAtEpochMs },
                localPendingOf = { it.pendingSync },
                toEntity = { dto, local -> dto.toEntity(now, local) },
                upsertAll = { db.transactionDao().upsertAll(it) },
                onServerTimestamp = ::noteServerTimestamp,
            )
            total += mergePulledRows(
                remotes = bdgDtos.map { it.withIds(ids::toLocal) },
                idOf = { it.id },
                remoteUpdatedAtOf = { IsoTime.parseOr(it.updatedAt, now) },
                fetchExistingChunk = { db.budgetDao().getByIds(it) },
                localIdOf = { it.id },
                localUpdatedAtOf = { it.updatedAtEpochMs },
                localPendingOf = { it.pendingSync },
                toEntity = { dto, _ -> dto.toEntity(now) },
                upsertAll = { db.budgetDao().upsertAll(it) },
                onServerTimestamp = ::noteServerTimestamp,
            )
            total += mergePulledRows(
                remotes = plnDtos.map { it.withIds(ids::toLocal) },
                idOf = { it.id },
                remoteUpdatedAtOf = { IsoTime.parseOr(it.updatedAt, now) },
                fetchExistingChunk = { db.plannedPaymentDao().getByIds(it) },
                localIdOf = { it.id },
                localUpdatedAtOf = { it.updatedAtEpochMs },
                localPendingOf = { it.pendingSync },
                toEntity = { dto, _ -> dto.toEntity(now) },
                upsertAll = { db.plannedPaymentDao().upsertAll(it) },
                onServerTimestamp = ::noteServerTimestamp,
            )
            total += mergePulledRows(
                remotes = hldDtos.map { it.withIds(ids::toLocal) },
                idOf = { it.id },
                remoteUpdatedAtOf = { IsoTime.parseOr(it.updatedAt, now) },
                fetchExistingChunk = { db.holdingDao().getByIds(it) },
                localIdOf = { it.id },
                localUpdatedAtOf = { it.updatedAtEpochMs },
                localPendingOf = { it.pendingSync },
                toEntity = { dto, _ -> dto.toEntity(now) },
                upsertAll = { db.holdingDao().upsertAll(it) },
                onServerTimestamp = ::noteServerTimestamp,
            )
            total += mergePulledRows(
                remotes = rleDtos.map { it.withIds(ids::toLocal) },
                idOf = { it.id },
                remoteUpdatedAtOf = { IsoTime.parseOr(it.updatedAt, now) },
                fetchExistingChunk = { db.magicRuleDao().getByIds(it) },
                localIdOf = { it.id },
                localUpdatedAtOf = { it.updatedAtEpochMs },
                localPendingOf = { it.pendingSync },
                toEntity = { dto, _ -> dto.toEntity(now) },
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

    private suspend inline fun <D, E> mergePulledRows(
        remotes: List<D>,
        idOf: (D) -> String,
        remoteUpdatedAtOf: (D) -> Long,
        fetchExistingChunk: suspend (List<String>) -> List<E>,
        localIdOf: (E) -> String,
        localUpdatedAtOf: (E) -> Long,
        localPendingOf: (E) -> Boolean,
        toEntity: (D, E?) -> E,
        upsertAll: suspend (List<E>) -> Unit,
        onServerTimestamp: (Long) -> Unit,
        isReplaceable: (E) -> Boolean = { false },
    ): Int {
        if (remotes.isEmpty()) return 0
        remotes.forEach { onServerTimestamp(remoteUpdatedAtOf(it)) }
        val existing = remotes.map(idOf).distinct().chunked(400)
            .flatMap { fetchExistingChunk(it) }
            .associateBy(localIdOf)
        // Remotes arrive oldest first, so when two map to one local row the newer one is written last.
        val winners = remotes.mapNotNull { dto ->
            val local = existing[idOf(dto)]
            val wins = local == null || isReplaceable(local) || shouldApplyRemote(
                localPendingSync = localPendingOf(local),
                localUpdatedAtMs = localUpdatedAtOf(local),
                remoteUpdatedAtMs = remoteUpdatedAtOf(dto),
            )
            if (wins) toEntity(dto, local) else null
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
        // Older backups carry the hierarchy only in categoryHierarchy and have no transaction details.
        val catMeta = backup.categoryHierarchy.associateBy { it.id }
        val cats = backup.categories.map { dto ->
            val meta = catMeta[dto.id]
            dto.toEntity(nowMs, local = null).copy(
                parentCategoryId = meta?.parentCategoryId ?: dto.parentCategoryId,
                description = meta?.description ?: dto.description,
                pendingSync = true,
            )
        }
        val accs = backup.accounts.map { it.toEntity(nowMs, local = null).copy(pendingSync = true) }
        val txDetails = backup.transactionDetails.associateBy { it.id }
        val txs = backup.transactions.map { dto ->
            val restored = dto.toEntity(nowMs, local = null).copy(pendingSync = true)
            val detail = txDetails[dto.id]
            when {
                detail != null -> restored.copy(
                    reviewStatus = detail.reviewStatus,
                    paymentAppName = detail.paymentAppName,
                    paymentAppPackage = detail.paymentAppPackage,
                    referenceId = detail.referenceId,
                    accountHint = detail.accountHint,
                    notificationKey = detail.notificationKey,
                    notificationFingerprint = detail.notificationFingerprint,
                    originalParsedAmountMinor = detail.originalParsedAmountMinor,
                    isRefundOrReversal = detail.isRefundOrReversal,
                )
                // Only a payment still awaiting review can be without an account.
                restored.accountId.isBlank() -> restored.copy(reviewStatus = TransactionEntity.STATUS_PENDING_REVIEW)
                else -> restored
            }
        }
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
        val transactions = db.transactionDao().getAllActive()
        val backup = LedgerVaultBackup(
            exportedAt = IsoTime.format(System.currentTimeMillis()),
            baseCurrency = state.baseCurrency,
            accounts = db.accountDao().getAllActive().map { it.toDto(uid) },
            categories = db.categoryDao().getAllActive().map { it.toDto(uid) },
            transactions = transactions.map { it.toDto(uid) },
            budgets = db.budgetDao().getAllActive().map { it.toDto(uid) },
            plannedPayments = db.plannedPaymentDao().getAllActive().map { it.toDto(uid) },
            holdings = db.holdingDao().getAllActive().map { it.toDto(uid) },
            magicRules = db.magicRuleDao().getAllActive().map { it.toDto(uid) },
            debts = db.debtDao().getAllActive().map { it.toBackupDto() },
            savingsGoals = db.savingsGoalDao().getAllActive().map { it.toBackupDto() },
            shoppingLists = db.shoppingListDao().getAllActive().map { it.toBackupDto() },
            shoppingItems = db.shoppingItemDao().getAllActive().map { it.toBackupDto() },
            categoryHierarchy = db.categoryDao().getAllActive()
                .filter { it.parentCategoryId != null || it.description.isNotBlank() }
                .map { VaultCategoryHierarchyDto(it.id, it.parentCategoryId, it.description) },
            transactionDetails = transactions.map { it.toBackupDetail() },
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
            val amountStr = BigDecimal.valueOf(tx.amountMinor)
                .movePointLeft(MoneyFormatter.decimalPlaces(tx.currency))
                .toPlainString()
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

// Account types the core cloud schema reports when it cannot express the exact local one.
private val COARSE_ACCOUNT_TYPES = setOf("CARD", "SHARED")

private val ACCOUNT_DETAIL_KEYS = setOf("credit_limit_minor", "account_subtype", "detail_version")
private val CATEGORY_DETAIL_KEYS = setOf("parent_category_id", "description", "detail_version")
private val TRANSACTION_DETAIL_KEYS = setOf("category_ids_csv", "spending_purpose", "detail_version")

/** Marks a row whose detail columns were actually written; rows without it leave local detail alone. */
private const val DETAIL_VERSION = 1

private fun AccountEntity.toDto(userId: String) = SupabaseAccountDto(
    id = id, userId = userId, name = name, institution = institution, accountType = accountType,
    currency = currency, balanceMinor = balanceMinor, creditLimitMinor = creditLimitMinor,
    accountSubtype = accountType, detailVersion = DETAIL_VERSION, lastFour = lastFour, sourceType = sourceType,
    syncStatus = syncStatus, lastSyncedAt = lastSyncedAtEpochMs?.let(IsoTime::format),
    createdAt = IsoTime.format(createdAtEpochMs), updatedAt = IsoTime.format(updatedAtEpochMs),
    deletedAt = deletedAtEpochMs?.let(IsoTime::format),
)

private fun AccountEntity.toRemoteDto(userId: String) = toDto(userId).copy(
    accountType = AccountTypeCatalog.toSupabaseSafeType(accountType),
)

/** [local] supplies whatever the incoming row cannot express, so a pull never blanks it. */
private fun SupabaseAccountDto.toEntity(now: Long, local: AccountEntity?): AccountEntity {
    val detailed = detailVersion >= DETAIL_VERSION
    // Cloud rows carry the exact type in account_subtype and a coarse one in account_type; backups
    // carry the exact type in both.
    val exactType = accountSubtype.takeIf {
        detailed && it.isNotBlank() && it !in COARSE_ACCOUNT_TYPES &&
            (it == accountType || AccountTypeCatalog.toSupabaseSafeType(it) == accountType)
    }
    val localType = local?.accountType?.takeIf { AccountTypeCatalog.toSupabaseSafeType(it) == accountType }
    return AccountEntity(
        id = id, name = name, institution = institution, accountType = exactType ?: localType ?: accountType,
        currency = currency, balanceMinor = balanceMinor, lastFour = lastFour,
        creditLimitMinor = if (local == null || exactType != null) creditLimitMinor else local.creditLimitMinor,
        sourceType = sourceType, syncStatus = syncStatus,
        lastSyncedAtEpochMs = lastSyncedAt?.let { IsoTime.parseOr(it, now) },
        createdAtEpochMs = IsoTime.parseOr(createdAt, now), updatedAtEpochMs = IsoTime.parseOr(updatedAt, now),
        deletedAtEpochMs = deletedAt?.let { IsoTime.parseOr(it, now) }, pendingSync = false,
    )
}

private fun SupabaseAccountDto.withIds(map: (String) -> String) = copy(id = map(id))

private fun CategoryEntity.toDto(userId: String) = SupabaseCategoryDto(
    id = id, userId = userId, name = name, kind = kind, iconKey = iconKey, colorHex = colorHex,
    parentCategoryId = parentCategoryId, description = description, detailVersion = DETAIL_VERSION,
    createdAt = IsoTime.format(createdAtEpochMs), updatedAt = IsoTime.format(updatedAtEpochMs),
    deletedAt = deletedAtEpochMs?.let(IsoTime::format),
)

private fun SupabaseCategoryDto.toEntity(now: Long, local: CategoryEntity?): CategoryEntity {
    val detailed = detailVersion >= DETAIL_VERSION
    return CategoryEntity(
        id = id, name = name, kind = kind, iconKey = iconKey, colorHex = colorHex,
        parentCategoryId = if (detailed || local == null) parentCategoryId else local.parentCategoryId,
        description = if (detailed || local == null) description else local.description,
        createdAtEpochMs = IsoTime.parseOr(createdAt, now), updatedAtEpochMs = IsoTime.parseOr(updatedAt, now),
        deletedAtEpochMs = deletedAt?.let { IsoTime.parseOr(it, now) }, pendingSync = false,
    )
}

private fun SupabaseCategoryDto.withIds(map: (String) -> String) =
    copy(id = map(id), parentCategoryId = parentCategoryId?.let(map))

private fun TransactionEntity.toDto(userId: String) = SupabaseTransactionDto(
    id = id, userId = userId, accountId = accountId, transferAccountId = transferAccountId, categoryId = categoryId,
    categoryIdsCsv = categoryIdsCsv, spendingPurpose = spendingPurpose, detailVersion = DETAIL_VERSION,
    txType = txType, amountMinor = amountMinor, currency = currency, convertedMinor = convertedMinor,
    convertedCurrency = convertedCurrency, merchant = merchant, note = note, labelsCsv = labelsCsv,
    sourceTag = sourceTag, autoRuleApplied = autoRuleApplied, occurredAt = IsoTime.format(occurredAtEpochMs),
    createdAt = IsoTime.format(createdAtEpochMs), updatedAt = IsoTime.format(updatedAtEpochMs),
    deletedAt = deletedAtEpochMs?.let(IsoTime::format),
)

private fun cloudSourceTag(sourceTag: String): String = when (sourceTag) {
    "GPAY_AUTO", "BANK_SYNC", "PLANNED", "IMPORT" -> sourceTag
    else -> "MANUAL"
}

/** The single note a cloud row holds when it has no separate spending purpose column. */
private fun combinedNote(spendingPurpose: String, note: String): String = listOfNotNull(
    spendingPurpose.trim().takeIf { it.isNotEmpty() },
    note.trim().takeIf { it.isNotEmpty() },
).distinct().joinToString(" • ")

private fun TransactionEntity.toRemoteDto(userId: String) =
    toDto(userId).copy(sourceTag = cloudSourceTag(sourceTag))

private fun TransactionEntity.toCoreRemoteDto(userId: String) =
    toRemoteDto(userId).copy(note = combinedNote(spendingPurpose, note))

private fun SupabaseTransactionDto.toEntity(now: Long, local: TransactionEntity?): TransactionEntity {
    val detailed = detailVersion >= DETAIL_VERSION
    // Without detail columns, an unchanged combined note means the local split is still accurate.
    val localSplit = local?.takeIf { !detailed && combinedNote(it.spendingPurpose, it.note) == note }
    return TransactionEntity(
        id = id, accountId = accountId, transferAccountId = transferAccountId, categoryId = categoryId,
        categoryIdsCsv = if (!detailed && local != null && local.categoryId == categoryId) {
            local.categoryIdsCsv
        } else {
            categoryIdsCsv
        },
        txType = txType, amountMinor = amountMinor, currency = currency, convertedMinor = convertedMinor,
        convertedCurrency = convertedCurrency, merchant = merchant,
        note = localSplit?.note ?: note,
        spendingPurpose = if (detailed) spendingPurpose else localSplit?.spendingPurpose.orEmpty(),
        labelsCsv = labelsCsv,
        sourceTag = local?.sourceTag?.takeIf { cloudSourceTag(it) == sourceTag } ?: sourceTag,
        autoRuleApplied = autoRuleApplied, occurredAtEpochMs = IsoTime.parseOr(occurredAt, now),
        createdAtEpochMs = IsoTime.parseOr(createdAt, now), updatedAtEpochMs = IsoTime.parseOr(updatedAt, now),
        deletedAtEpochMs = deletedAt?.let { IsoTime.parseOr(it, now) }, pendingSync = false,
        reviewStatus = TransactionEntity.STATUS_CONFIRMED,
        // Payment capture metadata is never uploaded, so it can only come from this device.
        paymentAppName = local?.paymentAppName.orEmpty(),
        paymentAppPackage = local?.paymentAppPackage.orEmpty(),
        referenceId = local?.referenceId.orEmpty(),
        accountHint = local?.accountHint.orEmpty(),
        notificationKey = local?.notificationKey.orEmpty(),
        notificationFingerprint = local?.notificationFingerprint.orEmpty(),
        originalParsedAmountMinor = local?.originalParsedAmountMinor,
        isRefundOrReversal = local?.isRefundOrReversal ?: false,
    )
}

private fun SupabaseTransactionDto.withIds(ids: CloudIdMapper, toLocal: Boolean = false): SupabaseTransactionDto {
    val map: (String) -> String = if (toLocal) ids::toLocal else ids::toCloud
    return copy(
        id = map(id),
        accountId = map(accountId),
        transferAccountId = transferAccountId?.let(map),
        categoryId = categoryId?.let(map),
        categoryIdsCsv = if (toLocal) ids.csvToLocal(categoryIdsCsv) else ids.csvToCloud(categoryIdsCsv),
    )
}

private fun TransactionEntity.toBackupDetail() = VaultTransactionDetailDto(
    id = id, reviewStatus = reviewStatus, paymentAppName = paymentAppName,
    paymentAppPackage = paymentAppPackage, referenceId = referenceId, accountHint = accountHint,
    notificationKey = notificationKey, notificationFingerprint = notificationFingerprint,
    originalParsedAmountMinor = originalParsedAmountMinor, isRefundOrReversal = isRefundOrReversal,
)

private fun SupabaseBudgetDto.withIds(map: (String) -> String) =
    copy(id = map(id), categoryId = categoryId?.let(map))

private fun SupabasePlannedDto.withIds(map: (String) -> String) =
    copy(id = map(id), accountId = accountId?.let(map), categoryId = categoryId?.let(map))

private fun SupabaseHoldingDto.withIds(map: (String) -> String) = copy(id = map(id))

private fun SupabaseMagicRuleDto.withIds(map: (String) -> String) =
    copy(id = map(id), targetCategoryId = targetCategoryId?.let(map))

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
    lastConvertedTxId = lastConvertedTxId, lastConvertedSignature = lastConvertedSignature,
    createdAtEpochMs = createdAtEpochMs, updatedAtEpochMs = updatedAtEpochMs, deletedAtEpochMs = deletedAtEpochMs,
)

private fun VaultShoppingListDto.toEntity(now: Long) = ShoppingListEntity(
    id = id, title = title, defaultCategoryId = defaultCategoryId,
    createdAtEpochMs = createdAtEpochMs.takeIf { it > 0L } ?: now,
    updatedAtEpochMs = updatedAtEpochMs.takeIf { it > 0L } ?: now,
    deletedAtEpochMs = deletedAtEpochMs,
    pendingSync = true,
    lastConvertedTxId = lastConvertedTxId,
    lastConvertedSignature = lastConvertedSignature,
)

private fun ShoppingItemEntity.toBackupDto() = VaultShoppingItemDto(
    id = id, listId = listId, name = name, quantityLabel = quantityLabel,
    quantityValue = quantityValue, quantityUnit = quantityUnit,
    estimatedPriceMinor = estimatedPriceMinor, checked = checked,
    createdAtEpochMs = createdAtEpochMs, updatedAtEpochMs = updatedAtEpochMs, deletedAtEpochMs = deletedAtEpochMs,
)

private fun VaultShoppingItemDto.toEntity(now: Long): ShoppingItemEntity {
    val parsed = if (quantityValue == null && quantityUnit.isBlank() && quantityLabel.isNotBlank()) {
        com.ledgerflow.app.domain.ShoppingUnitCatalog.parseLegacyLabel(quantityLabel)
    } else {
        null
    }
    val resolvedQty = quantityValue ?: parsed?.quantityValue
    val resolvedUnit = quantityUnit.ifBlank { parsed?.quantityUnit.orEmpty() }
    val resolvedLabel = com.ledgerflow.app.domain.ShoppingUnitCatalog.formatQuantityLabel(
        resolvedQty,
        resolvedUnit,
        quantityLabel,
    )
    return ShoppingItemEntity(
        id = id, listId = listId, name = name, quantityLabel = resolvedLabel,
        quantityValue = resolvedQty, quantityUnit = resolvedUnit,
        estimatedPriceMinor = estimatedPriceMinor, checked = checked,
        createdAtEpochMs = createdAtEpochMs.takeIf { it > 0L } ?: now,
        updatedAtEpochMs = updatedAtEpochMs.takeIf { it > 0L } ?: now,
        deletedAtEpochMs = deletedAtEpochMs,
        pendingSync = true,
    )
}
