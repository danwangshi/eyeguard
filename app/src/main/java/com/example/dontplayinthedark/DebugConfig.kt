package com.example.dontplayinthedark

/**
 * 全局调试配置
 * 正式发布时务必设置为 false，以最大化减少资源消耗和电量消耗
 */
object DebugConfig {
    /**
     * 是否启用调试日志
     * - true: 启用详细调试日志（开发阶段使用）
     * - false: 关闭所有调试日志（正式发布时使用）
     */
    const val ENABLE_DEBUG_LOG = true
    
    /**
     * 是否启用详细的状态机日志
     * - true: 记录状态机流转的详细日志
     * - false: 关闭状态机日志
     */
    const val ENABLE_STATE_MACHINE_LOG = true
    
    /**
     * 是否启用传感器数据日志
     * - true: 记录传感器数据变化
     * - false: 关闭传感器日志
     */
    const val ENABLE_SENSOR_LOG = true
}
