package icu.wuhui.voxlink.app.shared.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

// UI字符串集中管理, 全部通过key取值, 不硬编码
object Strings {
    private val zh: Map<String, String> = mapOf(
        "app_title" to "VoxLink",
        "create_room" to "创建房间",
        "join_room" to "加入房间",
        "back" to "返回",
        "create_room_step1" to "在 MC 中点'对局域网开放'，然后点下方按钮",
        "scan_ports" to "我已开放局域网",
        "create_room_step2" to "选择 MC 开放的端口",
        "manual_input_port" to "手动输入端口",
        "manual_input_hint" to "从 MC 聊天框 '本地游戏已在端口 XXXXX 上开启' 抄写",
        "create" to "创建",
        "creating" to "创建中...",
        "room_created" to "房间创建成功 :D awa",
        "room_code_label" to "房间码",
        "share_room_code" to "把房间码发给对方",
        "waiting_peer" to "等待对方加入...",
        "copy_room_code" to "复制房间码",
        "close_room" to "关闭房间",
        "enter_room_code" to "输入房间码",
        "connect" to "连接",
        "connecting" to "尝试连接中...",
        "join_failed" to "加入失败 qwq",
        "create_failed" to "创建失败 qwq",
        "connection_success" to "连接成功 awa",
        "mc_direct_connect" to "在 MC 中：多人游戏 → 直接连接",
        "enter_address" to "输入地址",
        "copy_address" to "复制地址",
        "close" to "关闭",
        "port_occupied" to "端口被占用，尝试下一个",
        "scan_failed" to "端口扫描失败，请手动输入",
        // 补充key
        "detected_port" to "检测到 MC 端口",
        "select_port" to "选择端口",
        "port_label" to "端口号",
        "retry" to "重试",
        "copied" to "已复制",
        "address_label" to "地址",
        // 后端错误key映射
        "voxlink.error.bridge_start_failed" to "主机桥启动失败",
        "voxlink.error.create_failed" to "创建房间失败",
        "voxlink.error.room_not_found" to "房间不存在",
        "voxlink.error.join_failed" to "加入房间失败",
        "voxlink.error.connection_timeout" to "连接超时",
        // 语言选择器, 语言名固定用本语言写法
        "language_zh" to "中文",
        "language_en" to "English",
        // 关于页面
        "about" to "关于此项目",
        "about_title" to "VoxLink",
        "about_desc" to "VoxLink 是一个 MC 多人联机 mod，APP 端作为过渡方案，通过 P2P 隧道让 MC 自带局域网联机可用，无需安装 mod。",
        "about_author_bilibili" to "作者 B站",
        "about_website" to "官方网站",
        "about_mcmod" to "MC百科",
        "about_modrinth" to "Modrinth",
        "about_github" to "GitHub",
        "about_curseforge" to "CurseForge",
        "about_open_failed" to "打开浏览器失败 qwq"
    )

    // 英文语言表, key 与 zh 一一对应, 玩家文本保持 :D awa / qwq 风格
    private val en: Map<String, String> = mapOf(
        "app_title" to "VoxLink",
        "create_room" to "Create Room",
        "join_room" to "Join Room",
        "back" to "Back",
        "create_room_step1" to "In MC, click 'Open to LAN', then click the button below",
        "scan_ports" to "I've opened LAN",
        "create_room_step2" to "Select the port MC opened",
        "manual_input_port" to "Enter port manually",
        "manual_input_hint" to "Copy from MC chat 'Local game hosted on port XXXXX'",
        "create" to "Create",
        "creating" to "Creating...",
        "room_created" to "Room created :D awa",
        "room_code_label" to "Room Code",
        "share_room_code" to "Share the room code with your friend",
        "waiting_peer" to "Waiting for peer to join...",
        "copy_room_code" to "Copy Room Code",
        "close_room" to "Close Room",
        "enter_room_code" to "Enter Room Code",
        "connect" to "Connect",
        "connecting" to "Connecting...",
        "join_failed" to "Join failed qwq",
        "create_failed" to "Create failed qwq",
        "connection_success" to "Connected awa",
        "mc_direct_connect" to "In MC: Multiplayer -> Direct Connect",
        "enter_address" to "Enter address",
        "copy_address" to "Copy Address",
        "close" to "Close",
        "port_occupied" to "Port in use, trying next",
        "scan_failed" to "Port scan failed, please enter manually",
        // 补充key
        "detected_port" to "Detected MC port",
        "select_port" to "Select Port",
        "port_label" to "Port",
        "retry" to "Retry",
        "copied" to "Copied",
        "address_label" to "Address",
        // 后端错误key映射
        "voxlink.error.bridge_start_failed" to "Host bridge failed to start",
        "voxlink.error.create_failed" to "Failed to create room",
        "voxlink.error.room_not_found" to "Room not found",
        "voxlink.error.join_failed" to "Failed to join room",
        "voxlink.error.connection_timeout" to "Connection timed out",
        // 语言选择器, 语言名固定用本语言写法
        "language_zh" to "中文",
        "language_en" to "English",
        // 关于页面
        "about" to "About",
        "about_title" to "VoxLink",
        "about_desc" to "VoxLink is a MC multiplayer mod. The APP acts as a transitional solution, using P2P tunnels to enable MC's built-in LAN multiplayer without installing the mod.",
        "about_author_bilibili" to "Author Bilibili",
        "about_website" to "Website",
        "about_mcmod" to "MC Mod Wiki",
        "about_modrinth" to "Modrinth",
        "about_github" to "GitHub",
        "about_curseforge" to "CurseForge",
        "about_open_failed" to "Failed to open browser qwq"
    )

    // 支持的语言表
    private val languages: Map<String, Map<String, String>> = mapOf("zh" to zh, "en" to en)

    // 当前语言, 默认中文, mutableStateOf 让 Compose 响应切换
    var currentLanguage: String by mutableStateOf("zh")
        private set

    // 切换语言, 仅支持 zh / en, 相同语言不重复触发
    fun setLanguage(lang: String) {
        if (lang != currentLanguage && languages.containsKey(lang)) {
            currentLanguage = lang
        }
    }

    // 取值, 引用 currentLanguage 触发 Compose 重组, 缺失则原样返回 key
    operator fun get(key: String): String {
        val lang = currentLanguage
        return languages[lang]?.get(key) ?: key
    }
}
