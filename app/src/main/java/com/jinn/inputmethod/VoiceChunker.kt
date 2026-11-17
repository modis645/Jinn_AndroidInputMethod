package com.jinn.inputmethod

/**
 * 长音频的切块（纯数学，可单测）。
 *
 * 为什么必须有它：真机日志（2026-11-17）给出两组反差极大的数字 ——
 *  · 每拍解 8 秒尾巴：**115ms**（RTF≈0.014，非常快）；
 *  · 把 106.68 秒音频一次性交给同一条识别器：**92 527ms**。
 * 也就是说手机端「短音频解码」极快，而超长单流解码会崩（耗时随长度超线性增长）。
 * 结论：**任何一次 decode 的音频都不许超过 10 秒**，长音频一律先切块再逐块解。
 *
 * 切块点尽量落在「静音帧」上（最多往前找 2 秒），避免切在字中间；找不到就硬切。
 */
internal object VoiceChunker {

    /** 单次解码的音频上限：10 秒（160 000 采样） */
    const val MAX_CHUNK_SAMPLES = 10 * VoiceDsp.SAMPLE_RATE

    /** 找切点时最多往前回看 2 秒 */
    private const val SEARCH_BACK_SAMPLES = 2 * VoiceDsp.SAMPLE_RATE

    /**
     * 把 [samples] 切成若干区间（左闭右开、首尾相接、无重叠），每块不超过 [maxChunk]。
     * [frameRms] 是 10ms 帧能量（长度可短于采样数，缺帧按静音处理），[speechRms] 是语音阈值。
     */
    fun split(
        samples: FloatArray,
        frameRms: FloatArray,
        speechRms: Float,
        maxChunk: Int = MAX_CHUNK_SAMPLES,
    ): List<IntRange> {
        if (samples.isEmpty()) return emptyList()
        require(maxChunk > 0) { "maxChunk 必须为正" }
        val out = ArrayList<IntRange>()
        var start = 0
        while (start < samples.size) {
            val hardEnd = minOf(start + maxChunk, samples.size)
            var end = hardEnd
            if (hardEnd < samples.size) {
                val fromFrame = (maxOf(start, hardEnd - SEARCH_BACK_SAMPLES) / VoiceDsp.FRAME_SAMPLES)
                val lastFrame = frameRms.size - 1
                val toFrame = if (lastFrame >= 0) {
                    (hardEnd / VoiceDsp.FRAME_SAMPLES).coerceAtMost(lastFrame)
                } else {
                    -1
                }
                var frame = toFrame
                while (frame > fromFrame) {
                    val rms = if (frame < frameRms.size) frameRms[frame] else 0f
                    if (rms <= speechRms) break
                    frame--
                }
                val atSilence = frame * VoiceDsp.FRAME_SAMPLES
                if (frame > fromFrame && atSilence > start) end = atSilence
            }
            out.add(start until end)
            start = end
        }
        return out
    }
}
