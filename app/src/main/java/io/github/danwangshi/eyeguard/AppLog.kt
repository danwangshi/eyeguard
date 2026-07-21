package io.github.danwangshi.eyeguard

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志工具类
 * 发布版本所有日志均关闭，零日志输出，零资源消耗
 *
 * 文件日志（按天轮转）：
 * - 写入外部存储 Android/data/io.github.danwangshi.eyeguard/files/logs/ 目录
 * - 文件名格式：eyeguard-YYYY-MM-dd.log
 * - 保留最近 7 天，超出自动清理
 */
object AppLog {
    private const val TAG = "EyeGuard"
    private const val LOG_DIR = "logs"
    private const val FILE_PREFIX = "eyeguard-"
    private const val FILE_SUFFIX = ".log"
    private const val ROTATION_DAYS = 7  // 保留天数

    private var fileWriter: LogFileWriter? = null

    /**
     * 初始化文件日志（如果需要）
     * 在 Application / Service / Activity 的 onCreate 中调用
     */
    fun init(context: Context) {
        if (!DebugConfig.ENABLE_FILE_LOG) return
        if (fileWriter != null) return  // 已经初始化过了

        val appCtx = context.applicationContext
        val logDir: File? = try {
            // 优先使用外部存储（无需权限，随应用卸载自动删除）
            (appCtx.getExternalFilesDir(null) ?: appCtx.filesDir).let {
                File(it, LOG_DIR).also { dir -> dir.mkdirs() }
            }
        } catch (e: Exception) {
            // 外部存储不可用时回退到内部存储
            File(appCtx.filesDir, LOG_DIR).also { dir -> dir.mkdirs() }
        }

        if (logDir != null && logDir.exists()) {
            fileWriter = LogFileWriter(logDir)
            Log.d(TAG, "文件日志已初始化: ${logDir.absolutePath}")
        }
    }

    /**
     * 关闭文件日志
     */
    fun shutdown() {
        fileWriter?.close()
        fileWriter = null
    }

    // ==================== 日志接口 ====================

    fun d(tag: String, message: String) {
        if (DebugConfig.ENABLE_DEBUG_LOG) {
            Log.d(tag, message)
        }
        fileWriter?.write(tag, "D", message)
    }

    fun i(tag: String, message: String) {
        if (DebugConfig.ENABLE_DEBUG_LOG) {
            Log.i(tag, message)
        }
        fileWriter?.write(tag, "I", message)
    }

    fun w(tag: String, message: String) {
        if (DebugConfig.ENABLE_DEBUG_LOG) {
            Log.w(tag, message)
        }
        fileWriter?.write(tag, "W", message)
    }

    fun e(tag: String, message: String) {
        if (DebugConfig.ENABLE_DEBUG_LOG) {
            Log.e(tag, message)
        }
        fileWriter?.write(tag, "E", message)
    }

    fun e(tag: String, message: String, throwable: Throwable) {
        if (DebugConfig.ENABLE_DEBUG_LOG) {
            Log.e(tag, message, throwable)
        }
        fileWriter?.write(tag, "E", "$message\n${Log.getStackTraceString(throwable)}")
    }

    fun stateMachine(message: String) {
        if (DebugConfig.ENABLE_STATE_MACHINE_LOG) {
            Log.d("$TAG-StateMachine", message)
        }
        fileWriter?.write("$TAG-StateMachine", "S", message)
    }

    fun sensor(message: String) {
        if (DebugConfig.ENABLE_SENSOR_LOG) {
            Log.d("$TAG-Sensor", message)
        }
        fileWriter?.write("$TAG-Sensor", "N", message)
    }

    /**
     * 文件日志写入器
     * 按天轮转，超出 ROTATION_DAYS 自动清理
     */
    private class LogFileWriter(private val logDir: File) {
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
        private var currentDate: String? = null
        private var writer: FileWriter? = null
        private var lastCleanupDay = 0  // 避免每次写入都清理

        @Synchronized
        fun write(tag: String, level: String, message: String) {
            try {
                val today = dateFormat.format(Date())
                if (today != currentDate) {
                    rotate(today)
                }
                val timestamp = timestampFormat.format(Date())
                val threadName = Thread.currentThread().name
                writer?.write("$timestamp [$level] $tag ($threadName): $message\n")
                writer?.flush()
            } catch (e: IOException) {
                // 文件写入失败时静默降级
                Log.w(TAG, "文件日志写入失败: ${e.message}")
            }
        }

        private fun rotate(date: String) {
            try {
                writer?.close()
            } catch (_: IOException) { }
            currentDate = date
            val file = File(logDir, "$FILE_PREFIX$date$FILE_SUFFIX")
            try {
                writer = FileWriter(file, true)  // append 模式
            } catch (e: IOException) {
                Log.w(TAG, "日志文件打开失败: ${e.message}")
                writer = null
            }
            // 每天只清理一次
            val todayDay = dateFormat.parse(date)?.time?.let {
                (it / (24 * 60 * 60 * 1000L)).toInt()
            } ?: 0
            if (todayDay != lastCleanupDay) {
                lastCleanupDay = todayDay
                cleanup()
            }
        }

        /**
         * 清理超过 ROTATION_DAYS 天的旧日志文件
         */
        private fun cleanup() {
            val cutoff = System.currentTimeMillis() - ROTATION_DAYS * 24 * 60 * 60 * 1000L
            val files = logDir.listFiles { f -> f.name.startsWith(FILE_PREFIX) && f.name.endsWith(FILE_SUFFIX) }
            files?.forEach { file ->
                if (file.lastModified() < cutoff) {
                    if (file.delete()) {
                        Log.d(TAG, "已清理旧日志: ${file.name}")
                    }
                }
            }
        }

        fun close() {
            try {
                writer?.close()
            } catch (_: IOException) { }
            writer = null
        }
    }
}
