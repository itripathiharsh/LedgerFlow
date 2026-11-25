package com.ledgerflow.app.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ledgerflow.app.LedgerFlowApplication
import com.ledgerflow.app.data.local.LedgerDatabase
import com.ledgerflow.app.data.sync.LedgerSyncRepository
import com.ledgerflow.app.data.sync.SyncOutcome
import com.ledgerflow.app.security.VaultStore
import java.util.concurrent.TimeUnit

class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repo = (applicationContext as? LedgerFlowApplication)?.syncRepository
            ?: LedgerSyncRepository(
                context = applicationContext,
                db = LedgerDatabase.getInstance(applicationContext),
                vault = VaultStore(applicationContext),
            )
        return when (repo.syncNow()) {
            is SyncOutcome.Success -> Result.success()
            is SyncOutcome.LocalOnly -> Result.success()
            is SyncOutcome.Error -> Result.retry()
        }
    }

    companion object {
        private const val PERIODIC_WORK_NAME = "ledgerflow_periodic_sync"
        private const val IMMEDIATE_WORK_NAME = "ledgerflow_immediate_sync"

        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val req = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                req,
            )
        }

        fun enqueueWhenOnline(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                req,
            )
        }
    }
}
