package io.github.danwangshi.eyeguard

/**
 * 锁定状态公共工具
 * 集中管理"是否应跳过锁定"的判断，避免多处重复
 */
object LockStateHelper {
    /**
     * 是否应跳过锁定（通话中、电话应用在前台、微信活跃）
     */
    fun shouldSkipLock(): Boolean {
        return LightMonitorService.isInCall || LockOverlayService.isPhoneAppActive || LockOverlayService.isWeChatActive
    }
}
