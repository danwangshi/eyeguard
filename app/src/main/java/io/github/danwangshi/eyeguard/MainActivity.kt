package io.github.danwangshi.eyeguard

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.widget.EditText

import android.Manifest
import android.content.pm.PackageManager

/**
 * 主界面 - 包含权限引导和护眼配置
 */
class MainActivity : AppCompatActivity(), SensorEventListener {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_OVERLAY_PERMISSION = 1001
        private const val REQUEST_ACCESSIBILITY_PERMISSION = 1002
        private const val REQUEST_POST_NOTIFICATIONS = 1004
        private const val REQUEST_CAMERA = 1005
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
    private lateinit var btnSettings: ImageButton

    private lateinit var prefs: android.content.SharedPreferences

    // 光线传感器（独立于 LightMonitorService，用于 UI 实时显示）
    private var sensorManager: SensorManager? = null
    private var lightSensor: Sensor? = null
    private lateinit var tvRealtimeLux: TextView
    // 传感器诊断 UI
    private lateinit var tvRawLux: TextView
    private lateinit var tvSmoothedLux: TextView
    private lateinit var tvDiagnosticHint: TextView
    // 本地 EMA 平滑计算（独立于服务，仅用于诊断展示）
    private var localSmoothedLux = 0f
    private var localHasSmoothed = false
    private val SMOOTHING_ALPHA = 0.5f
    // 连续零值计数，用于检测传感器遮挡
    private var consecutiveZeroCount = 0
    private val ZERO_THRESHOLD = 10  // 连续 10 次读到 0 视为遮挡



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 初始化文件日志（如果已启用），确保 MainActivity 打开时也有日志
        AppLog.init(this)

        prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)

        initViews()
        setupListeners()
        loadSettings()

        // 应用启动时自动启动光线监测服务
        autoStartService()

        // 检查权限状态
        checkPermissions()
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
            val rawLux = event.values[0]
            val rawLuxInt = rawLux.toInt()

            // 更新主显示
            tvRealtimeLux.text = rawLuxInt.toString()

            // === 诊断面板计算 ===

            // 本地 EMA 平滑（与 LightMonitorService 算法一致）
            if (!localHasSmoothed) {
                localHasSmoothed = true
                localSmoothedLux = rawLux
            } else {
                localSmoothedLux = SMOOTHING_ALPHA * rawLux + (1 - SMOOTHING_ALPHA) * localSmoothedLux
            }

            // 更新诊断 UI
            tvRawLux.text = rawLuxInt.toString()
            tvSmoothedLux.text = String.format("%.0f", localSmoothedLux)

            // 遮挡检测：连续零值计数
            if (rawLuxInt == 0) {
                consecutiveZeroCount++
            } else {
                consecutiveZeroCount = 0
            }

            // 更新传感器状态
            updateSensorStatus(rawLuxInt)
        }
    }

    /**
     * 更新传感器状态诊断（显示在 ActionBar 标题栏）
     */
    private fun updateSensorStatus(rawLux: Int) {
        val statusText: String
        val hintVisible: Boolean

        if (consecutiveZeroCount >= ZERO_THRESHOLD && rawLux <= 4) {
            statusText = "❌ 传感器被遮挡"
            hintVisible = true
        } else if (consecutiveZeroCount >= ZERO_THRESHOLD / 2 && rawLux == 0) {
            statusText = "⚠️ 传感器疑似遮挡"
            hintVisible = true
        } else if (rawLux > 0) {
            statusText = "✅ ${rawLux} lux"
            hintVisible = false
        } else {
            statusText = "检测中..."
            hintVisible = false
        }

        supportActionBar?.subtitle = statusText
        tvDiagnosticHint.visibility = if (hintVisible) View.VISIBLE else View.GONE
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // 不需要处理
    }

    override fun onDestroy() {
        super.onDestroy()
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
        btnSettings = findViewById(R.id.btn_settings)

        // 传感器诊断
        tvRawLux = findViewById(R.id.tv_raw_lux)
        tvSmoothedLux = findViewById(R.id.tv_smoothed_lux)
        tvDiagnosticHint = findViewById(R.id.tv_diagnostic_hint)

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
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 右上角设置按钮 → 设置主页
        btnSettings.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
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
        AppLog.d(TAG, "加载设置: threshold_value=$thresholdValue")
        seekbarThreshold.progress = thresholdValue
        tvThresholdValue.text = thresholdValue.toString()
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

        // 如果权限不满足则显示引导页
        if (!(hasOverlay && hasAccessibility)) {
            layoutPermissions.visibility = View.VISIBLE
            layoutMainSettings.visibility = View.GONE
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

    /**
     * 自动启动光线监测前台服务
     * 应用只要存活就自动运行主体功能
     */
    private fun autoStartService() {
        val intent = Intent(this, LightMonitorService::class.java).apply {
            action = LightMonitorService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        AppLog.d(TAG, "光线监测服务已自动启动")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_POST_NOTIFICATIONS -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    AppLog.d(TAG, "POST_NOTIFICATIONS 权限已授予")
                } else {
                    AppLog.d(TAG, "POST_NOTIFICATIONS 权限被拒绝，通知将不显示")
                    Toast.makeText(this, "未授予通知权限，前台服务通知将不可见", Toast.LENGTH_LONG).show()
                }
            }
            REQUEST_CAMERA -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    AppLog.d(TAG, "CAMERA 权限已授予")
                } else {
                    AppLog.d(TAG, "CAMERA 权限被拒绝，手电筒功能不可用")
                    Toast.makeText(this, "未授予相机权限，遮罩层手电筒功能无法使用", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        checkPermissions()
    }
}
