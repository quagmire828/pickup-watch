package com.pickupwatch.bridge

import android.content.Context
import android.util.Log
import com.pickupwatch.data.PickupCode
import com.pickupwatch.data.PickupDatabase
import com.pickupwatch.notify.PickupNotify
import com.pickupwatch.scanner.SmsScanner
import com.xiaomi.xms.wearable.Wearable
import com.xiaomi.xms.wearable.auth.AuthApi
import com.xiaomi.xms.wearable.auth.Permission
import com.xiaomi.xms.wearable.exception.AppNotInstalledException
import com.xiaomi.xms.wearable.exception.DeviceDisconnectedException
import com.xiaomi.xms.wearable.exception.PermissionDeniedException
import com.xiaomi.xms.wearable.exception.SignatureVerifyFailedException
import com.xiaomi.xms.wearable.message.MessageApi
import com.xiaomi.xms.wearable.message.OnMessageReceivedListener
import com.xiaomi.xms.wearable.node.DataItem
import com.xiaomi.xms.wearable.node.DataSubscribeResult
import com.xiaomi.xms.wearable.node.Node
import com.xiaomi.xms.wearable.node.NodeApi
import com.xiaomi.xms.wearable.node.OnDataChangedListener
import com.xiaomi.xms.wearable.notify.NotifyApi
import com.xiaomi.xms.wearable.service.OnServiceConnectionListener
import com.xiaomi.xms.wearable.service.ServiceApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 小米穿戴 Interconnect 跨端通信核心管理类
 *
 * 负责与红米 Watch 6 上的 Vela 快应用 (com.pickupwatch) 建立双向蓝牙通信：
 * - 手表发 {"type":"sync"}  -> 手机返回当前待取列表
 * - 手表发 {"type":"scan"}  -> 手机重新扫描短信收件箱并返回最新待取列表
 * - 手表发 {"type":"pick", "id":123, "ids":[...]} -> 手机标记已取、更新通知并返回最新列表
 * - 手机侧数据变更（扫描新增 / 手机端标记已取） -> 主动推送给手表端
 */
object WatchBridge {

    private const val TAG = "WatchBridge"
    private const val MAX_WATCH_ITEMS = 30

    data class BridgeState(
        val connected: Boolean = false,
        val nodeId: String? = null,
        val nodeName: String? = null,
        val watchAppInstalled: Boolean? = null,
        val statusText: String = "正在连接小米运动健康服务...",
        val lastSyncAt: Long = 0L
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _state = MutableStateFlow(BridgeState())
    val state: StateFlow<BridgeState> = _state.asStateFlow()

    @Volatile
    private var initialized = false
    private lateinit var appContext: Context

    private var serviceApi: ServiceApi? = null
    private var nodeApi: NodeApi? = null
    private var authApi: AuthApi? = null
    private var messageApi: MessageApi? = null
    private var notifyApi: NotifyApi? = null

    @Volatile
    private var activeNode: Node? = null

    @Volatile
    private var listeningNodeId: String? = null

    @Volatile
    private var notifyPermissionGranted: Boolean = false

    private val serviceConnectionListener = object : OnServiceConnectionListener {
        override fun onServiceConnected() {
            Log.d(TAG, "小米运动健康服务已连接")
            refreshConnection(launchWatch = false)
        }

        override fun onServiceDisconnected() {
            Log.w(TAG, "小米运动健康服务连接断开")
            listeningNodeId = null
            activeNode = null
            _state.value = BridgeState(
                connected = false,
                statusText = "小米运动健康服务已断开，等待重连..."
            )
        }
    }

    private val connectionDataListener = OnDataChangedListener { nodeId, dataItem, result ->
        if (dataItem.type == DataItem.ITEM_CONNECTION.type) {
            val status = result.connectedStatus
            Log.d(TAG, "设备连接状态变更 nodeId=$nodeId status=$status")
            if (status == DataSubscribeResult.RESULT_CONNECTION_CONNECTED) {
                refreshConnection(launchWatch = false)
            } else if (status == DataSubscribeResult.RESULT_CONNECTION_DISCONNECTED) {
                listeningNodeId = null
                _state.value = _state.value.copy(
                    connected = false,
                    statusText = "⌚ 手表蓝牙已断开，重连后自动同步"
                )
            }
        }
    }

    private val messageListener = OnMessageReceivedListener { nodeId, bytes ->
        val text = try {
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "解码手表消息失败", e)
            return@OnMessageReceivedListener
        }
        Log.d(TAG, "收到手表消息($nodeId): $text")
        handleWatchMessage(nodeId, text)
    }

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        try {
            serviceApi = Wearable.getServiceApi(appContext)
            nodeApi = Wearable.getNodeApi(appContext)
            authApi = Wearable.getAuthApi(appContext)
            messageApi = Wearable.getMessageApi(appContext)
            notifyApi = Wearable.getNotifyApi(appContext)
            serviceApi?.registerServiceConnectionListener(serviceConnectionListener)
            initialized = true
            refreshConnection(launchWatch = false)
        } catch (e: Exception) {
            Log.e(TAG, "初始化 Xiaomi Wearable SDK 失败", e)
            _state.value = BridgeState(
                connected = false,
                statusText = "未检测到小米运动健康组件: ${e.message}"
            )
        }
    }

    /**
     * 刷新设备连接、检查授权、注册手表消息监听并同步当前取件码
     */
    fun refreshConnection(launchWatch: Boolean = false) {
        if (!initialized) return
        nodeApi?.connectedNodes
            ?.addOnSuccessListener { nodes ->
                if (nodes.isNullOrEmpty()) {
                    activeNode = null
                    listeningNodeId = null
                    _state.value = BridgeState(
                        connected = false,
                        statusText = "⌚ 未检测到已连接手表（请确认小米运动健康已连接红米Watch 6）"
                    )
                    return@addOnSuccessListener
                }
                val node = nodes[0]
                activeNode = node
                ensurePermissionsAndSetup(node, launchWatch)
            }
            ?.addOnFailureListener { e ->
                Log.w(TAG, "查询已连接手表失败", e)
                _state.value = BridgeState(
                    connected = false,
                    statusText = formatError("查询手表连接失败", e)
                )
            }
    }

    private fun ensurePermissionsAndSetup(node: Node, launchWatch: Boolean) {
        val perms = arrayOf(Permission.DEVICE_MANAGER, Permission.NOTIFY)
        authApi?.checkPermissions(node.id, perms)
            ?.addOnSuccessListener { granted ->
                val deviceGranted = granted != null && granted.isNotEmpty() && granted[0]
                notifyPermissionGranted = granted != null && granted.size > 1 && granted[1]
                if (deviceGranted) {
                    setupNodeChannel(node, launchWatch)
                } else {
                    requestWearPermissions(node, launchWatch)
                }
            }
            ?.addOnFailureListener {
                // 首次检查若异常则直接尝试申请授权
                requestWearPermissions(node, launchWatch)
            }
    }

    private fun requestWearPermissions(node: Node, launchWatch: Boolean) {
        _state.value = _state.value.copy(
            nodeId = node.id,
            nodeName = node.name,
            statusText = "⌚ 正在向小米运动健康申请穿戴设备通信权限..."
        )
        authApi?.requestPermission(node.id, Permission.DEVICE_MANAGER, Permission.NOTIFY)
            ?.addOnSuccessListener { grantedPerms ->
                val names = grantedPerms?.map { it.name }?.toSet() ?: emptySet()
                notifyPermissionGranted = names.contains(Permission.NOTIFY.name)
                if (names.contains(Permission.DEVICE_MANAGER.name) || grantedPerms != null) {
                    setupNodeChannel(node, launchWatch)
                } else {
                    _state.value = BridgeState(
                        connected = false,
                        nodeId = node.id,
                        nodeName = node.name,
                        statusText = "⌚ 未授予设备管理权限，请在小米运动健康「设备授权管理」中开启"
                    )
                }
            }
            ?.addOnFailureListener { e ->
                Log.e(TAG, "申请穿戴通信权限失败", e)
                _state.value = BridgeState(
                    connected = false,
                    nodeId = node.id,
                    nodeName = node.name,
                    statusText = formatError("穿戴通信授权失败", e)
                )
            }
    }

    private fun setupNodeChannel(node: Node, launchWatch: Boolean) {
        // 订阅设备断连/重连事件
        try {
            nodeApi?.subscribe(node.id, DataItem.ITEM_CONNECTION, connectionDataListener)
        } catch (_: Exception) {
        }

        // 检查表端 rpk 是否已安装
        nodeApi?.isWearAppInstalled(node.id)
            ?.addOnSuccessListener { installed ->
                val current = _state.value
                val hint = if (installed == true) {
                    "⌚ 已连接 ${node.name} · 蓝牙互通就绪"
                } else {
                    "⌚ 已连接 ${node.name} · 请在手表端安装「取件码助手.rpk」"
                }
                _state.value = current.copy(
                    nodeId = node.id,
                    nodeName = node.name,
                    watchAppInstalled = installed,
                    statusText = hint
                )
            }

        // 注册消息监听器（若切换了 nodeId 则先移除旧监听）
        val prevId = listeningNodeId
        if (prevId != null && prevId != node.id) {
            try {
                messageApi?.removeListener(prevId)
            } catch (_: Exception) {
            }
        }

        messageApi?.addListener(node.id, messageListener)
            ?.addOnSuccessListener {
                listeningNodeId = node.id
                Log.d(TAG, "成功注册手表消息监听: ${node.name}(${node.id})")
                val installedFlag = _state.value.watchAppInstalled
                _state.value = _state.value.copy(
                    connected = true,
                    nodeId = node.id,
                    nodeName = node.name,
                    statusText = if (installedFlag == false) {
                        "⌚ 已连接 ${node.name} · 待安装手表端 rpk"
                    } else {
                        "⌚ 已连接 ${node.name} · 蓝牙互通就绪"
                    }
                )

                // 立即推送一次最新取件码
                pushCodesAsync(notifyNewCount = 0)

                if (launchWatch) {
                    launchWatchAppAndPush(node)
                }
            }
            ?.addOnFailureListener { e ->
                Log.e(TAG, "注册手表消息监听失败", e)
                listeningNodeId = null
                _state.value = _state.value.copy(
                    connected = false,
                    nodeId = node.id,
                    nodeName = node.name,
                    statusText = formatError("建立手表通信监听失败", e)
                )
            }
    }

    private fun launchWatchAppAndPush(node: Node) {
        nodeApi?.launchWearApp(node.id, "pages/index")
            ?.addOnSuccessListener {
                Log.d(TAG, "已唤起手表端取件码助手")
                scope.launch {
                    delay(700)
                    pushCodesSuspend(node.id, notifyNewCount = 0)
                }
            }
            ?.addOnFailureListener { e ->
                Log.w(TAG, "唤起手表端应用失败: ${e.message}")
                _state.value = _state.value.copy(
                    statusText = formatError("唤起手表应用失败", e)
                )
            }
    }

    private fun handleWatchMessage(nodeId: String, raw: String) {
        scope.launch {
            val db = PickupDatabase.getInstance(appContext)
            val dao = db.pickupDao()
            try {
                val json = parseJsonCompat(raw)
                val type = json?.optString("type", "sync") ?: "sync"

                when (type) {
                    "scan" -> {
                        val added = SmsScanner(appContext, dao).scan(7)
                        val codes = dao.getUnpicked()
                        PickupNotify.update(appContext, codes, silent = added == 0)
                        sendCodesPayload(nodeId, codes, scanAdded = added)
                    }

                    "pick" -> {
                        val singleId = json?.optLong("id", -1L) ?: -1L
                        if (singleId > 0L) {
                            dao.markPicked(singleId)
                        }
                        val idsArr = json?.optJSONArray("ids")
                        if (idsArr != null) {
                            for (i in 0 until idsArr.length()) {
                                val id = idsArr.optLong(i, -1L)
                                if (id > 0L) dao.markPicked(id)
                            }
                        }
                        val codes = dao.getUnpicked()
                        PickupNotify.update(appContext, codes, silent = true)
                        sendCodesPayload(nodeId, codes, scanAdded = null)
                    }

                    else -> {
                        // 默认 "sync"
                        val codes = dao.getUnpicked()
                        sendCodesPayload(nodeId, codes, scanAdded = null)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "处理手表请求异常", e)
                val codes = dao.getUnpicked()
                sendCodesPayload(nodeId, codes, scanAdded = null)
            }
        }
    }

    /**
     * 兼容 Vela interconnect.send 传入对象或字符串时的 JSON 解析
     */
    private fun parseJsonCompat(raw: String): JSONObject? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return try {
            if (trimmed.startsWith("{")) {
                val outer = JSONObject(trimmed)
                // 部分固件可能包一层 {"data": ...}
                if (!outer.has("type") && outer.has("data")) {
                    val inner = outer.opt("data")
                    when (inner) {
                        is JSONObject -> inner
                        is String -> if (inner.trim().startsWith("{")) JSONObject(inner.trim()) else outer
                        else -> outer
                    }
                } else {
                    outer
                }
            } else if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                val unquoted = JSONArray("[$trimmed]").optString(0)
                if (unquoted.startsWith("{")) JSONObject(unquoted) else null
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 手机端主动推送当前待取列表至手表
     */
    fun pushCodesAsync(notifyNewCount: Int = 0) {
        if (!initialized) return
        scope.launch {
            val node = activeNode
            if (node == null) {
                refreshConnection(launchWatch = false)
                return@launch
            }
            pushCodesSuspend(node.id, notifyNewCount)
        }
    }

    private suspend fun pushCodesSuspend(nodeId: String, notifyNewCount: Int) {
        val codes = PickupDatabase.getInstance(appContext).pickupDao().getUnpicked()
        sendCodesPayload(nodeId, codes, scanAdded = null)

        if (notifyNewCount > 0 && notifyPermissionGranted && codes.isNotEmpty()) {
            val top = codes.first()
            val stationText = listOfNotNull(top.courier, top.station)
                .filter { it.isNotEmpty() }
                .joinToString("·")
                .ifEmpty { "快递" }
            val title = "取件码 ${top.code}"
            val msg = if (notifyNewCount > 1) {
                "$stationText（共待取 ${codes.size} 件）"
            } else {
                stationText
            }
            notifyApi?.sendNotify(nodeId, title, msg)
        }
    }

    private fun sendCodesPayload(nodeId: String, codes: List<PickupCode>, scanAdded: Int?) {
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
        val arr = JSONArray()
        for (c in codes.take(MAX_WATCH_ITEMS)) {
            val item = JSONObject()
            item.put("id", c.id)
            item.put("code", c.code)
            item.put("courier", c.courier ?: "")
            item.put("station", c.station ?: "")
            item.put("time", fmt.format(Date(c.eventTime)))
            arr.put(item)
        }
        val root = JSONObject()
        root.put("type", "codes")
        root.put("total", codes.size)
        root.put("ts", System.currentTimeMillis())
        if (scanAdded != null) {
            root.put("added", scanAdded)
        }
        root.put("list", arr)

        val payloadBytes = root.toString().toByteArray(Charsets.UTF_8)
        messageApi?.sendMessage(nodeId, payloadBytes)
            ?.addOnSuccessListener {
                Log.d(TAG, "已同步 ${codes.size} 条取件码到手表 (${payloadBytes.size} bytes)")
                val nodeName = activeNode?.name ?: "手表"
                _state.value = _state.value.copy(
                    connected = true,
                    watchAppInstalled = true,
                    lastSyncAt = System.currentTimeMillis(),
                    statusText = "⌚ 已同步至 $nodeName（待取 ${codes.size} 件）"
                )
            }
            ?.addOnFailureListener { e ->
                // 如果手表端 App 当前未打开，sendMessage 返回失败属于正常现象（手表打开后会主动发 sync 拉取）
                Log.d(TAG, "手表端暂未在前台接收实时推送: ${e.message}")
                if (e is SignatureVerifyFailedException) {
                    _state.value = _state.value.copy(
                        statusText = formatError("签名校验失败", e)
                    )
                } else if (e is AppNotInstalledException) {
                    _state.value = _state.value.copy(
                        watchAppInstalled = false,
                        statusText = "⌚ 手表端尚未安装「取件码助手.rpk」"
                    )
                }
            }
    }

    private fun formatError(prefix: String, e: Exception): String {
        return when (e) {
            is SignatureVerifyFailedException ->
                "⌚ 签名不一致：请卸载旧版后安装同一批次的 APK 与 RPK"
            is AppNotInstalledException ->
                "⌚ 手表尚未安装「取件码助手.rpk」"
            is PermissionDeniedException ->
                "⌚ 权限被拒绝：请在小米运动健康「设备授权管理」中开启权限"
            is DeviceDisconnectedException ->
                "⌚ 手表未连接蓝牙，请打开小米运动健康检查连接"
            else ->
                "⌚ $prefix：${e.message ?: "请确认小米运动健康正在运行"}"
        }
    }
}
