package io.github.danwangshi.eyeguard

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.widget.NumberPicker
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

/**
 * 设置主页面
 * 包含定时启用、稳定运行设置指引、关于等配置项入口
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SettingsActivity"
        private const val PREFS_NAME = "settings"
        private const val KEY_SCHEDULE_ENABLED = "schedule_enabled"
        private const val KEY_SCHEDULE_START_HOUR = "schedule_start_hour"
        private const val KEY_SCHEDULE_START_MINUTE = "schedule_start_minute"
        private const val KEY_SCHEDULE_END_HOUR = "schedule_end_hour"
        private const val KEY_SCHEDULE_END_MINUTE = "schedule_end_minute"
    }

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var switchSchedule: SwitchCompat
    private lateinit var switchDebounce: SwitchCompat
    private lateinit var layoutScheduleTimes: View
    private lateinit var tvStartTime: TextView
    private lateinit var tvEndTime: TextView
    private var lightMonitorService: LightMonitorService? = null
    private var isBound = false

    private val serviceConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
            val binder = service as LightMonitorService.LocalBinder
            lightMonitorService = binder.getService()
            isBound = true
        }
        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            lightMonitorService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        // 支持返回按钮
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "设置"

        // 绑定服务以同步设置（服务由主界面自动启动，始终处于运行状态）
        try {
            bindService(Intent(this, LightMonitorService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            AppLog.w(TAG, "绑定服务失败: ${e.message}")
        }

        initViews()
        loadScheduleSettings()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    private fun initViews() {
        // 稳定运行设置指引
        findViewById<androidx.cardview.widget.CardView>(R.id.card_stable_settings).setOnClickListener {
            AppLog.d(TAG, "点击：稳定运行设置指引")
            val intent = Intent(this, StableSettingsActivity::class.java)
            startActivity(intent)
        }

        // === 定时启用 ===
        switchSchedule = findViewById(R.id.switch_schedule)
        layoutScheduleTimes = findViewById(R.id.layout_schedule_times)
        tvStartTime = findViewById(R.id.tv_start_time)
        tvEndTime = findViewById(R.id.tv_end_time)

        switchSchedule.setOnCheckedChangeListener { _, isChecked ->
            AppLog.d(TAG, "定时启用: $isChecked")
            prefs.edit().putBoolean(KEY_SCHEDULE_ENABLED, isChecked).apply()
            layoutScheduleTimes.visibility = if (isChecked) View.VISIBLE else View.GONE
            notifyScheduleChanged()
        }

        findViewById<View>(R.id.row_start_time).setOnClickListener {
            showTimePicker(true)
        }
        findViewById<View>(R.id.row_end_time).setOnClickListener {
            showTimePicker(false)
        }

        // === 防抖延迟触发 ===
        switchDebounce = findViewById(R.id.switch_debounce)

        // === 关于 ===
        findViewById<androidx.cardview.widget.CardView>(R.id.card_about).setOnClickListener {
            AppLog.d(TAG, "点击：关于")
            showAboutDialog()
        }
    }

    private fun loadScheduleSettings() {
        val enabled = prefs.getBoolean(KEY_SCHEDULE_ENABLED, false)
        val startHour = prefs.getInt(KEY_SCHEDULE_START_HOUR, 22)
        val startMinute = prefs.getInt(KEY_SCHEDULE_START_MINUTE, 0)
        val endHour = prefs.getInt(KEY_SCHEDULE_END_HOUR, 6)
        val endMinute = prefs.getInt(KEY_SCHEDULE_END_MINUTE, 0)

        // 设置 Switch 前先移除监听器，避免触发 onChangeListener
        switchSchedule.setOnCheckedChangeListener(null)
        switchSchedule.isChecked = enabled
        layoutScheduleTimes.visibility = if (enabled) View.VISIBLE else View.GONE
        tvStartTime.text = String.format("%02d:%02d", startHour, startMinute)
        tvEndTime.text = String.format("%02d:%02d", endHour, endMinute)

        // 加载防抖设置
        val debounceEnabled = prefs.getBoolean("debounce_enabled", false)
        switchDebounce.setOnCheckedChangeListener(null)
        switchDebounce.isChecked = debounceEnabled

        // 重新设置监听器
        switchSchedule.setOnCheckedChangeListener { _, isChecked ->
            AppLog.d(TAG, "定时启用: $isChecked")
            prefs.edit().putBoolean(KEY_SCHEDULE_ENABLED, isChecked).apply()
            layoutScheduleTimes.visibility = if (isChecked) View.VISIBLE else View.GONE
            notifyScheduleChanged()
        }
        switchDebounce.setOnCheckedChangeListener { _, isChecked ->
            AppLog.d(TAG, "防抖开关切换: $isChecked")
            prefs.edit().putBoolean("debounce_enabled", isChecked).apply()
            lightMonitorService?.setDebounceEnabled(isChecked)
        }
    }

    private fun showTimePicker(isStart: Boolean) {
        val currentHour = prefs.getInt(
            if (isStart) KEY_SCHEDULE_START_HOUR else KEY_SCHEDULE_END_HOUR,
            if (isStart) 22 else 6
        )
        val currentMinute = prefs.getInt(
            if (isStart) KEY_SCHEDULE_START_MINUTE else KEY_SCHEDULE_END_MINUTE,
            0
        )

        // 使用 NumberPicker 实现上下滑动选择
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_time_picker, null)
        val hourPicker = view.findViewById<NumberPicker>(R.id.number_picker_hour)
        val minutePicker = view.findViewById<NumberPicker>(R.id.number_picker_minute)

        // 配置小时选择器 (0-23)
        hourPicker.minValue = 0
        hourPicker.maxValue = 23
        hourPicker.value = currentHour
        hourPicker.wrapSelectorWheel = false

        // 配置分钟选择器 (00-59)
        minutePicker.minValue = 0
        minutePicker.maxValue = 59
        minutePicker.value = currentMinute
        minutePicker.wrapSelectorWheel = false

        AlertDialog.Builder(this)
            .setTitle(if (isStart) "选择开始时间" else "选择结束时间")
            .setView(view)
            .setPositiveButton("确定") { _, _ ->
                val selectedHour = hourPicker.value
                val selectedMinute = minutePicker.value
                val keyHour = if (isStart) KEY_SCHEDULE_START_HOUR else KEY_SCHEDULE_END_HOUR
                val keyMinute = if (isStart) KEY_SCHEDULE_START_MINUTE else KEY_SCHEDULE_END_MINUTE
                prefs.edit()
                    .putInt(keyHour, selectedHour)
                    .putInt(keyMinute, selectedMinute)
                    .apply()
                if (isStart) {
                    tvStartTime.text = String.format("%02d:%02d", selectedHour, selectedMinute)
                } else {
                    tvEndTime.text = String.format("%02d:%02d", selectedHour, selectedMinute)
                }
                AppLog.d(TAG, "定时时间${if (isStart) "开始" else "结束"}: $selectedHour:$selectedMinute")
                notifyScheduleChanged()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 通知 LightMonitorService 定时设置已变更
     * 通过已绑定的服务直接调用 checkLockState 使新配置生效
     */
    private fun notifyScheduleChanged() {
        lightMonitorService?.let { service ->
            AppLog.d(TAG, "定时设置已变更，触发重新检查")
            service.triggerRecheck()
        }
    }

    /**
     * 显示关于对话框
     */
    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle("不要关灯玩手机")
            .setMessage(buildAboutContent())
            .setPositiveButton("确定", null)
            .setNeutralButton("GitHub") { _, _ ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/danwangshi/eyeguard"))
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun buildAboutContent(): String {
        return """
应用版本：1.3.0 (Build 5)
开发者：danwangshimoluo
开源协议：MIT License

通过光线传感器监测环境亮度，
低于设定阈值时自动锁定屏幕，
提醒您开灯后再使用手机。
        """.trimIndent()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
