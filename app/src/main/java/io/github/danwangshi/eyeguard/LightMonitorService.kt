package io.github.danwangshi.eyeguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.util.Calendar
import androidx.core.app.NotificationCompat

/**
 * 光线监测前台服务
 * 持续监听光线传感器，当光线低于阈值时触发锁定
 */
class LightMonitorService : Service(), SensorEventListener {

    companion object {
        private const val TAG = "LightMonitorService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "light_monitor_channel"

        // 阈值参考（根据环境光线场景选择）
        // 夜晚室内关灯：0 ~ 10 Lux
        // 昏暗环境：10 ~ 50 Lux
        // 夜晚室内开灯：50 ~ 200 Lux
        // 明亮环境：300 ~ 500 Lux
        const val THRESHOLD_STRICT = 10f   // 严格：10 lux（夜晚关灯级别）
        const val THRESHOLD_NORMAL = 50f   // 标准：50 lux（昏暗环境级别）
        const val THRESHOLD_LOOSE = 200f   // 宽松：200 lux（夜晚开灯级别）

        // 动作
        const val ACTION_START = "io.github.danwangshi.eyeguard.ACTION_START"
        const val ACTION_STOP = "io.github.danwangshi.eyeguard.ACTION_STOP"

        // 当前服务运行状态
        var isRunning = false
            private set

        // 当前是否处于通话中
        @Volatile
        var isInCall = false
            private set

        // 当前是否在定时启用范围内（供无障碍服务判断是否处理窗口事件）
        @Volatile
        var isInScheduleRange = true
            private set

        // 无障碍服务请求强制锁定（防抖恢复遮罩时同步状态）
        @Volatile
        var pendingLockFromAccessibility = false
    }

    private lateinit var sensorManager: SensorManager
    private var lightSensor: Sensor? = null
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var powerManager: PowerManager
    private var notificationManager: NotificationManager? = null

    private var currentThreshold = 0f
    private var currentLux = 0f
    // 指数移动平均平滑值，用于阈值比较，滤除传感器瞬时波动
    private var smoothedLux = 0f
    private var hasSmoothedLux = false  // smoothedLux 是否已建立
    // EMA 平滑系数：0.0~1.0，越大对新值响应越快，越小越平滑
    // 0.7 时约 1-2 个采样（2-4 秒）适应剧烈变化，能有效滤除单次瞬时波动
    private val SMOOTHING_ALPHA = 0.7f
    private var hasSensorData = false  // 是否已收到首次传感器数据
    // 传感器连续上报 0 lux 的次数，用于避免单次噪声导致平滑值归零
    // 阈值场景：全黑环境传感器可能稳定报 0，此时无需多次采样就能确认是真正的黑暗
    // 噪声场景：亮环境下传感器偶发报 0，需要连续多次报 0 才认为是真实黑暗
    private var consecutiveZeroCount = 0
    // 连续上报 0 的阈值：达到此值才允许将 smoothedLux 归零
    // 500ms 采样间隔下，3 次 = 1.5 秒真实黑暗，已足以排除噪声
    private val ZERO_COUNT_THRESHOLD = 3
    private var isLocked = false

    // 防抖状态机
    private enum class LightState {
        IDLE,       // 空闲态：正常监听，等待光线变化
        LOCKED,     // 锁定态：遮罩层显示中，等待解锁
        COOLDOWN    // 冷却态：解锁后强制冷却，忽略所有光线
    }
    
    private var currentState = LightState.IDLE
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    
    // 防抖开关：用户可配置是否启用防抖延迟触发
    private var debounceEnabled = false
    
    // 防抖延迟时间（毫秒）
    private val LOCK_DEBOUNCE_DELAY = 3000L  // 3秒后锁定
    private val COOLDOWN_DURATION = 3000L  // 解锁后 3秒冷却期
    
    // 定时器任务
    private var lockRunnable: Runnable? = null
    private var cooldownRunnable: Runnable? = null
    
    // 冷却期内的光线状态记录
    private var lightBelowThresholdDuringCooldown = false
    private var cooldownAboveThresholdCount = 0  // 冷却期内连续高于阈值的采样次数
    private val COOLDOWN_EARLY_EXIT_COUNT = 3  // 连续3次高于阈值则提前退出冷却

    private val binder = LocalBinder()

    // WakeLock 保持 CPU 唤醒，确保后台传感器事件正常投递
    private var wakeLock: PowerManager.WakeLock? = null

    // 屏幕状态跟踪
    private var isScreenOn = true
    private var screenStateReceiver: android.content.BroadcastReceiver? = null
    // 防止广播接收器/传感器重复注册
    private var receiversRegistered = false

    // 定时范围外省电模式：暂停传感器、WakeLock、屏幕监听，仅保留定时器
    private var isPausedForSchedule = false

    // 通知栏缓存
    private var lastNotificationContent: String? = null
    private var lastNotifiedLux = -1
    private var lastNotifiedLocked = false
    private var lastNotifiedPausedForSchedule = false
    private var lastNotifiedInCall = false
    private var lastNotifiedWeChatActive = false
    private var notificationBuilder: NotificationCompat.Builder? = null  // 缓存 Builder

    // 定时启用缓存（避免每 2 秒读 SharedPreferences）
    private var cachedScheduleEnabled = false
    private var cachedStartHour = 22
    private var cachedStartMinute = 0
    private var cachedEndHour = 6
    private var cachedEndMinute = 0

    private fun reloadScheduleCache() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        cachedScheduleEnabled = prefs.getBoolean("schedule_enabled", false)
        cachedStartHour = prefs.getInt("schedule_start_hour", 22)
        cachedStartMinute = prefs.getInt("schedule_start_minute", 0)
        cachedEndHour = prefs.getInt("schedule_end_hour", 6)
        cachedEndMinute = prefs.getInt("schedule_end_minute", 0)
    }

    /** 从 SharedPreferences 重新读取阈值，确保 UI 调整及时生效 */
    private fun reloadThreshold() {
        currentThreshold = getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getInt("threshold_value", 0).toFloat()
    }

    /** 判断当前时间是否在定时启用范围内 */
    private fun isWithinSchedule(): Boolean {
        if (!cachedScheduleEnabled) return true

        val now = Calendar.getInstance()
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val startMinutes = cachedStartHour * 60 + cachedStartMinute
        val endMinutes = cachedEndHour * 60 + cachedEndMinute

        return if (startMinutes <= endMinutes) {
            currentMinutes in startMinutes until endMinutes
        } else {
            currentMinutes >= startMinutes || currentMinutes < endMinutes
        }
    }

    /** 定时边界轮询（动态间隔，精确到下一个边界时间） */
    private var schedulePollRunnable: Runnable? = null

    private fun startSchedulePoll() {
        stopSchedulePoll()
        schedulePollRunnable = Runnable {
            reloadScheduleCache()
            doScheduleTransition()
            if (cachedScheduleEnabled && isWithinSchedule()) {
                recheckAfterScheduleChange()
            }
            // 动态计算下次轮询延迟，确保准时到达边界
            val delay = calculateNextPollDelay()
            handler.postDelayed(schedulePollRunnable!!, delay)
        }
        // 首次轮询延迟 10 秒（给传感器初始化时间），之后动态调整
        handler.postDelayed(schedulePollRunnable!!, 10_000L)
        AppLog.d(TAG, "定时轮询已启动（动态间隔）")
    }

    private fun stopSchedulePoll() {
        schedulePollRunnable?.let {
            handler.removeCallbacks(it)
            schedulePollRunnable = null
        }
    }

    /**
     * 计算下次轮询延迟，精确到下一个定时边界时间
     * - 非定时启用：固定 60 秒
     * - 在范围内：延迟到结束时间 + 1 秒（不超过 60 秒）
     * - 在范围外：延迟到开始时间 + 1 秒（不超过 60 秒）
     */
    private fun calculateNextPollDelay(): Long {
        if (!cachedScheduleEnabled) return 60_000L

        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()

        // 计算今天的开始/结束时间戳
        cal.set(Calendar.HOUR_OF_DAY, cachedStartHour)
        cal.set(Calendar.MINUTE, cachedStartMinute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val todayStart = cal.timeInMillis

        cal.set(Calendar.HOUR_OF_DAY, cachedEndHour)
        cal.set(Calendar.MINUTE, cachedEndMinute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        var todayEnd = cal.timeInMillis

        // 跨午夜：结束时间加一天
        if (todayEnd <= todayStart) {
            cal.add(Calendar.DAY_OF_MONTH, 1)
            todayEnd = cal.timeInMillis
        }

        return if (isWithinSchedule()) {
            // 在范围内：延迟到结束时间
            (todayEnd - now + 1000L).coerceIn(10_000L, 60_000L)
        } else {
            // 在范围外：延迟到开始时间
            val nextStart = if (now < todayStart) todayStart
                           else todayStart + 24 * 60 * 60 * 1000L // 明天的开始
            (nextStart - now + 1000L).coerceIn(10_000L, 60_000L)
        }
    }

    /** 定时设置变更后重新检查锁定状态 */
    private fun recheckAfterScheduleChange() {
        // 如果处于省电暂停状态但已进入定时范围，先恢复
        if (isPausedForSchedule && isWithinSchedule()) {
            resumeForSchedule()
        }
        if (!isScreenOn) return
        if (hasSensorData) {
            if (debounceEnabled) checkLight() else checkLockState()
        }
    }

    /**
     * 定时边界转换处理：进入/退出定时范围时自动暂停或恢复监测
     * 在每次定时轮询中执行，确保准时切换
     */
    private fun doScheduleTransition() {
        if (!cachedScheduleEnabled) {
            // 定时未启用：如果处于暂停状态则恢复
            if (isPausedForSchedule) {
                resumeForSchedule()
            }
            return
        }
        if (isWithinSchedule()) {
            // 进入定时范围：如果处于暂停状态则恢复
            if (isPausedForSchedule) {
                resumeForSchedule()
                recheckAfterScheduleChange()
            }
        } else {
            // 退出定时范围：如果未暂停则进入省电模式
            if (!isPausedForSchedule) {
                pauseForSchedule()
            }
        }
    }

    /**
     * 判断当前是否在定时启用时间段内，并管理省电模式。
     * 传感器监测始终运行（不因定时暂停），
     * 仅通过返回值控制是否允许锁定/遮罩行为。
     * - 定时未启用 → 始终返回 true（允许锁定），并确保省电模式已退出
     * - 在时间段内 → 返回 true，并确保省电模式已退出
     * - 不在时间段内 → 返回 false，自动进入省电模式（暂停传感器等）
     */
    private fun applySchedule(): Boolean {
        if (!cachedScheduleEnabled) {
            // 定时未启用：确保不在省电暂停状态
            if (isPausedForSchedule) {
                resumeForSchedule()
            }
            return true
        }
        if (isWithinSchedule()) {
            // 在时间段内：确保不在省电暂停状态
            if (isPausedForSchedule) {
                resumeForSchedule()
            }
            return true
        }

        // 不在时间段内：自动进入省电模式（仅首次）
        if (!isPausedForSchedule) {
            pauseForSchedule()
        }
        return false
    }

    /**
     * 取消注册屏幕状态广播接收器
     */
    private fun unregisterScreenStateReceiver() {
        try {
            screenStateReceiver?.let { unregisterReceiver(it) }
        } catch (e: Exception) {
            // 忽略未注册错误
        }
        screenStateReceiver = null
    }

    /**
     * 进入省电模式：定时范围外暂停传感器、WakeLock、屏幕监听，
     * 仅保留主程序和定时轮询，确保到时能恢复工作。
     * 同时更新通知栏状态为"暂停遮罩 | 定时暂停中"。
     */
    private fun pauseForSchedule() {
        if (isPausedForSchedule) return
        AppLog.d(TAG, "[省电] 定时范围外，暂停传感器监测（省电模式）")
        isPausedForSchedule = true

        // 如果当前处于锁定状态，先解除锁定
        if (isLocked) {
            // 通知无障碍服务
            LockAccessibilityService.setLocked(false)
            isLocked = false
            // 移除锁定覆盖层
            val removeIntent = Intent(this, LockOverlayService::class.java).apply {
                action = LockOverlayService.ACTION_REMOVE_LOCK
            }
            startService(removeIntent)
            AppLog.d(TAG, "[省电] 已解除锁定")
        }

        // 取消所有防抖定时器
        lockRunnable?.let { handler.removeCallbacks(it); lockRunnable = null }
        cooldownRunnable?.let { handler.removeCallbacks(it); cooldownRunnable = null }
        pendingLockCheck?.let { handler.removeCallbacks(it); pendingLockCheck = null }
        currentState = LightState.IDLE

        // 重置传感器状态并取消传感器监听
        hasSensorData = false
        consecutiveZeroCount = 0
        smoothedLux = 0f
        hasSmoothedLux = false
        sensorManager.unregisterListener(this)
        AppLog.d(TAG, "[省电] 传感器监听已取消")

        // 释放 WakeLock
        wakeLock?.let { lock ->
            if (lock.isHeld) {
                lock.release()
                AppLog.d(TAG, "[省电] WakeLock 已释放")
            }
        }
        wakeLock = null

        // 取消注册屏幕状态广播接收器
        unregisterScreenStateReceiver()

        // 更新通知栏
        updateNotification()
        isInScheduleRange = false
        AppLog.d(TAG, "[省电] 省电模式已启用，仅保留定时轮询")
    }

    /**
     * 退出省电模式：进入定时范围时恢复传感器、WakeLock、屏幕监听，
     * 重新开始监测环境光线。
     */
    private fun resumeForSchedule() {
        if (!isPausedForSchedule) return
        AppLog.d(TAG, "[省电] 进入定时范围，恢复传感器监测")
        isPausedForSchedule = false

        // 重置传感器状态标记，等待首次数据
        hasSensorData = false
        consecutiveZeroCount = 0
        smoothedLux = 0f
        hasSmoothedLux = false

        // 重新获取 WakeLock
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "LightMonitorService::SensorWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
        AppLog.d(TAG, "[省电] WakeLock 已重新获取")

        // 重新注册传感器监听
        sensorManager.registerListener(
            this,
            lightSensor,
            SENSOR_SAMPLING_INTERVAL_US
        )
        AppLog.d(TAG, "[省电] 传感器监听已恢复")

        // 恢复屏幕状态广播接收器
        receiversRegistered = true  // 确保 startMonitoring 的防重入标记正确
        registerScreenStateReceiver()

        isInScheduleRange = true
        // 更新通知栏
        updateNotification()
        AppLog.d(TAG, "[省电] 省电模式已退出，恢复正常监测")
    }

    // 传感器采样间隔（微秒）：500ms，环境光变化缓慢，但仍需足够采样率
    // 以便 EMA 平滑能有效滤除单次噪声读数。500ms 间隔下约 60% CPU 占用，
    // 已在性能和抗混叠之间取得平衡。
    private val SENSOR_SAMPLING_INTERVAL_US = 500 * 1000
    // 文件日志节流计数器：每 4 次传感器事件写一次文件日志，减少闪存写入
    private var fileLogThrottle = 0

    inner class LocalBinder : Binder() {
        fun getService(): LightMonitorService = this@LightMonitorService
    }

    override fun onCreate() {
        super.onCreate()
        // 初始化文件日志（如果已启用）
        AppLog.init(this)
        AppLog.d(TAG, "onCreate")

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        notificationManager = getSystemService(NotificationManager::class.java)

        // 读取用户设置的阈值和防抖开关
        // 默认使用 1 lux（仅极端黑暗环境触发），配合防抖避免误触发
        currentThreshold = prefs.getInt("threshold_value", 1).toFloat()
        debounceEnabled = prefs.getBoolean("debounce_enabled", false)

        // 加载定时缓存
        reloadScheduleCache()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLog.d(TAG, "onStartCommand: ${intent?.action}")

        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startMonitoring()
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLog.d(TAG, "onDestroy")
        stopMonitoring()
        isRunning = false
        AppLog.shutdown()
    }

    /**
     * 开始监测
     */
    private fun startMonitoring() {
        AppLog.d(TAG, "开始光线监测，阈值: $currentThreshold lux")
        isRunning = true
        hasSensorData = false  // 重置传感器数据标记，等待首次数据
        consecutiveZeroCount = 0  // 重置连续归零计数器

        // 防止重复注册导致崩溃
        if (receiversRegistered) {
            AppLog.d(TAG, "监测已启动，跳过重复注册")
            return
        }

        // 检查是否有光线传感器
        if (lightSensor == null) {
            AppLog.e(TAG, "设备没有光线传感器")
            stopSelf()
            return
        }

        // 启动前台服务
        startForeground(NOTIFICATION_ID, createNotification())

        // 获取 WakeLock，防止屏幕关闭后 CPU 休眠导致传感器停止投递
        // 先释放旧的 WakeLock 避免泄漏
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "LightMonitorService::SensorWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
        AppLog.d(TAG, "WakeLock 已获取")

        // 注册传感器监听（使用自定义采样间隔，降低功耗）
        sensorManager.registerListener(
            this,
            lightSensor,
            SENSOR_SAMPLING_INTERVAL_US
        )

        // 注册广播接收器监听状态检查请求
        val filter = IntentFilter("io.github.danwangshi.eyeguard.ACTION_CHECK_LOCK")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(lockCheckReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(lockCheckReceiver, filter)
        }

        // 注册屏幕状态广播接收器
        registerScreenStateReceiver()

        // 启动定时边界轮询（每 60 秒刷新缓存，确保准时进入/退出定时窗口）
        startSchedulePoll()

        receiversRegistered = true
    }

    /**
     * 注册屏幕状态广播接收器
     * 息屏时暂停传感器检测，亮屏解锁时恢复
     */
    private fun registerScreenStateReceiver() {
        screenStateReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                // 使用 goAsync 确保息屏时广播能在 CPU 休眠前完成处理
                val pendingResult = goAsync()
                try {
                    when (intent?.action) {
                        Intent.ACTION_SCREEN_OFF -> {
                            AppLog.d(TAG, "[屏幕] 收到息屏广播，暂停传感器检测")
                            isScreenOn = false
                            pauseMonitoring()
                        }
                        Intent.ACTION_SCREEN_ON -> {
                            AppLog.d(TAG, "[屏幕] 收到亮屏广播，恢复传感器检测")
                            isScreenOn = true
                            resumeMonitoring()
                        }
                        Intent.ACTION_USER_PRESENT -> {
                            AppLog.d(TAG, "[屏幕] 用户解锁确认")
                            // 始终恢复传感器监听，不依赖 isScreenOn 状态
                            // 修复：服务被系统杀死重启后 isScreenOn 默认为 true，
                            // 如果 ACTION_SCREEN_ON 在重启前已到达，此处会错误跳过
                            // 导致传感器从未被重新注册，遮罩层永远无法触发
                            isScreenOn = true
                            resumeMonitoring()
                            // 解锁后执行一次兜底锁定检查（不等传感器首次数据）
                            performPostResumeCheck()
                        }
                    }
                } finally {
                    pendingResult.finish()
                }
            }
        }
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenStateReceiver, screenFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(screenStateReceiver, screenFilter)
        }
        AppLog.d(TAG, "屏幕状态广播接收器已注册")
    }

    /**
     * 暂停监测（息屏时调用）
     * 仅暂停传感器和释放 WakeLock，保持锁定状态不变
     * 亮屏后由 resumeMonitoring → 首次传感器数据 决定解锁
     */
    private fun pauseMonitoring() {
        // 注意：不调用 transitionToIdle()，保持 isLocked 和 currentState 不变
        // 息屏时遮罩层由系统自动隐藏，无需手动移除
        // 亮屏后首次传感器数据会触发 checkLight/checkLockState

        // 重置传感器数据处理状态，等待 sensor 重新注册后的首次数据
        // 原因：息屏期间传感器读数已过期，亮屏后不应使用息屏前的旧值做判断
        hasSensorData = false
        consecutiveZeroCount = 0
        smoothedLux = 0f       // 重置平滑值
        hasSmoothedLux = false // 标记平滑未建立，resume 后从首次数据重新开始

        // 取消传感器监听，节省电量
        sensorManager.unregisterListener(this)
        AppLog.d(TAG, "传感器监听已暂停")

        // 释放 WakeLock，让 CPU 正常休眠
        wakeLock?.let { lock ->
            if (lock.isHeld) {
                lock.release()
                AppLog.d(TAG, "WakeLock 已释放")
            }
        }
        wakeLock = null
    }

    /**
     * 恢复监测（亮屏解锁后调用）
     * 重新获取 WakeLock、注册传感器、等待传感器首次数据到来时判断锁定状态
     * 注意：checkLockState 在 resume 后不会立即执行（hasSensorData=false 提前返回），
     * 必须等首次传感器数据到达后由 onSensorChanged 触发状态判断。
     * 这样避免了使用息屏前的过期亮度值做错误决策。
     */
    private fun resumeMonitoring() {
        // 先释放旧的 WakeLock 避免泄漏（可能来自省电模式恢复）
        wakeLock?.let { lock ->
            if (lock.isHeld) {
                lock.release()
                AppLog.d(TAG, "旧的 WakeLock 已释放")
            }
        }
        // 重新获取 WakeLock
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "LightMonitorService::SensorWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
        AppLog.d(TAG, "WakeLock 已重新获取")

        // 重新注册传感器监听（使用自定义采样间隔）
        sensorManager.registerListener(
            this,
            lightSensor,
            SENSOR_SAMPLING_INTERVAL_US
        )
        AppLog.d(TAG, "传感器监听已恢复")

        // 等待首次传感器数据，避免使用息屏前的过期亮度值
        // 首次传感器数据到达后，onSensorChanged 会执行 checkLight / checkLockState
        AppLog.d(TAG, "[恢复监测] isLocked=$isLocked, currentState=$currentState, threshold=$currentThreshold")
    }

    /**
     * 解锁后兜底检查
     * 在 resumeMonitoring 后执行，确保即使传感器无事件投递也能做出锁定决策。
     * 防抖模式下走状态机路径，非防抖模式下直接检查。
     * 首次使用 currentLux 快速检查（可能有延迟），1.5 秒后再次确认（给传感器足够时间）。
     */
    private fun performPostResumeCheck() {
        // 1. 立即用当前值检查（可能为过期值，但比永远不检查好）
        postResumeLockCheck()
        // 2. 延迟 1.5 秒后重新检查，给传感器充足时间投递首次数据
        handler.postDelayed({ postResumeLockCheck() }, 1500L)
    }

    /**
     * 恢复后锁定状态快速检查
     * 绕过 hasSensorData 守卫，直接基于 currentLux 判断。
     * - 防抖模式：走 handleIdleState 启动倒计时，不立即锁定
     * - 非防抖模式：根据当前光线立即锁定/解锁
     */
    private fun postResumeLockCheck() {
        reloadThreshold()
        if (isInCall || LockOverlayService.isPhoneAppActive || LockOverlayService.isWeChatActive) return
        if (!isScreenOn) return

        AppLog.d(TAG, "[resume检查] currentLux=$currentLux, threshold=$currentThreshold, isLocked=$isLocked")

        if (debounceEnabled) {
            // 防抖模式：走状态机路径，不会立即锁定
            if (!isLocked) {
                handleIdleState()
            } else {
                handleLockedState()
            }
        } else {
            // 非防抖模式：立即检查
            if (currentLux <= currentThreshold && !isLocked) {
                AppLog.d(TAG, "[resume检查] 光线不足，执行锁定")
                lockDevice()
            } else if (currentLux > currentThreshold && isLocked) {
                AppLog.d(TAG, "[resume检查] 光线充足，解除锁定")
                unlockDevice()
            }
        }
    }

    // 延迟锁定检查：离开电话应用后等待传感器稳定
    private var pendingLockCheck: Runnable? = null

    private val lockCheckReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "io.github.danwangshi.eyeguard.ACTION_CHECK_LOCK") {
                AppLog.d(TAG, "收到锁定检查请求，延迟 2 秒执行（等待传感器稳定）")
                // 取消之前的待处理检查
                pendingLockCheck?.let { handler.removeCallbacks(it) }
                // 延迟 2 秒再检查，避免拨号器屏幕亮度导致误判解锁
                pendingLockCheck = Runnable {
                    pendingLockCheck = null
                    AppLog.d(TAG, "延迟锁定检查执行")
                    checkLockState()
                }
                handler.postDelayed(pendingLockCheck!!, 2000L)
            }
        }
    }


    /**
     * 停止监测
     */
    private fun stopMonitoring() {
        AppLog.d(TAG, "停止光线监测")

        // 重置电话/微信应用标记，避免下次启动时遮罩层无法显示
        LockOverlayService.clearPhoneAppActive()
        LockOverlayService.clearWeChatActive()
        isInCall = false

        // 取消屏幕状态广播接收器
        unregisterScreenStateReceiver()

        try {
            unregisterReceiver(lockCheckReceiver)
        } catch (e: Exception) {
            // 忽略未注册错误
        }
        sensorManager.unregisterListener(this)

        // 取消所有待处理的防抖动作，回到 IDLE 状态
        pendingLockCheck?.let { handler.removeCallbacks(it); pendingLockCheck = null }
        transitionToIdle()

        // 停止定时边界轮询
        stopSchedulePoll()

        // 释放 WakeLock
        wakeLock?.let { lock ->
            if (lock.isHeld) {
                lock.release()
                AppLog.d(TAG, "WakeLock 已释放")
            }
        }
        wakeLock = null

        // 如果当前处于锁定状态，解除锁定
        if (isLocked) {
            unlockDevice()
        }

        // 通知UI更新
        sendBroadcast(Intent(ACTION_STOP))
        receiversRegistered = false
        isPausedForSchedule = false
        isInScheduleRange = true
    }

    /**
     * 更新阈值
     */
    fun updateThreshold(newThreshold: Float) {
        currentThreshold = newThreshold
        AppLog.d(TAG, "阈值更新为: $currentThreshold lux")

        // 取消所有待处理的定时器（防止旧阈值下的计时器在新阈值下误触发）
        lockRunnable?.let { handler.removeCallbacks(it); lockRunnable = null }
        cooldownRunnable?.let { handler.removeCallbacks(it); cooldownRunnable = null }

        checkLockState()
        // 同步状态机状态：手动调阈值触发的锁定/解锁是非防抖的，
        // 需要将 currentState 与 isLocked 对齐，避免后续防抖状态机出现冗余操作
        if (debounceEnabled) {
            currentState = if (isLocked) LightState.LOCKED else LightState.IDLE
            AppLog.stateMachine("阈值更新后同步状态: currentState=$currentState, isLocked=$isLocked")
        }
    }

    /**
     * 传感器数值变化回调
     */
    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_LIGHT) {
            val rawLux = event.values[0]
            hasSensorData = true  // 标记已收到有效传感器数据

            // 指数移动平均（EMA）平滑，滤除传感器瞬时波动
            // 例如：环境 40lux 瞬间跌到 1.5lux -> 平滑后 20.75lux，不会误触发
            // 平滑值替代原始值用于所有阈值判断，确保短暂波动不导致误锁定
            if (!hasSmoothedLux) {
                hasSmoothedLux = true
                smoothedLux = rawLux
            } else {
                // 连续归零计数器：记录传感器连续上报 0 的次数
                if (rawLux == 0f) {
                    consecutiveZeroCount++
                } else {
                    consecutiveZeroCount = 0
                }

                smoothedLux = SMOOTHING_ALPHA * rawLux + (1 - SMOOTHING_ALPHA) * smoothedLux
                // 当原始光线连续多次为 0 且平滑值趋近于 0 时，直接归零
                // 避免亮环境下传感器单次噪声（报 0）导致平滑值过快下跌
                // 同时在全黑环境（传感器稳定报 0）下仍能精确达到 0 lux
                if (rawLux == 0f && consecutiveZeroCount >= ZERO_COUNT_THRESHOLD && smoothedLux < 0.5f) {
                    smoothedLux = 0f
                }
            }
            currentLux = smoothedLux

            // 输出传感器日志（原始值和平滑值）
            // 文件日志每 4 次写一次，减少闪存写入
            fileLogThrottle++
            val logMsg = "环境光线变化: ${rawLux} lux (平滑: ${"%.1f".format(smoothedLux)}), 阈值: ${currentThreshold} lux"
            if (fileLogThrottle % 4 == 0) {
                AppLog.sensor(logMsg)
            } else if (DebugConfig.ENABLE_SENSOR_LOG) {
                // 仅输出到 logcat，不写文件
                android.util.Log.d("EyeGuard-Sensor", logMsg)
            }

            // 更新通知
            updateNotification()

            // 实时更新遮罩层亮度显示
            if (LockOverlayService.isShowing) {
                val updateIntent = Intent(this, LockOverlayService::class.java).apply {
                    action = LockOverlayService.ACTION_UPDATE_LUX
                    putExtra("lux", currentLux)
                }
                startService(updateIntent)
            }

            // 判断是否需要锁定/解锁（根据开关决定是否启用防抖）
            if (debounceEnabled) {
                checkLight()  // 使用新的状态机方法
            } else {
                checkLockState()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // 精度变化时不需要处理
    }

    /**
     * 防抖状态机核心逻辑
     * 单一调度入口，通过 when(当前状态) 分发逻辑
     */
    private fun checkLight() {
        // 每次检查前重新读取阈值，确保用户实时调整生效
        reloadThreshold()

        // 处理无障碍服务的强制锁定请求（防抖恢复遮罩时同步状态）
        if (pendingLockFromAccessibility) {
            pendingLockFromAccessibility = false
            isLocked = true
            currentState = LightState.LOCKED
            AppLog.stateMachine("收到无障碍强制锁定请求，同步锁定状态")
        }

        // 息屏状态下不执行状态机
        if (!isScreenOn) {
            return
        }

        // 通话中、电话应用或微信在前台时，保持当前状态，不切换状态机
        // 电话/微信标记由无障碍服务管理（窗口变化检测），不在此处检查超时
        // 避免通话中静态界面因 60 秒超时误清除标记导致遮罩弹出
        if (isInCall || LockOverlayService.isPhoneAppActive || LockOverlayService.isWeChatActive) {
            // 取消待处理的锁定/冷却定时器，防止状态机不同步
            lockRunnable?.let { handler.removeCallbacks(it); lockRunnable = null }
            cooldownRunnable?.let { handler.removeCallbacks(it); cooldownRunnable = null }
            currentState = LightState.IDLE
            AppLog.stateMachine("通话中或电话/微信应用活跃，取消定时器并回到 IDLE (isLocked=$isLocked)")
            return
        }

        // 定时调度检查：不在时间段内自动进入省电模式
        if (!applySchedule()) {
            return
        }

        when (currentState) {
            LightState.IDLE -> handleIdleState()
            LightState.LOCKED -> handleLockedState()
            LightState.COOLDOWN -> handleCooldownState()
        }
    }

    /**
     * IDLE 状态处理：正常监听，等待光线低于阈值
     * 防抖策略：光线低于阈值启动倒计时，倒计时到期时重新检查当前光线值，
     * 只有到期时仍然低于阈值才执行锁定。
     * 这样避免了瞬时的光线波动（手遮挡、传感器噪声等）导致误锁定。
     */
    private fun handleIdleState() {
        if (currentLux <= currentThreshold) {
            // 光线低于阈值，准备锁定
            if (lockRunnable == null) {
                AppLog.stateMachine("[IDLE] 光线不足 (${currentLux}lux < ${currentThreshold}lux)，启动锁定倒计时 ${LOCK_DEBOUNCE_DELAY}ms")
                lockRunnable = Runnable {
                    lockRunnable = null
                    // 重新检查当前光线值——如果倒计时期间光线已恢复，不锁定
                    if (currentLux <= currentThreshold) {
                        AppLog.stateMachine("[IDLE] 倒计时结束，光线仍然不足 (${currentLux}lux < ${currentThreshold}lux)，执行锁定")
                        performLock()
                    } else {
                        AppLog.stateMachine("[IDLE] 倒计时期间光线已恢复 (${currentLux}lux >= ${currentThreshold}lux)，取消锁定")
                    }
                }
                handler.postDelayed(lockRunnable!!, LOCK_DEBOUNCE_DELAY)
            }
        } else {
            // 光线充足，清除锁定计时器
            if (lockRunnable != null) {
                AppLog.stateMachine("[IDLE] 光线恢复，清除锁定计时器")
                handler.removeCallbacks(lockRunnable!!)
                lockRunnable = null
            }
        }
    }

    /**
     * LOCKED 状态处理：遮罩层显示中，光线恢复立即解锁
     */
    private fun handleLockedState() {
        if (currentLux > currentThreshold) {
            // 光线恢复，立即解锁
            AppLog.stateMachine("[LOCKED] 光线恢复，立即解锁")
            performUnlock()
        } else {
            // 光线仍然低于阈值，保持锁定状态
            AppLog.stateMachine("[LOCKED] 光线仍然低于阈值，保持锁定")
        }
    }

    /**
     * COOLDOWN 状态处理：强制冷却，忽略所有光线变化，但记录光线状态
     */
    private fun handleCooldownState() {
        // 冷却期内记录光线状态，连续高于阈值则提前退出
        if (currentLux <= currentThreshold) {
            lightBelowThresholdDuringCooldown = true
            cooldownAboveThresholdCount = 0
        } else {
            cooldownAboveThresholdCount++
            if (cooldownAboveThresholdCount >= COOLDOWN_EARLY_EXIT_COUNT) {
                // 光线持续充足，提前结束冷却
                AppLog.stateMachine("[COOLDOWN] 光线持续充足，提前结束冷却 → IDLE")
                cooldownRunnable?.let {
                    handler.removeCallbacks(it)
                    cooldownRunnable = null
                }
                currentState = LightState.IDLE
                checkLight()
                return
            }
        }
    }

    /**
     * 执行锁定动作：显示遮罩层，状态切换到 LOCKED
     */
    private fun performLock() {
        AppLog.stateMachine("状态转换: IDLE → LOCKED")
        lockDevice()
        currentState = LightState.LOCKED
    }

    /**
     * 执行解锁动作：隐藏遮罩层，状态切换到 COOLDOWN
     */
    private fun performUnlock() {
        AppLog.stateMachine("状态转换: LOCKED → COOLDOWN")
        
        // 进入冷却期前，清除所有待处理的定时器
        lockRunnable?.let {
            handler.removeCallbacks(it)
            lockRunnable = null
            AppLog.stateMachine("[LOCKED] 清除残留的锁定计时器")
        }
        
        unlockDevice()
        currentState = LightState.COOLDOWN
        lightBelowThresholdDuringCooldown = false  // 重置冷却期光线状态记录
        cooldownAboveThresholdCount = 0  // 重置连续高于阈值计数
        
        // 立即启动冷却倒计时（无缝衔接）
        cooldownRunnable = Runnable {
            AppLog.stateMachine("[COOLDOWN] 冷却期结束，状态转换: COOLDOWN → IDLE")
            cooldownRunnable = null
            currentState = LightState.IDLE
            
            // 冷却结束后立即检查光线状态
            // 如果冷却期间光线曾低于阈值，且当前仍然低于阈值，启动倒计时
            // 注意：需要用 currentLux 再次确认，避免冷却期间仅瞬间低值就触发重新锁定
            if (lightBelowThresholdDuringCooldown && currentLux <= currentThreshold) {
                AppLog.stateMachine("[COOLDOWN] 冷却期间光线低于阈值且当前仍不足 (${currentLux}lux < ${currentThreshold}lux)，启动锁定倒计时")
                // 立即启动锁定倒计时（到期时再次检查）
                lockRunnable = Runnable {
                    lockRunnable = null
                    if (currentLux <= currentThreshold) {
                        AppLog.stateMachine("[COOLDOWN] 锁屏倒计时结束，光线仍不足，执行锁定")
                        performLock()
                    } else {
                        AppLog.stateMachine("[COOLDOWN] 锁屏倒计时期间光线已恢复，取消锁定")
                    }
                }
                handler.postDelayed(lockRunnable!!, LOCK_DEBOUNCE_DELAY)
            } else {
                // 否则正常检测当前光线状态
                AppLog.stateMachine("[COOLDOWN] 冷却期间光线正常，重新检测当前状态")
                checkLight()
            }
        }
        handler.postDelayed(cooldownRunnable!!, COOLDOWN_DURATION)
        AppLog.stateMachine("启动冷却倒计时 ${COOLDOWN_DURATION}ms")
    }

    /**
     * 强制回到 IDLE 状态（用于通话等特殊情况）
     */
    private fun transitionToIdle() {
        // 清除所有定时器
        lockRunnable?.let {
            handler.removeCallbacks(it)
            lockRunnable = null
        }
        cooldownRunnable?.let {
            handler.removeCallbacks(it)
            cooldownRunnable = null
        }
        
        // 电话/微信应用活跃时不解锁，保持 isLocked 状态
        // 避免场景下 isLocked 被意外设为 false 导致遮罩层无法恢复
        if (isLocked && !LockOverlayService.isPhoneAppActive && !LockOverlayService.isWeChatActive && !isInCall) {
            unlockDevice()
        }
        
        currentState = LightState.IDLE
        AppLog.stateMachine("状态转换: * → IDLE")
    }

    /**
     * 设置防抖开关
     */
    fun setDebounceEnabled(enabled: Boolean) {
        debounceEnabled = enabled
        AppLog.d(TAG, "防抖开关切换: $enabled")
        // 关闭防抖时，强制回到 IDLE 状态
        if (!enabled) {
            transitionToIdle()
            // 立即根据当前光线状态执行一次检查
            checkLockState()
        }
    }

    /**
     * 检查锁定状态（无防抖，用于阈值更新等场景）
     */
    private fun checkLockState() {
        // 每次检查前重新读取阈值，确保用户实时调整生效
        reloadThreshold()

        // 未收到首次传感器数据前，不执行锁定判断
        if (!hasSensorData) {
            AppLog.d(TAG, "等待首次传感器数据，跳过锁定检查")
            return
        }

        // 息屏状态下不锁定，等用户亮屏解锁后再判断
        if (!isScreenOn) {
            AppLog.d(TAG, "息屏状态，跳过锁定检查")
            return
        }

        // 通话中、电话应用或微信在前台时，不锁定也不解锁，保持当前状态
        // 电话/微信标记由无障碍服务管理，不在此处检查超时
        if (isInCall || LockOverlayService.isPhoneAppActive || LockOverlayService.isWeChatActive) {
            AppLog.d(TAG, "通话中或电话/微信应用活跃，跳过锁定检查 (isLocked=$isLocked)")
            return
        }

        // 定时调度检查：不在时间段内自动进入省电模式
        if (!applySchedule()) {
            return
        }

        if (currentLux <= currentThreshold && !isLocked) {
            // 光线不足，需要锁定
            lockDevice()
        } else if (currentLux > currentThreshold && isLocked) {
            // 光线恢复，解除锁定
            unlockDevice()
        }
    }

    /**
     * 锁定设备
     */
    private fun lockDevice() {
        // 息屏状态下不锁定
        if (!isScreenOn) {
            AppLog.d(TAG, "息屏状态，跳过锁定")
            return
        }

        // 通话中或电话应用在前台时，不锁定
        if (LockStateHelper.shouldSkipLock()) {
            AppLog.d(TAG, "通话中或电话应用活跃，跳过锁定")
            return
        }

        // 定时启用检查：不在设定时间段内不锁定
        if (!isWithinSchedule()) {
            AppLog.d(TAG, "当前不在定时启用时间段内，跳过锁定")
            return
        }

        AppLog.d(TAG, "锁定设备，当前亮度: $currentLux lux")
        isLocked = true

        // 通知无障碍服务
        LockAccessibilityService.setLocked(true)

        // 显示锁定覆盖层
        val intent = Intent(this, LockOverlayService::class.java).apply {
            action = LockOverlayService.ACTION_SHOW_LOCK
            putExtra("lux", currentLux)
            putExtra("threshold", currentThreshold)
        }
        startService(intent)

        // 更新通知
        updateNotification()
    }

    /**
     * 解除锁定
     */
    private fun unlockDevice() {
        AppLog.d(TAG, "解除锁定，当前亮度: $currentLux lux")
        isLocked = false

        // 通知无障碍服务
        LockAccessibilityService.setLocked(false)

        // 移除锁定覆盖层
        val intent = Intent(this, LockOverlayService::class.java).apply {
            action = LockOverlayService.ACTION_REMOVE_LOCK
        }
        startService(intent)

        // 更新通知
        updateNotification()
    }

    /**
     * 创建通知（Builder 缓存，仅首次创建渠道和 Builder 实例）
     */
    private fun createNotification(): Notification {
        // 创建通知渠道（Android 8.0+），仅首次执行
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                setSound(null, null)
            }
            notificationManager?.createNotificationChannel(channel)
        }

        // 缓存 Builder 实例，避免每次重建
        if (notificationBuilder == null) {
            notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notification_title))
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true)
        }

        return notificationBuilder!!
            .setContentText(buildNotificationText())
            .build()
    }

    /**
     * 构建通知文本，根据当前状态显示不同内容：
     * - 通话中 → "电话使用中"
     * - 微信中 → "微信使用中"
     * - 定时暂停 → "定时暂停中"
     * - 锁定中 → "光线太暗，已锁定"
     * - 正常工作 → "当前亮度：xxx lux"
     */
    private fun buildNotificationText(): String {
        return when {
            isInCall || LockOverlayService.isPhoneAppActive ->
                getString(R.string.notification_in_call)
            LockOverlayService.isWeChatActive ->
                getString(R.string.notification_in_wechat)
            isPausedForSchedule ->
                getString(R.string.notification_schedule_paused)
            isLocked ->
                getString(R.string.notification_locked)
            else ->
                getString(R.string.notification_content, currentLux.toInt())
        }
    }

    /**
     * 更新通知（节流：仅在状态或 lux 整数值变化超过 2 时更新）
     */
    private fun updateNotification() {
        val lockedChanged = isLocked != lastNotifiedLocked
        val luxChanged = kotlin.math.abs(currentLux.toInt() - lastNotifiedLux) >= 2
        val pausedChanged = isPausedForSchedule != lastNotifiedPausedForSchedule
        val inCallChanged = isInCall != lastNotifiedInCall
        val weChatChanged = LockOverlayService.isWeChatActive != lastNotifiedWeChatActive
        if (!lockedChanged && !luxChanged && !pausedChanged && !inCallChanged && !weChatChanged) return

        val contentText = buildNotificationText()
        // 内容未变化时跳过更新
        if (contentText == lastNotificationContent) return
        lastNotificationContent = contentText
        lastNotifiedLux = currentLux.toInt()
        lastNotifiedLocked = isLocked
        lastNotifiedPausedForSchedule = isPausedForSchedule
        lastNotifiedInCall = isInCall
        lastNotifiedWeChatActive = LockOverlayService.isWeChatActive
        notificationManager?.notify(NOTIFICATION_ID, createNotification())
    }

    /**
     * 获取当前亮度值
     */
    fun getCurrentLux(): Float = currentLux

    /**
     * 获取当前阈值
     */
    fun getThreshold(): Float = currentThreshold

    /**
     * 是否处于锁定状态
     */
    fun isDeviceLocked(): Boolean = isLocked

    /**
     * 触发一次锁定状态重新检查（供 SettingsActivity 在定时/防抖设置变更后调用）
     */
    fun triggerRecheck() {
        AppLog.d(TAG, "收到外部触发，重新加载定时缓存并检查锁定状态")
        reloadScheduleCache()
        // 重置定时轮询，基于新时间重新计算下次检查延迟
        startSchedulePoll()
        recheckAfterScheduleChange()
    }
}
