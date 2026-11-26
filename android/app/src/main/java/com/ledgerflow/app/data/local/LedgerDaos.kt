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

    @Query("SELECT * FROM categories WHERE deletedAtEpochMs IS NULL")
    suspend fun getAllActive(): List<CategoryEntity>

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
