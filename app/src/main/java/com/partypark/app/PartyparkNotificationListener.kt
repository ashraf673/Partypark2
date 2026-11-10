package com.partypark.app

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class PartyparkNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // Notification listener is only used to improve background connectivity.
        // No data is collected.
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}
}