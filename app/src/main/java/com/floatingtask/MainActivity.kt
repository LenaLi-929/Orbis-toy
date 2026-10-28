package com.floatingtask

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.Collections
import java.util.Enumeration

/**
 * 入口页：授权 + 启动服务 + 自诊断（服务器监听自检、局域网地址展示）。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        val title = TextView(this).apply {
            text = "PD01 MCP Bridge v2.3\nOrbis 配置: Streamable HTTP\nhttp://127.0.0.1:8790/mcp"
            textSize = 16f
        }
        status = TextView(this).apply { text = "服务未启动" }
        val btnStart = Button(this).apply {
            text = "① 启动桥接服务"
            setOnClickListener { requestPermsThenStart() }
        }
        val btnSelfTest = Button(this).apply {
            text = "② 自检服务器端口"
            setOnClickListener { selfTest() }
        }
        val btnScan = Button(this).apply {
            text = "③ 扫描打印机"
            setOnClickListener { doScan() }
        }
        val btnBattery = Button(this).apply {
            text = "④ 申请忽略电池优化（OPPO 必点）"
            setOnClickListener { requestBatteryExemption() }
        }
        val btnPip = Button(this).apply {
            text = "⑤ 缩成悬浮小窗（防冻结，推荐！）"
            setOnClickListener { enterPip() }
        }
        // MAC 输入：绕过扫描，直连打印机
        val macInput = android.widget.EditText(this).apply {
            hint = "打印机 MAC，如 D1:09:22:16:2D:22"
            setText(BlePrinter.loadSavedMac(this@MainActivity) ?: "D1:09:22:16:2D:22")
            setSingleLine(true)
        }
        val btnMac = Button(this).apply {
            text = "⑥ 保存 MAC 并测试连接打印机"
            setOnClickListener { testMacConnect(macInput.text.toString().trim()) }
        }
        box.addView(title); box.addView(btnStart); box.addView(btnSelfTest)
        box.addView(btnScan); box.addView(btnBattery); box.addView(btnPip)
        box.addView(macInput); box.addView(btnMac); box.addView(status)
        root.addView(box)
        setContentView(root)
    }

    private fun needPerms(): List<String> {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            perms += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        return perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermsThenStart() {
        val perms = needPerms()
        if (perms.isNotEmpty()) {
            requestPermissions(perms.toTypedArray(), 1)
        } else startBridge()
    }

    override fun onRequestPermissionsResult(code: Int, ps: Array<out String>, gr: IntArray) {
        super.onRequestPermissionsResult(code, ps, gr)
        if (gr.all { it == PackageManager.PERMISSION_GRANTED }) startBridge()
        else status.text = "权限被拒绝，蓝牙不可用"
    }

    private fun startBridge() {
        val bt = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        if (bt.adapter?.isEnabled != true) {
            status.text = "请先打开系统蓝牙"
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, BridgeService::class.java))
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            showAddresses()
        }, 1500)
    }

    private fun localIps(): List<String> = try {
        val out = mutableListOf<String>()
        val en: Enumeration<InetAddress> = Collections.enumeration(
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
        )
        while (en.hasMoreElements()) {
            val a = en.nextElement()
            if (!a.isLoopbackAddress && a.hostAddress?.contains(':') == false) {
                out.add(a.hostAddress ?: "")
            }
        }
        out
    } catch (e: Exception) { emptyList() }

    private fun showAddresses() {
        val ips = localIps()
        status.text = buildString {
            appendLine("服务已启动（通知栏应有常驻通知）")
            appendLine("本机局域网地址: " + (if (ips.isEmpty()) "未获取到" else ips.joinToString("、")))
            appendLine()
            appendLine("现在点「② 自检服务器端口」验证。")
            appendLine("若自检失败: 系统设置→应用→本App→电池→改为无限制，再重启服务。")
        }
    }

    /** 在 App 内部真实请求一次 http://127.0.0.1:8790/mcp */
    private fun selfTest() {
        status.text = "自检中…"
        CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val conn = URL("http://127.0.0.1:8790/mcp").openConnection() as HttpURLConnection
                    conn.connectTimeout = 4000
                    conn.readTimeout = 4000
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.write("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""".toByteArray())
                    val code = conn.responseCode
                    val body = conn.inputStream.bufferedReader().readText()
                    "HTTP $code, 返回前 120 字: ${body.take(120)}"
                }.getOrElse { "请求异常: ${it.message}" }
            }
            status.text = if (result.startsWith("HTTP 200"))
                "✓ 服务正常监听！\n$result\n\n→ 去 Orbis 填 http://127.0.0.1:8790/mcp"
            else
                "✗ $result\n\n服务没起来：检查通知栏常驻通知是否存在；\n去系统设置关掉本 App 的电池优化后重启服务。"
        }
    }

    /** 进入画中画：App 保持"前台可见"，ColorOS 不会冻结它 */
    private fun enterPip() {
        if (Build.VERSION.SDK_INT >= 26) {
            status.text = "已进入悬浮小窗。现在可以切去 Orbis 使用了！"
            runCatching {
                val params = android.app.PictureInPictureParams.Builder().build()
                enterPictureInPictureMode(params)
            }.onFailure {
                status.text = "进入悬浮窗失败: ${it.message}，请改用系统分屏"
            }
        }
    }

    /** 直接按 MAC 连接打印机（绕过扫描），结果在界面显示 */
    private fun testMacConnect(macRaw: String) {
        val mac = macRaw.uppercase().replace(Regex("[^0-9A-F:]"), "")
        if (!Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$").matches(mac)) {
            status.text = "MAC 格式不对: $mac"
            return
        }
        BlePrinter.saveMac(this, mac)
        status.text = "正在连接 $mac …"
        val printer = BlePrinter(this).apply { savedMac = mac }
        CoroutineScope(Dispatchers.Main).launch {
            try {
                printer.connect(mac)
                status.text = "✓ 已连接打印机 $mac！\nMCP 打印工具现在可用了。"
                printer.disconnect() // 打印时会自动重连
            } catch (e: Exception) {
                status.text = "✗ 连接失败: ${e.message}\n提示：确认打印机开机、未被厂商App占用连接"
            }
        }
    }

    /** 申请加入电池优化白名单（对抗 ColorOS 应用速冻） */
    private fun requestBatteryExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            status.text = "已经在电池优化白名单里了 ✓"
            return
        }
        runCatching {
            @android.annotation.SuppressLint("BatteryLife")
            val i = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(android.net.Uri.parse("package:$packageName"))
            startActivity(i)
        }.onFailure {
            status.text = "跳转失败，请手动: 设置→应用→本App→电池→允许完全后台行为"
        }
    }

    private fun doScan() {
        val perms = needPerms()
        if (perms.isNotEmpty()) { requestPermsThenStart(); return }
        status.text = "扫描中…"
        val printer = BlePrinter(this)
        CoroutineScope(Dispatchers.Main).launch {
            val sb = StringBuilder()
            kotlinx.coroutines.withTimeoutOrNull(6_000) {
                printer.scanPrinterNames().collect { (name, mac) ->
                    sb.appendLine("$name  $mac")
                    status.text = sb.toString()
                }
            }
            if (sb.isEmpty()) status.text = "未发现打印机（确认开机，名字含 GB/MX/PAPERANG 等关键词）"
            else status.text = "发现:\n$sb"
        }
    }
}
