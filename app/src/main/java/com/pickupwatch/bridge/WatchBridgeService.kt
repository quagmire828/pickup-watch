package com.pickupwatch.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.pickupwatch.data.PickupDatabase
import com.pickupwatch.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 手表蓝牙互联常驻守护服务
 *
 * 保持进程存活并维持 Xiaomi Wearable MessageApi 监听，
 * 确保用户在快递柜前抬腕打开手表端 App 时，无需掏出手机即可实时同步与标记已取。
 */
class WatchBridgeService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val CHANNEL_ID = "pickup_watch_bridge"
        private const val NOTIFY_ID = 1

        @Volatile
        var isRunning: Boolean = false
            private set

        fun startSafely(context: Context) {
            try {
                val intent = Intent(context, WatchBridgeService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createChannel()
        startForeground(NOTIFY_ID, buildNotification("手表蓝牙监听准备中..."))

        WatchBridge.init(applicationContext)

        val dao = PickupDatabase.getInstance(applicationContext).pickupDao()
        scope.launch {
            var lastIdsSignature = ""
            combine(dao.observeUnpicked(), WatchBridge.state) { codes, bridgeState ->
                codes to bridgeState
            }.collect { (codes, bridgeState) ->
                val statusSummary = if (bridgeState.connected) {
                    "已就绪（${bridgeState.nodeName ?: "手表"}）· 待取 ${codes.size} 件"
                } else {
                    "后台就绪 · 待取 ${codes.size} 件"
                }
                updateForegroundNotification(statusSummary)

                // 当待取列表内容发生改变时，主动推送给手表
                val sig = codes.joinToString(",") { "${it.id}:${it.picked}" }
                if (sig != lastIdsSignature) {
                    lastIdsSignature = sig
                    WatchBridge.pushCodesAsync(notifyNewCount = 0)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        WatchBridge.init(applicationContext)
        WatchBridge.refreshConnection(launchWatch = false)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("取件码手表同步服务")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateForegroundNotification(text: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFY_ID, buildNotification(text))
        } catch (_: SecurityException) {
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                CHANNEL_ID,
                "手表蓝牙互联守护",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持手表端随时可拉取取件码与标记已取"
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }
    }
}
