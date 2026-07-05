package icu.wuhui.voxlink.app.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import icu.wuhui.voxlink.app.desktop.ipc.IpcServer
import icu.wuhui.voxlink.app.desktop.log.LogInitializer
import icu.wuhui.voxlink.app.desktop.port.WindowsPortScanner
import icu.wuhui.voxlink.app.shared.ui.AppScreen
import icu.wuhui.voxlink.app.shared.ui.Strings
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream

fun main(args: Array<String>) {
    // 第一行: 初始化日志路径
    val logFile = LogInitializer.init()

    // 双保险: 设slf4j属性 + 重定向System.err
    // 1) slf4j-simple懒加载, 第一次getLogger时读logFile属性, 直接写文件
    System.setProperty("org.slf4j.simpleLogger.logFile", logFile)
    System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "info")
    System.setProperty("org.slf4j.simpleLogger.showDateTime", "true")
    System.setProperty("org.slf4j.simpleLogger.dateTimeFormat", "HH:mm:ss.SSS")
    System.setProperty("org.slf4j.simpleLogger.showThreadName", "true")
    System.setProperty("org.slf4j.simpleLogger.showShortLogName", "true")
    System.setProperty("org.slf4j.simpleLogger.levelInBrackets", "true")

    // 2) 万一slf4j已经初始化(类加载时序), 用的默认System.err, 这里先重定向
    //    slf4j后续init时取System.err拿到TeeStream, 也能进文件
    redirectStdErr(logFile)

    System.err.println("[Main] VoxLink-app 启动, 日志: $logFile, args: ${args.toList()}")

    // ---- 无头 IPC 模式: 供 PCL 等外部宿主以子进程方式驱动联机 ----
    // 触发: 传入 --headless (或 --ipc)
    // 握手: stdout 第一行输出 "VOXLINK_IPC_PORT:<port>", 宿主读到后连该端口走 JSON Lines 协议。
    // 退出: 收到 shutdown 指令, 或 stdin 关闭(宿主退出)。
    if (args.any { it == "--headless" || it == "--ipc" }) {
        runHeadless(args)
        return
    }

    // ---- 默认: Compose Desktop GUI 模式 ----
    application {
        val portScanner = remember { WindowsPortScanner() }
        val icon = painterResource("voxlink-icon.png")
        Window(
            onCloseRequest = ::exitApplication,
            title = Strings["app_title"],
            icon = icon,
            state = rememberWindowState(width = 480.dp, height = 640.dp)
        ) {
            AppScreen(portScanner = portScanner)
        }
    }
}

/**
 * 无头 IPC 后台模式: 不启动任何 GUI, 只起本地 IPC server 供宿主(PCL)驱动。
 *
 * 命令行参数:
 *   --server <url>     覆盖信令服务器地址 (默认 https://p2p.wuhui.icu)
 *   --log <path>       覆盖日志文件路径
 */
private fun runHeadless(args: Array<String>) {
    System.err.println("[Headless] 进入无头 IPC 模式")

    // 解析 --server 参数
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--server" -> {
                if (i + 1 < args.size) {
                    val url = args[i + 1]
                    try {
                        icu.wuhui.voxlink.app.AppContext.getConfig().setServerUrl(url)
                        System.err.println("[Headless] 信令服务器: $url")
                    } catch (e: Exception) {
                        System.err.println("[Headless] 设置服务器地址失败: ${e.message}")
                    }
                    i += 2; continue
                }
            }
        }
        i++
    }

    // 启动 IPC server
    val ipc = IpcServer()
    val port = ipc.start()

    // 握手: stdout 第一行输出端口 (宿主靠这一行建立连接)
    // 注意必须用 System.out.println 且立即 flush, 不能被 stderr 混入
    println("VOXLINK_IPC_PORT:$port")
    System.out.flush()

    System.err.println("[Headless] IPC 端口已上报: $port, 等待宿主连接...")

    // 监听 stdin EOF: 宿主退出时 stdin 关闭, 我们也优雅退出 (容错, 防僵尸进程)
    val stdinWatcher = Thread({
        try {
            // 阻塞读 stdin; 当宿主关闭管道时 readLine 返回 null
            while (System.`in`.read() != -1) { /* 丢弃输入 */ }
        } catch (_: Exception) {}
        System.err.println("[Headless] stdin 已关闭(宿主退出), 准备退出")
        ipc.stop()
        Thread { try { Thread.sleep(150) } catch (_: Exception) {}; kotlin.system.exitProcess(0) }.start()
    }, "VoxLink-StdinWatcher").apply { isDaemon = true; start() }

    // 主线程阻塞: 等 IPC server 自然结束 (shutdown 指令会调 stop)
    // 用 Object.wait 避免空转; shutdown 路径里 IpcServer.runtimeExit 会直接 exitProcess
    try {
        synchronized(ipc) {
            while (true) {
                (ipc as java.lang.Object).wait(60_000)
            }
        }
    } catch (_: InterruptedException) {}
}

// 重定向stderr到文件+console双输出, autoFlush=true保证每行实时落盘
private fun redirectStdErr(logFilePath: String) {
    try {
        val fileStream = FileOutputStream(logFilePath, true)
        val consoleStream = System.err
        val tee = TeeOutputStream(consoleStream, fileStream)
        System.setErr(PrintStream(tee, true, "UTF-8"))
    } catch (e: Exception) {
        // 重定向失败继续启动, 至少console有输出
    }
}

// 双输出流: console + 文件
private class TeeOutputStream(private val a: OutputStream, private val b: OutputStream) : OutputStream() {
    override fun write(v: Int) {
        try { a.write(v) } catch (e: Exception) {}
        try { b.write(v) } catch (e: Exception) {}
    }
    override fun write(buf: ByteArray, off: Int, len: Int) {
        try { a.write(buf, off, len) } catch (e: Exception) {}
        try { b.write(buf, off, len) } catch (e: Exception) {}
    }
    override fun flush() {
        try { a.flush() } catch (e: Exception) {}
        try { b.flush() } catch (e: Exception) {}
    }
    override fun close() {
        try { a.flush() } catch (e: Exception) {}
        try { b.flush() } catch (e: Exception) {}
    }
}
