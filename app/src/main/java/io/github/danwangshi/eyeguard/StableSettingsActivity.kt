package io.github.danwangshi.eyeguard

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 稳定运行设置指引页面
 */
class StableSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stable_settings)

        // 支持返回按钮
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "稳定运行设置"

        // 设置各个设置项的点击事件（跳转到对应设置页面）
        setupClickableItems()
    }

    private fun setupClickableItems() {
        // 1. 允许后台运行 - 跳转到应用详情
        findViewById<View>(R.id.card_background_run)?.setOnClickListener {
            AppLog.d("StableSettings", "点击了：允许后台运行")
            openAppDetails()
        }
        
        // 2. 忽略电池优化 - 跳转到电池优化白名单
        findViewById<View>(R.id.card_battery_optimization)?.setOnClickListener {
            AppLog.d("StableSettings", "点击了：忽略电池优化")
            requestIgnoreBatteryOptimizations()
        }
        
        // 3. 后台管理 - 跳转到启动管理（部分厂商支持）
        findViewById<View>(R.id.card_background_management)?.setOnClickListener {
            AppLog.d("StableSettings", "点击了：后台管理")
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            } catch (e: Exception) {
                AppLog.e("StableSettings", "无法打开启动管理: ${e.message}")
                Toast.makeText(this, "无法打开启动管理，请手动进入设置 → 应用 → 启动管理", Toast.LENGTH_LONG).show()
            }
        }
        
        // 4. 应用后台锁定 - 提示用户操作
        findViewById<View>(R.id.card_app_lock)?.setOnClickListener {
            AppLog.d("StableSettings", "点击了：应用后台锁定")
            Toast.makeText(
                this,
                "请从最近任务中长按本应用卡片，点击锁图标进行锁定",
                Toast.LENGTH_LONG
            ).show()
        }
        
        // 5. 关闭省电模式 - 跳转到电池设置
        findViewById<View>(R.id.card_battery_saver)?.setOnClickListener {
            AppLog.d("StableSettings", "点击了：关闭省电模式")
            try {
                val intent = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                startActivity(intent)
            } catch (e: Exception) {
                AppLog.e("StableSettings", "无法打开电池设置: ${e.message}")
                Toast.makeText(this, "无法打开电池设置，请手动进入设置 → 电池", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * 打开应用详情页面
     */
    private fun openAppDetails() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开应用设置，请手动进入设置 → 应用", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 请求忽略电池优化
     */
    private fun requestIgnoreBatteryOptimizations() {
        AppLog.d("StableSettings", "开始请求忽略电池优化")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                // 尝试直接打开电池优化列表页面
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                AppLog.d("StableSettings", "跳转到电池优化列表页面")
                startActivity(intent)
            } catch (e: Exception) {
                AppLog.e("StableSettings", "跳转失败，尝试其他方式: ${e.message}")
                // 如果失败，尝试请求忽略电池优化
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.fromParts("package", packageName, null)
                    }
                    startActivity(intent)
                } catch (e2: Exception) {
                    AppLog.e("StableSettings", "再次失败，打开应用详情: ${e2.message}")
                    // 最后尝试打开应用详情
                    openAppDetails()
                    Toast.makeText(
                        this,
                        "请在应用详情中找到「电池」选项，设置为「无限制」或「不优化」",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        } else {
            AppLog.d("StableSettings", "Android版本低于M，打开应用详情")
            openAppDetails()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
