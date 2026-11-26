package com.ledgerflow.app.data.local

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "accounts",
    indices = [Index("updatedAtEpochMs"), Index("deletedAtEpochMs")]
)
data class AccountEntity(
    @PrimaryKey val id: String,
    val name: String,
    val institution: String = "",
    val accountType: String = "CHECKING", // CHECKING, CARD, CASH, SAVINGS, INVESTMENT, SHARED
    val currency: String = "USD",
    val balanceMinor: Long = 0L,
    val lastFour: String = "",
    val creditLimitMinor: Long = 0L,
    val sourceType: String = "MANUAL", // MANUAL, OPEN_BANKING, SHARED
    val syncStatus: String = "OK", // OK, RECONNECT, SYNCING
    val lastSyncedAtEpochMs: Long? = null,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val deletedAtEpochMs: Long? = null,
    val pendingSync: Boolean = true,
)

@Entity(
    tableName = "categories",
    indices = [Index("updatedAtEpochMs")]
)
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val kind: String = "EXPENSE", // EXPENSE, INCOME, TRANSFER
    val iconKey: String = "category",
    val colorHex: String = "#059669",
    val parentCategoryId: String? = null,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val deletedAtEpochMs: Long? = null,
    val pendingSync: Boolean = true,
)

@Entity(
    tableName = "transactions",
    indices = [
        Index("accountId"),
        Index("categoryId"),
        Index("occurredAtEpochMs"),
        Index("updatedAtEpochMs"),
        Index("deletedAtEpochMs"),
    ]
)
data class TransactionEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val transferAccountId: String? = null,
    val categoryId: String? = null,
    val categoryIdsCsv: String = "",
    val txType: String, // EXPENSE, INCOME, TRANSFER
    val amountMinor: Long, // negative for expense, positive for income, positive magnitude on transfer
    val currency: String = "USD",
    val convertedMinor: Long? = null,
    val convertedCurrency: String? = null,
    val merchant: String,
    val note: String = "",
    val labelsCsv: String = "",
    val sourceTag: String = "MANUAL", // MANUAL, GPAY_AUTO, BANK_SYNC, PLANNED, IMPORT
    val autoRuleApplied: Boolean = false,
    val occurredAtEpochMs: Long,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val deletedAtEpochMs: Long? = null,
    val pendingSync: Boolean = true,
)

@Fts4(contentEntity = TransactionEntity::class)
@Entity(tableName = "transactions_fts")
data class TransactionFtsEntity(
    val merchant: String,
    val note: String,
    val labelsCsv: String,
)

@Entity(
    tableName = "budgets",
    indices = [Index("updatedAtEpochMs")]
)
data class BudgetEntity(
    @PrimaryKey val id: String,
    val categoryId: String? = null,
    val name: String,
    val limitMinor: Long,
    val currency: String = "USD",
    val period: String = "MONTHLY",
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val deletedAtEpochMs: Long? = null,
    val pendingSync: Boolean = true,
)

@Entity(
    tableName = "planned_payments",
    indices = [Index("nextDueAtEpochMs"), Index("updatedAtEpochMs")]
)
data class PlannedPaymentEntity(
    @PrimaryKey val id: String,
    val accountId: String? = null,
    val categoryId: String? = null,
    val title: String,
    val amountMinor: Long,
    val currency: String = "USD",
    val txType: String = "EXPENSE",
    val cadence: String = "MONTHLY",
    val nextDueAtEpochMs: Long,
    val autoPay: Boolean = false,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val deletedAtEpochMs: Long? = null,
    val pendingSync: Boolean = true,
)

@Entity(
    tableName = "holdings",
    indices = [Index("updatedAtEpochMs")]
)
data class HoldingEntity(
    @PrimaryKey val id: String,
    val symbol: String,
    val name: String,
    val assetClass: String, // STOCKS, CRYPTO, FOREX
    val exchange: String = "",
    val quantity: Double,
    val avgCostMinor: Long,
    val currentPriceMinor: Long,
    val currency: String = "USD",
    val dayChangeBps: Int = 0,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val deletedAtEpochMs: Long? = null,
    val pendingSync: Boolean = true,
)

@Entity(
    tableName = "magic_rules",
    indices = [Index("updatedAtEpochMs")]
)
data class MagicRuleEntity(
    @PrimaryKey val id: String,
    val matchPattern: String,
    val targetCategoryId: String? = null,
    val targetLabel: String = "",
    val enabled: Boolean = true,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val deletedAtEpochMs: Long? = null,
    val pendingSync: Boolean = true,
)
