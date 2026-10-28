package com.floatingtask

import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 哨兵协议探针：监听 127.0.0.1:8090，把 Orbis 外连哨兵时发来的
 * 原始请求（方法/路径/请求头/payload）逐字记录到日志文件。
 *
 * 目的：Orbis 文档未公开外部哨兵事件协议，让它来连我们，抓一次包就知道格式。
 * 测试期功能，协议摸清后可整体删除。
 */
object SentinelProbeServer {

    private const val TAG = "SentinelProbe"
    private const val HTTP_PORT = 8090
    private const val MAX_LOG_LINES = 2000

    @Volatile private var running = false
    private lateinit var logFile: File
    private val executor = Executors.newCachedThreadPool()
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA)

    fun start(filesDir: File) {
        if (running) return
        running = true
        logFile = File(filesDir, "sentinel_probe.log")
        appendLog("=== 探针启动，监听 :$HTTP_PORT ===")
        Thread({
            try {
                ServerSocket(HTTP_PORT).use { server ->
                    Log.i(TAG, "listening on :$HTTP_PORT")
                    while (running) {
                        val client = server.accept()
                        executor.submit { handle(client) }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "server died", e)
                appendLog("!! server died: $e")
            }
        }, "SentinelProbe").start()
    }

    fun stop() {
        running = false
    }

    /** 供 MCP 工具调用：返回完整日志文本 */
    fun dumpLog(): String =
        if (logFile.exists()) logFile.readText() else "(探针尚无记录)"

    fun clearLog() {
        if (logFile.exists()) logFile.writeText("")
    }

    // ------------------------------------------------------------------

    private fun handle(socket: Socket) {
        val id = "conn@${timeFmt.format(Date())}"
        val sb = StringBuilder()
        try {
            socket.soTimeout = 10_000
            val raw = socket.getInputStream()
            val reader = BufferedReader(InputStreamReader(raw, Charsets.ISO_8859_1))

            sb.append("\n-------- $id --------\n")
            sb.append("remote = ${socket.inetAddress.hostAddress}:${socket.port}\n")
            var contentLength = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                sb.append(line).append('\n')
                if (line.startsWith("Content-Length:", true))
                    contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
            }

            if (contentLength > 0) {
                val body = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = raw.read(body, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                sb.append("-- body ($contentLength bytes) --\n")
                val bodyText = String(body, Charsets.UTF_8)
                sb.append(bodyText).append('\n')
                if (bodyText.any { it.code < 32 && it != '\n' && it != '\r' && it != '\t' }) {
                    sb.append("-- body 疑似二进制，hex 前 512 字节 --\n")
                    sb.append(body.take(512).joinToString(" ") { "%02x".format(it) }).append('\n')
                }
            }

            // 先回通用 200 JSON；Orbis 若嫌格式不对，报错内容本身就是协议线索
            val resp = """{"ok":true,"probe":"received"}"""
            socket.getOutputStream().apply {
                write(
                    ("HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: ${resp.toByteArray().size}\r\n" +
                        "Connection: close\r\n\r\n").toByteArray()
                )
                write(resp.toByteArray())
                flush()
            }
        } catch (e: Exception) {
            sb.append("!! read error: $e\n")
        } finally {
            try { socket.close() } catch (_: Exception) {}
            appendLog(sb.toString())
            Log.i(TAG, sb.toString())
        }
    }

    private fun appendLog(text: String) {
        try {
            if (!::logFile.isInitialized) return
            val lines = if (logFile.exists()) logFile.readLines() else emptyList()
            val kept = (lines + text.lines()).takeLast(MAX_LOG_LINES)
            logFile.writeText(kept.joinToString("\n"))
        } catch (e: Exception) {
            Log.e(TAG, "log write failed", e)
        }
    }
}
