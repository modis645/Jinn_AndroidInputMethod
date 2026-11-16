package com.jinn.inputmethod

/**
 * 语音链路分阶段打点（纯 Kotlin，不依赖 Android，可被 JVM 单测直接驱动）。
 *
 * 用户反复说「语音准不准、快不快」却拿不到数字：原先只有零散的「解码耗时」日志，
 * 看不出时间花在**装模型、建 VAD、建识别器、等第一句话、上屏**哪一段。
 * 这里统一记一条会话的七个阶段：
 *
 *  `model present → size ok → vad ok → recognizer init ok → first frame → first sentence → committed`
 *
 * 每个阶段记录相对会话开始的毫秒数；结束（或失败）时输出一行汇总，写进常规日志，
 * 随「导出诊断包」一起外发。日志只含阶段名与毫秒数，不含任何识别正文。
 */
internal object VoiceTrace {

    private const val STAGE_LIMIT = 16

    private val lock = Any()
    private var startedAt = 0L
    private var label = ""
    private val marks = LinkedHashMap<String, Long>()

    /** 真机日志出口：由 [JinnIme] 在创建时接上 Diagnostics.i；单测里换成收集器 */
    var sink: ((String) -> Unit)? = null

    /** 阶段顺序（用于汇总里判断谁没到） */
    val ORDER = listOf(
        "model present",
        "size ok",
        "vad ok",
        "recognizer init ok",
        "first frame",
        "first sentence",
        "committed",
    )

    fun begin(what: String) {
        synchronized(lock) {
            startedAt = System.nanoTime() / 1_000_000
            label = what
            marks.clear()
        }
    }

    /** 打一个阶段；重复打同一阶段只保留第一次（第一次才有「何时到达」的意义） */
    fun mark(stage: String, detail: String = "") {
        val elapsed: Long
        synchronized(lock) {
            if (startedAt == 0L) startedAt = System.nanoTime() / 1_000_000
            if (marks.containsKey(stage)) return
            if (marks.size >= STAGE_LIMIT) return
            elapsed = System.nanoTime() / 1_000_000 - startedAt
            marks[stage] = elapsed
        }
        val line = "$label $stage +${elapsed}ms" + if (detail.isEmpty()) "" else " $detail"
        sink?.invoke(line)
    }

    /** 汇总：按阶段顺序列出各自毫秒数，缺的阶段标「未到」 */
    fun summary(): String {
        synchronized(lock) {
            if (startedAt == 0L) return "$label 未开始"
            val total = System.nanoTime() / 1_000_000 - startedAt
            val parts = ORDER.map { s -> s + " " + (marks[s]?.let { "+${it}ms" } ?: "未到") }
            return "$label 总 +${total}ms｜" + parts.joinToString("｜")
        }
    }

    fun lastMs(stage: String): Long? = synchronized(lock) { marks[stage] }

    /** 单测与「换会话」用：清掉上一轮的阶段，避免跨轮串味 */
    fun reset() {
        synchronized(lock) {
            startedAt = 0L
            label = ""
            marks.clear()
        }
    }
}
