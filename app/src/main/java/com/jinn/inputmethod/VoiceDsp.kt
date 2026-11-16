package com.jinn.inputmethod

/**
 * 语音链路里的纯数字处理：帧能量与段首回补。
 *
 * 抽出来的唯一理由：这两条决定**「长录音丢句首 1~4 字」**是否复发（2026-11-16 用户反馈），
 * 而原先它们锁在 [LocalSenseVoiceClient] 的 private 方法里，只能靠真机听一遍才能判断。
 * 现在不需要 Context、不需要音频文件，任何一组帧能量都能直接跑。
 */
internal object VoiceDsp {

    const val SAMPLE_RATE = 16_000

    /** 10ms 一帧 */
    const val FRAME_SAMPLES = SAMPLE_RATE / 100

    /** 段首无条件回补的帧数（0.3s） */
    const val ONSET_ALWAYS_FRAMES = 30

    /** 回补途中容忍的低能量间隙（0.25s） */
    const val ONSET_GAP_FRAMES = 25

    /** 单段最多回补多少帧（3s），防止一口气把整段话都吞进来 */
    const val ONSET_MAX_BACK_FRAMES = 300

    /** 逐 10ms 帧的 RMS（0..1）。不足一帧的尾巴丢掉（对回补判据无影响） */
    fun frameEnergies(samples: FloatArray): FloatArray {
        if (FRAME_SAMPLES <= 0 || samples.size < FRAME_SAMPLES) return FloatArray(0)
        val count = samples.size / FRAME_SAMPLES
        val out = FloatArray(count)
        for (f in 0 until count) {
            var sum = 0.0
            val base = f * FRAME_SAMPLES
            for (j in 0 until FRAME_SAMPLES) {
                val v = samples[base + j]
                sum += v * v
            }
            out[f] = kotlin.math.sqrt(sum / FRAME_SAMPLES).toFloat()
        }
        return out
    }

    /**
     * 段首回推到第几帧（返回采样下标）。
     *
     * [floorSample] = 上一段的段尾：两段音频绝不重叠，否则同一句话会被解码两遍、上屏两次。
     * 规则与沙鸥逐项对齐：先无条件往前 0.3 秒（气声没有能量但确属语音），之后沿「语音帧」继续走，
     * 途中允许最多 0.25 秒的低能量间隙，总回补不超过 3 秒。
     */
    fun onsetBacktrack(frameRms: FloatArray, speechRms: Float, segStart: Int, floorSample: Int): Int {
        if (frameRms.isEmpty()) return segStart
        val startFrame = (segStart / FRAME_SAMPLES).coerceIn(0, frameRms.size)
        val floorFrame = (floorSample / FRAME_SAMPLES).coerceAtLeast(0)
        var idx = startFrame
        var steps = 0
        var gap = 0
        while (idx > floorFrame && idx > 0 && steps <= ONSET_MAX_BACK_FRAMES) {
            val loud = frameRms[idx - 1] >= speechRms
            when {
                steps < ONSET_ALWAYS_FRAMES -> Unit          // 无条件补的 0.3 秒
                loud -> gap = 0                              // 语音继续，间隙清零
                gap < ONSET_GAP_FRAMES -> gap++               // 低能量但还在容忍窗口内
                else -> break                                // 彻底掉出语音段，停
            }
            idx--
            steps++
        }
        return idx * FRAME_SAMPLES
    }
}
