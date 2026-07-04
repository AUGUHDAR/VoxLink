package icu.wuhui.voxlink.app.shared.session

import icu.wuhui.voxlink.app.AppContext
import icu.wuhui.voxlink.network.P2PBridge
import icu.wuhui.voxlink.network.SignalingClient
import icu.wuhui.voxlink.network.TopologyClient
import icu.wuhui.voxlink.room.RoomManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// APP会话: 封装RoomManager + 连接状态
object AppSession {
    init { System.err.println("[AppSession] 静态初始化开始") }

    private val signalingClient: SignalingClient = AppContext.getSignalingClient()
        .also { System.err.println("[AppSession] signalingClient=$it") }

    private val topologyClient: TopologyClient = TopologyClient(signalingClient)
        .also { System.err.println("[AppSession] topologyClient=$it") }

    val roomManager: RoomManager = RoomManager(signalingClient, topologyClient)
        .also { System.err.println("[AppSession] roomManager=$it") }

    init { System.err.println("[AppSession] 静态初始化完成") }

    private val _connectionState = MutableStateFlow(ConnectionState.IDLE)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _roomCode = MutableStateFlow<String?>(null)
    val roomCode: StateFlow<String?> = _roomCode.asStateFlow()

    private val _localPort = MutableStateFlow(0)
    val localPort: StateFlow<Int> = _localPort.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    enum class ConnectionState { IDLE, CREATING, WAITING_PEER, JOINING, PUNCHING, CONNECTED, FAILED }

    fun reset() {
        _connectionState.value = ConnectionState.IDLE
        _roomCode.value = null
        _localPort.value = 0
        _error.value = null
    }

    fun onRoomCreated(code: String) {
        _roomCode.value = code
        _connectionState.value = ConnectionState.WAITING_PEER
    }

    fun onP2PConnecting() {
        _connectionState.value = ConnectionState.PUNCHING
    }

    fun onConnected(localPort: Int) {
        _localPort.value = localPort
        _connectionState.value = ConnectionState.CONNECTED
    }

    fun onFailed(error: String) {
        _error.value = error
        _connectionState.value = ConnectionState.FAILED
    }

    fun isConnected(): Boolean = _connectionState.value == ConnectionState.CONNECTED

    fun leave() {
        try {
            roomManager.leaveRoom()
        } catch (e: Exception) {
            println("[AppSession] 离开房间失败: ${e.message}")
        }
        P2PBridge.disconnect()
        reset()
    }
}
