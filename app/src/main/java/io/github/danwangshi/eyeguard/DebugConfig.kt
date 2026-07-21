package io.github.danwangshi.eyeguard

/**
 * 全局调试配置
 * 正式发布时务必设置为 false，以最大化减少资源消耗和电量消耗
 */
object DebugConfig {
    const val ENABLE_DEBUG_LOG = true
    const val ENABLE_STATE_MACHINE_LOG = true
    const val ENABLE_SENSOR_LOG = true
    const val ENABLE_FILE_LOG = true
}
