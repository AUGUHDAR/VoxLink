package icu.wuhui.voxlink.app.desktop.port

import icu.wuhui.voxlink.app.shared.port.PortScanner
import java.io.BufferedReader
import java.io.InputStreamReader

// Windows端口扫描: netstat -ano 解析LISTENING端口，tasklist过滤java进程
class WindowsPortScanner : PortScanner {

    override fun scanJavaListeningPorts(): Set<Int> {
        val javaPids = getJavaPids()
        if (javaPids.isEmpty()) return emptySet()

        val ports = mutableSetOf<Int>()
        val process = ProcessBuilder("netstat", "-ano").redirectErrorStream(true).start()
        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line!!.trim()
                // 只看LISTENING行: "TCP 0.0.0.0:49152 0.0.0.0:0 LISTENING 12345"
                if (!l.contains("LISTENING")) continue
                val parts = l.split("\\s+".toRegex())
                if (parts.size < 5) continue
                // parts[1] = "0.0.0.0:49152" 或 "[::]:49152"
                val localAddr = parts[1]
                val port = extractPort(localAddr) ?: continue
                val pid = parts[4].toIntOrNull() ?: continue
                if (pid in javaPids) {
                    ports.add(port)
                }
            }
        }
        process.waitFor()
        return ports
    }

    // 从 "0.0.0.0:49152" 或 "[::]:49152" 提取端口
    private fun extractPort(addr: String): Int? {
        val idx = addr.lastIndexOf(':')
        if (idx < 0) return null
        return addr.substring(idx + 1).toIntOrNull()
    }

    // 获取所有java/javaw进程的PID, MC启动器常用javaw.exe
    // 大小写不敏感匹配, tasklist输出格式: "javaw.exe","12345","Console","1","..."
    private fun getJavaPids(): Set<Int> {
        val pids = mutableSetOf<Int>()
        val process = ProcessBuilder("tasklist", "/FO", "CSV", "/NH")
            .redirectErrorStream(true).start()
        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val trimmed = line!!.trim()
                // 跳过非java进程, 大小写不敏感
                val lower = trimmed.lowercase()
                if (!lower.startsWith("\"java")) continue
                if (!lower.contains(".exe")) continue
                // 按CSV分割: "javaw.exe","12345",...
                val parts = trimmed.split("\",\"")
                if (parts.size >= 2) {
                    val pid = parts[1].replace("\"", "").toIntOrNull()
                    if (pid != null) pids.add(pid)
                }
            }
        }
        process.waitFor()
        return pids
    }
}
