package io.github.danwangshi.eyeguard

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * 无障碍服务
 * 用于检测窗口变化、识别电话应用
 */
class LockAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "LockAccessibilityService"

        // 当前是否处于锁定状态
        @Volatile
        private var isLocked = false

        // 最近任务界面类名常量
        private val RECENTS_ACTIVITIES = listOf(
            "com.android.systemui.recents.RecentsActivity",
            "com.android.systemui.recent.RecentsActivity",
            "com.android.systemui.recents.TvRecentsActivity",
            "com.miui.home.recents.RecentsActivity",  // 小米
            "com.huawei.android.launcher.unihome.UniHomeLauncher",  // 华为
            "com.coloros.recents.RecentsActivity",  // OPPO
            "com.vivo.android.launcher.Launcher",  // vivo
            "com.samsung.android.app.taskedge.ui.recents.RecentsActivity"  // 三星
        )

        // 电话相关应用包名（仅用于 isPhoneRelated 检测，不包含非电话应用）
        private val PHONE_PACKAGES = listOf(
            "com.android.dialer",
            "com.google.android.dialer",
            "com.android.phone",
            "com.android.incallui",
            "com.android.contacts",
            "com.google.android.contacts",
            "com.samsung.android.contacts"
        )

        // 白名单应用包名常量（合并电话、短信、手电筒、本应用）
        private val WHITELIST_APPS = listOf(
            // 电话/拨号相关
            "com.android.dialer",
            "com.google.android.dialer",
            "com.android.contacts",
            "com.google.android.contacts",
            "com.samsung.android.contacts",
            "com.android.phone",
            "com.android.incallui",
            // 短信相关
            "com.android.messaging",
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            // 系统设置
            "com.android.settings",
            // 本应用
            "io.github.danwangshi.eyeguard"
        )

        // 桌面启动器包名常量
        private val LAUNCHERS = listOf(
            "com.android.launcher",
            "com.android.launcher2",
            "com.android.launcher3",
            "com.google.android.launcher",
            "com.miui.home",  // 小米
            "com.huawei.android.launcher",  // 华为
            "com.coloros.launcher",  // OPPO
            "com.vivo.android.launcher",  // vivo
            "com.samsung.android.launcher",  // 三星
            "com.microsoft.launcher",
            "com.teslacoilsw.launcher"
        )

        /**
         * 设置锁定状态
         */
        fun setLocked(locked: Boolean) {
            isLocked = locked
            AppLog.d(TAG, "锁定状态设置为: $locked")
        }

        /**
         * 获取锁定状态
         */
        fun isLocked(): Boolean = isLocked
    }

    private var lastPackageName: String? = null
    // Task 5: TYPE_WINDOW_CONTENT_CHANGED 节流，至少间隔 2 秒
    private var lastContentChangedTime = 0L
    private val CONTENT_CHANGED_THROTTLE_MS = 2000L

    override fun onServiceConnected() {
        super.onServiceConnected()
        AppLog.d(TAG, "无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val packageName = event.packageName?.toString()
                val className = event.className?.toString()

                if (packageName == null || className == null) return

                AppLog.d(TAG, "窗口变化: $packageName / $className")

                // === 电话应用检测（优先级最高，不受 isLocked 限制）===

                // 兜底：检查电话应用标记是否已超时
                LockOverlayService.checkPhoneAppTimeout()

                // 检测是否是电话相关应用（拨号器、通话界面）
                if (isPhoneRelated(packageName)) {
                    if (isLocked && !LockOverlayService.isPhoneAppActive) {
                        AppLog.d(TAG, "电话应用在前台: $packageName，隐藏遮罩层")
                        LockOverlayService.isPhoneAppActive = true
                    }
                    // 移除遮罩层 UI（不改变 isLocked）
                    if (LockOverlayService.isShowing) {
                        val intent = Intent(this, LockOverlayService::class.java).apply {
                            action = LockOverlayService.ACTION_REMOVE_LOCK
                        }
                        startService(intent)
                    }
                    lastPackageName = packageName
                    return
                }

                // 如果之前在电话中，现在切换到非电话应用，重置标记并重新遮罩
                if (LockOverlayService.isPhoneAppActive) {
                    AppLog.d(TAG, "离开电话应用，重新启用遮罩")
                    LockOverlayService.clearPhoneAppActive()
                }

                // === 以下逻辑需要 isLocked ===
                if (!isLocked) return

                // 检测是否打开了最近任务
                if (isRecentsActivity(className)) {
                    AppLog.d(TAG, "检测到最近任务键，强制返回")
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    restartLockOverlay()
                    return
                }

                // 检测是否是白名单应用
                if (isWhitelistApp(packageName)) {
                    lastPackageName = packageName
                    return
                }

                // 检测是否切换到桌面
                if (isLauncher(packageName)) {
                    AppLog.d(TAG, "检测到桌面，保持锁定")
                    restartLockOverlay()
                    return
                }

                // 检测到切换到其他非白名单应用
                if (packageName != lastPackageName) {
                    AppLog.d(TAG, "尝试切换到非白名单应用: $packageName，若光线不足将恢复锁定")
                    checkAndRequestLock()
                }

                lastPackageName = packageName
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // 节流处理：至少间隔 2 秒才处理一次
                val now = System.currentTimeMillis()
                if (now - lastContentChangedTime < CONTENT_CHANGED_THROTTLE_MS) return
                lastContentChangedTime = now

                // 电话应用活跃时不处理
                if (LockOverlayService.isPhoneAppActive || LightMonitorService.isInCall) return

                if (!isLocked) return

                // 窗口内容变化时，如果锁定界面未显示，确保锁定界面存在
                if (!LockOverlayService.isShowing) {
                    AppLog.d(TAG, "检测到窗口内容变化且锁定界面未显示，重新显示")
                    restartLockOverlay()
                }
            }
        }
    }

    override fun onInterrupt() {
        AppLog.d(TAG, "无障碍服务中断")
    }

    /**
     * 检测是否是最近任务界面
     */
    private fun isRecentsActivity(className: String): Boolean {
        return RECENTS_ACTIVITIES.any { className.contains(it) } ||
               className.contains("Recents") ||
               className.contains("RecentApps")
    }

    /**
     * 检测是否是电话相关应用（拨号器、通话界面）
     * 注意：不包含本应用和非电话类白名单应用
     */
    private fun isPhoneRelated(packageName: String): Boolean {
        return PHONE_PACKAGES.contains(packageName) ||
               packageName.contains("dialer") ||
               packageName.contains("incallui") ||
               packageName == "com.android.phone"
    }

    /**
     * 检测是否是白名单应用（含电话/拨号/短信/系统设置/本应用）
     */
    private fun isWhitelistApp(packageName: String): Boolean {
        return WHITELIST_APPS.contains(packageName) ||
               packageName.contains("dialer") ||
               packageName.contains("contacts") ||
               packageName.contains("messaging") ||
               packageName.contains("phone")
    }

    /**
     * 检测是否是桌面启动器
     */
    private fun isLauncher(packageName: String): Boolean {
        return LAUNCHERS.contains(packageName) ||
               packageName.contains("launcher")
    }

    /**
     * 检查并请求锁定
     */
    private fun checkAndRequestLock() {
        // 发送广播给 LightMonitorService 让其重新检查光线和锁定状态
        val intent = Intent("io.github.danwangshi.eyeguard.ACTION_CHECK_LOCK")
        sendBroadcast(intent)
    }

    /**
     * 重新显示锁定覆盖层
     */
    private fun restartLockOverlay() {
        // 通话中或电话应用在前台时，不重新显示遮罩层
        if (LockStateHelper.shouldSkipLock()) {
            AppLog.d(TAG, "通话中或电话应用活跃，跳过重新显示遮罩层")
            return
        }

        val intent = Intent(this, LockOverlayService::class.java).apply {
            action = LockOverlayService.ACTION_SHOW_LOCK
            putExtra("force_refresh", true)
        }
        startService(intent)
    }
}
