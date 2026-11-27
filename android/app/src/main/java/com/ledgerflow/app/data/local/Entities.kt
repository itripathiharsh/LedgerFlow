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
    indices = [Index("updatedAtEpochMs"), Index("parentCategoryId")]
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
    val description: String = "",
)

@Entity(
    tableName = "transactions",
    indices = [
        Index("accountId"),
        Index("categoryId"),
        Index("occurredAtEpochMs"),
        Index("updatedAtEpochMs"),
        Index("deletedAtEpochMs"),
        Index("reviewStatus"),
        Index("referenceId"),
        Index("notificationKey"),
        Index("notificationFingerprint"),
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
    val reviewStatus: String = STATUS_CONFIRMED, // PENDING_REVIEW, CONFIRMED
    val spendingPurpose: String = "",
    val paymentAppName: String = "",
    val paymentAppPackage: String = "",
    val referenceId: String = "",
    val accountHint: String = "",
    val notificationKey: String = "",
    val notificationFingerprint: String = "",
    val originalParsedAmountMinor: Long? = null,
    val isRefundOrReversal: Boolean = false,
) {
    companion object {
        const val UNASSIGNED_ACCOUNT_ID = ""
        const val STATUS_PENDING_REVIEW = "PENDING_REVIEW"
        const val STATUS_CONFIRMED = "CONFIRMED"
    }
}

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

@Entity(
    tableName = "debts",
    indices = [Index("updatedAtEpochMs")]
)
data class DebtEntity(
    @PrimaryKey val id: String,
    val personName: String,
    val direction: String, // "LENT" or "BORROWED"
    val totalAmountMinor: Long,
    val remainingAmountMinor: Long,
    val currency: String,
    val accountId: String? = null,
    val dueAtEpochMs: Long? = null,
    val note: String = "",
    val status: String = "ACTIVE", // "ACTIVE" or "CLOSED"
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val deletedAtEpochMs: Long? = null,
    val pendingSync: Boolean = true,
)

@Entity(
    tableName = "savings_goals",
    indices = [Index("updatedAtEpochMs")]
)
data class SavingsGoalEntity(
    @PrimaryKey val id: String,
    val name: String,
    val targetAmountMinor: Long,
    val savedAmountMinor: Long = 0L,
    val currency: String,
    val targetDateEpochMs: Long? = null,
    val iconKey: String = "savings",
    val colorHex: String = "#059669",
    val linkedAccountId: String? = null,
    val status: String = "ACTIVE", // "ACTIVE", "PAUSED", "REACHED"
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val deletedAtEpochMs: Long? = null,
    val pendingSync: Boolean = true,
)

@Entity(
    tableName = "shopping_lists",
    indices = [Index("updatedAtEpochMs")]
)
data class ShoppingListEntity(
    @PrimaryKey val id: String,
    val title: String,
    val defaultCategoryId: String? = null,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val deletedAtEpochMs: Long? = null,
    val pendingSync: Boolean = true,
    val lastConvertedTxId: String? = null,
    val lastConvertedSignature: String = "",
)

@Entity(
    tableName = "shopping_items",
    indices = [Index("listId"), Index("updatedAtEpochMs")]
)
data class ShoppingItemEntity(
    @PrimaryKey val id: String,
    val listId: String,
    val name: String,
    val estimatedPriceMinor: Long = 0L,
    val checked: Boolean = false,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val deletedAtEpochMs: Long? = null,
    val pendingSync: Boolean = true,
    val quantityLabel: String = "",
    val quantityValue: Double? = null,
    val quantityUnit: String = "",
)
