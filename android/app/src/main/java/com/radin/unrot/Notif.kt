package com.radin.unrot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

object Notif {
    const val CH_STATUS = "status"
    const val CH_ALERTS = "alerts"
    const val ID_STATUS = 1
    private var nextId = 100

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, "Balance", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows how much scroll time you have left"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERTS, "Heads-ups", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Low balance warnings and earned time"
            }
        )
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun status(ctx: Context, text: String): Notification =
        Notification.Builder(ctx, CH_STATUS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openApp(ctx))
            .build()

    fun alert(ctx: Context, title: String, body: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val n = Notification.Builder(ctx, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(nextId++, n) }
    }
}
