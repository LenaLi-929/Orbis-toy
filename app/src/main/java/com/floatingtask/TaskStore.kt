package com.floatingtask

/** 任务全周期共享状态（进程内单例） */
object TaskStore {
    @Volatile var taskText: String = ""
    @Volatile var deadline: Long = 0L
    @Volatile var active: Boolean = false
    /** 用户已提交照片、等待 AI 验收 */
    @Volatile var submitted: Boolean = false
    /** 最新一次提交的照片绝对路径；null=还没有 */
    @Volatile var photoPath: String? = null
    /** AI 最近一次判定不合格的原因（null=没有） */
    @Volatile var rejectReason: String? = null
    /** 任务原始时限（分钟），reject_task 未传 minutes 时沿用 */
    @Volatile var totalMinutes: Int = 30

    fun start(text: String, minutes: Int) {
        taskText = text
        totalMinutes = minutes
        rejectReason = null
        restart(minutes)
    }

    /** 重置时限、回到待拍摄状态（新建任务 / AI 打回重拍共用） */
    fun restart(minutes: Int) {
        deadline = System.currentTimeMillis() + minutes * 60_000L
        active = true
        submitted = false
        photoPath = null
    }

    fun submit(path: String) {
        photoPath = path
        submitted = true
    }

    fun clear() {
        active = false
        submitted = false
        photoPath = null
        rejectReason = null
    }

    /** 返回 (状态, 剩余秒)。状态: none / running / timeout / ready_for_review */
    fun status(): Pair<String, Long> {
        if (!active) return "none" to 0L
        if (submitted) return "ready_for_review" to 0L
        val left = deadline - System.currentTimeMillis()
        return if (left > 0) "running" to left / 1000 else "timeout" to 0L
    }
}
