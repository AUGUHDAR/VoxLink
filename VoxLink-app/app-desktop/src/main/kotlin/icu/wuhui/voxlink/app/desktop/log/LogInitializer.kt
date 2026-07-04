package icu.wuhui.voxlink.app.desktop.log

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

// 日志管理: 安装目录/logs/, latest.log为当前, 历史按时间戳归档, 同MC习惯
object LogInitializer {

    // 定位安装目录: 优先用jpackage.app-path(指向exe), 回退到java.command, 再回退user.dir
    fun getLogDir(): File {
        val installRoot = findInstallRoot()
        val logDir = File(installRoot, "logs")
        if (!logDir.exists()) logDir.mkdirs()
        return logDir
    }

    // jpackage app-image结构: VoxLink/VoxLink.exe, VoxLink/runtime/bin/java.exe
    // 优先用jpackage.app-path(exe路径), 回退到java.command(java.exe路径向上找)
    private fun findInstallRoot(): File {
        // 1) jpackage.app-path 指向 VoxLink.exe
        val appPath = System.getProperty("jpackage.app-path")
        if (appPath != null) {
            val exe = File(appPath)
            if (exe.isFile) return exe.parentFile
        }
        // 2) java.class.path 指向 jar, jar在 app/ 目录, 向上找
        val classPath = System.getProperty("java.class.path")
        if (classPath != null && classPath.isNotEmpty()) {
            val jar = File(classPath.split(File.pathSeparator)[0])
            if (jar.isFile) {
                // jar在 installRoot/app/, 向上2级
                val appDir = jar.parentFile
                if (appDir != null && appDir.name.equals("app", ignoreCase = true)) {
                    return appDir.parentFile
                }
            }
        }
        // 3) 回退: user.dir, 如果是bin则向上1级
        val appDir = File(System.getProperty("user.dir"))
        return if (appDir.name.equals("bin", ignoreCase = true)) appDir.parentFile else appDir
    }

    // 启动时: 旧latest.log归档为历史日志, 返回新latest.log路径
    fun init(): String {
        val logDir = getLogDir()
        val latest = File(logDir, "latest.log")
        // 旧latest.log存在且有内容则归档
        if (latest.exists() && latest.length() > 0) {
            val timestamp = SimpleDateFormat("yyyy-MM-dd-HHmmss").format(Date(latest.lastModified()))
            val archived = File(logDir, "$timestamp.log")
            if (!archived.exists()) {
                latest.renameTo(archived)
            }
        }
        // 清理超过30天的历史日志
        val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        logDir.listFiles { f -> f.name.endsWith(".log") && f.name != "latest.log" }
            ?.filter { it.lastModified() < cutoff }
            ?.forEach { it.delete() }
        return latest.absolutePath
    }
}
