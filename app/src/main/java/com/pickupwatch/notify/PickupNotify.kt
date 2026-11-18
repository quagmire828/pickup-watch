package com.pickupwatch.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.pickupwatch.data.PickupCode
import com.pickupwatch.ui.MainActivity

/**
 * 通知管理 — 待取快递通知栏提醒（非 Ongoing 常驻，兼容小米运动健康通知同步）
 */
object PickupNotify {

    private const val CHANNEL_ID = "pickup_codes"
    private const val NOTIFY_ID = 100

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "待取快递提醒",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "显示待取的快递取件码，并支持同步至手表通知栏"
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    fun update(context: Context, codes: List<PickupCode>, silent: Boolean = true) {
        createChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (codes.isEmpty()) {
            nm.cancel(NOTIFY_ID)
            return
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = "📦 待取快递 ${codes.size} 件"
        val content = codes.take(5).joinToString("\n") { c ->
            val label = buildString {
                if (!c.courier.isNullOrEmpty()) append(c.courier)
                if (!c.station.isNullOrEmpty()) {
                    if (isNotEmpty()) append("·")
                    append(c.station)
                }
            }
            "${label.ifEmpty { "快递" }}: ${c.code}"
        }.let {
            if (codes.size > 5) "$it\n...还有 ${codes.size - 5} 件" else it
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(content.lineSequence().firstOrNull() ?: title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setContentIntent(pendingIntent)
            .setAutoCancel(false)
            .setOngoing(false)
            .setOnlyAlertOnce(silent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        try {
            nm.notify(NOTIFY_ID, notification)
        } catch (_: SecurityException) {
        }
    }

    fun cancel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFY_ID)
    }
}
