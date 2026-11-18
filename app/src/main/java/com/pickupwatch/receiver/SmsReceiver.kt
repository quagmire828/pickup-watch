package com.pickupwatch.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.pickupwatch.bridge.WatchBridge
import com.pickupwatch.bridge.WatchBridgeService
import com.pickupwatch.data.PickupCode
import com.pickupwatch.data.PickupDatabase
import com.pickupwatch.notify.PickupNotify
import com.pickupwatch.scanner.PickupParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 实时新短信广播接收器 — 收到快递短信时毫秒级解析并推送到手表
 */
class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = try {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
        } catch (e: Exception) {
            Log.e(TAG, "解析短信广播失败", e)
            return
        }
        if (messages.isNullOrEmpty()) return

        // 拼接长短信的多段正文
        val grouped = linkedMapOf<String, Pair<Long, StringBuilder>>()
        for (msg in messages) {
            val sender = msg.displayOriginatingAddress ?: ""
            val body = msg.displayMessageBody ?: continue
            val ts = if (msg.timestampMillis > 0) msg.timestampMillis else System.currentTimeMillis()
            val entry = grouped.getOrPut(sender) { ts to StringBuilder() }
            entry.second.append(body)
        }
        if (grouped.isEmpty()) return

        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                val dao = PickupDatabase.getInstance(appContext).pickupDao()
                var added = 0
                for ((sender, pair) in grouped) {
                    val (ts, sb) = pair
                    val text = sb.toString()
                    val parsed = PickupParser.parse(text, ts)
                    for (r in parsed) {
                        val existing = dao.findExisting(r.code, r.station)
                        if (existing == null) {
                            dao.insert(
                                PickupCode(
                                    code = r.code,
                                    courier = r.courier ?: guessCourier(sender),
                                    station = r.station,
                                    rawText = r.rawText,
                                    eventTime = r.eventTime,
                                    createdAt = System.currentTimeMillis()
                                )
                            )
                            added++
                        }
                    }
                }
                if (added > 0) {
                    Log.d(TAG, "实时捕获新增 $added 条取件码")
                    val unpicked = dao.getUnpicked()
                    PickupNotify.update(appContext, unpicked, silent = false)
                    WatchBridgeService.startSafely(appContext)
                    WatchBridge.init(appContext)
                    WatchBridge.pushCodesAsync(notifyNewCount = added)
                }
            } catch (e: Exception) {
                Log.e(TAG, "处理实时短信异常", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun guessCourier(address: String): String? {
        return when {
            address.contains("95338") || address.contains("顺丰") -> "顺丰"
            address.contains("95311") || address.contains("中通") -> "中通"
            address.contains("95554") || address.contains("圆通") -> "圆通"
            address.contains("95543") || address.contains("申通") -> "申通"
            address.contains("950616") || address.contains("京东") -> "京东"
            address.contains("956025") || address.contains("极兔") -> "极兔"
            address.contains("95353") || address.contains("德邦") -> "德邦"
            address.contains("11183") || address.contains("邮政") -> "邮政"
            else -> null
        }
    }
}
