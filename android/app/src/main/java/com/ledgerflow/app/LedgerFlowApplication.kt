package com.ledgerflow.app

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import com.ledgerflow.app.data.local.LedgerDatabase
import com.ledgerflow.app.data.local.SeedData
import com.ledgerflow.app.data.remote.SupabaseSyncClient
import com.ledgerflow.app.data.sync.LedgerSyncRepository
import com.ledgerflow.app.security.VaultStore
import com.ledgerflow.app.update.AppUpdateManager
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
        }
        SyncWorker.schedulePeriodic(this)
        UpdateCheckWorker.schedulePeriodic(this)
    }
}
