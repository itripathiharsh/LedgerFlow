package com.ledgerflow.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts WHERE deletedAtEpochMs IS NULL ORDER BY createdAtEpochMs ASC")
    fun observeActive(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE deletedAtEpochMs IS NULL")
    suspend fun getAllActive(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): AccountEntity?

    @Query("SELECT COUNT(*) FROM accounts")
    suspend fun countAll(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: AccountEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(accounts: List<AccountEntity>)

    @Query("UPDATE accounts SET balanceMinor = balanceMinor + :deltaMinor, updatedAtEpochMs = :nowMs, pendingSync = 1 WHERE id = :accountId")
    suspend fun adjustBalance(accountId: String, deltaMinor: Long, nowMs: Long)

    @Query("SELECT * FROM accounts WHERE pendingSync = 1")
    suspend fun getDirty(): List<AccountEntity>

    @Query("UPDATE accounts SET pendingSync = 0 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    @Query("DELETE FROM accounts")
    suspend fun deleteAll()
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories WHERE deletedAtEpochMs IS NULL ORDER BY name ASC")
    fun observeActive(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY name ASC")
    fun observeAllIncludingDeleted(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE deletedAtEpochMs IS NULL")
    suspend fun getAllActive(): List<CategoryEntity>

    @Query("SELECT * FROM categories")
    suspend fun getAllIncludingDeleted(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<CategoryEntity>

    @Query("SELECT id FROM categories")
    suspend fun getAllIds(): List<String>

    @Query("SELECT * FROM categories WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): CategoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(category: CategoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(categories: List<CategoryEntity>)

    @Query("SELECT * FROM categories WHERE pendingSync = 1")
    suspend fun getDirty(): List<CategoryEntity>

    @Query("UPDATE categories SET pendingSync = 0 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    @Query("DELETE FROM categories")
    suspend fun deleteAll()
}

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions WHERE deletedAtEpochMs IS NULL ORDER BY occurredAtEpochMs DESC")
    fun observeActive(): Flow<List<TransactionEntity>>

    @Query(
        "SELECT transactions.* FROM transactions " +
            "JOIN transactions_fts ON transactions.rowid = transactions_fts.rowid " +
            "WHERE transactions_fts MATCH :ftsQuery AND transactions.deletedAtEpochMs IS NULL " +
            "ORDER BY transactions.occurredAtEpochMs DESC"
    )
    suspend fun searchFts(ftsQuery: String): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE deletedAtEpochMs IS NULL ORDER BY occurredAtEpochMs DESC")
    suspend fun getAllActive(): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE deletedAtEpochMs IS NULL AND reviewStatus = 'PENDING_REVIEW' ORDER BY occurredAtEpochMs DESC")
    fun observePendingReview(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE deletedAtEpochMs IS NULL AND reviewStatus = 'PENDING_REVIEW' ORDER BY occurredAtEpochMs DESC")
    suspend fun getPendingReview(): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE notificationKey = :notificationKey AND :notificationKey != '' ORDER BY updatedAtEpochMs DESC LIMIT 1")
    suspend fun findByNotificationKey(notificationKey: String): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE referenceId = :referenceId AND :referenceId != '' AND deletedAtEpochMs IS NULL ORDER BY updatedAtEpochMs DESC LIMIT 1")
    suspend fun findByReferenceId(referenceId: String): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE notificationFingerprint = :fingerprint AND :fingerprint != '' AND deletedAtEpochMs IS NULL ORDER BY updatedAtEpochMs DESC LIMIT 1")
    suspend fun findByFingerprint(fingerprint: String): TransactionEntity?

    @Query(
        "SELECT * FROM transactions WHERE deletedAtEpochMs IS NULL " +
            "AND amountMinor = :amountMinor AND currency = :currency " +
            "AND LOWER(merchant) = LOWER(:merchant) " +
            "AND occurredAtEpochMs BETWEEN :minEpochMs AND :maxEpochMs " +
            "LIMIT 1"
    )
    suspend fun findRecentDuplicate(
        amountMinor: Long,
        currency: String,
        merchant: String,
        minEpochMs: Long,
        maxEpochMs: Long,
    ): TransactionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tx: TransactionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(txs: List<TransactionEntity>)

    @Query("SELECT * FROM transactions WHERE pendingSync = 1")
    suspend fun getDirty(): List<TransactionEntity>

    @Query("UPDATE transactions SET pendingSync = 0 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    @Query("DELETE FROM transactions")
    suspend fun deleteAll()
}

@Dao
interface BudgetDao {
    @Query("SELECT * FROM budgets WHERE deletedAtEpochMs IS NULL ORDER BY name ASC")
    fun observeActive(): Flow<List<BudgetEntity>>

    @Query("SELECT * FROM budgets WHERE deletedAtEpochMs IS NULL")
    suspend fun getAllActive(): List<BudgetEntity>

    @Query("SELECT * FROM budgets WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<BudgetEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(budget: BudgetEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(budgets: List<BudgetEntity>)

    @Query("SELECT * FROM budgets WHERE pendingSync = 1")
    suspend fun getDirty(): List<BudgetEntity>

    @Query("UPDATE budgets SET pendingSync = 0 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    @Query("DELETE FROM budgets")
    suspend fun deleteAll()
}

@Dao
interface PlannedPaymentDao {
    @Query("SELECT * FROM planned_payments WHERE deletedAtEpochMs IS NULL ORDER BY nextDueAtEpochMs ASC")
    fun observeActive(): Flow<List<PlannedPaymentEntity>>

    @Query("SELECT * FROM planned_payments WHERE deletedAtEpochMs IS NULL")
    suspend fun getAllActive(): List<PlannedPaymentEntity>

    @Query("SELECT * FROM planned_payments WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<PlannedPaymentEntity>

    @Query("SELECT * FROM planned_payments WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): PlannedPaymentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: PlannedPaymentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<PlannedPaymentEntity>)

    @Query("SELECT * FROM planned_payments WHERE pendingSync = 1")
    suspend fun getDirty(): List<PlannedPaymentEntity>

    @Query("UPDATE planned_payments SET pendingSync = 0 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    @Query("DELETE FROM planned_payments")
    suspend fun deleteAll()
}

@Dao
interface HoldingDao {
    @Query("SELECT * FROM holdings WHERE deletedAtEpochMs IS NULL ORDER BY symbol ASC")
    fun observeActive(): Flow<List<HoldingEntity>>

    @Query("SELECT * FROM holdings WHERE deletedAtEpochMs IS NULL")
    suspend fun getAllActive(): List<HoldingEntity>

    @Query("SELECT * FROM holdings WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<HoldingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(holding: HoldingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(holdings: List<HoldingEntity>)

    @Query("SELECT * FROM holdings WHERE pendingSync = 1")
    suspend fun getDirty(): List<HoldingEntity>

    @Query("UPDATE holdings SET pendingSync = 0 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    @Query("DELETE FROM holdings")
    suspend fun deleteAll()
}

@Dao
interface MagicRuleDao {
    @Query("SELECT * FROM magic_rules WHERE deletedAtEpochMs IS NULL ORDER BY createdAtEpochMs ASC")
    fun observeActive(): Flow<List<MagicRuleEntity>>

    @Query("SELECT * FROM magic_rules WHERE deletedAtEpochMs IS NULL")
    suspend fun getAllActive(): List<MagicRuleEntity>

    @Query("SELECT * FROM magic_rules WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<MagicRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: MagicRuleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rules: List<MagicRuleEntity>)

    @Query("SELECT * FROM magic_rules WHERE pendingSync = 1")
    suspend fun getDirty(): List<MagicRuleEntity>

    @Query("UPDATE magic_rules SET pendingSync = 0 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    @Query("DELETE FROM magic_rules")
    suspend fun deleteAll()
}

@Dao
interface DebtDao {
    @Query("SELECT * FROM debts WHERE deletedAtEpochMs IS NULL ORDER BY updatedAtEpochMs DESC")
    fun observeActive(): Flow<List<DebtEntity>>

    @Query("SELECT * FROM debts WHERE deletedAtEpochMs IS NULL")
    suspend fun getAllActive(): List<DebtEntity>

    @Query("SELECT * FROM debts WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): DebtEntity?

    @Query("SELECT * FROM debts WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<DebtEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(debt: DebtEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(debts: List<DebtEntity>)

    @Query("DELETE FROM debts")
    suspend fun deleteAll()
}

@Dao
interface SavingsGoalDao {
    @Query("SELECT * FROM savings_goals WHERE deletedAtEpochMs IS NULL ORDER BY updatedAtEpochMs DESC")
    fun observeActive(): Flow<List<SavingsGoalEntity>>

    @Query("SELECT * FROM savings_goals WHERE deletedAtEpochMs IS NULL")
    suspend fun getAllActive(): List<SavingsGoalEntity>

    @Query("SELECT * FROM savings_goals WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): SavingsGoalEntity?

    @Query("SELECT * FROM savings_goals WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<SavingsGoalEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(goal: SavingsGoalEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(goals: List<SavingsGoalEntity>)

    @Query("DELETE FROM savings_goals")
    suspend fun deleteAll()
}

@Dao
interface ShoppingListDao {
    @Query("SELECT * FROM shopping_lists WHERE deletedAtEpochMs IS NULL ORDER BY updatedAtEpochMs DESC")
    fun observeActive(): Flow<List<ShoppingListEntity>>

    @Query("SELECT * FROM shopping_lists WHERE deletedAtEpochMs IS NULL")
    suspend fun getAllActive(): List<ShoppingListEntity>

    @Query("SELECT * FROM shopping_lists WHERE deletedAtEpochMs IS NULL ORDER BY updatedAtEpochMs DESC LIMIT 1")
    suspend fun getMostRecentActive(): ShoppingListEntity?

    @Query("SELECT * FROM shopping_lists WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ShoppingListEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(list: ShoppingListEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(lists: List<ShoppingListEntity>)

    @Query("DELETE FROM shopping_lists")
    suspend fun deleteAll()
}

@Dao
interface ShoppingItemDao {
    @Query("SELECT * FROM shopping_items WHERE deletedAtEpochMs IS NULL ORDER BY checked ASC, createdAtEpochMs ASC")
    fun observeActive(): Flow<List<ShoppingItemEntity>>

    @Query("SELECT * FROM shopping_items WHERE deletedAtEpochMs IS NULL AND listId = :listId ORDER BY checked ASC, createdAtEpochMs ASC")
    fun observeForList(listId: String): Flow<List<ShoppingItemEntity>>

    @Query("SELECT * FROM shopping_items WHERE deletedAtEpochMs IS NULL AND listId = :listId")
    suspend fun getActiveForList(listId: String): List<ShoppingItemEntity>

    @Query("SELECT * FROM shopping_items WHERE deletedAtEpochMs IS NULL AND listId = :listId ORDER BY checked ASC, createdAtEpochMs ASC")
    suspend fun getActiveOrderedForList(listId: String): List<ShoppingItemEntity>

    @Query("SELECT * FROM shopping_items WHERE deletedAtEpochMs IS NULL")
    suspend fun getAllActive(): List<ShoppingItemEntity>

    @Query("SELECT * FROM shopping_items WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ShoppingItemEntity?

    @Query("SELECT * FROM shopping_items WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<ShoppingItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ShoppingItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ShoppingItemEntity>)

    @Query("DELETE FROM shopping_items")
    suspend fun deleteAll()
}
