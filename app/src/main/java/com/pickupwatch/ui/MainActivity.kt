package com.pickupwatch.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pickupwatch.bridge.WatchBridge
import com.pickupwatch.bridge.WatchBridgeService
import com.pickupwatch.data.PickupCode
import com.pickupwatch.data.PickupDatabase
import com.pickupwatch.databinding.ActivityMainBinding
import com.pickupwatch.notify.PickupNotify
import com.pickupwatch.scanner.SmsScanner
import com.pickupwatch.worker.ScanWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var db: PickupDatabase
    private lateinit var adapter: PickupAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        PickupNotify.createChannel(this)
        db = PickupDatabase.getInstance(this)

        setupRecyclerView()
        setupButtons()
        observeDataAndBridge()
        requestPermissions()

        WatchBridgeService.startSafely(this)
        WatchBridge.init(this)
        ScanWorker.schedule(this)
    }

    override fun onResume() {
        super.onResume()
        WatchBridge.refreshConnection(launchWatch = false)
    }

    private fun observeDataAndBridge() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    db.pickupDao().observeUnpicked().collect { codes ->
                        adapter.submitList(codes)
                        binding.tvEmpty.visibility = if (codes.isEmpty()) View.VISIBLE else View.GONE
                        binding.recyclerView.visibility = if (codes.isEmpty()) View.GONE else View.VISIBLE
                        binding.tvCount.text = if (codes.isEmpty()) "没有待取快递" else "待取 ${codes.size} 件"
                        PickupNotify.update(this@MainActivity, codes, silent = true)
                    }
                }
                launch {
                    WatchBridge.state.collect { state ->
                        binding.tvServerStatus.visibility = View.VISIBLE
                        binding.tvServerStatus.text = state.statusText
                        if (state.connected) {
                            binding.tvServerStatus.setBackgroundColor(Color.parseColor("#E8F5E9"))
                            binding.tvServerStatus.setTextColor(Color.parseColor("#2E7D32"))
                        } else {
                            binding.tvServerStatus.setBackgroundColor(Color.parseColor("#FFF8E1"))
                            binding.tvServerStatus.setTextColor(Color.parseColor("#EF6C00"))
                        }
                    }
                }
            }
        }
    }

    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val missing = mutableListOf<String>()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                missing.add(Manifest.permission.READ_SMS)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                missing.add(Manifest.permission.RECEIVE_SMS)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    missing.add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            if (missing.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            val smsGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_SMS
            ) == PackageManager.PERMISSION_GRANTED
            if (smsGranted) {
                lifecycleScope.launch {
                    delay(300)
                    doScanSms()
                }
            }
        }
    }

    private suspend fun doScanSms() = withContext(Dispatchers.Main) {
        binding.btnScan.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        binding.tvStatus.text = "正在扫描最近 7 天短信..."
        binding.tvStatus.visibility = View.VISIBLE

        val count = withContext(Dispatchers.IO) {
            SmsScanner(this@MainActivity, db.pickupDao()).scan(7)
        }
        binding.tvStatus.text = if (count > 0) {
            "扫描完成：新增 $count 条取件码，已同步手表"
        } else {
            "没有新的取件码（如漏扫快递短信，请开启系统「通知类短信」读取权限）"
        }
        binding.btnScan.isEnabled = true
        binding.progressBar.visibility = View.GONE
        WatchBridge.pushCodesAsync(notifyNewCount = count)
    }

    private fun setupRecyclerView() {
        adapter = PickupAdapter { markPicked(it) }

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder,
                t: RecyclerView.ViewHolder
            ) = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val pos = viewHolder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION && pos < adapter.currentList.size) {
                    val code = adapter.currentList[pos]
                    markPicked(code)
                }
            }
        })
        touchHelper.attachToRecyclerView(binding.recyclerView)
    }

    private fun setupButtons() {
        binding.btnScan.setOnClickListener {
            lifecycleScope.launch { doScanSms() }
        }

        binding.btnServer.setOnClickListener {
            WatchBridgeService.startSafely(this)
            WatchBridge.init(this)
            WatchBridge.refreshConnection(launchWatch = true)
            binding.tvStatus.visibility = View.VISIBLE
            binding.tvStatus.text = "已向手表发起蓝牙同步与唤醒请求"
        }
    }

    private fun markPicked(code: PickupCode) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { db.pickupDao().markPicked(code.id) }
            WatchBridge.pushCodesAsync(notifyNewCount = 0)
        }
    }
}
