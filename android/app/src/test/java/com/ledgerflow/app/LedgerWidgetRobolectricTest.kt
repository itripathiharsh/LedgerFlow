package com.ledgerflow.app

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ledgerflow.app.data.local.AccountEntity
import com.ledgerflow.app.data.local.LedgerDatabase
import com.ledgerflow.app.data.local.SeedData
import com.ledgerflow.app.data.local.ShoppingItemEntity
import com.ledgerflow.app.data.local.ShoppingListEntity
import com.ledgerflow.app.data.local.TransactionEntity
import com.ledgerflow.app.domain.MoneyFormatter
import com.ledgerflow.app.domain.ParsedShoppingItem
import com.ledgerflow.app.domain.VoiceShoppingParser
import com.ledgerflow.app.security.VaultSessionState
import com.ledgerflow.app.security.VaultStore
import com.ledgerflow.app.ui.theme.ThemeModes
import com.ledgerflow.app.widget.CompactActionBarWidgetProvider
import com.ledgerflow.app.widget.LedgerWidgetCoordinator
import com.ledgerflow.app.widget.QuickTransactionOverlayActivity
import com.ledgerflow.app.widget.SmartPasteOutcome
import com.ledgerflow.app.widget.SmartPasteProcessor
import com.ledgerflow.app.widget.SmartPasteTrampolineActivity
import com.ledgerflow.app.widget.SpendShoppingWidgetProvider
import com.ledgerflow.app.widget.VoiceShoppingOverlayActivity
import com.ledgerflow.app.widget.WidgetShoppingActionReceiver
import com.ledgerflow.app.widget.WidgetThemeStyle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File
import java.security.Key
import java.security.Security
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LedgerWidgetRobolectricTest {
    private lateinit var context: Context
    private lateinit var db: LedgerDatabase
    private lateinit var vault: VaultStore
    private val testJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    companion object {
        private val testKeys = ConcurrentHashMap<String, Key>()

        @JvmStatic
        @BeforeClass
        fun installKeyStore() {
            testKeys.clear()
            if (Security.getProvider("AndroidKeyStore") == null) {
                Security.insertProviderAt(RobolectricAndroidKeyStoreProvider(testKeys), 1)
            }
        }

        @JvmStatic
        @AfterClass
        fun removeKeyStore() {
            Security.removeProvider("AndroidKeyStore")
            testKeys.clear()
        }
    }

    @Before
    fun setup() {
        VaultStore.clearCacheForTests()
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "ledgerflow_vault.enc").delete()
        db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        vault = VaultStore(context)
        vault.update {
            VaultSessionState(
                baseCurrency = "INR",
                themeMode = ThemeModes.FLOW_DARK,
                hideBalances = false,
                showWidgetsOnLockScreen = false,
                onboardingCompleted = true,
            )
        }
    }

    @After
    fun tearDown() {
        LedgerWidgetCoordinator.cancelPendingRefreshes()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        db.close()
        File(context.filesDir, "ledgerflow_vault.enc").delete()
        VaultStore.clearCacheForTests()
    }

    @Test
    fun widgetSnapshot_respectsThemeModesMaskBalancesAndLockScreenPrivacy() = runTest {
        SeedData.ensureMinimalSetup(db, defaultCurrency = "INR", nowMs = 1_000L)
        val cash = db.accountDao().getAllActive().first()
        db.accountDao().upsert(cash.copy(balanceMinor = 295_000L)) // ₹2,950.00
        val now = System.currentTimeMillis()
        db.recordTransactionAtomic(
            TransactionEntity(
                id = "tx1",
                accountId = cash.id,
                categoryId = SeedData.CAT_DINING,
                txType = "EXPENSE",
                amountMinor = -50_000L, // ₹500.00 spent today
                currency = "INR",
                merchant = "Cafe",
                occurredAtEpochMs = now,
                createdAtEpochMs = now,
                updatedAtEpochMs = now,
            )
        )

        // 1. Unlocked & unmasked -> Flow Dark + exact amounts
        val unlockedSnap = LedgerWidgetCoordinator.loadSnapshot(
            context = context,
            dbOverride = db,
            vaultOverride = vault,
            nowMs = now,
            deviceLockedOverride = false,
        )
        assertEquals(WidgetThemeStyle.FLOW_DARK, unlockedSnap.themeStyle)
        assertFalse(unlockedSnap.isMasked)
        assertEquals(MoneyFormatter.formatMinor(50_000L, "INR"), unlockedSnap.todaySpendDisplay)
        assertEquals(MoneyFormatter.formatMinor(245_000L, "INR"), unlockedSnap.liquidBalanceDisplay)

        // 2. Device locked + default showWidgetsOnLockScreen = false -> Masked automatically!
        val lockedDefaultSnap = LedgerWidgetCoordinator.loadSnapshot(
            context = context,
            dbOverride = db,
            vaultOverride = vault,
            nowMs = now,
            deviceLockedOverride = true,
        )
        assertTrue(lockedDefaultSnap.isMasked)
        assertEquals("₹••••", lockedDefaultSnap.todaySpendDisplay)
        assertEquals("₹••••", lockedDefaultSnap.liquidBalanceDisplay)

        // Check RemoteViews contains NO unmasked numbers when masked
        val maskedViews = LedgerWidgetCoordinator.buildCompactRemoteViews(context, lockedDefaultSnap)
        val root = maskedViews.apply(context, FrameLayout(context))
        val spendText = root.findViewById<TextView>(R.id.widget_today_spend_value).text.toString()
        val liquidText = root.findViewById<TextView>(R.id.widget_liquid_balance_value).text.toString()
        assertEquals("₹••••", spendText)
        assertEquals("₹••••", liquidText)
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.widget_lock_icon).visibility)

        // 3. Opt-in lock screen display enabled -> visible while hideBalances is false
        vault.update { it.copy(showWidgetsOnLockScreen = true, themeMode = ThemeModes.PREMIUM_DARK) }
        val lockOptInSnap = LedgerWidgetCoordinator.loadSnapshot(
            context = context,
            dbOverride = db,
            vaultOverride = vault,
            nowMs = now,
            deviceLockedOverride = true,
        )
        assertEquals(WidgetThemeStyle.PREMIUM_BLACK, lockOptInSnap.themeStyle)
        assertFalse(lockOptInSnap.isMasked)

        // 4. Global Mask Balances ALWAYS overrides lock screen setting
        vault.update { it.copy(hideBalances = true) }
        val forcedMaskSnap = LedgerWidgetCoordinator.loadSnapshot(
            context = context,
            dbOverride = db,
            vaultOverride = vault,
            nowMs = now,
            deviceLockedOverride = false,
        )
        assertTrue(forcedMaskSnap.isMasked)
        assertEquals("₹••••", forcedMaskSnap.liquidBalanceDisplay)
    }

    @Test
    fun legacyVaultJsonWithoutLockScreenField_safelyDefaultsToHiddenOnLockScreen() {
        val legacyJson = """{"baseCurrency":"INR","hideBalances":false,"themeMode":"PREMIUM_DARK","onboardingCompleted":true}"""
        val decoded = testJson.decodeFromString(VaultSessionState.serializer(), legacyJson)
        assertFalse(decoded.showWidgetsOnLockScreen)
        assertTrue(LedgerWidgetCoordinator.shouldMaskBalances(decoded, deviceLocked = true))
        assertFalse(LedgerWidgetCoordinator.shouldMaskBalances(decoded, deviceLocked = false))
    }

    @Test
    fun shoppingWidgetStates_renderEmptyChecklistCompletedAndAdaptiveResize() = runTest {
        SeedData.ensureMinimalSetup(db, defaultCurrency = "INR", nowMs = 1_000L)
        val emptySnap = LedgerWidgetCoordinator.loadSnapshot(context, dbOverride = db, vaultOverride = vault, deviceLockedOverride = false)
        assertFalse(emptySnap.hasActiveList)

        val emptyRoot = LedgerWidgetCoordinator.buildShoppingRemoteViews(context, emptySnap).apply(context, FrameLayout(context))
        assertEquals(View.VISIBLE, emptyRoot.findViewById<View>(R.id.shopping_empty_container).visibility)
        assertEquals(View.GONE, emptyRoot.findViewById<View>(R.id.shopping_items_container).visibility)
        assertEquals("Create Shopping List", emptyRoot.findViewById<TextView>(R.id.btn_create_shopping_list).text.toString())

        // Populate a list with 5 items (1 checked, 4 unchecked)
        val outcome = db.addParsedShoppingItemsAtomic(
            listId = null,
            items = listOf(
                ParsedShoppingItem("Atta", "2 kg"),
                ParsedShoppingItem("Milk", "1"),
                ParsedShoppingItem("Paneer", "500 g"),
                ParsedShoppingItem("Bread", "1"),
                ParsedShoppingItem("Eggs", "1 dozen"),
            ),
            defaultListTitle = "Sunday Market",
            nowMs = 5_000L,
        )
        val eggs = db.shoppingItemDao().getActiveOrderedForList(outcome.listId).first { it.name == "Eggs" }
        db.toggleShoppingItemCheckedAtomic(eggs.id, nowMs = 6_000L)

        val populatedSnap = LedgerWidgetCoordinator.loadSnapshot(context, dbOverride = db, vaultOverride = vault, deviceLockedOverride = false)
        assertTrue(populatedSnap.hasActiveList)
        assertEquals("Sunday Market", populatedSnap.activeListTitle)
        assertEquals(1, populatedSnap.checkedCount)
        assertEquals(5, populatedSnap.totalCount)
        assertEquals(4, populatedSnap.uncheckedRows.size)

        val normalRoot = LedgerWidgetCoordinator.buildShoppingRemoteViews(context, populatedSnap).apply(context, FrameLayout(context))
        assertEquals("1/5", normalRoot.findViewById<TextView>(R.id.shopping_progress_badge).text.toString())
        assertEquals("2 kg Atta", normalRoot.findViewById<TextView>(R.id.shopping_item_text_0).text.toString())
        assertEquals("Milk", normalRoot.findViewById<TextView>(R.id.shopping_item_text_1).text.toString())
        assertEquals("500 g Paneer", normalRoot.findViewById<TextView>(R.id.shopping_item_text_2).text.toString())
        assertEquals(View.VISIBLE, normalRoot.findViewById<View>(R.id.shopping_more_count).visibility)
        assertEquals("+1 more items", normalRoot.findViewById<TextView>(R.id.shopping_more_count).text.toString())

        // Compact height resize (<165dp) trims safely to 2 rows + "+2 more items" and narrow width condenses "+ Exp"
        val tightOptions = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 150)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 195)
        }
        val resizedRoot = LedgerWidgetCoordinator.buildShoppingRemoteViews(context, populatedSnap, tightOptions).apply(context, FrameLayout(context))
        assertEquals("+ Exp", resizedRoot.findViewById<TextView>(R.id.btn_add_expense).text.toString())
        assertEquals(View.GONE, resizedRoot.findViewById<View>(R.id.shopping_row_2).visibility)
        assertEquals("+2 more items", resizedRoot.findViewById<TextView>(R.id.shopping_more_count).text.toString())

        // Check all remaining items -> switches cleanly to completed state
        for (item in db.shoppingItemDao().getActiveOrderedForList(outcome.listId)) {
            if (!item.checked) db.toggleShoppingItemCheckedAtomic(item.id, nowMs = 7_000L)
        }
        val doneSnap = LedgerWidgetCoordinator.loadSnapshot(context, dbOverride = db, vaultOverride = vault, deviceLockedOverride = false)
        val doneRoot = LedgerWidgetCoordinator.buildShoppingRemoteViews(context, doneSnap).apply(context, FrameLayout(context))
        assertEquals(View.VISIBLE, doneRoot.findViewById<View>(R.id.shopping_all_done_container).visibility)
        assertEquals(View.GONE, doneRoot.findViewById<View>(R.id.shopping_items_container).visibility)
    }

    @Test
    fun smartPasteProcessor_handlesValidDeclinedAmbiguousAndUnsupportedInputs() = runTest {
        SeedData.ensureMinimalSetup(db, defaultCurrency = "INR", nowMs = 1_000L)
        val sbi = AccountEntity("sbi1", "SBI Savings", "SBI", "CHECKING", "INR", 500_000L, "4821", 0L, "MANUAL", "OK", null, 1L, 1L)
        db.accountDao().upsert(sbi)

        // 1. Empty
        assertEquals(SmartPasteOutcome.EmptyClipboard, SmartPasteProcessor.evaluateAndCapture(context, "  ", db, vault))

        // 2. Valid completed payment -> creates PENDING_REVIEW with no category assigned
        val gpayMsg = "Paid ₹480.00 to Swiggy via Google Pay India from SBI A/c XX4821 UPI Ref 987654321"
        val valid = SmartPasteProcessor.evaluateAndCapture(context, gpayMsg, db, vault)
        assertTrue(valid is SmartPasteOutcome.CapturedPending)
        val captured = valid as SmartPasteOutcome.CapturedPending
        assertFalse(captured.duplicateSkipped)
        assertEquals("EXPENSE", captured.txType)
        val savedTx = db.transactionDao().getAllActive().single()
        assertEquals(TransactionEntity.STATUS_PENDING_REVIEW, savedTx.reviewStatus)
        assertNull(savedTx.categoryId)
        assertEquals("sbi1", savedTx.accountId)
        assertEquals(500_000L - 48_000L, db.accountDao().getById("sbi1")!!.balanceMinor)

        // Repeating same paste must deduplicate cleanly without double debit
        val repeat = SmartPasteProcessor.evaluateAndCapture(context, gpayMsg, db, vault) as SmartPasteOutcome.CapturedPending
        assertTrue(repeat.duplicateSkipped)
        assertEquals(1, db.transactionDao().getAllActive().size)
        assertEquals(500_000L - 48_000L, db.accountDao().getById("sbi1")!!.balanceMinor)

        // 3. Declined alert must be ignored cleanly
        val declined = SmartPasteProcessor.evaluateAndCapture(
            context,
            "Payment of ₹500 to Zomato failed due to wrong PIN",
            db,
            vault,
        )
        assertEquals(SmartPasteOutcome.IgnoredDeclinedAlert, declined)
        assertEquals(1, db.transactionDao().getAllActive().size)

        // 4. Uncertain / partial monetary text opens confirmation prefill instead of auto-saving
        val ambiguous = SmartPasteProcessor.evaluateAndCapture(
            context,
            "Order bill total Rs. 320.00",
            db,
            vault,
        )
        assertTrue(ambiguous is SmartPasteOutcome.NeedsConfirmation)
        assertEquals(32_000L, (ambiguous as SmartPasteOutcome.NeedsConfirmation).prefillAmountMinor)
        assertEquals(1, db.transactionDao().getAllActive().size)

        // 5. Random non-payment text -> unsupported
        assertEquals(
            SmartPasteOutcome.UnsupportedContent,
            SmartPasteProcessor.evaluateAndCapture(context, "Meet me at 5pm near the park", db, vault),
        )
    }

    @Test
    fun appWidgetProviders_bindWidgetIdsAndPersistenceAcrossReopen() = runTest {
        val dbFile = context.getDatabasePath("widget_persist_test.db")
        dbFile.delete()
        val diskDb = Room.databaseBuilder(context, LedgerDatabase::class.java, dbFile.name)
            .addMigrations(
                LedgerDatabase.MIGRATION_1_2,
                LedgerDatabase.MIGRATION_2_3,
                LedgerDatabase.MIGRATION_3_4,
                LedgerDatabase.MIGRATION_4_5,
            )
            .allowMainThreadQueries()
            .build()

        SeedData.ensureMinimalSetup(diskDb, defaultCurrency = "INR", nowMs = 1_000L)
        val added = diskDb.addParsedShoppingItemsAtomic(
            listId = null,
            items = VoiceShoppingParser.parse("2 kg atta, milk"),
            nowMs = 2_000L,
        )
        val itemToCheck = diskDb.shoppingItemDao().getActiveOrderedForList(added.listId).first()
        diskDb.toggleShoppingItemCheckedAtomic(itemToCheck.id, nowMs = 3_000L)
        diskDb.close()

        // Reopen DB (simulating process restart) and confirm toggled shopping item persisted
        val reopenedDb = Room.databaseBuilder(context, LedgerDatabase::class.java, dbFile.name)
            .addMigrations(
                LedgerDatabase.MIGRATION_1_2,
                LedgerDatabase.MIGRATION_2_3,
                LedgerDatabase.MIGRATION_3_4,
                LedgerDatabase.MIGRATION_4_5,
            )
            .allowMainThreadQueries()
            .build()
        val persisted = reopenedDb.shoppingItemDao().getById(itemToCheck.id)
        assertNotNull(persisted)
        assertTrue(persisted!!.checked)
        reopenedDb.close()
        dbFile.delete()

        // Bind both widget providers with ShadowAppWidgetManager and verify RemoteViews update + actions
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val shadowAvm = Shadows.shadowOf(appWidgetManager)
        val compactId = shadowAvm.createWidget(CompactActionBarWidgetProvider::class.java, R.layout.widget_compact_action_bar)
        val shoppingId = shadowAvm.createWidget(SpendShoppingWidgetProvider::class.java, R.layout.widget_spend_shopping_list)

        val snap = LedgerWidgetCoordinator.loadSnapshot(context, dbOverride = db, vaultOverride = vault, deviceLockedOverride = false)
        appWidgetManager.updateAppWidget(compactId, LedgerWidgetCoordinator.buildCompactRemoteViews(context, snap))
        appWidgetManager.updateAppWidget(shoppingId, LedgerWidgetCoordinator.buildShoppingRemoteViews(context, snap))

        val compactView = shadowAvm.getViewFor(compactId)
        val shoppingView = shadowAvm.getViewFor(shoppingId)
        assertNotNull(compactView.findViewById<View>(R.id.btn_add_expense))
        assertNotNull(compactView.findViewById<View>(R.id.btn_voice_list))
        assertNotNull(compactView.findViewById<View>(R.id.btn_smart_paste))
        assertNotNull(compactView.findViewById<View>(R.id.btn_add_income))
        assertNotNull(shoppingView.findViewById<View>(R.id.shopping_empty_container))

        // Verify quick intents construct clean launcher overlay targets
        val expIntent = QuickTransactionOverlayActivity.newIntent(context, "EXPENSE")
        assertEquals(ComponentName(context, QuickTransactionOverlayActivity::class.java), expIntent.component)
        assertEquals("EXPENSE", expIntent.getStringExtra(QuickTransactionOverlayActivity.EXTRA_TX_TYPE))

        val incIntent = QuickTransactionOverlayActivity.newIntent(context, "INCOME")
        assertEquals("INCOME", incIntent.getStringExtra(QuickTransactionOverlayActivity.EXTRA_TX_TYPE))

        val voiceIntent = VoiceShoppingOverlayActivity.newIntent(context)
        assertEquals(ComponentName(context, VoiceShoppingOverlayActivity::class.java), voiceIntent.component)

        val pasteIntent = SmartPasteTrampolineActivity.newIntent(context)
        assertEquals(ComponentName(context, SmartPasteTrampolineActivity::class.java), pasteIntent.component)

        val toggleIntent = WidgetShoppingActionReceiver.toggleIntent(context, "item_123")
        assertEquals(WidgetShoppingActionReceiver.ACTION_TOGGLE_SHOPPING_ITEM, toggleIntent.action)
        assertEquals("item_123", toggleIntent.getStringExtra(WidgetShoppingActionReceiver.EXTRA_ITEM_ID))
    }
}
