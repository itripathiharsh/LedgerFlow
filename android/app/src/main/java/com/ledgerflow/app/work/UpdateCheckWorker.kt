package com.ledgerflow.app.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ledgerflow.app.LedgerFlowApplication
import com.ledgerflow.app.security.VaultStore
import com.ledgerflow.app.update.AppUpdateManager
import com.ledgerflow.app.util.NetworkMonitor
import java.util.concurrent.TimeUnit

class UpdateCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // Offline-first guarantee: never interrupt or retry repeatedly while offline.
        if (!NetworkMonitor.isOnline(applicationContext)) {
            return Result.success()
        }
        val updater = (applicationContext as? LedgerFlowApplication)?.updateManager
            ?: AppUpdateManager(applicationContext, VaultStore(applicationContext))
        updater.checkForUpdate(ignoreDismissed = false)
        return Result.success()
    }

    companion object {
        private const val PERIODIC_UPDATE_WORK = "ledgerflow_periodic_update_check"

        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val req = PeriodicWorkRequestBuilder<UpdateCheckWorker>(12, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_UPDATE_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                req,
            )
        }
    }
}
