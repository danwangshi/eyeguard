package io.github.danwangshi.eyeguard

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

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

        // 白名单应用包名常量（合并电话、短信、微信、系统设置、本应用）
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
            // 微信
            "com.tencent.mm",
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

    // 离开电话应用后延迟恢复遮罩的防抖（避免短暂操作如下拉通知栏后误触发）
    private val overlayRestoreHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var overlayRestoreRunnable: Runnable? = null
    private val OVERLAY_RESTORE_DEBOUNCE_MS = 3000L

    // 离开微信后延迟清除标记的防抖（避免 startActivity 中间窗口误清除）
    private var weChatExitRunnable: Runnable? = null
    private val WECHAT_EXIT_DEBOUNCE_MS = 3000L

    override fun onServiceConnected() {
        super.onServiceConnected()
        AppLog.d(TAG, "无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // 定时范围外不处理任何窗口事件（省电）
                if (!LightMonitorService.isInScheduleRange) return

                val packageName = event.packageName?.toString()
                val className = event.className?.toString()

                if (packageName == null || className == null) return

                AppLog.d(TAG, "窗口变化: $packageName / $className")

                // 兜底检测：getWindows() 检查微信是否在前台
                checkWeChatInWindows()

                // === 电话应用检测（优先级最高，不受 isLocked 限制）===
                if (isPhoneRelated(packageName)) {
                    cancelPendingOverlayRestore()

                    if (!LockOverlayService.isPhoneAppActive) {
                        AppLog.d(TAG, "电话应用在前台: $packageName")
                        LockOverlayService.isPhoneAppActive = true
                    }
                    LockOverlayService.refreshPhoneAppTime()
                    if (LockOverlayService.isShowing) {
                        val intent = Intent(this, LockOverlayService::class.java).apply {
                            action = LockOverlayService.ACTION_REMOVE_LOCK
                        }
                        startService(intent)
                    }
                    lastPackageName = packageName
                    return
                }

                // === 微信前台检测（不受 isLocked 限制）===
                if (packageName == "com.tencent.mm") {
                    if (!LockOverlayService.isWeChatActive) {
                        AppLog.d(TAG, "微信在前台: $packageName，设置微信活跃")
                        LockOverlayService.isWeChatActive = true
                        LockOverlayService.refreshWeChatTime()
                    } else {
                        LockOverlayService.refreshWeChatTime()
                    }
                    weChatExitRunnable?.let {
                        overlayRestoreHandler.removeCallbacks(it)
                        weChatExitRunnable = null
                    }
                    if (LockOverlayService.isShowing) {
                        val intent = Intent(this, LockOverlayService::class.java).apply {
                            action = LockOverlayService.ACTION_REMOVE_LOCK
                        }
                        startService(intent)
                    }
                    lastPackageName = packageName
                    return
                }

                // 如果之前在微信中，现在切换到非微信应用，延迟清除标记
                if (LockOverlayService.isWeChatActive && packageName != "com.tencent.mm") {
                    if (isSystemTransientWindow(packageName)) {
                        LockOverlayService.checkWeChatTimeout()
                    } else {
                        AppLog.d(TAG, "离开微信应用，延迟 ${WECHAT_EXIT_DEBOUNCE_MS}ms 后清除微信标记")
                        weChatExitRunnable?.let { overlayRestoreHandler.removeCallbacks(it) }
                        weChatExitRunnable = Runnable {
                            weChatExitRunnable = null
                            AppLog.d(TAG, "微信防抖到期，清除微信标记")
                            LockOverlayService.clearWeChatActive()
                        }
                        overlayRestoreHandler.postDelayed(weChatExitRunnable!!, WECHAT_EXIT_DEBOUNCE_MS)
                    }
                }

                // 检测到微信在前台，取消待处理的微信退出防抖
                if (LockOverlayService.isWeChatActive && packageName == "com.tencent.mm") {
                    weChatExitRunnable?.let {
                        AppLog.d(TAG, "微信重新活跃，取消微信退出防抖")
                        overlayRestoreHandler.removeCallbacks(it)
                        weChatExitRunnable = null
                    }
                }

                // 如果之前在电话中，现在切换到非电话应用，重置标记并延迟恢复遮罩
                if (LockOverlayService.isPhoneAppActive) {
                    AppLog.d(TAG, "离开电话应用，重置标记")
                    LockOverlayService.clearPhoneAppActive()
                    if (isLocked) {
                        AppLog.d(TAG, "离开电话应用，延迟 ${OVERLAY_RESTORE_DEBOUNCE_MS}ms 后恢复遮罩层")
                        overlayRestoreRunnable = Runnable {
                            overlayRestoreRunnable = null
                            if (isPhoneAppStillForeground()) {
                                AppLog.d(TAG, "防抖结束但电话应用仍在前台，跳过遮罩恢复")
                                LockOverlayService.isPhoneAppActive = true
                                if (LockOverlayService.isShowing) {
                                    val removeIntent = Intent(this@LockAccessibilityService, LockOverlayService::class.java).apply {
                                        action = LockOverlayService.ACTION_REMOVE_LOCK
                                    }
                                    startService(removeIntent)
                                }
                            } else {
                                AppLog.d(TAG, "防抖结束，恢复遮罩层")
                                restartLockOverlay()
                            }
                        }
                        overlayRestoreHandler.postDelayed(overlayRestoreRunnable!!, OVERLAY_RESTORE_DEBOUNCE_MS)
                        return
                    }
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
                // 定时范围外不处理任何窗口事件（省电）
                if (!LightMonitorService.isInScheduleRange) return

                val now = System.currentTimeMillis()
                if (now - lastContentChangedTime < CONTENT_CHANGED_THROTTLE_MS) return
                lastContentChangedTime = now

                // 兜底检测微信前台
                checkWeChatInWindows()

                if (LockOverlayService.isPhoneAppActive || LightMonitorService.isInCall || LockOverlayService.isWeChatActive) return

                if (!isLocked) return

                if (!LockOverlayService.isShowing) {
                    if (overlayRestoreRunnable != null) {
                        AppLog.d(TAG, "窗口内容变化但防抖期内，跳过遮罩恢复")
                        return
                    }
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
     * 通过 getWindows() 兜底检测前台应用（微信/电话等）
     * 部分 ROM（如 Flyme）不触发上述应用的 TYPE_WINDOW_STATE_CHANGED
     */
    private fun checkWeChatInWindows() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        val windows = windows ?: return
        for (window in windows) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = window.root ?: continue
            val pkg = root.packageName?.toString() ?: continue

            // 检测微信在前台
            if (pkg == "com.tencent.mm" && !LockOverlayService.isWeChatActive) {
                AppLog.d(TAG, "getWindows() 检测到微信窗口，设置微信活跃")
                LockOverlayService.isWeChatActive = true
                LockOverlayService.refreshWeChatTime()
                if (LockOverlayService.isShowing) {
                    startService(Intent(this, LockOverlayService::class.java).apply {
                        action = LockOverlayService.ACTION_REMOVE_LOCK
                    })
                }
                return
            }

            // 检测电话应用在前台（取消待处理的遮罩恢复防抖）
            if (isPhoneRelated(pkg) && !LockOverlayService.isPhoneAppActive) {
                AppLog.d(TAG, "getWindows() 检测到电话应用: $pkg")
                LockOverlayService.isPhoneAppActive = true
                cancelPendingOverlayRestore()
                if (LockOverlayService.isShowing) {
                    startService(Intent(this, LockOverlayService::class.java).apply {
                        action = LockOverlayService.ACTION_REMOVE_LOCK
                    })
                }
                return
            }
        }
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
     * 检测是否是电话相关应用
     */
    private fun isPhoneRelated(packageName: String): Boolean {
        return PHONE_PACKAGES.contains(packageName) ||
               packageName.contains("dialer") ||
               packageName.contains("incallui") ||
               packageName == "com.android.phone"
    }

    /**
     * 检测是否是白名单应用
     */
    private fun isWhitelistApp(packageName: String): Boolean {
        return WHITELIST_APPS.contains(packageName) ||
               packageName.contains("dialer") ||
               packageName.contains("contacts") ||
               packageName.contains("messaging") ||
               packageName == "com.android.phone"
    }

    /**
     * 检测是否是系统临时窗口（Intent 选择器、系统对话框等）
     */
    private fun isSystemTransientWindow(packageName: String): Boolean {
        return packageName == "android" ||
               packageName == "com.android.intentresolver" ||
               packageName == "com.android.systemui" ||
               packageName == "com.android.packageinstaller" ||
               packageName.startsWith("com.google.android.packageinstaller")
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
        val intent = Intent("io.github.danwangshi.eyeguard.ACTION_CHECK_LOCK")
        sendBroadcast(intent)
    }

    /**
     * 取消待处理的遮罩恢复（离开电话应用防抖）
     */
    private fun cancelPendingOverlayRestore() {
        overlayRestoreRunnable?.let {
            overlayRestoreHandler.removeCallbacks(it)
            overlayRestoreRunnable = null
        }
    }

    /**
     * 检查当前前台窗口是否仍是电话应用
     * 不依赖 isActive（部分 ROM 不设置该字段）
     */
    private fun isPhoneAppStillForeground(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val windows = windows ?: return false
            for (windowInfo in windows) {
                if (windowInfo.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val pkg = windowInfo.root?.packageName?.toString() ?: continue
                if (isPhoneRelated(pkg)) return true
            }
        }
        return false
    }

    /**
     * 重新显示锁定覆盖层
     * 同步设置锁定状态，确保 LightMonitorService 状态一致
     */
    private fun restartLockOverlay() {
        if (LockStateHelper.shouldSkipLock()) {
            AppLog.d(TAG, "通话中或电话应用活跃，跳过重新显示遮罩层")
            return
        }

        // 通知 LightMonitorService 强制同步锁定状态
        LightMonitorService.pendingLockFromAccessibility = true

        val intent = Intent(this, LockOverlayService::class.java).apply {
            action = LockOverlayService.ACTION_SHOW_LOCK
            putExtra("force_refresh", true)
        }
        startService(intent)
    }
}
