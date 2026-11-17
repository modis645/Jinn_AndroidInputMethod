package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长音频切块（2026-11-17「结束等 70 秒」的第三个真根因）。
 *
 * 真机日志给的两组数字定了口径：8 秒尾巴解 115ms，106.68 秒单流解 92 527ms。
 * 所以规则是硬的：**任何一次 decode 的音频 ≤ 10 秒**，长音频先切块。
 * 这些用例把「不丢采样、块不超限、尽量切在静音上」全部钉死，不需要真机。
 */
class VoiceChunkerTest {

    private val max = VoiceChunker.MAX_CHUNK_SAMPLES

    private fun rmsOf(samples: FloatArray, speaking: (Int) -> Boolean): FloatArray {
        val frames = samples.size / VoiceDsp.FRAME_SAMPLES
        return FloatArray(frames) { f ->
            if (speaking(f * VoiceDsp.FRAME_SAMPLES)) 0.5f else 0.001f
        }
    }

    @Test
    fun `短音频只有一块且不切`() {
        val samples = FloatArray(5 * VoiceDsp.SAMPLE_RATE)
        val chunks = VoiceChunker.split(samples, rmsOf(samples) { true }, 0.02f)
        assertEquals(1, chunks.size)
        assertEquals(0 to samples.size - 1, chunks[0].first to chunks[0].last)
    }

    @Test
    fun `一百零六秒音频被切成不超过十秒的块`() {
        val samples = FloatArray(106 * VoiceDsp.SAMPLE_RATE)
        val chunks = VoiceChunker.split(samples, rmsOf(samples) { true }, 0.02f)
        assertTrue("块数应 > 10", chunks.size > 10)
        chunks.forEach { assertEquals("每块 ≤ 10 秒", true, it.last - it.first + 1 <= max) }
        assertEquals("首块从头开始", 0, chunks.first().first)
        assertEquals("末块到音频末尾", samples.size - 1, chunks.last().last)
    }

    @Test
    fun `块之间首尾相接不丢采样不重叠`() {
        val samples = FloatArray(63 * VoiceDsp.SAMPLE_RATE + 777)
        val chunks = VoiceChunker.split(samples, rmsOf(samples) { true }, 0.02f)
        var expectedStart = 0
        chunks.forEach { c ->
            assertEquals("块起点必须接上一块", expectedStart, c.first)
            expectedStart = c.last + 1
        }
        assertEquals("必须覆盖到最后一个采样", samples.size, expectedStart)
        val total = chunks.sumOf { it.last - it.first + 1 }
        assertEquals("总采样数必须等于输入", samples.size, total)
    }

    @Test
    fun `切点尽量落在静音处`() {
        // 每 1 秒里最后 0.2 秒是静音：切点应落在静音帧附近，而不是硬切在第 10 秒
        val seconds = 20
        val samples = FloatArray(seconds * VoiceDsp.SAMPLE_RATE)
        val chunks = VoiceChunker.split(
            samples,
            rmsOf(samples) { i -> (i % VoiceDsp.SAMPLE_RATE) < VoiceDsp.SAMPLE_RATE - VoiceDsp.SAMPLE_RATE / 5 },
            0.02f,
        )
        chunks.dropLast(1).forEach { c ->
            val cutSample = c.last + 1
            val phase = cutSample % VoiceDsp.SAMPLE_RATE
            assertTrue(
                "切点应在每秒的静音段（相位于 ${phase}），实际 ${cutSample}",
                phase >= VoiceDsp.SAMPLE_RATE - VoiceDsp.SAMPLE_RATE / 5 - VoiceDsp.FRAME_SAMPLES,
            )
        }
    }

    @Test
    fun `全静音也不许丢音频`() {
        val samples = FloatArray(47 * VoiceDsp.SAMPLE_RATE)
        val chunks = VoiceChunker.split(samples, rmsOf(samples) { false }, 0.02f)
        assertEquals("全静音按 10 秒硬切", 5, chunks.size)
        assertEquals(samples.size - 1, chunks.last().last)
    }

    @Test
    fun `解码路径必须走切块而不是一次性整段`() {
        val src = TestSources.rawSource("src/main/java/com/jinn/inputmethod/LocalSenseVoiceClient.kt")
        assertTrue("必须存在分块解码", src.contains("decodeChunked"))
        assertTrue("必须调用 VoiceChunker", src.contains("VoiceChunker.split"))
        val fn = TestSources.blockAfter(src, "private fun decodeSamples")
        assertTrue("decodeSamples 兜底不许直接单流解整段", !fn.contains("acceptWaveform(samples, SAMPLE_RATE)"))
    }
}
