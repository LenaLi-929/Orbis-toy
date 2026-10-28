package com.floatingtask

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * PD01 蓝牙打印驱动：扫描、连接（服务 ae30）、写特征 ae01、逐行打印。
 * 所有打印经单线程互斥执行，避免交错。
 */
@SuppressLint("MissingPermission")
class BlePrinter(private val context: Context) {

    companion object {
        private const val TAG = "BlePrinter"
        val SERVICE_UUID: UUID = UUID.fromString("0000ae30-0000-1000-8000-00805f9b34fb")
        val WRITE_UUID: UUID = UUID.fromString("0000ae01-0000-1000-8000-00805f9b34fb")
        val NOTIFY_UUID: UUID = UUID.fromString("0000ae02-0000-1000-8000-00805f9b34fb")

        /** BLE MTU 内安全分包大小 */
        private const val CHUNK = 480
        private const val CONNECT_TIMEOUT_MS = 10_000L

        /** 用户保存的打印机 MAC 持久化 */
        fun loadSavedMac(ctx: Context): String? =
            ctx.getSharedPreferences("bridge", Context.MODE_PRIVATE).getString("mac", null)
        fun saveMac(ctx: Context, mac: String) {
            ctx.getSharedPreferences("bridge", Context.MODE_PRIVATE)
                .edit().putString("mac", mac).apply()
        }
    }

    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = btManager.adapter

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null

    /** 串行化所有打印任务 */
    val printMutex = Mutex()

    @Volatile var printerName: String = "PD01"

    /** 用户指定的打印机 MAC（优先于扫描），由 MainActivity 持久化 */
    @Volatile var savedMac: String? = null

    // ---------- 扫描 ----------

    /** 按名字关键词扫描打印机，返回 (名字, MAC) 列表 */
    fun scanPrinterNames(keywords: List<String> = listOf("GB01", "GB02", "MX", "PRINTER", "PAPERANG", "PD01", "PT800")): Flow<Pair<String, String>> =
        callbackFlow {
            val leScanner = adapter?.bluetoothLeScanner
            if (leScanner == null) { close(IllegalStateException("蓝牙不可用")); return@callbackFlow }
            val seen = HashMap<String, String>()
            val cb = object : ScanCallback() {
                override fun onScanResult(type: Int, result: ScanResult) {
                    val name = result.scanRecord?.deviceName ?: return
                    val mac = result.device.address
                    if (seen.putIfAbsent(mac, name) == null &&
                        keywords.any { name.uppercase().contains(it) }) {
                        trySend(name to mac)
                    }
                }
            }
            // 不设系统级名字过滤（名字大小写/后缀差异大），全量扫描后在应用层过滤
            leScanner.startScan(emptyList<ScanFilter>(), ScanSettings.Builder().build(), cb)
            awaitClose { runCatching { leScanner.stopScan(cb) } }
        }

    // ---------- 连接 ----------

    @SuppressLint("MissingPermission")
    suspend fun connect(mac: String? = null) = withContext(Dispatchers.Main) {
        disconnect()
        // 优先级：传入 MAC > 用户保存的 MAC > 扫描发现
        val target = mac ?: savedMac ?: run {
            val found = withTimeoutOrNull(5_000) { scanPrinterNames().first() }
            found?.second
        } ?: throw IllegalStateException("未找到打印机：请设置打印机 MAC，或确认它开机且未被其他 App 连接")

        val dev = adapter?.getRemoteDevice(target)
            ?: throw IllegalStateException("蓝牙不可用")

        printerName = dev.name ?: printerName
        val connected = CompletableDeferred<Boolean>()
        val newGatt = dev.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    writeChar = null
                    if (!connected.isCompleted) connected.complete(false)
                    runCatching { g.close() }
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (connected.isCompleted) return
                val ch = g.getService(SERVICE_UUID)?.getCharacteristic(WRITE_UUID)
                if (ch != null) {
                    writeChar = ch
                    ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    connected.complete(true)
                } else connected.complete(false)
            }
        }, BluetoothDevice.TRANSPORT_LE)

        val ok = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { connected.await() } == true
        if (!ok) {
            runCatching { newGatt?.disconnect(); newGatt?.close() }
            throw IllegalStateException("连接失败：超时或未找到写特征 ae01")
        }
        gatt = newGatt
        runCatching { newGatt.requestMtu(512) }
    }

    fun disconnect() {
        gatt?.let { runCatching { it.disconnect(); it.close() } }
        gatt = null
        writeChar = null
    }

    val isConnected: Boolean get() = gatt != null && writeChar != null

    // ---------- 写 ----------

    /** 每行位图之间的间隔 ms。打印丢行/虚线时调大 */
    @Volatile var rowDelayMs: Long = 15

    @SuppressLint("MissingPermission")
    private suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        val g = gatt ?: throw IllegalStateException("未连接")
        val ch = writeChar ?: throw IllegalStateException("未连接")
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        data.toList().chunked(CHUNK).forEach { chunkBytes ->
            val chunk = chunkBytes.toByteArray()
            @Suppress("DEPRECATION")
            run {
                ch.value = chunk
                g.writeCharacteristic(ch)
            }
            // no-response 模式下靠节流避免丢包
            Thread.sleep(4)
        }
    }

    /** 逐行打印：每行一个 56 字节帧，行间隔充足——cat printer 系机器大块发送会丢行 */
    @SuppressLint("MissingPermission")
    private suspend fun writeRows(frameBlobs: List<ByteArray>) = withContext(Dispatchers.IO) {
        val g = gatt ?: throw IllegalStateException("未连接")
        val ch = writeChar ?: throw IllegalStateException("未连接")
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        frameBlobs.forEach { frame ->
            @Suppress("DEPRECATION")
            run {
                ch.value = frame
                g.writeCharacteristic(ch)
            }
            Thread.sleep(rowDelayMs)
        }
    }

    // ---------- 渲染 ----------

    /** 文本 -> 384 宽 1bpp 位图行（自动折行），黑=1 */
    fun renderText(text: String, fontSize: Int = 48): List<ByteArray> {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.DEFAULT_BOLD
            textSize = fontSize.toFloat()
        }
        val fm = paint.fontMetrics
        val lineHeight = (fm.descent - fm.ascent).toInt().coerceAtLeast(fontSize)

        // 按真实像素宽度折行
        val lines = mutableListOf<String>()
        text.replace("\r", "").split("\n").forEach { raw ->
            var cur = StringBuilder()
            raw.forEach { c ->
                val candidate = cur.toString() + c
                if (paint.measureText(candidate) > Pd01Protocol.PRINT_WIDTH - 8 &&
                    cur.isNotEmpty()) {
                    lines.add(cur.toString())
                    cur = StringBuilder()
                }
                cur.append(c)
            }
            lines.add(cur.toString())
        }

        val height = (lines.size * lineHeight + lineHeight / 3)
            .coerceAtLeast(lineHeight)
        val bmp = Bitmap.createBitmap(Pd01Protocol.PRINT_WIDTH, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        lines.forEachIndexed { i, line ->
            if (line.isNotBlank()) {
                // 第 i 行顶部 = i*lineHeight；基线 = 顶部 - fm.ascent（ascent 为负）
                val baseline = i * lineHeight - fm.ascent
                canvas.drawText(line, 4f, baseline, paint)
            }
        }
        return bitmapToRows(bmp)
    }

    /** 任意 ARGB 图 -> 缩放到 384 宽 -> 阈值二值化 -> 行数据 */
    fun bitmapToRows(src: Bitmap): List<ByteArray> {
        val w = Pd01Protocol.PRINT_WIDTH
        val h = (src.height.toDouble() * w / src.width).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        scaled.getPixels(px, 0, w, 0, 0, w, h)
        val rows = ArrayList<ByteArray>(h)
        for (row in 0 until h) {
            val bytes = ByteArray(Pd01Protocol.BYTES_PER_LINE)
            for (col in 0 until w) {
                val p = px[row * w + col]
                // 亮度阈值: 亮=白(0) 暗=黑(1)
                val lum = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
                if (lum < 128) bytes[col / 8] = (bytes[col / 8].toInt() or (1 shl (col % 8))).toByte()
            }
            rows.add(bytes)
        }
        return rows
    }

    // ---------- 打印 ----------

    suspend fun printRows(rows: List<ByteArray>) = printMutex.withLock {
        ensureConnected()
        writeRows(Pd01Protocol.buildPrintFrames(rows))
    }

    suspend fun sendHex(hex: String) = printMutex.withLock {
        ensureConnected()
        val clean = hex.replace(Regex("[^0-9a-fA-F]"), "")
        require(clean.length % 2 == 0) { "hex 长度必须为偶数" }
        write(clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
    }

    suspend fun feed(lines: Int) = printMutex.withLock {
        ensureConnected()
        write(Pd01Protocol.feedPaper(lines))
    }

    private suspend fun ensureConnected() {
        // 每次都重新读取已保存的 MAC（可能是在服务启动后才保存的）
        if (savedMac == null) savedMac = loadSavedMac(context)
        if (!isConnected) connect(null)
    }
}
