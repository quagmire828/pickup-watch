package com.pickupwatch.scanner

import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import com.pickupwatch.data.PickupCode
import com.pickupwatch.data.PickupDao

/**
 * 短信扫描器 — 读取收件箱中的短信，用PickupParser解析取件码
 */
class SmsScanner(private val context: Context, private val dao: PickupDao) {

    companion object {
        private const val TAG = "SmsScanner"
    }

    /**
     * 扫描最近N天的短信，提取取件码并入库
     * @param daysBack 往回扫描多少天
     * @return 新发现的取件码数量
     */
    suspend fun scan(daysBack: Int = 7): Int {
        Log.d(TAG, "开始扫描，回溯 $daysBack 天")
        val since = System.currentTimeMillis() - daysBack * 24 * 3600_000L
        val uri = Telephony.Sms.Inbox.CONTENT_URI
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.ADDRESS
        )
        val selection = "${Telephony.Sms.DATE} > ?"
        val selectionArgs = arrayOf(since.toString())
        val sortOrder = "${Telephony.Sms.DATE} DESC"

        var newCount = 0
        var totalSms = 0

        try {
            context.contentResolver.query(
                uri, projection, selection, selectionArgs, sortOrder
            )?.use { cursor ->
                val bodyIdx = cursor.getColumnIndex(Telephony.Sms.BODY)
                val dateIdx = cursor.getColumnIndex(Telephony.Sms.DATE)
                val addrIdx = cursor.getColumnIndex(Telephony.Sms.ADDRESS)

                while (cursor.moveToNext()) {
                    totalSms++
                    val body = cursor.getString(bodyIdx) ?: continue
                    val date = cursor.getLong(dateIdx)
                    val address = cursor.getString(addrIdx) ?: ""

                    val results = PickupParser.parse(body, date)
                    if (results.isNotEmpty()) {
                        Log.d(TAG, "命中: [$body] -> ${results.map { it.code }}")
                    }
                    for (r in results) {
                        val existing = dao.findExisting(r.code, r.station)
                        if (existing == null) {
                            dao.insert(
                                PickupCode(
                                    code = r.code,
                                    courier = r.courier ?: guessCourierFromAddress(address),
                                    station = r.station,
                                    rawText = r.rawText,
                                    eventTime = r.eventTime,
                                    createdAt = System.currentTimeMillis()
                                )
                            )
                            newCount++
                        }
                    }
                }
                Log.d(TAG, "扫描完成: $totalSms 条短信, 新增 $newCount 条取件码")
            } ?: run {
                Log.e(TAG, "query 返回 null，检查短信权限")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "缺少 READ_SMS 权限", e)
        } catch (e: Exception) {
            Log.e(TAG, "扫描异常", e)
        }

        // 清理7天前已取的记录
        val weekAgo = System.currentTimeMillis() - 7 * 24 * 3600_000L
        dao.cleanOldPicked(weekAgo)

        return newCount
    }

    /**
     * 从短信发送者号码推断快递公司
     */
    private fun guessCourierFromAddress(address: String): String? {
        return when {
            address.contains("1069") || address.contains("1065") -> null // 通用平台号
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