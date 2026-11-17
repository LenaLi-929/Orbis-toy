package com.floatingtask

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 悬浮任务 · 主界面
 * 1. 输入任务描述 + 时长 → 启动悬浮窗
 * 2. 首次使用引导授予「显示在其他应用上层」权限与电池白名单
 */
class MainActivity : Activity() {

    private fun startBridge() {
        val i = Intent(this, BridgeService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = this
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
        }
        box.addView(TextView(ctx).apply {
            text = "悬浮任务"
            textSize = 28f
        })
        box.addView(TextView(ctx).apply {
            text = "输入 AI 给你布置的任务，点击开始后屏幕右上角出现倒计时悬浮窗，\n拍照发送给 AI 后自动消失，超时也会自动消失。"
            textSize = 13f
            setPadding(0, 16, 0, 32)
        })

        val taskInput = EditText(ctx).apply {
            hint = "任务描述，如：倒垃圾"
            setSingleLine(false)
            minLines = 2
        }
        box.addView(taskInput)

        val minuteInput = EditText(ctx).apply {
            hint = "时限（分钟），如 30"
            setText("30")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        box.addView(minuteInput)

        val start = Button(ctx).apply {
            text = "▶ 开始任务"
            setOnClickListener {
                val task = taskInput.text.toString().trim()
                val minutes = minuteInput.text.toString().toIntOrNull() ?: 0
                if (task.isEmpty() || minutes <= 0) {
                    Toast.makeText(ctx, "请填写任务和时限", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (!Settings.canDrawOverlays(ctx)) {
                    Toast.makeText(ctx, "请先授予「显示在其他应用上层」权限", Toast.LENGTH_LONG).show()
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")))
                    return@setOnClickListener
                }
                startBridge()
                val i = Intent(ctx, TaskWindowService::class.java)
                    .putExtra(TaskWindowService.EXTRA_TASK, task)
                    .putExtra(TaskWindowService.EXTRA_MINUTES, minutes)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
                Toast.makeText(ctx, "悬浮窗已启动", Toast.LENGTH_SHORT).show()
                moveTaskToBack(true)
            }
        }
        val bridgeBtn = Button(ctx).apply {
            text = "🔗 启动 AI 桥接（一次性，保持常驻）"
            setOnClickListener { startBridge(); Toast.makeText(ctx, "桥接已启动 :8791", Toast.LENGTH_SHORT).show() }
        }
        box.addView(bridgeBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 12 })

        box.addView(TextView(ctx).apply {
            text = "Orbis MCP 配置：Streamable HTTP → http://127.0.0.1:8791/mcp\n配置好后 AI 可用 create_task 直接给你弹任务窗"
            textSize = 12f
            setPadding(0, 24, 0, 0)
        })

        box.addView(start, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 32 })

        box.addView(TextView(ctx).apply {
            text = "小提示\n· 建议在系统设置里允许本 App 自启动和后台运行（电池白名单），防止被 ColorOS 冻结\n· 任务进行中请勿强杀本 App，否则悬浮窗会消失"
            textSize = 11f
            setPadding(0, 40, 0, 0)
        })
        setContentView(box)

        // 可选：引导加电池白名单（未加过时拉起一次）
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            @Suppress("BatteryLife")
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")))
        }
    }
}
