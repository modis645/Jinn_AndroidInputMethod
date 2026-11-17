package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分段缓存的喂数账本（2026-11-17「结束等 70 秒」的真根因）。
 *
 * 真机现象：录音过程中「边说边出」看起来正常，点结束后却要等 70 秒 —— 说明结束时走了
 * 整段重解（缓存被判为空/不可用）。根因是 silero 的 Vad 按 `windowSize=512` 采样推进，
 * 我们把「一拍采集到的一坨」原样喂给它，尾数滞留在它内部缓冲，段迟迟不吐。
 *
 * 这些用例不需要模型、不需要真机：用一个「只接受 512 整数倍」的假 VAD 模拟 silero 的
 * 契约，验证 WindowFeeder 永远不丢采样、永远只交整窗口。
 */
class WindowFeederTest {

    /** 假 silero：收到非整数倍就记违约，收到整块就当「推进一步」 */
    private class FakeSilero(private val window: Int) {
        var violations = 0
        var steps = 0
        var samples = ArrayList<Float>()

        fun accept(chunk: FloatArray) {
            if (chunk.size % window != 0) violations++
            steps++
            chunk.forEach { samples.add(it) }
        }
    }

    @Test
    fun `任意长度一拍都会被攒成整窗口`() {
        val feeder = WindowFeeder(512)
        val vad = FakeSilero(512)
        // 模拟采集节拍：每次 700 采样（0.0437 秒），共 100 拍
        repeat(100) { tick ->
            val base = tick * 700
            feeder.feed(FloatArray(700) { i -> (base + i).toFloat() }, vad::accept)
        }
        assertEquals("喂给 silero 的必须永远整窗口", 0, vad.violations)
        assertEquals("喂进去的采样数必须是窗口整数倍", 0, vad.samples.size % 512)
        assertEquals("喂进去 + 滞留 = 采集到的", 100 * 700, vad.samples.size + feeder.pending)
        assertTrue("滞留永远不足一个窗口", feeder.pending < 512)
        assertTrue("确实推进了", vad.steps > 0)
    }

    @Test
    fun `数据顺序不被打乱`() {
        val feeder = WindowFeeder(512)
        val got = ArrayList<Float>()
        var n = 0f
        repeat(10) {
            val chunk = FloatArray(300) { n++ }
            feeder.feed(chunk) { got.addAll(it.toList()) }
        }
        // 攒够窗口后吐出来的前缀必须与采集顺序一致
        got.forEachIndexed { i, v -> assertEquals("第 $i 个采样", i.toFloat(), v, 0.0f) }
    }

    @Test
    fun `一拍不够一个窗口就完全不喂`() {
        val feeder = WindowFeeder(512)
        var calls = 0
        val fed = feeder.feed(FloatArray(500)) { calls++ }
        assertEquals("不足 512 不喂", 0, fed)
        assertEquals(0, calls)
        assertEquals(500, feeder.pending)
    }

    @Test
    fun `细碎小拍最终能凑成整窗口`() {
        val feeder = WindowFeeder(512)
        var fedTotal = 0
        repeat(52) {
            fedTotal += feeder.feed(FloatArray(10)) { }
        }
        assertEquals("52×10=520 采样，应吐出 512", 512, fedTotal)
        assertEquals("剩 8 采样滞留", 8, feeder.pending)
    }

    @Test
    fun `空拍不改账本`() {
        val feeder = WindowFeeder(512)
        assertEquals(0, feeder.feed(FloatArray(0)) { })
        assertEquals(0, feeder.pending)
    }

    @Test
    fun `窗口取 512 与 silero 配置一致`() {
        // sileroVadModelConfig(windowSize = 512) —— 两边必须同一个数，否则一段都不出
        assertEquals(512, VAD_WINDOW_SAMPLES)
        val src = TestSources.rawSource("app/src/main/java/com/jinn/inputmethod/VoiceStreamCache.kt")
        assertTrue("分段缓存必须走 WindowFeeder", src.contains("WindowFeeder(VAD_WINDOW_SAMPLES)"))
    }
}
