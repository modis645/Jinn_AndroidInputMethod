package com.jinn.inputmethod

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 源码守卫：分段缓存必须走「我们自己的帧能量切句」，不许再退回 sherpa 流式 Vad。
 *
 * 2026-11-17 两轮真机实测给出了同一条结论：预览（录音中）快，结束那一下慢到 70 秒。
 * 前者说明句子本来就能在录音时解好，后者说明结束仍走了整段重解。根因有两处，都已修：
 *  ① 切句依赖 silero 的出段时序（不可控、喂入长度敏感）→ 换成 [EnergySegmenter]；
 *  ② 预览每 0.7 秒把 8 秒尾巴塞进串行识别器 → 结束的最终解码排在它后面。
 * 这两个如果再被改回去，这里必须红。
 */
class SegmentPipelineGuardTest {

    private fun cacheSource(): String =
        TestSources.rawSource("src/main/java/com/jinn/inputmethod/VoiceStreamCache.kt")

    private fun clientSource(): String =
        TestSources.rawSource("src/main/java/com/jinn/inputmethod/LocalSenseVoiceClient.kt")

    @Test
    fun `分段缓存的切句必须走帧能量而不依赖流式Vad`() {
        val src = cacheSource()
        assertTrue("必须有帧能量切句器", src.contains("EnergySegmenter"))
        assertFalse("不许再用 sherpa 流式 Vad 切句", src.contains("SileroVadModelConfig"))
        assertFalse("不许再问 Vad 要段落", src.contains("vad.front()"))
    }

    @Test
    fun `预览不许每拍解尾巴`() {
        val src = clientSource()
        val fn = TestSources.blockAfter(src, "private fun emitPreview")
        assertTrue("预览只在还没有缓存句时才补尾巴", fn.contains("cached.isEmpty()"))
        assertTrue("补尾巴要有最小间隔", fn.contains("PREVIEW_MIN_INTERVAL_MS"))
        assertTrue("尾巴上限 4 秒", src.contains("PREVIEW_TAIL_SAMPLES = 4 * SAMPLE_RATE"))
    }

    @Test
    fun `结束瞬间要快照缓存，不许被清掉后退回整段重解`() {
        val src = clientSource()
        val fn = TestSources.blockAfter(src, "override fun endTask()")
        assertTrue("endTask 必须存快照", fn.contains("finishedTexts"))
        assertTrue("endTask 要记下结束时刻", fn.contains("stoppedAtMs"))
        val rec = TestSources.blockAfter(src, "override fun recognize")
        assertTrue("recognize 优先用快照", rec.contains("finishedTexts.ifEmpty"))
    }

    @Test
    fun `识别器仍然是 2 线程`() {
        assertTrue(clientSource().contains("numThreads = 2"))
    }
}
