package com.jinn.inputmethod

/**
 * 「结束时的尾巴怎么处理」的纯逻辑（可 JVM 单测，不碰模型与音频）。
 *
 * 由来（2026-11-17 真机日志，v99 首次真跑）：
 *  - 8.94 秒音频切出 2 句后剩 0.306 秒零头，被单独解出 **5 个字**（用户反馈「末尾乱码」）；
 *  - 17.72 秒音频剩 0.606 秒，解出 2 个字；7.44 秒音频剩 1.732 秒，解出 2 个字。
 * silero 的段尾天然裁掉尾部低能量，剩下的零头里没有一句完整的话，单独送模型只能瞎猜。
 *
 * 所以两件事分开判：
 *  1. 收尾时最后一段的右边界能不能推到音频末尾（合并零头，而不是单解）；
 *  2. 万一还剩下零头，够不够长到值得解。
 */
internal object VoiceTailPolicy {

    /** 收尾时允许把最后一段延伸到最后的上限：超过它就是长静音，别硬并（白解 2 秒静音） */
    const val MAX_EXTEND_SAMPLES = 2 * 16_000

    /** 短于这个长度（1 秒）的零头不再单独解码：实测 0.3~0.6 秒只能解出凭空的字 */
    const val MIN_REMAINDER_SAMPLES = 16_000

    /** 最后一段的右边界：与音频末尾只差 [MAX_EXTEND_SAMPLES] 以内就推到最后，否则保持原样 */
    fun extendedEnd(segmentEnd: Int, totalSamples: Int): Int {
        if (totalSamples <= segmentEnd) return segmentEnd
        return if (totalSamples - segmentEnd <= MAX_EXTEND_SAMPLES) totalSamples else segmentEnd
    }

    /** 这段零头值不值得单独解 */
    fun shouldDecodeRemainder(remainSamples: Int): Boolean =
        remainSamples >= MIN_REMAINDER_SAMPLES
}
