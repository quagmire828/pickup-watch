package com.pickupwatch.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pickupwatch.bridge.WatchBridgeService

/**
 * 开机自启 — 重新注册定时任务并拉起手表蓝牙互联守护服务
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            ScanWorker.schedule(context)
            WatchBridgeService.startSafely(context)
        }
    }
}
