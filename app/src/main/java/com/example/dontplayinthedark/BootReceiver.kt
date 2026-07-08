package com.example.dontplayinthedark

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 开机广播接收器
 * 设备启动后自动启动光线监测服务
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "收到开机广播")

            // 检查是否需要自动启动
            val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val autoStart = prefs.getBoolean("auto_start", false)

            if (autoStart) {
                Log.d(TAG, "自动启动光线监测服务")

                val serviceIntent = Intent(context, LightMonitorService::class.java).apply {
                    action = LightMonitorService.ACTION_START
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}
