package com.floatingtask

import android.content.Intent
import android.os.Build
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject

/**
 * 悬浮任务桥 MCP server (Streamable HTTP)。
 * 地址: http://127.0.0.1:8791/mcp
 * 工具: create_task / get_task_status / get_photo / reject_task / dismiss_task
 * 协议实现沿用 pd01-mcp-bridge 已验证的写法（UTF-8 安全、通知 202、GET 405）。
 */
class McpServer(
    private val context: android.content.Context,
    private val log: (String) -> Unit
) : NanoHTTPD("127.0.0.1", 8791) {

    fun startServer() = start(SOCKET_READ_TIMEOUT, false)
    fun isRunning(): Boolean = isAlive
    fun shutdown() = runCatching { super.stop() }

    override fun serve(session: IHTTPSession): Response {
        if (session.uri != "/mcp") return jsonResponse(Response.Status.NOT_FOUND, "{}")
        return when (session.method) {
            Method.POST -> handlePost(session)
            Method.GET -> jsonResponse(Response.Status.METHOD_NOT_ALLOWED, "{}")
            Method.OPTIONS -> jsonResponse(Response.Status.OK, "{}")
            else -> jsonResponse(Response.Status.NOT_FOUND, "{}")
        }
    }

    private fun jsonResponse(status: Response.Status, json: String): Response =
        newFixedLengthResponse(status, "application/json", json)

    private fun handlePost(session: IHTTPSession): Response {
        // 按 Content-Length 读原始字节并显式 UTF-8 解码（NanoHTTPD 解码字符集不可靠）
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
        val json = try { JSONObject(String(bodyBytes, Charsets.UTF_8)) } catch (e: Exception) {
            return errorResponse(JSONObject().put("id", JSONObject.NULL), -32700, "解析错误: ${e.message}")
        }

        val id = json.opt("id")
        if (id == null || json.optString("method").startsWith("notifications/")) {
            return jsonResponse(Response.Status.ACCEPTED, "{}")
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
            onFailure = { e -> return errorResponse(resp, -32603, e.message ?: "内部错误") }
        )
        return jsonResponse(Response.Status.OK, resp.toString())
    }

    private fun errorResponse(resp: JSONObject, code: Int, msg: String): Response {
        resp.put("error", JSONObject().put("code", code).put("message", msg))
        return jsonResponse(Response.Status.OK, resp.toString())
    }

    private companion object {
        val INSTRUCTIONS = """
【悬浮任务】你可以在用户手机上布置"必须拍照才算完成"的实体任务，并亲自看图验收。

工具
- create_task(desc, minutes)：弹出悬浮窗（任务文字+倒计时），用户无法手动关闭。一次只允许一个任务，新任务覆盖旧任务。
- get_task_status()：none / running(剩余秒) / timeout / ready_for_review(用户已提交照片，等你验收)
- get_photo()：取回用户刚提交的照片（JPEG 图片，可直接看）。可重复调用，返回最新一张。
- reject_task(reason, minutes?, desc?)：判定不合格时调用——悬浮窗上显示你的不合格原因并重置时限，用户重拍后你再 get_photo。minutes 缺省沿用原时限；desc 可选，可顺带更新任务要求（悬浮窗上会显示新任务）。
- dismiss_task()：验收通过或用户放弃时调用，关闭悬浮窗并清空照片。

关键规则：用户点【完成】提交照片后，悬浮窗【不会消失】，显示"等待 AI 验收"。窗口的结局由你决定：通过就 dismiss_task，不合格就 reject_task。窗口只在三种情况下消失：你 dismiss、倒计时超时、或你替放弃的用户 dismiss。

标准流程（严格按序）
1. 判断这是需要用户离开聊天去做的实体事（拍照、清扫、确认设备状态…）才派任务；纯对话别派。
2. create_task，desc 写成"要做什么+拍什么"，分钟数按难度给（简单 5~15，出门类 30~60），最长 1440。
3. 直接告诉用户："已弹窗，拍完点【完成】再回来说一声"。
4. 用户回来说拍好/完成 → 调 get_photo 看图。
5. 看图判定：
   - 通过 → 一句话说清看到了什么，明确"验收通过"，再 dismiss_task 关窗。
   - 不合格 → 调 reject_task，reason 具体指出照片缺什么/哪里不对，不要假装通过。
   - 看不到图/报错 → 调 get_task_status 查状态，让用户确认是否点了【完成】，不要猜内容。
6. 超时：说明任务已过期，问是否再来一次，别反复催。

任务描述写法
- 好："把客厅垃圾袋拿到门外垃圾桶旁，拍袋子已放下的照片"（地点+动作+画面要有什么）
- 差："收拾一下""完成任务"——无法从照片判断真假
- 一张照片拍不全时分步派任务，别一次塞三件事。

验收纪律
1. 只判照片里能看见的东西，看不见就说看不见，禁止脑补。
2. 涉安全（燃气、门锁、电器、药品）从严，模糊就 reject_task 打回重拍。
3. 判定必须明确：通过 / 重拍 / 无法判断，三选一，不接受"应该可以了"。

注意
- 用户嫌挡屏幕会自己拖动窗口位置，这是正常的，不要因此重派任务。
- 用户中途放弃，直接 dismiss_task，简短确认即可，不要念叨。
""".trimIndent()
    }

    private fun handleInitialize(json: JSONObject): JSONObject {
        val p = json.optJSONObject("params") ?: JSONObject()
        val clientVersion = p.optString("protocolVersion", "2024-11-05")
        log("MCP 客户端接入: ${p.optJSONObject("clientInfo")?.optString("name")}")
        return JSONObject()
            .put("protocolVersion", if (clientVersion.isNotEmpty()) clientVersion else "2024-11-05")
            .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
            .put("serverInfo", JSONObject().put("name", "floating-task").put("version", "4.7"))
            .put("instructions", INSTRUCTIONS)
    }

    private fun toolDef(name: String, desc: String, props: JSONObject, required: JSONArray): JSONObject =
        JSONObject()
            .put("name", name)
            .put("description", desc)
            .put("inputSchema", JSONObject()
                .put("type", "object")
                .put("properties", props)
                .put("required", required))

    private fun handleToolsList(): JSONObject = JSONObject().put("tools", JSONArray()
        .put(toolDef(
            "create_task",
            "给用户布置拍照验证任务：立即弹出不可手动关闭的悬浮窗（任务+倒计时）。用户拍完点【完成】后窗口转为等待验收（不消失），你用 get_photo 取图判定：合格 dismiss_task 关窗，不合格 reject_task 打回重拍。超时窗口自动消失。一次只允许一个任务。",
            JSONObject()
                .put("desc", JSONObject().put("type", "string").put("description", "任务描述，必须能从照片判断真假。含地点+动作+画面要素，如：把客厅垃圾袋拿到门外，拍袋子已放在桶边"))
                .put("minutes", JSONObject().put("type", "integer").put("description", "时限（分钟），1~1440，简单任务 5~15")),
            JSONArray().put("desc").put("minutes")
        ))
        .put(toolDef(
            "get_task_status",
            "查询当前悬浮任务状态：none=无任务；running=进行中（含剩余秒数）；timeout=已超时窗口已消失；ready_for_review=用户已提交照片，等你验收",
            JSONObject(),
            JSONArray()
        ))
        .put(toolDef(
            "get_photo",
            "获取用户为当前任务提交的照片（MCP image content，base64 JPEG）。用户说『拍好了』后调用本工具验收。可重复调用，返回最新一张。",
            JSONObject(),
            JSONArray()
        ))
        .put(toolDef(
            "reject_task",
            "判定用户照片不合格时调用：悬浮窗上显示不合格原因并重置倒计时，用户重拍提交后你再 get_photo 重新验收。不要用 create_task 重派同一任务。",
            JSONObject()
                .put("reason", JSONObject().put("type", "string").put("description", "不合格原因，要具体：照片里缺什么/哪里不对，如：只拍了垃圾桶盖，没拍到垃圾袋已放进桶里"))
                .put("minutes", JSONObject().put("type", "integer").put("description", "重拍时限（分钟），不传则沿用任务原时限"))
                .put("desc", JSONObject().put("type", "string").put("description", "新任务描述（可选）：顺带更新任务要求，如换个拍摄角度或补充画面要素，悬浮窗上会显示新任务")),
            JSONArray().put("reason")
        ))
        .put(toolDef(
            "dismiss_task",
            "验收通过或用户放弃时调用：关闭悬浮窗并清空照片（收尾动作）",
            JSONObject(),
            JSONArray()
        ))
    )

    private fun textResult(msg: String, isError: Boolean = false): JSONObject = JSONObject()
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", msg)))
        .put("isError", isError)

    private fun handleToolCall(params: JSONObject): JSONObject {
        val name = params.getString("name")
        val args = params.optJSONObject("arguments") ?: JSONObject()
        return runCatching {
            when (name) {
                "create_task" -> {
                    val desc = args.getString("desc").trim()
                    val minutes = args.optInt("minutes", 30)
                    if (desc.isEmpty()) throw McpError("desc 不能为空")
                    if (minutes <= 0 || minutes > 24 * 60) throw McpError("minutes 需在 1~1440 之间")
                    val i = Intent(context, TaskWindowService::class.java)
                        .putExtra(TaskWindowService.EXTRA_TASK, desc)
                        .putExtra(TaskWindowService.EXTRA_MINUTES, minutes)
                    if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i)
                    else context.startService(i)
                    log("create_task: $desc / ${minutes}min")
                    textResult("悬浮窗已弹出，任务【$desc】时限 ${minutes} 分钟。让用户拍完点【完成】再回来说一声，然后 get_photo 验收：合格 dismiss_task 关窗，不合格 reject_task 打回重拍。")
                }
                "get_task_status" -> {
                    val s = TaskStore.status()
                    val rejectSuffix = TaskStore.rejectReason?.let { "（上轮不合格：$it）" } ?: ""
                    textResult(
                        when (s.first) {
                            "none" -> "当前没有进行中的任务"
                            "running" -> "任务【${TaskStore.taskText}】进行中，剩余 ${s.second} 秒$rejectSuffix"
                            "timeout" -> "任务【${TaskStore.taskText}】已超时，窗口已消失"
                            else -> "任务【${TaskStore.taskText}】用户已提交照片，可用 get_photo 验收"
                        }
                    )
                }
                "get_photo" -> {
                    val path = TaskStore.photoPath
                        ?: return@runCatching textResult("还没有待验收的照片。状态: " + TaskStore.status().first, isError = true)
                    val f = java.io.File(path)
                    if (!f.exists()) return@runCatching textResult("照片文件丢失", isError = true)
                    val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeFile(path, opts)
                    var sample = 1
                    while (opts.outWidth / sample > 720 || opts.outHeight / sample > 720) sample *= 2
                    val bmp = android.graphics.BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })!!
                    val baos = java.io.ByteArrayOutputStream()
                    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, baos)
                    val b64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
                    JSONObject().put("content", JSONArray()
                        .put(JSONObject().put("type", "image")
                            .put("data", b64).put("mimeType", "image/jpeg"))
                        .put(JSONObject().put("type", "text")
                            .put("text", "任务【${TaskStore.taskText}】的照片，请验收。")))
                }
                "reject_task" -> {
                    val reason = args.optString("reason").trim()
                    if (reason.isEmpty()) throw McpError("reason 不能为空")
                    val minutes = args.optInt("minutes", TaskStore.totalMinutes)
                    if (minutes <= 0 || minutes > 24 * 60) throw McpError("minutes 需在 1~1440 之间")
                    val newDesc = args.optString("desc").trim().ifEmpty { null }
                    TaskWindowService.rejectTask(context, reason, minutes, newDesc)
                    log("reject_task: $reason / ${minutes}min / desc=$newDesc")
                    val descNote = if (newDesc != null) "任务要求已更新为【$newDesc】。" else ""
                    textResult("已打回重拍。${descNote}悬浮窗显示原因【$reason】，时限重置为 ${minutes} 分钟。用户重拍提交后你再调 get_photo 重新验收。")
                }
                "dismiss_task" -> {
                    TaskWindowService.dismissWindow(context)
                    textResult("悬浮窗已关闭，任务【${TaskStore.taskText}】结束，照片已清空")
                }
                else -> throw McpError("未知工具: $name")
            }
        }.getOrElse { e -> textResult("操作失败: ${e.message}", isError = true) }
    }

    private class McpError(msg: String) : RuntimeException(msg)
}
