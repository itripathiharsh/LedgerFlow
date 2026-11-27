package com.ledgerflow.app.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.ledgerflow.app.domain.SupportedPaymentApps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class PayAndTrackNotificationListenerService : NotificationListenerService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val pkg = sbn.packageName ?: return
        if (pkg == applicationContext.packageName) return

        val notification = sbn.notification ?: return
        if ((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) return
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val app = SupportedPaymentApps.resolve(pkg) ?: return
        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val text = bigText.ifBlank { extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty() }
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        val key = sbn.key.orEmpty()
        val postTime = sbn.postTime.takeIf { it > 0L } ?: System.currentTimeMillis()

        serviceScope.launch {
            PayAndTrackCapture.capturePaymentDetailed(
                context = applicationContext,
                title = title,
                text = text,
                packageName = app.packageName,
                appName = app.displayName,
                subText = subText,
                notificationKey = key,
                occurredAtEpochMs = postTime,
                manualEntry = false,
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
