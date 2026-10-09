package com.framemirror.android.utils

import java.io.File

/**
 * 历史媒体缓存与临时文件清理工具类。
 * 针对历史版本残留文件以及公共目录临时配置进行扫描与一键清理。
 */
object ResidualCleaner {

    data class ScanResult(
        val path: String,
        val description: String,
        val exists: Boolean,
        val isDirectory: Boolean = false
    )

    // 历史临时路径列表
    private val KNOWN_RESIDUAL_PATHS = listOf(
        "/sdcard/DCIM/Camera1" to "FrameMirror 默认配置及媒体目录",
        "/sdcard/DCIM/Camera1/cs_config.json" to "FrameMirror 主配置文件",
        "/sdcard/DCIM/Camera1/virtual.mp4" to "FrameMirror 默认测试视频",
        "/sdcard/DCIM/Camera/virtual.mp4" to "传统测试视频文件",
        "/sdcard/DCIM/Camera/disable.jpg" to "禁用标记文件",
        "/sdcard/DCIM/Camera/no-silent.jpg" to "静音标记文件",
        "/data/local/tmp/libcs_camserver.so" to "CameraServer 模块缓存",
        "/data/local/tmp/cs-injector" to "CameraServer 辅助程序文件",
        "/data/local/tmp/cs_cam_shm" to "FrameMirror 共享内存临时句柄"
    )

    /**
     * 扫描系统中的临时缓存路径（单次批量检测）
     */
    fun scanResiduals(useRoot: Boolean = false): List<ScanResult> {
        val results = mutableListOf<ScanResult>()

        // 1. 先通过 Java 本地直接探测可读路径
        val unverifiedPaths = mutableListOf<Pair<String, String>>()
        for ((path, desc) in KNOWN_RESIDUAL_PATHS) {
            try {
                val file = File(path)
                if (file.exists()) {
                    results.add(ScanResult(path, desc, exists = true, isDirectory = file.isDirectory))
                } else if (useRoot) {
                    unverifiedPaths.add(path to desc)
                }
            } catch (e: Throwable) {
                if (useRoot) unverifiedPaths.add(path to desc)
            }
        }

        // 2. 如果开启了系统提权检查且有未确认路径，仅启动单次批量检查
        if (useRoot && unverifiedPaths.isNotEmpty()) {
            try {
                val checkScript = unverifiedPaths.joinToString("\n") { (path, _) ->
                    "if [ -e '$path' ]; then echo '1:$path'; else echo '0:$path'; fi"
                }
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", checkScript))
                process.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (trimmed.startsWith("1:")) {
                            val path = trimmed.substring(2)
                            val desc = unverifiedPaths.firstOrNull { it.first == path }?.second ?: ""
                            if (results.none { it.path == path }) {
                                results.add(ScanResult(path, desc, exists = true, isDirectory = false))
                            }
                        }
                    }
                }
                process.waitFor()
            } catch (_: Throwable) {
                // 忽略未授权或执行失败
            }
        }

        return results
    }

    /**
     * 清理单个指定路径的文件或目录
     */
    fun cleanItem(path: String): Boolean {
        return try {
            val file = File(path)
            var success = false
            if (file.exists()) {
                success = if (file.isDirectory) {
                    file.deleteRecursively()
                } else {
                    file.delete()
                }
            }
            if (!success) {
                success = deleteViaSu(path)
            }
            success
        } catch (e: Exception) {
            LogUtil.log("【CS】【ResidualCleaner】清理残留失败 $path: ${e.message}")
            false
        }
    }

    /**
     * 一键清理所有已扫描出的残留
     */
    fun cleanAll(items: List<ScanResult>): Int {
        var cleanedCount = 0
        for (item in items) {
            if (cleanItem(item.path)) {
                cleanedCount++
            }
        }
        return cleanedCount
    }

    private fun checkExistsViaSu(path: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "ls '$path' >/dev/null 2>&1; echo $?"))
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            output == "0"
        } catch (ignored: Exception) {
            false
        }
    }

    private fun deleteViaSu(path: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "rm -rf '$path'"))
            process.waitFor() == 0
        } catch (ignored: Exception) {
            false
        }
    }
}
