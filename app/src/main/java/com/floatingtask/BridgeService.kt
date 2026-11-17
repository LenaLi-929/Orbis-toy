package com.floatingtask

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock

/**
 * 前台服务：常驻托管本地 MCP HTTP 服务器（悬浮任务桥）。
 * 内置看门狗：每 60 秒自检端口，死了自动拉起；WakeLock 对抗 ColorOS 速冻。
 */
class BridgeService : Service() {

    companion object {
        const val CHANNEL_ID = "bridge"
        const val NOTIFICATION_ID = 1
        const val WATCHDOG_ACTION = "com.floatingtask.WATCHDOG"
        const val PORT = 8791
    }

    private lateinit var server: McpServer
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        server = McpServer(this, log = { msg ->
            android.util.Log.i("BridgeService", msg)
        })
        startServerIfDead()
        scheduleWatchdog()
        acquireWakeLock()
    }

    /** 持有 CPU 唤醒锁，对抗 ColorOS 速冻 */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "floatingtask:bridge").apply {
            setReferenceCounted(false)
            acquire(24 * 60 * 60 * 1000L) // 24h 上限
        }
    }

    private fun startServerIfDead() {
        if (server.isRunning()) return
        val err = runCatching { server.startServer() }.exceptionOrNull()
        if (err == null) {
            android.util.Log.i("BridgeService", "server started on $PORT")
            updateNotification("悬浮任务桥运行中 · 127.0.0.1:$PORT/mcp")
        } else {
            android.util.Log.e("BridgeService", "start failed", err)
            updateNotification("❌ 端口 $PORT 启动失败：${err.message ?: err.javaClass.simpleName}")
        }
    }

    /** 在常驻通知里显示当前状态，便于用户/AI 排查端口被占用 */
    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun scheduleWatchdog() {
        val am = getSystemService(ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, WatchdogReceiver::class.java).setAction(WATCHDOG_ACTION)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= 31) flags = flags or PendingIntent.FLAG_IMMUTABLE
        am.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + 60_000,
            60_000,
            PendingIntent.getBroadcast(this, 1001, intent, flags)
        )
    }

    /** 看门狗：每分钟探测端口，死了就拉起服务 */
    class WatchdogReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val alive = runCatching {
                java.net.Socket("127.0.0.1", PORT).use { s ->
                    s.soTimeout = 1500
                    true
                }
            }.getOrDefault(false)
            if (!alive) {
                val i = Intent(ctx, BridgeService::class.java)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i)
                else ctx.startService(i)
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "悬浮任务桥", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("悬浮任务桥")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("MCP 桥接启动中 :$PORT"))
        startServerIfDead() // 看门狗拉活时 onCreate 不会再走，这里兜底
        acquireWakeLock()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { server.shutdown() }
        runCatching { wakeLock?.release() }
        super.onDestroy()
    }
}
