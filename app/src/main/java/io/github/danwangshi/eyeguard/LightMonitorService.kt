package io.github.danwangshi.eyeguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
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
    }

    private lateinit var sensorManager: SensorManager
    private var lightSensor: Sensor? = null
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var powerManager: PowerManager
    private var notificationManager: NotificationManager? = null

    private var currentThreshold = 0f
    private var currentLux = 0f
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

    private val binder = LocalBinder()

    // 电话状态监听
    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: PhoneStateListener? = null

    // WakeLock 保持 CPU 唤醒，确保后台传感器事件正常投递
    private var wakeLock: PowerManager.WakeLock? = null

    // 屏幕状态跟踪
    private var isScreenOn = true
    private var screenStateReceiver: android.content.BroadcastReceiver? = null

    // 通知栏缓存：记录上次通知内容，避免重复构建 Notification
    private var lastNotificationContent: String? = null
    // 通知节流：记录上次通知时的 lux 整数值和锁定状态
    private var lastNotifiedLux = -1
    private var lastNotifiedLocked = false

    // 传感器采样间隔（微秒）：500ms，环境光变化缓慢，无需高频采样
    private val SENSOR_SAMPLING_INTERVAL_US = 500 * 1000

    inner class LocalBinder : Binder() {
        fun getService(): LightMonitorService = this@LightMonitorService
    }

    override fun onCreate() {
        super.onCreate()
        AppLog.d(TAG, "onCreate")

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        notificationManager = getSystemService(NotificationManager::class.java)

        // 读取用户设置的阈值和防抖开关
        currentThreshold = prefs.getInt("threshold_value", 0).toFloat()
        debounceEnabled = prefs.getBoolean("debounce_enabled", false)

        // 初始化电话状态监听
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        setupPhoneStateListener()
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
    }

    /**
     * 开始监测
     */
    private fun startMonitoring() {
        AppLog.d(TAG, "开始光线监测，阈值: $currentThreshold lux")
        isRunning = true

        // 检查是否有光线传感器
        if (lightSensor == null) {
            Log.e(TAG, "设备没有光线传感器")
            stopSelf()
            return
        }

        // 启动前台服务
        startForeground(NOTIFICATION_ID, createNotification())

        // 获取 WakeLock，防止屏幕关闭后 CPU 休眠导致传感器停止投递
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
                            AppLog.d(TAG, "[屏幕] 收到亮屏广播，等待用户解锁")
                            // 亮屏但不解锁，暂不恢复监测
                        }
                        Intent.ACTION_USER_PRESENT -> {
                            AppLog.d(TAG, "[屏幕] 用户解锁，恢复传感器检测")
                            isScreenOn = true
                            resumeMonitoring()
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
        registerReceiver(screenStateReceiver, screenFilter)
        AppLog.d(TAG, "屏幕状态广播接收器已注册")
    }

    /**
     * 暂停监测（息屏时调用）
     * 仅暂停传感器和释放 WakeLock，保持锁定状态不变
     * 亮屏后由 resumeMonitoring → checkLockState 根据新传感器数据决定解锁
     */
    private fun pauseMonitoring() {
        // 注意：不调用 transitionToIdle()，保持 isLocked 和 currentState 不变
        // 息屏时遮罩层由系统自动隐藏，无需手动移除
        // 亮屏后 checkLockState() 会根据新的传感器数据决定是否解锁

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
     * 重新获取 WakeLock、注册传感器、根据当前光线状态判断是否解锁
     */
    private fun resumeMonitoring() {
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

        // 恢复后立即检查当前光线状态
        // 如果亮屏后光线已高于阈值，checkLockState 会解锁并移除遮罩层
        // 如果光线仍低于阈值，保持锁定状态
        AppLog.d(TAG, "[恢复监测] isLocked=$isLocked, currentState=$currentState, currentLux=$currentLux, threshold=$currentThreshold")
        checkLockState()
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
     * 设置电话状态监听器
     */
    private fun setupPhoneStateListener() {
        // 检查是否有电话状态权限
        if (checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "没有READ_PHONE_STATE权限，无法监听通话状态")
            return
        }

        phoneStateListener = object : PhoneStateListener() {
            @Deprecated("Deprecated in Java")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                when (state) {
                    TelephonyManager.CALL_STATE_IDLE -> {
                        AppLog.d(TAG, "电话状态: 空闲")
                        isInCall = false
                        // 通话结束后，如果光线仍然不足，重新检查锁定状态
                        if (isRunning) {
                            checkLockState()
                        }
                    }
                    TelephonyManager.CALL_STATE_RINGING -> {
                        AppLog.d(TAG, "电话状态: 响铃")
                        isInCall = true
                        // 来电时移除遮罩层，让用户能接听
                        if (isLocked) {
                            removeOverlayForCall()
                        }
                    }
                    TelephonyManager.CALL_STATE_OFFHOOK -> {
                        AppLog.d(TAG, "电话状态: 通话中")
                        isInCall = true
                        // 通话中移除遮罩层
                        if (isLocked) {
                            removeOverlayForCall()
                        }
                    }
                }
            }
        }

        try {
            telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
            AppLog.d(TAG, "电话状态监听已注册")
        } catch (e: Exception) {
            AppLog.w(TAG, "电话状态监听注册失败: ${e.message}")
        }
    }

    /**
     * 通话时移除遮罩层
     */
    private fun removeOverlayForCall() {
        AppLog.d(TAG, "通话中，移除遮罩层")
        val intent = Intent(this, LockOverlayService::class.java).apply {
            action = LockOverlayService.ACTION_REMOVE_LOCK
        }
        startService(intent)
    }

    /**
     * 停止监测
     */
    private fun stopMonitoring() {
        AppLog.d(TAG, "停止光线监测")

        // 重置电话应用标记，避免下次启动时遮罩层无法显示
        LockOverlayService.clearPhoneAppActive()
        isInCall = false

        // 取消屏幕状态广播接收器
        try {
            screenStateReceiver?.let { unregisterReceiver(it) }
            screenStateReceiver = null
        } catch (e: Exception) {
            // 忽略未注册错误
        }

        // 取消电话状态监听
        try {
            telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
        } catch (e: Exception) {
            AppLog.w(TAG, "取消电话状态监听失败: ${e.message}")
        }

        try {
            unregisterReceiver(lockCheckReceiver)
        } catch (e: Exception) {
            // 忽略未注册错误
        }
        sensorManager.unregisterListener(this)

        // 取消所有待处理的防抖动作，回到 IDLE 状态
        pendingLockCheck?.let { handler.removeCallbacks(it); pendingLockCheck = null }
        transitionToIdle()

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
    }

    /**
     * 更新阈值
     */
    fun updateThreshold(newThreshold: Float) {
        currentThreshold = newThreshold
        AppLog.d(TAG, "阈值更新为: $currentThreshold lux")
        checkLockState()
    }

    /**
     * 传感器数值变化回调
     */
    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_LIGHT) {
            currentLux = event.values[0]

            // 安全网：检查屏幕是否已关闭，如果是则主动暂停监测
            // 防止 SCREEN_OFF 广播未收到导致息屏后仍然运行状态机
            if (!powerManager.isInteractive && isScreenOn) {
                AppLog.d(TAG, "[安全网] 屏幕已关闭但状态未同步，主动暂停传感器检测")
                isScreenOn = false
                pauseMonitoring()
                return
            }
            if (!isScreenOn) {
                // 已暂停状态，忽略传感器数据
                return
            }
            
            // 输出传感器日志（受调试开关控制）
            AppLog.sensor("环境光线变化: ${currentLux} lux, 阈值: ${currentThreshold} lux")

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
     * 光线防抖状态机核心逻辑
     * 单一调度入口，通过 when(当前状态) 分发逻辑
     */
    private fun checkLight() {
        // 息屏状态下不执行状态机
        if (!isScreenOn) {
            return
        }

        // 通话中或电话应用在前台时，保持当前状态，不切换状态机
        // 重要：不能调用 transitionToIdle()，因为它会 unlockDevice() 导致 isLocked=false
        if (isInCall || LockOverlayService.isPhoneAppActive) {
            // 检查电话应用标记是否已超时（兜底：无障碍服务未能检测离开拨号器时）
            LockOverlayService.checkPhoneAppTimeout()
            if (!isInCall && !LockOverlayService.isPhoneAppActive) {
                // 超时已清除，重新检查锁定状态
                AppLog.stateMachine("电话应用标记超时，恢复状态机检查")
            } else {
                AppLog.stateMachine("通话中或电话应用活跃，保持当前状态 (state=$currentState, isLocked=$isLocked)")
                return
            }
        }

        // 根据当前状态分发逻辑
        when (currentState) {
            LightState.IDLE -> handleIdleState()
            LightState.LOCKED -> handleLockedState()
            LightState.COOLDOWN -> handleCooldownState()
        }
    }

    /**
     * IDLE 状态处理：正常监听，等待光线低于阈值
     */
    private fun handleIdleState() {
        if (currentLux < currentThreshold) {
            // 光线低于阈值，准备锁定
            if (lockRunnable == null) {
                AppLog.stateMachine("[IDLE] 光线不足，启动锁定倒计时 ${LOCK_DEBOUNCE_DELAY}ms")
                lockRunnable = Runnable {
                    AppLog.stateMachine("[IDLE] 锁定倒计时结束，执行锁定")
                    lockRunnable = null
                    performLock()
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
        if (currentLux >= currentThreshold) {
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
        // 冷却期内虽然不响应，但记录光线状态
        if (currentLux < currentThreshold) {
            lightBelowThresholdDuringCooldown = true
            AppLog.stateMachine("[COOLDOWN] 冷却中，记录光线状态: 低于阈值")
        } else {
            lightBelowThresholdDuringCooldown = false
            AppLog.stateMachine("[COOLDOWN] 冷却中，记录光线状态: 高于阈值")
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
        
        // 立即启动冷却倒计时（无缝衔接）
        cooldownRunnable = Runnable {
            AppLog.stateMachine("[COOLDOWN] 冷却期结束，状态转换: COOLDOWN → IDLE")
            cooldownRunnable = null
            currentState = LightState.IDLE
            
            // 冷却结束后立即检查光线状态
            // 如果冷却期间光线曾低于阈值，立即启动锁定倒计时
            if (lightBelowThresholdDuringCooldown) {
                AppLog.stateMachine("[COOLDOWN] 冷却期间光线低于阈值，立即启动锁定倒计时")
                // 立即启动锁定倒计时
                lockRunnable = Runnable {
                    AppLog.stateMachine("[IDLE] 锁定倒计时结束，执行锁定")
                    lockRunnable = null
                    performLock()
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
        
        // 电话应用活跃时不解锁，保持 isLocked 状态
        // 避免电话场景下 isLocked 被意外设为 false 导致遮罩层无法恢复
        if (isLocked && !LockOverlayService.isPhoneAppActive && !isInCall) {
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
        // 息屏状态下不锁定，等用户亮屏解锁后再判断
        if (!isScreenOn) {
            AppLog.d(TAG, "息屏状态，跳过锁定检查")
            return
        }

        // 通话中或电话应用在前台时，不锁定也不解锁，保持当前状态
        if (isInCall || LockOverlayService.isPhoneAppActive) {
            // 检查电话应用标记是否已超时（兜底）
            LockOverlayService.checkPhoneAppTimeout()
            if (isInCall || LockOverlayService.isPhoneAppActive) {
                AppLog.d(TAG, "通话中或电话应用活跃，跳过锁定检查 (isLocked=$isLocked)")
                return
            }
            AppLog.d(TAG, "电话应用标记超时，恢复锁定检查")
        }

        if (currentLux < currentThreshold && !isLocked) {
            // 光线不足，需要锁定
            lockDevice()
        } else if (currentLux >= currentThreshold && isLocked) {
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
     * 创建通知
     */
    private fun createNotification(): Notification {
        // 创建通知渠道（Android 8.0+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_title)
                setShowBadge(false)
            }

            notificationManager?.createNotificationChannel(channel)
        }

        // 创建点击通知时打开主界面的PendingIntent
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        // 创建停止服务的动作
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, LightMonitorService::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = if (isLocked) {
            getString(R.string.notification_locked)
        } else {
            getString(R.string.notification_content, currentLux.toInt())
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_compass)  // 使用系统图标，实际项目中应使用自定义图标
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopIntent)
            .build()
    }

    /**
     * 更新通知（节流：仅在锁定状态变化或 lux 整数值变化超过 5 时更新）
     */
    private fun updateNotification() {
        val lockedChanged = isLocked != lastNotifiedLocked
        val luxChanged = kotlin.math.abs(currentLux.toInt() - lastNotifiedLux) >= 5
        if (!lockedChanged && !luxChanged) return

        val contentText = if (isLocked) {
            getString(R.string.notification_locked)
        } else {
            getString(R.string.notification_content, currentLux.toInt())
        }
        // 内容未变化时跳过更新
        if (contentText == lastNotificationContent) return
        lastNotificationContent = contentText
        lastNotifiedLux = currentLux.toInt()
        lastNotifiedLocked = isLocked
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
}
