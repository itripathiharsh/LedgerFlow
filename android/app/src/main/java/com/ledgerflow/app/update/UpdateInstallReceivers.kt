package com.ledgerflow.app.update

import android.Manifest
import android.app.ActivityOptions
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.IntentCompat
import com.ledgerflow.app.security.VaultStore

class UpdateInstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirmIntent != null) {
                    UpdateEventBus.emit(UpdateInstallEvent.UserConfirmationRequired(Intent(confirmIntent)))
                    if (!UpdateEventBus.isForegroundActive) {
                        val backgroundIntent = Intent(confirmIntent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { context.startActivity(backgroundIntent) }
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                UpdateEventBus.emit(UpdateInstallEvent.Success)
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                VaultStore(context).update { it.copy(pendingUpdateRelaunch = false, pendingUpdateVersionName = null) }
                UpdateEventBus.emit(UpdateInstallEvent.Failed("Update installation cancelled"))
            }
            else -> {
                VaultStore(context).update { it.copy(pendingUpdateRelaunch = false, pendingUpdateVersionName = null) }
                UpdateEventBus.emit(
                    UpdateInstallEvent.Failed(
                        message.ifBlank { "Update installation failed (status $status)" }
                    )
                )
            }
        }
    }
}

class AppReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val vault = VaultStore(context)
        val state = vault.read()
        AppUpdateManager.cleanupUpdateCache(context)

        if (!state.pendingUpdateRelaunch) return

        val updatedVersion = state.pendingUpdateVersionName ?: AppUpdateManager.installedVersionName(context)
        vault.update {
            it.copy(
                pendingUpdateRelaunch = false,
                pendingUpdateVersionName = null,
                pendingInstallResumeVersionCode = 0L,
                justUpdatedVersionName = updatedVersion,
                cachedUpdateJson = null,
                dismissedUpdateVersionCode = 0L,
            )
        }

        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            )
            putExtra(EXTRA_JUST_UPDATED, true)
            putExtra(EXTRA_UPDATED_VERSION, updatedVersion)
        } ?: return

        postFallbackReopenNotification(context, updatedVersion, launchIntent)

        val launchedViaPendingIntent = runCatching {
            val pi = PendingIntent.getActivity(
                context,
                AppUpdateManager.UPDATE_NOTIFICATION_ID + 1,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val opts = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                    .toBundle()
                pi.send(context, 0, null, null, null, null, opts)
                true
            } else {
                false
            }
        }.getOrDefault(false)

        if (!launchedViaPendingIntent) {
            runCatching { context.startActivity(launchIntent) }
        }
    }

    private fun postFallbackReopenNotification(
        context: Context,
        versionName: String,
        launchIntent: Intent,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "App Updates",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Notifies when LedgerFlow finishes updating cleanly"
            }
            nm.createNotificationChannel(channel)

            val contentPi = PendingIntent.getActivity(
                context,
                AppUpdateManager.UPDATE_NOTIFICATION_ID,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("LedgerFlow updated to v$versionName")
                .setContentText("Tap to reopen your updated financial vault")
                .setAutoCancel(true)
                .setContentIntent(contentPi)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            NotificationManagerCompat.from(context).notify(AppUpdateManager.UPDATE_NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "ledgerflow_updates"
        const val EXTRA_JUST_UPDATED = "ledgerflow_just_updated"
        const val EXTRA_UPDATED_VERSION = "ledgerflow_updated_version"
    }
}
