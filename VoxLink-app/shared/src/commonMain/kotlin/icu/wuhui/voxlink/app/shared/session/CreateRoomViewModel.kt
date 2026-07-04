package icu.wuhui.voxlink.app.shared.session

import icu.wuhui.voxlink.app.AppContext
import icu.wuhui.voxlink.network.P2PBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit

// 创建房间ViewModel
class CreateRoomViewModel {
    private val _status = MutableStateFlow(CreateStatus.IDLE)
    val status: StateFlow<CreateStatus> = _status.asStateFlow()

    private val _roomCode = MutableStateFlow<String?>(null)
    val roomCode: StateFlow<String?> = _roomCode.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    enum class CreateStatus { IDLE, CREATING, WAITING, CONNECTED, FAILED }

    fun createRoom(roomName: String, mcPort: Int, password: String? = null) {
        if (_status.value == CreateStatus.CREATING) {
            AppContext.LOGGER.warn("[CreateRoom] 已在创建中, 忽略重复请求")
            return
        }
        _status.value = CreateStatus.CREATING
        _error.value = null
        AppContext.LOGGER.info("[CreateRoom] 开始创建, mcPort={}", mcPort)

        // 第1步: 启动主机桥, 5s超时
        P2PBridge.startHostBridge(mcPort)
            .orTimeout(5, TimeUnit.SECONDS)
            .whenComplete { bridgePort, ex ->
                if (ex != null) {
                    AppContext.LOGGER.error("[CreateRoom] 主机桥启动失败: {}", ex.message)
                    _error.value = "voxlink.error.bridge_start_failed"
                    _status.value = CreateStatus.FAILED
                    return@whenComplete
                }
                if (bridgePort == null || bridgePort <= 0) {
                    AppContext.LOGGER.error("[CreateRoom] 主机桥返回无效端口: {}", bridgePort)
                    _error.value = "voxlink.error.bridge_start_failed"
                    _status.value = CreateStatus.FAILED
                    return@whenComplete
                }
                AppContext.LOGGER.info("[CreateRoom] 主机桥启动, bridgePort={}", bridgePort)
                System.err.println("[CreateRoom] 主机桥启动, bridgePort=$bridgePort")

                // 第2步: 调信令创建房间, client_type=app
                System.err.println("[CreateRoom] 准备访问AppSession, 触发静态初始化")
                val future = try {
                    AppSession.roomManager.createRoom(
                        roomName, password, 20, bridgePort,
                        false, "OFFLINE", "other", "app"
                    )
                } catch (t: Throwable) {
                    System.err.println("[CreateRoom] AppSession初始化或createRoom同步抛异常: $t")
                    t.printStackTrace(System.err)
                    AppContext.LOGGER.error("[CreateRoom] AppSession初始化或createRoom同步抛异常: {}", t.toString(), t)
                    _error.value = "voxlink.error.create_failed"
                    _status.value = CreateStatus.FAILED
                    return@whenComplete
                }
                System.err.println("[CreateRoom] createRoom返回future=$future, 注册回调")

                future.whenComplete { roomInfo, ex2 ->
                    System.err.println("[CreateRoom] future完成, roomInfo=$roomInfo, ex=$ex2")
                    if (ex2 != null) {
                        System.err.println("[CreateRoom] 创建房间异常: $ex2")
                        ex2.printStackTrace(System.err)
                        AppContext.LOGGER.error("[CreateRoom] 创建房间异常: {}", ex2.toString(), ex2)
                        _error.value = "voxlink.error.create_failed"
                        _status.value = CreateStatus.FAILED
                        return@whenComplete
                    }
                    if (roomInfo == null) {
                        System.err.println("[CreateRoom] 创建房间返回null")
                        AppContext.LOGGER.error("[CreateRoom] 创建房间返回null")
                        _error.value = "voxlink.error.create_failed"
                        _status.value = CreateStatus.FAILED
                        return@whenComplete
                    }
                    System.err.println("[CreateRoom] 房间创建成功, code=${roomInfo.code}")
                    AppContext.LOGGER.info("[CreateRoom] 房间创建成功, code={}", roomInfo.code)
                    roomInfo.clientType = "app"
                    _roomCode.value = roomInfo.code
                    AppSession.onRoomCreated(roomInfo.code)
                    _status.value = CreateStatus.WAITING
                }
            }
    }

    fun closeRoom() {
        AppSession.leave()
        _status.value = CreateStatus.IDLE
        _roomCode.value = null
    }
}
