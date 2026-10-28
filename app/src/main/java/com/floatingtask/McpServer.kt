package com.floatingtask

import android.graphics.BitmapFactory
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地 MCP server (Streamable HTTP)。
 * - 地址: http://127.0.0.1:8790/mcp
 * - 暴露工具: bt_print_text / bt_print_image / bt_send_hex / bt_feed / bt_scan
 * - 请求体为 JSON-RPC 2.0，响应为普通 application/json（Streamable HTTP 规范允许）
 */
class McpServer(
    private val printer: BlePrinter,
    private val log: (String) -> Unit,
    /** null = 监听所有网卡（方便局域网调试）；"127.0.0.1" = 仅本机 */
    bindHost: String? = null
) : NanoHTTPD(bindHost, 8790) {

    fun startServer() = start(SOCKET_READ_TIMEOUT, false)

    override fun serve(session: IHTTPSession): Response {
        if (session.uri != "/mcp") return notFound()
        return when (session.method) {
            Method.POST -> handlePost(session)
            // 不支持 SSE 推流：按 MCP Streamable HTTP 规范对 GET 返回 405。
            // 注意 body/类型也用 JSON，避免某些 SDK 对 text/plain 报 Unexpected content type
            Method.GET -> cors(newFixedLengthResponse(
                Response.Status.METHOD_NOT_ALLOWED, "application/json", "{}"))
            Method.OPTIONS -> cors(newFixedLengthResponse(
                Response.Status.OK, "application/json", "{}"))
            else -> notFound()
        }
    }

    private fun cors(r: Response): Response {
        r.addHeader("Access-Control-Allow-Origin", "*")
        r.addHeader("Access-Control-Allow-Methods", "POST, GET, OPTIONS")
        r.addHeader("Access-Control-Allow-Headers", "*")
        return r
    }

    private fun notFound() =
        cors(newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{}"))

    private fun jsonResponse(json: String): Response =
        cors(newFixedLengthResponse(Response.Status.OK, "application/json", json))

    private fun handlePost(session: IHTTPSession): Response {
        // 自己按 Content-Length 读原始字节并显式 UTF-8 解码——
        // NanoHTTPD 的 parseBody 解码字符集不可靠，中文会变乱码（打出来全是 �）
        val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
        val bodyBytes = if (contentLength > 0) {
            val buf = ByteArray(contentLength)
            var read = 0
            val ins = session.inputStream
            while (read < contentLength) {
                val n = ins.read(buf, read, contentLength - read)
                if (n < 0) break
                read += n
            }
            buf.copyOf(read)
        } else ByteArray(0)
        val raw = String(bodyBytes, Charsets.UTF_8)
        val json = try { JSONObject(raw) } catch (e: Exception) {
            return errorResponse(JSONObject().put("id", JSONObject.NULL), -32700, "解析错误: ${e.message}")
        }

        val id = json.opt("id")
        if (id == null || json.optString("method").startsWith("notifications/")) {
            // 通知：按 MCP 规范返回 202 Accepted（JSON）
            return cors(newFixedLengthResponse(
                Response.Status.ACCEPTED, "application/json", "{}"))
        }

        val method = json.optString("method")
        val result = runCatching {
            when (method) {
                "initialize" -> handleInitialize(json)
                "ping" -> JSONObject()
                "tools/list" -> handleToolsList()
                "tools/call" -> handleToolCall(json.getJSONObject("params"))
                else -> throw McpError("未知方法: $method")
            }
        }

        val resp = JSONObject().put("jsonrpc", "2.0").put("id", id)
        result.fold(
            onSuccess = { resp.put("result", it) },
            onFailure = { e ->
                return errorResponse(resp, -32603, e.message ?: "内部错误")
            }
        )
        return jsonResponse(resp.toString())
    }

    private fun errorResponse(resp: JSONObject, code: Int, msg: String): Response {
        resp.put("error", JSONObject().put("code", code).put("message", msg))
        return jsonResponse(resp.toString())
    }

    private fun handleInitialize(json: JSONObject): JSONObject {
        val p = json.optJSONObject("params") ?: JSONObject()
        val clientVersion = p.optString("protocolVersion", "2024-11-05")
        log("MCP 客户端接入: ${p.optJSONObject("clientInfo")?.optString("name")} (协议 $clientVersion)")
        return JSONObject()
            .put("protocolVersion", if (clientVersion.isNotEmpty()) clientVersion else "2024-11-05")
            .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
            .put("serverInfo", JSONObject().put("name", "floating-task").put("version", "1.0.0"))
            .put("instructions", "这是 PD01 热敏打印机桥接。打印前会自动连接打印机；纸张宽 384 点（约 48mm），单行最多约 32 个半角字符，超出请用 \\n 手动换行。可用工具：bt_print_text、bt_print_image、bt_feed、bt_scan、bt_send_hex。")
    }

    private fun toolDef(
        name: String, desc: String, props: JSONObject, required: JSONArray
    ): JSONObject = JSONObject()
        .put("name", name)
        .put("description", desc)
        .put("inputSchema", JSONObject()
            .put("type", "object")
            .put("properties", props)
            .put("required", required))

    private fun handleToolsList(): JSONObject {
        val tools = JSONArray()
            .put(toolDef(
                "bt_print_text",
                "把文本打印到 PD01 热敏打印机（384 点宽，自动折行，中文支持）",
                JSONObject()
                    .put("text", JSONObject().put("type", "string").put("description", "要打印的文本，支持 \\n 换行"))
                    .put("font_size", JSONObject().put("type", "integer").put("description", "字号像素，默认 48")),
                JSONArray().put("text")
            ))
            .put(toolDef(
                "bt_print_image",
                "把本地图片文件打印到 PD01（自动缩放到 384 宽并二值化）",
                JSONObject().put("path", JSONObject().put("type", "string").put("description", "图片绝对路径")),
                JSONArray().put("path")
            ))
            .put(toolDef(
                "bt_send_hex",
                "向打印机发送任意十六进制协议包（空格分隔可读）",
                JSONObject().put("hex", JSONObject().put("type", "string").put("description", "hex 字符串，如 51 78 A3 ...")),
                JSONArray().put("hex")
            ))
            .put(toolDef(
                "bt_print_test",
                "打印测试图案（实心黑条+棋盘格），用于区分传输问题与渲染问题",
                JSONObject(),
                JSONArray()
            ))
            .put(toolDef(
                "bt_feed",
                "走纸 n 行（每行 0.125mm）",
                JSONObject().put("lines", JSONObject().put("type", "integer")),
                JSONArray().put("lines")
            ))
            .put(toolDef(
                "bt_scan",
                "扫描附近的 PD01/cat printer 打印机",
                JSONObject(),
                JSONArray()
            ))
            .put(toolDef(
                "get_probe_log",
                "读取哨兵协议探针的抓包日志（Orbis 连接哨兵端点时发出的原始请求）",
                JSONObject(),
                JSONArray()
            ))
            .put(toolDef(
                "clear_probe_log",
                "清空哨兵协议探针的抓包日志",
                JSONObject(),
                JSONArray()
            ))
        return JSONObject().put("tools", tools)
    }

    private fun textResult(msg: String, isError: Boolean = false): JSONObject = JSONObject()
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", msg)))
        .put("isError", isError)

    private fun handleToolCall(params: JSONObject): JSONObject {
        val name = params.getString("name")
        val args = params.optJSONObject("arguments") ?: JSONObject()
        return runBlocking {
            runCatching {                when (name) {
                    "bt_print_text" -> {
                        val text = args.getString("text")
                        val size = args.optInt("font_size", 48)
                        printer.printRows(printer.renderText(text, size))
                        textResult("已打印文本，共 ${text.length} 字符")
                    }
                    "bt_print_image" -> {
                        val bmp = BitmapFactory.decodeFile(args.getString("path"))
                            ?: throw McpError("无法读取图片: ${args.getString("path")}")
                        printer.printRows(printer.bitmapToRows(bmp))
                        textResult("已打印图片 ${bmp.width}x${bmp.height}")
                    }
                    "bt_send_hex" -> {
                        printer.sendHex(args.getString("hex"))
                        textResult("hex 已发送")
                    }
                    "bt_print_test" -> {
                        // 8 行全黑 + 16 行棋盘格
                        val rows = mutableListOf<ByteArray>()
                        repeat(8) { rows.add(ByteArray(Pd01Protocol.BYTES_PER_LINE) { 0xFF.toByte() }) }
                        repeat(16) { r ->
                            val row = ByteArray(Pd01Protocol.BYTES_PER_LINE)
                            for (b in row.indices) {
                                val v = if ((r / 2 + b) % 2 == 0) 0xAA.toByte() else 0x55.toByte()
                                row[b] = v
                            }
                            rows.add(row)
                        }
                        printer.printRows(rows)
                        textResult("已打印测试图案（8 行黑条 + 16 行棋盘格）")
                    }
                    "bt_feed" -> {
                        printer.feed(args.optInt("lines", 10))
                        textResult("已走纸 ${args.optInt("lines", 10)} 行")
                    }
                    "bt_scan" -> {
                        val found = mutableListOf<String>()
                        kotlinx.coroutines.withTimeoutOrNull(5_000) {
                            printer.scanPrinterNames().collect { (n, m) ->
                                found.add("$n ($m)")
                                if (found.size >= 5) throw kotlinx.coroutines.CancellationException("enough")
                            }
                        }
                        textResult(if (found.isEmpty()) "未发现打印机" else "发现: ${found.joinToString("; ")}")
                    }
                    "get_probe_log" -> textResult(SentinelProbeServer.dumpLog())
                    "clear_probe_log" -> {
                        SentinelProbeServer.clearLog()
                        textResult("探针日志已清空")
                    }
                    else -> throw McpError("未知工具: $name")
                }
            }.getOrElse { e -> textResult("打印失败: ${e.message}", isError = true) }
        }
    }

    private class McpError(msg: String) : RuntimeException(msg)
}
