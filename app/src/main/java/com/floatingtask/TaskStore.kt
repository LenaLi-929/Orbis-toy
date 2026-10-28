package com.floatingtask

/** 任务进行期的共享状态（进程内单例） */
object TaskStore {
    @Volatile var taskText: String = ""
    @Volatile var deadline: Long = 0L
}
