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
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * 悬浮任务窗：常驻悬浮窗，显示任务 + 倒计时 + 拍照按钮。
 * 消失条件只有两个：
 *   1. AI 调 dismiss_task（验收通过或用户放弃）→ 关窗
 *   2. 倒计时归零（超时自动消失）
 * 用户点【完成】提交照片后窗口【不会消失】，转为"等待 AI 验收"状态；
 * AI 判定不合格调 reject_task → 显示原因 + 时限重置 + 重新拍摄按钮。
 * 除此之外不可关闭：无关闭按钮，Service 重启自愈（START_STICKY）。
 */
class TaskWindowService : Service() {

    companion object {
        const val EXTRA_TASK = "task"
        const val EXTRA_MINUTES = "minutes"
        const val EXTRA_REASON = "reason"
        const val ACTION_DISMISS = "com.floatingtask.DISMISS"
        const val ACTION_SUBMITTED = "com.floatingtask.SUBMITTED"
        const val ACTION_REJECT = "com.floatingtask.REJECT"
        private const val CHANNEL_ID = "task_window"
        private const val NOTIF_ID = 2

        /** 供 AI 判定通过/取消后调用：关窗停服务 */
        fun dismissWindow(ctx: android.content.Context) {
            ctx.startService(Intent(ctx, TaskWindowService::class.java).setAction(ACTION_DISMISS))
        }

        /** 供 SendActivity 提交照片后调用：窗口转为"等待 AI 验收" */
        fun notifySubmitted(ctx: android.content.Context) {
            ctx.startService(Intent(ctx, TaskWindowService::class.java).setAction(ACTION_SUBMITTED))
        }

        /** 供 MCP reject_task 调用：显示不合格原因、重置时限，可顺带更新任务描述 */
        fun rejectTask(ctx: android.content.Context, reason: String, minutes: Int, newDesc: String? = null) {
            val i = Intent(ctx, TaskWindowService::class.java)
                .setAction(ACTION_REJECT)
                .putExtra(EXTRA_REASON, reason)
                .putExtra(EXTRA_MINUTES, minutes)
            if (!newDesc.isNullOrBlank()) i.putExtra(EXTRA_TASK, newDesc.trim())
            ctx.startService(i)
        }
    }

    private var windowManager: WindowManager? = null
    private var rootView: LinearLayout? = null
    private var countdownView: TextView? = null
    private var reasonView: TextView? = null
    private var taskTitleView: TextView? = null
    private var cameraBtn: Button? = null
    private val handler = Handler(Looper.getMainLooper())
    private var dragParams: WindowManager.LayoutParams? = null
    private var baseY = 120

    private val tick = object : Runnable {
        override fun run() {
            // 直接读共享状态，避免服务字段与 TaskStore 不同步
            val left = TaskStore.deadline - System.currentTimeMillis()
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
        when (intent?.action) {
            ACTION_DISMISS -> {
                dismiss()
                return START_NOT_STICKY
            }
            ACTION_SUBMITTED -> {
                showSubmittedState()
                return START_STICKY
            }
            ACTION_REJECT -> {
                val reason = intent.getStringExtra(EXTRA_REASON) ?: "不合格，请重拍"
                val minutes = intent.getIntExtra(EXTRA_MINUTES, TaskStore.totalMinutes)
                val newDesc = intent.getStringExtra(EXTRA_TASK)
                if (!newDesc.isNullOrBlank()) TaskStore.taskText = newDesc.trim()
                TaskStore.rejectReason = reason
                TaskStore.restart(minutes)
                showRejectState(reason)
                return START_STICKY
            }
        }
        // 默认：新建任务
        val task = intent?.getStringExtra(EXTRA_TASK) ?: return START_NOT_STICKY
        val minutes = intent.getIntExtra(EXTRA_MINUTES, 30)
        TaskStore.start(task, minutes)

        startForeground(NOTIF_ID, buildNotification("任务进行中：$task"))
        showWindow(task)
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_STICKY
    }

    private fun showWindow(task: String) {
        // 已有窗口（新任务覆盖旧任务）→ 先拆掉重建
        rootView?.let { runCatching { windowManager?.removeView(it) } }
        rootView = null
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val ctx = this
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF2101018.toInt())
            setPadding(36, 28, 36, 28)
        }
        val title = TextView(ctx).apply {
            text = "📋 $task  (按住这里拖动)"
            setTextColor(Color.WHITE)
            textSize = 14f
            // 按住标题拖动悬浮窗
            var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
            setOnTouchListener { v, ev ->
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.rawX; downY = ev.rawY
                        (v.tag as? WindowManager.LayoutParams)?.let { lp ->
                            startX = lp.x; startY = lp.y
                            dragParams = lp
                        }
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val lp = dragParams ?: return@setOnTouchListener true
                        // gravity 是 TOP|END：向左拖 = x 增大
                        lp.x = startX - (ev.rawX - downX).toInt()
                        lp.y = startY + (ev.rawY - downY).toInt()
                        runCatching { windowManager?.updateViewLayout(rootView, lp) }
                        true
                    }
                    else -> false
                }
            }
        }
        taskTitleView = title
        // AI 打回重拍的原因（默认隐藏）
        val reason = TextView(ctx).apply {
            setTextColor(0xFFFF6666.toInt())
            textSize = 13f
            setPadding(0, 12, 0, 0)
            visibility = View.GONE
        }
        reasonView = reason
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
        cameraBtn = btn
        box.addView(title)
        box.addView(reason)
        box.addView(time)
        box.addView(btn)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_FOCUSABLE：悬浮窗不抢输入焦点，键盘永远归身后的聊天应用，用户可正常打字；
            // NOT_TOUCH_MODAL：悬浮窗以外的触摸全部穿透到下层应用
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 24; y = 120
        }
        title.tag = params
        baseY = params.y
        box.viewTreeObserver.addOnGlobalLayoutListener {
            val rect = android.graphics.Rect()
            box.getWindowVisibleDisplayFrame(rect)
            val screenH = resources.displayMetrics.heightPixels
            val keyboardH = screenH - rect.bottom
            val lp = dragParams ?: params
            if (keyboardH > screenH / 5) {
                // 键盘弹出：把悬浮窗挪到屏幕顶部附近，避免遮挡输入框
                if (lp.y > 200) {
                    lp.y = 120
                    runCatching { windowManager?.updateViewLayout(box, lp) }
                }
            }
        }
        windowManager?.addView(box, params)
        rootView = box

        // 服务被杀重启后处于"已提交"状态 → 直接进入等待验收 UI
        if (TaskStore.submitted) showSubmittedState()
        else if (TaskStore.rejectReason != null && TaskStore.active) {
            showRejectState(TaskStore.rejectReason!!)
        }
    }

    /** 用户已提交照片：倒计时行变成行动提示，窗口保持显示，等 AI 验收 */
    private fun showSubmittedState() {
        if (rootView == null) {
            if (TaskStore.taskText.isNotEmpty()) showWindow(TaskStore.taskText) else return
        }
        handler.removeCallbacks(tick)
        countdownView?.text = "✅ 拍照已完成！\n💬 去跟 AI 说「拍好了」，让它查收照片"
        countdownView?.setTextColor(0xFF66DD66.toInt())
        cameraBtn?.visibility = View.GONE
        reasonView?.visibility = View.GONE
        startForeground(NOTIF_ID, buildNotification("已提交待验收：${TaskStore.taskText}"))
    }

    /** AI 判定不合格：显示原因（可带新任务要求），时限重置，恢复拍摄按钮 */
    private fun showRejectState(reason: String) {
        if (rootView == null) {
            if (TaskStore.taskText.isNotEmpty()) showWindow(TaskStore.taskText) else return
        }
        taskTitleView?.text = "📋 ${TaskStore.taskText}  (按住这里拖动)"
        reasonView?.text = "❌ AI 判定不合格：$reason\n请重新拍摄"
        reasonView?.visibility = View.VISIBLE
        countdownView?.setTextColor(0xFFFF4444.toInt())
        cameraBtn?.visibility = View.VISIBLE
        cameraBtn?.text = "📷 重新拍摄"
        startForeground(NOTIF_ID, buildNotification("被要求重拍：${TaskStore.taskText}"))
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    /** AI dismiss 或超时 → 关窗停服务（照片一并清空） */
    private fun dismiss() {
        TaskStore.clear()
        handler.removeCallbacks(tick)
        rootView?.let { runCatching { windowManager?.removeView(it) } }
        rootView = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(text: String): Notification {
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
            .setContentTitle("悬浮任务")
            .setContentText(text)
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
