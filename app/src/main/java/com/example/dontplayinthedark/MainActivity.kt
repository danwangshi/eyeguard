package com.example.dontplayinthedark

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import android.widget.EditText

/**
 * 主界面 - 包含权限引导和护眼配置
 */
class MainActivity : AppCompatActivity(), SensorEventListener {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_OVERLAY_PERMISSION = 1001
        private const val REQUEST_ACCESSIBILITY_PERMISSION = 1002
    }

    // 布局容器
    private lateinit var layoutPermissions: LinearLayout
    private lateinit var layoutMainSettings: View

    // 权限引导组件
    private lateinit var ivOverlayCheck: ImageView
    private lateinit var ivAccessibilityCheck: ImageView
    private lateinit var btnGrantOverlay: Button
    private lateinit var btnGrantAccessibility: Button
    private lateinit var btnContinue: Button

    // 主设置组件
    private lateinit var tvThresholdValue: TextView
    private lateinit var seekbarThreshold: SeekBar
    private lateinit var btnToggle: Button
    private lateinit var btnStableSettings: Button
    private lateinit var switchDebounce: SwitchCompat

    private lateinit var prefs: android.content.SharedPreferences

    private var lightMonitorService: LightMonitorService? = null
    private var isBound = false

    // 光线传感器
    private var sensorManager: SensorManager? = null
    private var lightSensor: Sensor? = null
    private lateinit var tvRealtimeLux: TextView

    // 服务连接
    private val serviceConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: IBinder?) {
            val binder = service as LightMonitorService.LocalBinder
            lightMonitorService = binder.getService()
            isBound = true
            AppLog.d(TAG, "Service connected")
        }

        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            lightMonitorService = null
            isBound = false
            AppLog.d(TAG, "Service disconnected")
        }
    }

    // 广播接收器
    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                LightMonitorService.ACTION_START -> {
                    updateProtectionUI(true)
                }
                LightMonitorService.ACTION_STOP -> {
                    updateProtectionUI(false)
                }
            }
        }
    }



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)

        initViews()
        setupListeners()
        loadSettings()

        // 注册广播接收器
        val filter = IntentFilter().apply {
            addAction(LightMonitorService.ACTION_START)
            addAction(LightMonitorService.ACTION_STOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(serviceReceiver, filter)
        }

        // 初始状态检查
        checkPermissions()
        updateProtectionUI(LightMonitorService.isRunning)

        if (LightMonitorService.isRunning) {
            bindMonitorService()
        }
    }

    override fun onResume() {
        super.onResume()
        lightSensor?.let { sensor ->
            sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_LIGHT) {
            val lux = event.values[0].toInt()
            tvRealtimeLux.text = lux.toString()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // 不需要处理
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(serviceReceiver)
        unbindMonitorService()
    }

    private fun initViews() {
        // 布局容器
        layoutPermissions = findViewById(R.id.layout_permissions)
        layoutMainSettings = findViewById(R.id.layout_main_settings)

        // 权限引导
        ivOverlayCheck = findViewById(R.id.iv_overlay_check)
        ivAccessibilityCheck = findViewById(R.id.iv_accessibility_check)
        btnGrantOverlay = findViewById(R.id.btn_grant_overlay)
        btnGrantAccessibility = findViewById(R.id.btn_grant_accessibility)
        btnContinue = findViewById(R.id.btn_continue)

        // 主设置
        tvThresholdValue = findViewById(R.id.tv_threshold_value)
        seekbarThreshold = findViewById(R.id.seekbar_threshold)
        tvRealtimeLux = findViewById(R.id.tv_realtime_lux)
        btnToggle = findViewById(R.id.btn_toggle)
        btnStableSettings = findViewById(R.id.btn_stable_settings)
        switchDebounce = findViewById(R.id.switch_debounce)

        // 初始化光线传感器
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        lightSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)
    }

    private fun setupListeners() {
        // 权限引导按钮
        btnGrantOverlay.setOnClickListener { requestOverlayPermission() }
        btnGrantAccessibility.setOnClickListener { requestAccessibilityPermission() }
        btnContinue.setOnClickListener {
            layoutPermissions.visibility = View.GONE
            layoutMainSettings.visibility = View.VISIBLE
        }

        // 阈值数值点击输入
        tvThresholdValue.setOnClickListener {
            showThresholdInputDialog()
        }

        // 阈值滑块调节
        seekbarThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvThresholdValue.text = progress.toString()
                if (fromUser) {
                    AppLog.d(TAG, "用户调整阈值: $progress")
                    prefs.edit().putInt("threshold_value", progress).apply()
                    // 如果服务正在运行，同步更新服务的阈值
                    lightMonitorService?.updateThreshold(progress.toFloat())
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 运行/停止切换按钮
        btnToggle.setOnClickListener {
            if (LightMonitorService.isRunning) {
                stopProtection()
            } else {
                startProtection()
            }
        }

        // 稳定运行设置指引按钮
        btnStableSettings.setOnClickListener {
            val intent = Intent(this, StableSettingsActivity::class.java)
            startActivity(intent)
        }

        // 防抖延迟触发开关
        switchDebounce.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("debounce_enabled", isChecked).apply()
            // 同步到服务（如果正在运行）
            lightMonitorService?.setDebounceEnabled(isChecked)
            AppLog.d(TAG, "防抖开关切换: $isChecked")
        }
    }

    /**
     * 显示阈值输入对话框
     */
    private fun showThresholdInputDialog() {
        val currentValue = prefs.getInt("threshold_value", 0)
        val editText = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(currentValue.toString())
            setSelection(text.length)
            hint = "请输入 0-500 之间的数值"
        }

        AlertDialog.Builder(this)
            .setTitle("手动设置阈值")
            .setView(editText)
            .setPositiveButton("确定") { _, _ ->
                val input = editText.text.toString().toIntOrNull()
                if (input != null && input in 0..500) {
                    // 更新 UI
                    tvThresholdValue.text = input.toString()
                    seekbarThreshold.progress = input
                    // 保存设置
                    prefs.edit().putInt("threshold_value", input).apply()
                    // 同步到服务
                    lightMonitorService?.updateThreshold(input.toFloat())
                    AppLog.d(TAG, "用户手动输入阈值: $input")
                    Toast.makeText(this, "阈值已设置为 $input lux", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "请输入 0-500 之间的有效数值", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun loadSettings() {
        val thresholdValue = prefs.getInt("threshold_value", 0)
        val debounceEnabled = prefs.getBoolean("debounce_enabled", false)
        AppLog.d(TAG, "加载设置: threshold_value=$thresholdValue, debounce_enabled=$debounceEnabled")
        seekbarThreshold.progress = thresholdValue
        tvThresholdValue.text = thresholdValue.toString()
        switchDebounce.isChecked = debounceEnabled
    }

    /**
     * 实时检测权限并更新UI
     */
    private fun checkPermissions() {
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasAccessibility = isAccessibilityServiceEnabled()

        // 更新权限引导页面的状态
        updatePermissionStep(ivOverlayCheck, btnGrantOverlay, hasOverlay)
        updatePermissionStep(ivAccessibilityCheck, btnGrantAccessibility, hasAccessibility)

        btnContinue.isEnabled = hasOverlay && hasAccessibility

        // 如果权限不满足且当前不在设置界面，则显示引导页
        if (!(hasOverlay && hasAccessibility)) {
            // 如果服务没跑，说明是新用户或权限被关了，显示引导
            if (!LightMonitorService.isRunning) {
                layoutPermissions.visibility = View.VISIBLE
                layoutMainSettings.visibility = View.GONE
            }
        } else {
            // 权限满足，如果是从引导页过来的，或者初始检查，确保主设置可见
            if (layoutPermissions.visibility == View.VISIBLE) {
                layoutPermissions.visibility = View.GONE
                layoutMainSettings.visibility = View.VISIBLE
            } else if (layoutMainSettings.visibility == View.GONE) {
                // 初始启动且权限满足的情况
                layoutMainSettings.visibility = View.VISIBLE
            }
        }

        // 更新主设置页面的权限状态显示 (此处 ID 在 XML 中不存在，暂时移除或修复)
        // updatePermissionStatusText(tvOverlayStatus, btnOverlaySettings, hasOverlay)
        // updatePermissionStatusText(tvAccessibilityStatus, btnAccessibilitySettings, hasAccessibility)
    }

    private fun updatePermissionStep(ivCheck: ImageView, btn: Button, granted: Boolean) {
        if (granted) {
            ivCheck.setImageResource(android.R.drawable.checkbox_on_background)
            btn.text = "已开启"
            btn.isEnabled = false
        } else {
            ivCheck.setImageResource(android.R.drawable.checkbox_off_background)
            btn.text = "去开启"
            btn.isEnabled = true
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val accessibilityEnabled = try {
            Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED)
        } catch (e: Settings.SettingNotFoundException) { 0 }

        if (accessibilityEnabled == 1) {
            val services = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            return services?.contains(packageName) == true
        }
        return false
    }

    private fun requestOverlayPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
    }

    private fun requestAccessibilityPermission() {
        AlertDialog.Builder(this)
            .setTitle("需要无障碍权限")
            .setMessage("此权限用于检测窗口变化，确保在暗光环境下能有效锁定手机。请在设置中找到\"不要关灯玩手机\"并开启。")
            .setPositiveButton("去开启") { _, _ ->
                startActivityForResult(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), REQUEST_ACCESSIBILITY_PERMISSION)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startProtection() {
        if (!Settings.canDrawOverlays(this) || !isAccessibilityServiceEnabled()) {
            checkPermissions()
            return
        }

        val intent = Intent(this, LightMonitorService::class.java).apply { action = LightMonitorService.ACTION_START }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)

        bindMonitorService()
        updateProtectionUI(true)
        Toast.makeText(this, "护眼保护已启动", Toast.LENGTH_SHORT).show()
    }

    private fun stopProtection() {
        val intent = Intent(this, LightMonitorService::class.java).apply { action = LightMonitorService.ACTION_STOP }
        startService(intent)
        unbindMonitorService()
        updateProtectionUI(false)
        Toast.makeText(this, "护眼保护已停止", Toast.LENGTH_SHORT).show()
    }

    private fun updateProtectionUI(isRunning: Boolean) {
        if (isRunning) {
            btnToggle.text = getString(R.string.btn_stop)
            btnToggle.backgroundTintList = getColorStateList(R.color.accent)
        } else {
            btnToggle.text = getString(R.string.btn_start)
            btnToggle.backgroundTintList = getColorStateList(R.color.button_success)
        }
    }

    private fun bindMonitorService() {
        bindService(Intent(this, LightMonitorService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun unbindMonitorService() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        checkPermissions()
    }
}
