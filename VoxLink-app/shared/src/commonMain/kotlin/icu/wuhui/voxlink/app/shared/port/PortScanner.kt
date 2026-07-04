package icu.wuhui.voxlink.app.shared.port

// 端口扫描结果
data class PortScanResult(
    val newPorts: List<Int>,
    val allJavaPorts: Set<Int>
) {
    val hasNew: Boolean get() = newPorts.isNotEmpty()
    val uniqueNew: Int? get() = if (newPorts.size == 1) newPorts[0] else null
    val multipleNew: Boolean get() = newPorts.size > 1
}

// 端口扫描器接口，平台相关实现
interface PortScanner {
    // 扫描本机所有 java 进程的 LISTENING 端口
    fun scanJavaListeningPorts(): Set<Int>

    // 基线扫描 + diff：对比基线，返回新增端口
    // 重试5次取并集, MC点"对局域网开放"后端口可能要2-3秒才被netstat看到
    fun diffNewPorts(baseline: Set<Int>): PortScanResult {
        val allNew = mutableSetOf<Int>()
        var lastAll: Set<Int> = emptySet()
        for (attempt in 0 until 5) {
            val current = scanJavaListeningPorts()
            lastAll = current
            current.subtract(baseline)
                .filter { it in 49152..65535 || it == 25565 }
                .forEach { allNew.add(it) }
            // 第1次就找到可以早退, 后续重试只在没找到时才等
            if (allNew.isNotEmpty() && attempt > 0) break
            if (attempt < 4) Thread.sleep(800)
        }
        return PortScanResult(allNew.sorted(), lastAll)
    }
}
