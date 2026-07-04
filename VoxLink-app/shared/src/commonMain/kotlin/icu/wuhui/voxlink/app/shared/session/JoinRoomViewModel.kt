package icu.wuhui.voxlink.app.shared.session

import icu.wuhui.voxlink.network.P2PBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// 加入房间ViewModel
class JoinRoomViewModel {
    private val scope = CoroutineScope(Dispatchers.Default)

    private val _status = MutableStateFlow(JoinStatus.IDLE)
    val status: StateFlow<JoinStatus> = _status.asStateFlow()

    private val _localPort = MutableStateFlow(0)
    val localPort: StateFlow<Int> = _localPort.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    enum class JoinStatus { IDLE, JOINING, PUNCHING, CONNECTED, FAILED }

    fun joinRoom(code: String, password: String? = null) {
        _status.value = JoinStatus.JOINING
        _error.value = null
        scope.launch {
            try {
                val roomInfo = AppSession.roomManager.joinRoom(code, password).get()
                if (roomInfo == null) {
                    _error.value = "voxlink.error.room_not_found"
                    _status.value = JoinStatus.FAILED
                    return@launch
                }

                println("[JoinRoom] 加入房间: $code")
                _status.value = JoinStatus.PUNCHING
                AppSession.onP2PConnecting()

                // 等待P2P连接建立(ConnectionManager内部触发打洞+P2PBridge)
                // P2PBridge连接成功后getJoinerPort返回本地端口
                waitForConnection()
            } catch (e: Exception) {
                println("[JoinRoom] 加入失败: ${e.message}")
                _error.value = e.message ?: "voxlink.error.join_failed"
                _status.value = JoinStatus.FAILED
            }
        }
    }

    // 轮询等待P2P连接建立
    private fun waitForConnection() {
        scope.launch {
            val startTime = System.currentTimeMillis()
            val timeout = 90_000L // 90s超时

            while (System.currentTimeMillis() - startTime < timeout) {
                if (P2PBridge.isRunning()) {
                    val port = P2PBridge.getJoinerPort()
                    if (port > 0) {
                        _localPort.value = port
                        AppSession.onConnected(port)
                        _status.value = JoinStatus.CONNECTED
                        return@launch
                    }
                }
                kotlinx.coroutines.delay(500)
            }

            _error.value = "voxlink.error.connection_timeout"
            _status.value = JoinStatus.FAILED
            AppSession.onFailed("voxlink.error.connection_timeout")
        }
    }

    fun disconnect() {
        AppSession.leave()
        _status.value = JoinStatus.IDLE
        _localPort.value = 0
    }
}
