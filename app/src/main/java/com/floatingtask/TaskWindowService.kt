package com.floatingtask

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * 悬浮任务窗：常驻悬浮窗，显示任务 + 倒计时 + 拍照按钮。
 * 消失条件只有两个：
 *   1. 用户完成「拍照 → 编辑 → 分享给 AI」全流程（SendActivity 确认后调 dismiss）
 *   2. 倒计时归零（超时自动消失）
 * 除此之外不可关闭：无关闭按钮，Service 重启自愈（START_STICKY）。
 */
class TaskWindowService : Service() {

    companion object {
        const val EXTRA_TASK = "task"
        const val EXTRA_MINUTES = "minutes"
        const val ACTION_DISMISS = "com.floatingtask.DISMISS"
        private const val CHANNEL_ID = "task_window"
        private const val NOTIF_ID = 2

        /** 供 SendActivity 在分享成功后调用：关窗停服务 */
        fun dismissWindow(ctx: android.content.Context) {
            ctx.startService(Intent(ctx, TaskWindowService::class.java).setAction(ACTION_DISMISS))
        }
    }

    private var windowManager: WindowManager? = null
    private var rootView: LinearLayout? = null
    private var countdownView: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var deadline = 0L

    private val tick = object : Runnable {
        override fun run() {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) {
                dismiss() // 超时自动消失
                return
            }
            val m = left / 60000
            val s = (left % 60000) / 1000
            countdownView?.text = String.format(Locale.CHINA, "⏱ 剩 %02d:%02d", m, s)
            countdownView?.setTextColor(if (left < 5 * 60_000) 0xFFFF4444.toInt() else 0xFFEEEEEE.toInt())
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISMISS) {
            dismiss()
            return START_NOT_STICKY
        }
        val task = intent?.getStringExtra(EXTRA_TASK) ?: return START_NOT_STICKY
        val minutes = intent.getIntExtra(EXTRA_MINUTES, 30)
        deadline = System.currentTimeMillis() + minutes * 60_000L
        TaskStore.taskText = task
        TaskStore.deadline = deadline

        startForeground(NOTIF_ID, buildNotification(task))
        showWindow(task)
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_STICKY
    }

    private fun showWindow(task: String) {
        if (rootView != null) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val ctx = this
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF2101018.toInt())
            setPadding(36, 28, 36, 28)
        }
        val title = TextView(ctx).apply {
            text = "📋 $task"
            setTextColor(Color.WHITE)
            textSize = 16f
        }
        val time = TextView(ctx).apply {
            textSize = 14f
            setPadding(0, 12, 0, 12)
        }
        countdownView = time
        val btn = Button(ctx).apply {
            text = "📷 拍照完成"
            setOnClickListener {
                ctx.startActivity(
                    Intent(ctx, SendActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
        box.addView(title)
        box.addView(time)
        box.addView(btn)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 24; y = 120
        }
        windowManager?.addView(box, params)
        rootView = box
    }

    /** 发送成功或超时 → 关窗停服务 */
    private fun dismiss() {
        handler.removeCallbacks(tick)
        rootView?.let { runCatching { windowManager?.removeView(it) } }
        rootView = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(task: String): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "悬浮任务", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("悬浮任务进行中")
            .setContentText(task)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }
}
