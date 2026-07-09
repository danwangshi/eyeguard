package io.github.danwangshi.eyeguard

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * 锁定覆盖层服务
 * 显示全屏悬浮窗，拦截所有触摸事件
 * 注意：光线传感器监听由 LightMonitorService 统一管理，本服务不再独立注册
 */
class LockOverlayService : Service(), ComponentCallbacks2 {

    companion object {
        private const val TAG = "LockOverlayService"

        // 动作
        const val ACTION_SHOW_LOCK = "io.github.danwangshi.eyeguard.ACTION_SHOW_LOCK"
        const val ACTION_REMOVE_LOCK = "io.github.danwangshi.eyeguard.ACTION_REMOVE_LOCK"
        const val ACTION_UPDATE_LUX = "io.github.danwangshi.eyeguard.ACTION_UPDATE_LUX"

        // 当前是否显示锁定界面
        var isShowing = false
            private set

        // 手电筒状态
        private var isFlashlightOn = false

        // 电话应用是否处于前台（用户主动点击了电话按钮）
        @Volatile
        var isPhoneAppActive = false

        // 电话应用激活的时间戳（用于超时兜底）
        private var phoneAppActiveTime = 0L

        // 电话应用超时时间（毫秒）
        private const val PHONE_APP_TIMEOUT_MS = 60_000L

        /**
         * 刷新电话应用活跃时间戳
         * 由无障碍服务每次检测到电话应用在前台时调用，避免超时误重置
         */
        fun refreshPhoneAppTime() {
            if (isPhoneAppActive) {
                phoneAppActiveTime = System.currentTimeMillis()
            }
        }

        /**
         * 电话应用离开前台，重置标记
         */
        fun clearPhoneAppActive() {
            isPhoneAppActive = false
            phoneAppActiveTime = 0L
        }

        /**
         * 检查电话应用标记是否已超时，超时则自动重置
         * 用于兜底：当无障碍服务未能检测到离开拨号器时
         */
        fun checkPhoneAppTimeout() {
            if (isPhoneAppActive && phoneAppActiveTime > 0 &&
                System.currentTimeMillis() - phoneAppActiveTime > PHONE_APP_TIMEOUT_MS) {
                AppLog.d(TAG, "电话应用标记超时，自动重置")
                clearPhoneAppActive()
            }
        }
    }

    private lateinit var windowManager: WindowManager
    // 缓存遮罩层 View，避免重复 inflate
    private var overlayView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var currentLux = 0f
    private var threshold = 100f

    private var cameraManager: CameraManager? = null
    private var cameraId: String? = null
    private var torchCallback: CameraManager.TorchCallback? = null

    // 语音播报 MediaPlayer（播放 res/raw 音频文件）
    private var mediaPlayer: MediaPlayer? = null
    // 音量临时调高标记，用于服务销毁时恢复原音量
    private var volumeWasBoosted = false
    private var previousVolume = -1
    private var previousMaxVolume = -1

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        AppLog.d(TAG, "onCreate")
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // 初始化相机（用于手电筒）
        try {
            cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cameraId = cameraManager?.cameraIdList?.find { id ->
                cameraManager?.getCameraCharacteristics(id)
                    ?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "相机初始化失败", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLog.d(TAG, "onStartCommand: ${intent?.action}")

        when (intent?.action) {
            ACTION_SHOW_LOCK -> {
                // 通话中或电话应用在前台时，不显示遮罩层
                if (LockStateHelper.shouldSkipLock()) {
                    AppLog.d(TAG, "通话中或电话应用活跃，跳过显示遮罩层")
                    return START_NOT_STICKY
                }

                // 如果遮罩层已存在，检查是否需要刷新
                if (isShowing) {
                    val forceRefresh = intent.getBooleanExtra("force_refresh", false)
                    if (forceRefresh) {
                        AppLog.d(TAG, "强制刷新遮罩层")
                        // 重新注册手电筒回调
                        unregisterTorchCallback()
                        registerTorchCallback()
                    } else {
                        AppLog.d(TAG, "覆盖层已存在，更新亮度显示")
                        updateLuxDisplay()
                    }
                    return START_NOT_STICKY
                }
                
                // 获取亮度和阈值（如果提供的话）
                currentLux = intent.getFloatExtra("lux", currentLux)
                threshold = intent.getFloatExtra("threshold", threshold)
                showLockOverlay()
            }
            ACTION_REMOVE_LOCK -> {
                removeLockOverlay()
            }
            ACTION_UPDATE_LUX -> {
                currentLux = intent.getFloatExtra("lux", 0f)
                updateLuxDisplay()
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 系统内存不足时释放缓存的遮罩层 View
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_MODERATE && !isShowing) {
            overlayView = null
            layoutParams = null
            AppLog.d(TAG, "系统内存紧张，释放缓存的遮罩层 View")
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {}
    override fun onLowMemory() {
        if (!isShowing) {
            overlayView = null
            layoutParams = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLog.d(TAG, "onDestroy")
        removeLockOverlay()
        unregisterTorchCallback()
        // 释放 MediaPlayer 资源
        mediaPlayer?.release()
        mediaPlayer = null
        // 恢复音量
        restoreOriginalVolume()
        AppLog.d(TAG, "资源已释放，音量已恢复")
    }

    /**
     * 显示锁定覆盖层
     */
    private fun showLockOverlay() {
        // 通话中或电话应用在前台时，不显示遮罩层
        if (LockStateHelper.shouldSkipLock()) {
            AppLog.d(TAG, "通话中或电话应用活跃，不显示遮罩层")
            return
        }

        // 检查悬浮窗权限
        if (!Settings.canDrawOverlays(this)) {
            AppLog.e(TAG, "没有悬浮窗权限")
            return
        }

        // 如果 View 已缓存，直接复用
        if (overlayView == null) {
            AppLog.d(TAG, "创建锁定覆盖层")
            overlayView = LayoutInflater.from(this).inflate(R.layout.lock_overlay, null)

            // 设置窗口参数
            layoutParams = WindowManager.LayoutParams().apply {
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = WindowManager.LayoutParams.MATCH_PARENT

                // 关键标志位
                type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
                }

                // 标志位：保持屏幕常亮，不拦截通知栏
                flags = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON

                // 适配刘海屏/挖孔屏
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }

                format = PixelFormat.TRANSLUCENT
                gravity = Gravity.TOP or Gravity.START
                
                // 设置动画
                windowAnimations = R.style.LockAnimation
            }

            // 设置触摸监听器 - 消费所有触摸事件，完全阻止下拉通知栏
            overlayView?.setOnTouchListener { _, _ -> true }

            // 设置按钮点击事件
            setupButtons()
        } else {
            AppLog.d(TAG, "复用已缓存的锁定覆盖层")
        }

        // 同步手电筒按钮UI状态
        overlayView?.let { view ->
            updateFlashlightButtonUI(view, isFlashlightOn)
        }

        // 添加视图
        try {
            windowManager.addView(overlayView, layoutParams)
            isShowing = true
            updateLuxDisplay()

            // 注册手电筒状态回调，同步外部状态变化
            registerTorchCallback()

            AppLog.d(TAG, "锁定覆盖层显示成功")
        } catch (e: Exception) {
            AppLog.e(TAG, "显示锁定覆盖层失败", e)
        }
    }

    /**
     * 移除锁定覆盖层
     * 注意：不销毁 View，仅从 WindowManager 移除，下次可复用
     */
    private fun removeLockOverlay() {
        if (!isShowing || overlayView == null) {
            return
        }

        AppLog.d(TAG, "移除锁定覆盖层")

        try {
            // 检查 View 是否仍附着在 WindowManager 上
            // 息屏后部分系统会自动移除 TYPE_APPLICATION_OVERLAY 窗口
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT &&
                !overlayView!!.isAttachedToWindow
            ) {
                AppLog.d(TAG, "锁定覆盖层已被系统移除，仅重置状态")
            } else {
                windowManager.removeView(overlayView)
                AppLog.d(TAG, "锁定覆盖层移除成功")
            }
            isShowing = false
            // 取消手电筒状态回调
            unregisterTorchCallback()
        } catch (e: Exception) {
            AppLog.e(TAG, "移除锁定覆盖层失败，强制重置状态", e)
            isShowing = false
        }
    }

    /**
     * 设置按钮点击事件
     */
    private fun setupButtons() {
        overlayView?.let { view ->
            // 手电筒按钮
            view.findViewById<LinearLayout>(R.id.btn_flashlight)?.setOnClickListener {
                AppLog.d(TAG, "手电筒按钮点击")
                val newState = !isFlashlightOn
                toggleFlashlight(newState)
                updateFlashlightButtonUI(view, newState)
            }

            // 紧急电话按钮
            view.findViewById<LinearLayout>(R.id.btn_emergency)?.setOnClickListener {
                AppLog.d(TAG, "紧急电话按钮点击")
                openEmergencyDialer()
            }

            // 中央卡片区域点击 → 语音播报（播放预置音频文件）
            view.findViewById<View>(R.id.card_container)?.setOnClickListener {
                AppLog.d(TAG, "卡片区域点击，触发语音播报")
                playLockAudio()
            }
        }
    }

    /**
     * 更新手电筒按钮UI状态
     */
    private fun updateFlashlightButtonUI(view: View, isOn: Boolean) {
        val btnFlashlight = view.findViewById<LinearLayout>(R.id.btn_flashlight)
        val tvIcon = btnFlashlight?.findViewById<TextView>(android.R.id.text1)
            ?: btnFlashlight?.getChildAt(0) as? TextView

        if (isOn) {
            btnFlashlight?.setBackgroundResource(R.drawable.bg_circle_yellow)
            tvIcon?.text = "💡"
        } else {
            btnFlashlight?.setBackgroundResource(R.drawable.bg_circle_white)
            tvIcon?.text = "🔦"
        }
    }

    /**
     * 更新亮度显示
     */
    private fun updateLuxDisplay() {
        overlayView?.let { view ->
            handler.post {
                // 更新当前亮度文字
                view.findViewById<TextView>(R.id.tv_current_lux)?.text =
                    getString(R.string.lock_current_lux, currentLux.toInt())

                // 更新目标亮度文字
                view.findViewById<TextView>(R.id.tv_target_lux)?.text =
                    getString(R.string.lock_target_lux, threshold.toInt())

                // 更新进度条
                view.findViewById<ProgressBar>(R.id.progress_lux)?.let { progress ->
                    val maxVal = threshold.toInt().coerceAtLeast(1)
                    progress.max = maxVal
                    progress.progress = currentLux.toInt().coerceAtMost(maxVal)
                }
            }
        }
    }

    /**
     * 注册手电筒状态回调，监听外部手电筒状态变化（如系统快捷开关）
     */
    private fun registerTorchCallback() {
        cameraManager?.let { cm ->
            val callback = object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    super.onTorchModeChanged(cameraId, enabled)
                    if (cameraId == this@LockOverlayService.cameraId) {
                        AppLog.d(TAG, "手电筒外部状态变化: $enabled")
                        isFlashlightOn = enabled
                        // 更新按钮UI
                        overlayView?.let { view ->
                            handler.post { updateFlashlightButtonUI(view, enabled) }
                        }
                    }
                }
            }
            torchCallback = callback
            cm.registerTorchCallback(callback, handler)
            AppLog.d(TAG, "注册手电筒状态回调")
        }
    }

    /**
     * 取消手电筒状态回调
     */
    private fun unregisterTorchCallback() {
        torchCallback?.let { callback ->
            cameraManager?.unregisterTorchCallback(callback)
            AppLog.d(TAG, "取消手电筒状态回调")
        }
        torchCallback = null
    }

    /**
     * 切换手电筒
     */
    private fun toggleFlashlight(on: Boolean) {
        try {
            // 检查相机权限，未授予时静默失败
            if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                AppLog.w(TAG, "没有CAMERA权限，无法切换手电筒")
                return
            }
            cameraManager?.let { cm ->
                cameraId?.let { id ->
                    cm.setTorchMode(id, on)
                    isFlashlightOn = on
                    AppLog.d(TAG, "手电筒状态: $on")
                }
            }
        } catch (e: CameraAccessException) {
            AppLog.e(TAG, "手电筒操作失败", e)
        }
    }

    /**
     * 恢复原始媒体音量（用于播报完成或服务销毁时）
     */
    private fun restoreOriginalVolume() {
        if (volumeWasBoosted && previousVolume >= 0) {
            try {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, previousVolume, 0)
                AppLog.d(TAG, "音量已恢复: $previousVolume")
            } catch (e: Exception) {
                AppLog.w(TAG, "音量恢复失败: ${e.message}")
            }
            volumeWasBoosted = false
            previousVolume = -1
            previousMaxVolume = -1
        }
    }

    /**
     * 播放锁定提示语音
     * 播报前临时调大媒体音量，播报完成后恢复原音量
     */
    private fun playLockAudio() {
        try {
            if (mediaPlayer == null) {
                mediaPlayer = MediaPlayer.create(this, R.raw.lock_tts)
            }
            mediaPlayer?.let { mp ->
                // 获取 AudioManager
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

                // 临时将音量调到 50%，保证能听到又不吓人
                val targetVolume = (maxVolume * 0.5f).toInt().coerceAtLeast(1)
                if (currentVolume < targetVolume) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
                    volumeWasBoosted = true
                    previousVolume = currentVolume
                    previousMaxVolume = maxVolume
                    AppLog.d(TAG, "临时调大音量: $currentVolume → $targetVolume (最大 $maxVolume)")

                    // 播报完成后恢复原音量
                    mp.setOnCompletionListener { mp2 ->
                        restoreOriginalVolume()
                        mp2.setOnCompletionListener(null)
                    }
                }

                if (mp.isPlaying) {
                    mp.seekTo(0)  // 如果正在播放，从头重播
                } else {
                    mp.start()
                }
                AppLog.d(TAG, "语音播报已播放")
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "语音播报失败", e)
        }
    }

    /**
     * 打开紧急拨号器
     */
    private fun openEmergencyDialer() {
        try {
            // 标记电话应用激活，阻止遮罩层重新显示
            isPhoneAppActive = true
            phoneAppActiveTime = System.currentTimeMillis()

            // 完全移除遮罩，不耽误使用电话
            removeLockOverlay()

            // 打开拨号盘
            val intent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            AppLog.d(TAG, "打开紧急拨号器，已移除覆盖层，电话应用激活")

        } catch (e: Exception) {
            isPhoneAppActive = false
            AppLog.e(TAG, "打开拨号器失败", e)
        }
    }
}
