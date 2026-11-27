package com.ledgerflow.app

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.room.InvalidationTracker
import androidx.work.Configuration
import com.ledgerflow.app.data.local.LedgerDatabase
import com.ledgerflow.app.data.local.SeedData
import com.ledgerflow.app.data.remote.SupabaseSyncClient
import com.ledgerflow.app.data.sync.LedgerSyncRepository
import com.ledgerflow.app.security.VaultStore
import com.ledgerflow.app.update.AppUpdateManager
import com.ledgerflow.app.widget.LedgerWidgetCoordinator
import com.ledgerflow.app.work.SyncWorker
import com.ledgerflow.app.work.UpdateCheckWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LedgerFlowApplication : Application(), Configuration.Provider {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var database: LedgerDatabase
        private set
    lateinit var vaultStore: VaultStore
        private set
    lateinit var remoteClient: SupabaseSyncClient
        private set
    lateinit var syncRepository: LedgerSyncRepository
        private set
    lateinit var updateManager: AppUpdateManager
        private set

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        database = LedgerDatabase.getInstance(this)
        vaultStore = VaultStore(this)
        remoteClient = SupabaseSyncClient()
        syncRepository = LedgerSyncRepository(this, database, vaultStore, remoteClient)
        updateManager = AppUpdateManager(this, vaultStore)

        applicationScope.launch {
            SeedData.ensureMinimalSetup(database, defaultCurrency = vaultStore.read().baseCurrency)
            database.processDueAutoPayPlannedPayments()
            syncRepository.restoreCachedFxRates()
            if (!vaultStore.read().pendingUpdateRelaunch) {
                AppUpdateManager.cleanupUpdateCache(this@LedgerFlowApplication)
            }
            LedgerWidgetCoordinator.refreshAllWidgetsNow(this@LedgerFlowApplication)
        }
        database.invalidationTracker.addObserver(
            object : InvalidationTracker.Observer(
                arrayOf("transactions", "accounts", "shopping_lists", "shopping_items")
            ) {
                override fun onInvalidated(tables: Set<String>) {
                    LedgerWidgetCoordinator.requestRefresh(this@LedgerFlowApplication)
                }
            }
        )
        registerLockScreenPrivacyReceiver()
        SyncWorker.schedulePeriodic(this)
        UpdateCheckWorker.schedulePeriodic(this)
    }

    private fun registerLockScreenPrivacyReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF,
                    Intent.ACTION_USER_PRESENT -> LedgerWidgetCoordinator.requestRefresh(context)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
    }
}
