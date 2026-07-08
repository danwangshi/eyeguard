package io.github.danwangshi.eyeguard

import android.util.Log

/**
 * 日志工具类
 * 发布版本所有日志均关闭，零日志输出，零资源消耗
 */
object AppLog {
    private const val TAG = "EyeGuard"

    /**
     * 输出 DEBUG 级别日志
     */
    fun d(tag: String, message: String) {
        if (DebugConfig.ENABLE_DEBUG_LOG) {
            Log.d(tag, message)
        }
    }

    /**
     * 输出 INFO 级别日志
     */
    fun i(tag: String, message: String) {
        if (DebugConfig.ENABLE_DEBUG_LOG) {
            Log.i(tag, message)
        }
    }

    /**
     * 输出 WARN 级别日志
     */
    fun w(tag: String, message: String) {
        if (DebugConfig.ENABLE_DEBUG_LOG) {
            Log.w(tag, message)
        }
    }

    /**
     * 输出 ERROR 级别日志
     */
    fun e(tag: String, message: String) {
        if (DebugConfig.ENABLE_DEBUG_LOG) {
            Log.e(tag, message)
        }
    }

    /**
     * 输出 ERROR 级别日志（带异常堆栈）
     */
    fun e(tag: String, message: String, throwable: Throwable) {
        if (DebugConfig.ENABLE_DEBUG_LOG) {
            Log.e(tag, message, throwable)
        }
    }

    /**
     * 输出状态机相关日志
     */
    fun stateMachine(message: String) {
        if (DebugConfig.ENABLE_STATE_MACHINE_LOG) {
            Log.d("$TAG-StateMachine", message)
        }
    }

    /**
     * 输出传感器相关日志
     */
    fun sensor(message: String) {
        if (DebugConfig.ENABLE_SENSOR_LOG) {
            Log.d("$TAG-Sensor", message)
        }
    }
}
