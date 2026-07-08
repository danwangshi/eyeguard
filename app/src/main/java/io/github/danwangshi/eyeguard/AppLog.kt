package io.github.danwangshi.eyeguard

import android.util.Log

/**
 * 日志工具类
 * 根据 DebugConfig 的配置决定是否输出日志
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
     * 输出 WARN 级别日志（警告始终输出）
     */
    fun w(tag: String, message: String) {
        Log.w(tag, message)
    }
    
    /**
     * 输出 ERROR 级别日志（错误始终输出）
     */
    fun e(tag: String, message: String) {
        Log.e(tag, message)
    }

    /**
     * 输出 ERROR 级别日志（带异常堆栈，始终输出）
     */
    fun e(tag: String, message: String, throwable: Throwable) {
        Log.e(tag, message, throwable)
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
    
    /**
     * 输出关键操作日志（始终输出）
     */
    fun critical(message: String) {
        Log.w("$TAG-Critical", message)
    }
}
