package icu.wuhui.voxlink.app.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import icu.wuhui.voxlink.app.desktop.log.LogInitializer
import icu.wuhui.voxlink.app.desktop.port.WindowsPortScanner
import icu.wuhui.voxlink.app.shared.ui.AppScreen
import icu.wuhui.voxlink.app.shared.ui.Strings
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream

fun main() {
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

    System.err.println("[Main] VoxLink-app 启动, 日志: $logFile")

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
