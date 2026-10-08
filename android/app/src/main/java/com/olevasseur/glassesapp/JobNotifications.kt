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

class JobNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(NotificationChannel(
            "jobs", "Background jobs", NotificationManager.IMPORTANCE_DEFAULT,
        ))
    }

    fun show(event: JobEvent): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        if (!manager.areNotificationsEnabled() || manager.getNotificationChannel("jobs").importance == NotificationManager.IMPORTANCE_NONE) return false
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(event.jobId, 1, Notification.Builder(context, "jobs")
            .setSmallIcon(R.drawable.ic_glasses)
            .setContentTitle("Job ${event.status}")
            .setContentText(event.summary)
            .setStyle(Notification.BigTextStyle().bigText(event.summary))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(open).setAutoCancel(true).build())
        return true
    }
}
