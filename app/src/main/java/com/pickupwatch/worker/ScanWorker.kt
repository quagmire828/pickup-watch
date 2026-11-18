package com.pickupwatch.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import com.pickupwatch.bridge.WatchBridge
import com.pickupwatch.data.PickupDatabase
import com.pickupwatch.notify.PickupNotify
import com.pickupwatch.scanner.SmsScanner
import java.util.concurrent.TimeUnit

/**
 * 定时扫描 Worker — 早 7:00、午 13:00、晚 18:00 兜底扫描收件箱并同步手表
 */
class ScanWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.d("ScanWorker", "开始定时扫描短信...")
        return try {
            val dao = PickupDatabase.getInstance(applicationContext).pickupDao()
            val scanner = SmsScanner(applicationContext, dao)
            val count = scanner.scan(7)
            val unpicked = dao.getUnpicked()

            PickupNotify.update(applicationContext, unpicked, silent = count == 0)
            WatchBridge.init(applicationContext)
            WatchBridge.pushCodesAsync(notifyNewCount = count)

            Log.d("ScanWorker", "扫描完成，新增 $count 条取件码，当前待取 ${unpicked.size} 件")
            Result.success()
        } catch (e: SecurityException) {
            Log.e("ScanWorker", "缺少短信权限", e)
            Result.failure()
        } catch (e: Exception) {
            Log.e("ScanWorker", "扫描失败", e)
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "pickup_scan"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .build()

            val times = listOf(
                Triple(7, 0, "morning"),
                Triple(13, 0, "noon"),
                Triple(18, 0, "evening")
            )

            for ((hour, minute, tag) in times) {
                val request = PeriodicWorkRequestBuilder<ScanWorker>(24, TimeUnit.HOURS)
                    .setConstraints(constraints)
                    .setInitialDelay(initialDelayTo(hour, minute), TimeUnit.MILLISECONDS)
                    .addTag(tag)
                    .build()

                WorkManager.getInstance(context)
                    .enqueueUniquePeriodicWork(
                        "${WORK_NAME}_$tag",
                        ExistingPeriodicWorkPolicy.UPDATE,
                        request
                    )
            }
        }

        fun scanNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<ScanWorker>().build()
            WorkManager.getInstance(context).enqueue(request)
        }

        private fun initialDelayTo(hour: Int, minute: Int): Long {
            val now = System.currentTimeMillis()
            val cal = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, hour)
                set(java.util.Calendar.MINUTE, minute)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
                if (timeInMillis <= now) {
                    add(java.util.Calendar.DAY_OF_YEAR, 1)
                }
            }
            return cal.timeInMillis - now
        }
    }
}
