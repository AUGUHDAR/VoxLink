package icu.wuhui.voxlink.app.desktop.ipc

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import icu.wuhui.voxlink.app.AppContext
import icu.wuhui.voxlink.app.shared.session.AppSession
import icu.wuhui.voxlink.network.P2PBridge
import icu.wuhui.voxlink.room.RoomInfo
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * VoxLink <-> PCL 本地 IPC 桥。
 *
 * 协议: JSON Lines (每行一个 JSON 对象, UTF-8)。
 *  - 请求  (PCL -> Core):  {"id":1,"method":"createRoom","params":{...}}
 *  - 响应  (Core -> PCL):  {"id":1,"ok":true,"result":{...}}  /  {"id":1,"ok":false,"error":"..."}
 *  - 事件  (Core -> PCL):  {"event":"stateChange","data":{...}}   (无 id)
 *
 * 握手: 进程启动后, 在 stdout 第一行输出 "VOXLINK_IPC_PORT:<port>", PCL 读到后连该端口。
 * 只接受一个客户端 (PCL); 旧连接断开时可接受重连。
 *
 * 容错: 线程池 + per-request 超时; 客户端断开不杀进程; leave/shutdown 幂等。
 */
class IpcServer {

    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "VoxLink-IPC").apply { isDaemon = true }
    }

    @Volatile private var running = false
    @Volatile private var server: ServerSocket? = null

    // 当前唯一的 PCL 客户端连接
    private val clientRef = AtomicReference<ClientConn?>(null)

    // 自增请求 id (用于 Core 主动发起的场景, 这里基本用不到, 保留)
    private val nextId = AtomicInteger(0)

    private data class ClientConn(val socket: Socket, val writer: PrintWriter)

    /**
     * 启动 IPC 监听, 返回绑定的本地端口。
     */
    fun start(): Int {
        val srv = ServerSocket()
        srv.bind(InetSocketAddress("127.0.0.1", 0))
        srv.soTimeout = 0
        server = srv
        running = true
        val port = srv.localPort
        executor.execute { acceptLoop(srv) }
        AppContext.LOGGER.info("[IPC] 监听 127.0.0.1:{}", port)
        return port
    }

    private fun acceptLoop(srv: ServerSocket) {
        while (running) {
            try {
                val socket = srv.accept()
                socket.tcpNoDelay = true
                // 关掉旧连接, 单客户端模型
                clientRef.getAndSet(ClientConn(socket,
                    PrintWriter(OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true)
                ))?.let { old -> try { old.socket.close() } catch (_: Exception) {} }
                AppContext.LOGGER.info("[IPC] PCL 已连接: {}", socket.remoteSocketAddress)
                executor.execute { readLoop(socket) }
            } catch (e: Exception) {
                if (running) AppContext.LOGGER.warn("[IPC] accept 异常: {}", e.message)
            }
        }
    }

    private fun readLoop(socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), "UTF-8"))
            while (running && !socket.isClosed) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                try {
                    val req = JsonParser.parseString(line).asJsonObject
                    executor.execute { handleRequest(req) }
                } catch (e: Exception) {
                    AppContext.LOGGER.warn("[IPC] 解析请求失败: {} -> {}", line, e.message)
                }
            }
        } catch (e: Exception) {
            if (running) AppContext.LOGGER.info("[IPC] 客户端断开: {}", e.message)
        } finally {
            // 清理当前连接引用 (如果是自己)
            val cur = clientRef.get()
            if (cur?.socket === socket) clientRef.compareAndSet(cur, null)
            try { socket.close() } catch (_: Exception) {}
            AppContext.LOGGER.info("[IPC] 读循环结束")
        }
    }

    // ---------------- 请求分发 ----------------

    private fun handleRequest(req: JsonObject) {
        val id = if (req.has("id") && !req.get("id").isJsonNull) req.get("id").asInt else -1
        val method = if (req.has("method")) req.get("method").asString else ""
        val params = if (req.has("params") && req.get("params").isJsonObject) req.getAsJsonObject("params") else JsonObject()
        when (method) {
            "createRoom" -> createRoom(id, params)
            "joinRoom" -> joinRoom(id, params)
            "getStatus" -> getStatus(id)
            "leave" -> leave(id)
            "shutdown" -> shutdown(id)
            else -> sendError(id, "unknown_method: $method")
        }
    }

    // ---------------- 方法实现 ----------------

    private fun createRoom(id: Int, params: JsonObject) {
        val name = optString(params, "name", "PCL联机房间")
        val mcPort = optInt(params, "mcPort", 25565)
        val password = optStringNull(params, "password")

        // 复用 shared 的 ViewModel 逻辑, 但这里直接调 AppSession.roomManager 以拿到完整 future
        AppContext.LOGGER.info("[IPC] createRoom name={} mcPort={} password={}", name, mcPort, if (password == null) "无" else "有")
        // 1. 启动主机桥 (5s 超时)
        P2PBridge.startHostBridge(mcPort).orTimeout(5, TimeUnit.SECONDS).whenComplete { bridgePort, ex ->
            if (ex != null || bridgePort == null || bridgePort <= 0) {
                AppContext.LOGGER.error("[IPC] 主机桥启动失败: {}", ex?.message ?: "port=$bridgePort")
                sendError(id, "voxlink.error.bridge_start_failed")
                return@whenComplete
            }
            // 2. 调信令建房, client_type=app
            try {
                AppSession.roomManager.createRoom(name, password, 20, bridgePort, false, "OFFLINE", "other", "app")
                    .whenComplete { roomInfo, ex2 ->
                        if (ex2 != null) {
                            AppContext.LOGGER.error("[IPC] createRoom 失败: {}", ex2.message)
                            sendError(id, ex2.message ?: "voxlink.error.create_failed")
                            return@whenComplete
                        }
                        if (roomInfo == null) {
                            sendError(id, "voxlink.error.create_failed")
                            return@whenComplete
                        }
                        AppContext.LOGGER.info("[IPC] createRoom 成功 code={}", roomInfo.code)
                        val result = JsonObject().apply {
                            addProperty("roomCode", roomInfo.code)
                            addProperty("bridgePort", bridgePort)
                            addProperty("isHost", true)
                        }
                        sendResult(id, result)
                        pushState("created", roomInfo.code, true, 0)
                    }
            } catch (t: Throwable) {
                AppContext.LOGGER.error("[IPC] createRoom 同步异常: {}", t.message)
                sendError(id, "voxlink.error.create_failed")
            }
        }
    }

    private fun joinRoom(id: Int, params: JsonObject) {
        val code = optString(params, "code", "").trim().uppercase()
        val password = optStringNull(params, "password")
        if (!code.matches(Regex("^[A-HJ-NP-Z2-9]{6}$"))) {
            sendError(id, "voxlink.error.invalid_room_code")
            return
        }
        AppContext.LOGGER.info("[IPC] joinRoom code={}", code)
        try {
            AppSession.roomManager.joinRoom(code, password).whenComplete { roomInfo, ex ->
                if (ex != null) {
                    AppContext.LOGGER.error("[IPC] joinRoom 失败: {}", ex.message)
                    sendError(id, ex.message ?: "voxlink.error.join_failed")
                    return@whenComplete
                }
                if (roomInfo == null) {
                    sendError(id, "voxlink.error.room_not_found")
                    return@whenComplete
                }
                // 加入信令成功, 但还要等 P2P 桥 + 打洞完成 (localPort 可用)
                pushState("punching", code, false, 0)
                waitForLocalPort(id, code, roomInfo)
            }
        } catch (t: Throwable) {
            sendError(id, t.message ?: "voxlink.error.join_failed")
        }
    }

    /**
     * 轮询 P2PBridge.getJoinerPort(), >0 表示打洞+桥接完成, MC 可连该本地端口。
     * 超时 90s。
     */
    private fun waitForLocalPort(id: Int, code: String, roomInfo: RoomInfo) {
        executor.execute {
            val start = System.currentTimeMillis()
            val timeout = 90_000L
            while (System.currentTimeMillis() - start < timeout) {
                if (P2PBridge.isRunning()) {
                    val port = P2PBridge.getJoinerPort()
                    if (port > 0) {
                        AppContext.LOGGER.info("[IPC] joinRoom 完成 localPort={}", port)
                        val result = JsonObject().apply {
                            addProperty("localPort", port)
                            addProperty("roomCode", code)
                            addProperty("isHost", false)
                        }
                        sendResult(id, result)
                        pushState("connected", code, false, port)
                        return@execute
                    }
                }
                try { Thread.sleep(500) } catch (_: InterruptedException) { break }
            }
            AppContext.LOGGER.error("[IPC] joinRoom 等待本地端口超时")
            sendError(id, "voxlink.error.connection_timeout")
        }
    }

    private fun getStatus(id: Int) {
        val state = AppSession.connectionState.value.name
        val roomCode = AppSession.roomCode.value
        val localPort = AppSession.localPort.value
        val roomInfo = AppSession.roomManager.getCurrentRoom()
        val isHost = roomInfo?.isHost ?: false
        val playerCount = roomInfo?.currentPlayers ?: 0
        val result = JsonObject().apply {
            addProperty("state", state)
            if (roomCode != null) addProperty("roomCode", roomCode)
            addProperty("isHost", isHost)
            addProperty("localPort", localPort)
            addProperty("playerCount", playerCount)
            addProperty("inRoom", AppSession.roomManager.isInRoom())
        }
        sendResult(id, result)
    }

    private fun leave(id: Int) {
        AppContext.LOGGER.info("[IPC] leave")
        try {
            AppSession.leave()
        } catch (e: Exception) {
            AppContext.LOGGER.warn("[IPC] leave 异常: {}", e.message)
        }
        sendResult(id, JsonObject())
        pushState("idle", null, false, 0)
    }

    private fun shutdown(id: Int) {
        AppContext.LOGGER.info("[IPC] shutdown 请求, 优雅退出")
        sendResult(id, JsonObject())
        Thread {
            try { Thread.sleep(200) } catch (_: Exception) {}
            stop()
            // 留时间给响应写出
            try { Thread.sleep(100) } catch (_: Exception) {}
            runtimeExit(0)
        }.start()
    }

    // ---------------- 事件推送 ----------------

    private fun pushState(state: String, roomCode: String?, isHost: Boolean, localPort: Int) {
        val data = JsonObject().apply {
            addProperty("state", state)
            if (roomCode != null) addProperty("roomCode", roomCode)
            addProperty("isHost", isHost)
            addProperty("localPort", localPort)
        }
        sendEvent("stateChange", data)
    }

    // ---------------- 底层发送 ----------------

    private fun sendResult(id: Int, result: JsonObject) {
        val obj = JsonObject().apply {
            addProperty("id", id)
            addProperty("ok", true)
            add("result", result)
        }
        send(obj)
    }

    private fun sendError(id: Int, error: String) {
        val obj = JsonObject().apply {
            addProperty("id", id)
            addProperty("ok", false)
            addProperty("error", error)
        }
        send(obj)
    }

    private fun sendEvent(event: String, data: JsonObject) {
        val obj = JsonObject().apply {
            addProperty("event", event)
            add("data", data)
        }
        send(obj)
    }

    private fun send(obj: JsonObject) {
        val conn = clientRef.get() ?: return
        try {
            conn.writer.println(obj.toString())
            conn.writer.flush()
        } catch (e: Exception) {
            AppContext.LOGGER.warn("[IPC] 发送失败: {}", e.message)
        }
    }

    // ---------------- 工具 ----------------

    private fun optString(obj: JsonObject, key: String, default: String): String =
        if (obj.has(key) && !obj.get(key).isJsonNull) obj.get(key).asString else default

    private fun optStringNull(obj: JsonObject, key: String): String? =
        if (obj.has(key) && !obj.get(key).isJsonNull && obj.get(key).asString.isNotEmpty()) obj.get(key).asString else null

    private fun optInt(obj: JsonObject, key: String, default: Int): Int =
        if (obj.has(key) && !obj.get(key).isJsonNull) obj.get(key).asInt else default

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        clientRef.getAndSet(null)?.let { try { it.socket.close() } catch (_: Exception) {} }
        try { executor.shutdownNow() } catch (_: Exception) {}
    }

    /** 可被外部替换的退出钩子 (默认 System.exit) */
    var runtimeExit: (Int) -> Unit = { code -> kotlin.system.exitProcess(code) }
}
