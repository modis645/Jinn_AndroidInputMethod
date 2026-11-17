package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 录音期间切句的判据（2026-11-17「结束等 70 秒」的修法之一）。
 *
 * 真机口径：预览「边说边出」是快的，慢的只有结束那一下 —— 说明句子本该在录音时就解好。
 * 切句因此从「依赖 sherpa 流式 Vad 的出段时序」改成**纯帧能量状态机**：
 * 连续语音 ≥0.25 秒算一句、静音 ≥0.5 秒切句、一句最长 28 秒硬切。
 *
 * 这些用例只喂布尔序列（这帧是不是语音），不需要模型、不需要真机。
 */
class EnergySegmenterTest {

    private class Run(val cuts: MutableList<Int> = ArrayList(), val starts: MutableList<Int> = ArrayList())

    private fun feed(seg: EnergySegmenter, pattern: String): Run {
        val run = Run()
        for (c in pattern) {
            seg.onFrame(c == 'S')?.let { run.cuts.add(it) }
            if (seg.lastSegmentStartFrame() >= 0) {
                val last = seg.lastSegmentStartFrame()
                if (run.starts.isEmpty() || run.starts.last() != last) run.starts.add(last)
            }
        }
        return run
    }

    /** 生成「说 n 帧 + 停 m 帧」重复 k 次 */
    private fun pattern(speechFrames: Int, silenceFrames: Int, k: Int): String {
        val sb = StringBuilder()
        repeat(k) {
            repeat(speechFrames) { sb.append('S') }
            repeat(silenceFrames) { sb.append('.') }
        }
        return sb.toString()
    }

    @Test
    fun `说两秒停零点六秒就切一句`() {
        val seg = EnergySegmenter()
        val run = feed(seg, pattern(speechFrames = 200, silenceFrames = 60, k = 1))
        assertEquals("应正好切一句", 1, run.cuts.size)
        assertEquals("切点在语音结束处（不含静音）", 200, run.cuts[0])
    }

    @Test
    fun `停顿不够半秒不切`() {
        val seg = EnergySegmenter()
        val run = feed(seg, pattern(speechFrames = 200, silenceFrames = 40, k = 1))
        assertEquals("静音 0.4 秒在句内，不许切", 0, run.cuts.size)
    }

    @Test
    fun `语音不足零点二五秒的噪声不出句`() {
        val seg = EnergySegmenter()
        val run = feed(seg, pattern(speechFrames = 20, silenceFrames = 100, k = 1))
        assertEquals("20 帧（0.2 秒）太短，不当一句", 0, run.cuts.size)
    }

    @Test
    fun `一口气不停最多二十八秒硬切`() {
        val seg = EnergySegmenter()
        val run = feed(seg, "S".repeat(6_000))
        assertTrue("6000 帧（60 秒）至少硬切两次", run.cuts.size >= 2)
        assertEquals("第一次硬切在 28 秒", 2_800, run.cuts[0])
    }

    @Test
    fun `长文三十三句都能切出来且覆盖足够`() {
        // 200 秒、每 5 秒一句、句间停 0.6 秒 —— 正是用户那段马拉维长文的形状
        val seg = EnergySegmenter()
        val run = feed(seg, pattern(speechFrames = 500, silenceFrames = 60, k = 33))
        assertEquals("33 句都要切出来", 33, run.cuts.size)
        val covered = run.cuts.last() * VoiceDsp.FRAME_SAMPLES
        val total = (33 * 560) * VoiceDsp.FRAME_SAMPLES
        assertTrue(
            "覆盖 $covered/$total 必须过三分之一，否则结束要整段重解",
            VoiceTailMerge.isCacheUsable(run.cuts.size, covered, total),
        )
    }

    @Test
    fun `每句起点正确供段首回补`() {
        val seg = EnergySegmenter()
        val run = feed(seg, pattern(speechFrames = 300, silenceFrames = 60, k = 3))
        assertEquals(listOf(0, 360, 720), run.starts)
    }

    @Test
    fun `全是静音一个句子也不出`() {
        val seg = EnergySegmenter()
        assertNull(seg.onFrame(false))
        val run = feed(seg, ".".repeat(1_000))
        assertTrue(run.cuts.isEmpty())
        assertEquals(-1, seg.lastSegmentStartFrame())
    }
}
