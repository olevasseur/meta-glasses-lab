package com.olevasseur.glassesapp

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

class ApprovalNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    init {
        manager.createNotificationChannel(NotificationChannel("approvals", "Action approvals", NotificationManager.IMPORTANCE_HIGH))
    }
    fun show(request: DeviceApproval): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        if (!manager.areNotificationsEnabled() || manager.getNotificationChannel("approvals").importance == NotificationManager.IMPORTANCE_NONE) return false
        val open = PendingIntent.getActivity(context, 1, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(2, Notification.Builder(context, "approvals")
            .setSmallIcon(R.drawable.ic_glasses).setContentTitle("Review action · 60 seconds")
            .setContentText(request.summary).setStyle(Notification.BigTextStyle().bigText(request.displayText))
            .setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(open).setTimeoutAfter(60_000)
            .setOnlyAlertOnce(true).build())
        return true
    }
    fun hide() { manager.cancel(2) }
}
