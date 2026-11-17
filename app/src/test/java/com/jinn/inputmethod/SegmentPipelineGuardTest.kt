package com.jinn.inputmethod

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分段链路的源码守卫（2026-11-17 两次真机反馈收口后的定版口径）。
 *
 * 定版结构，两条不许被改回去：
 *  ① **预览**：每拍只解「最后 ≤8 秒」这一条尾巴（8 秒实测 115ms），写进组合态；
 *  ② **上屏**：结束那一刻对整段音频做一次完整解码（长音频走 ≤10 秒分块），
 *     不掺任何「录音期间逐句解出来的分段句」。
 *
 * 为什么要钉住 ②：2026-11-17 真机证明「分段句拼接」当最终文本会掉字、串字，
 * 识别准确率明显下降；宁愿多花 1~2 秒分块解码，也不能丢准确率。
 */
class SegmentPipelineGuardTest {

    private fun clientSource(): String =
        TestSources.rawSource("src/main/java/com/jinn/inputmethod/LocalSenseVoiceClient.kt")

    @Test
    fun `上屏只用一次完整解码，不拼分段句`() {
        val src = clientSource()
        val rec = TestSources.blockAfter(src, "override fun recognize")
        assertTrue("必须走整段解码", rec.contains("decodePcm16(pcm16, withVad = true)"))
        assertFalse("不许拼分段句", rec.contains("VoiceTailMerge"))
        assertFalse("不许读分段缓存的句子", rec.contains("streamCache"))
    }

    @Test
    fun `预览只解尾巴且节流`() {
        val src = clientSource()
        val fn = TestSources.blockAfter(src, "private fun emitPreview")
        assertTrue("尾巴长度受 PREVIEW_TAIL_SAMPLES 限制", fn.contains("PREVIEW_TAIL_SAMPLES"))
        assertTrue("必须节流", fn.contains("PREVIEW_MIN_INTERVAL_MS"))
        assertTrue("尾巴只解不切句", fn.contains("withVad = false"))
        assertTrue("6 秒尾巴（RTF 0.30 下约 1.8 秒）", src.contains("PREVIEW_TAIL_SAMPLES = 6 * SAMPLE_RATE"))
    }

    @Test
    fun `预览节拍必须随实际耗时自适应`() {
        // 固定 1.2 秒一拍 → 慢机器上预览线程永远饱和，最终解码抢不到 CPU
        val src = clientSource()
        val fn = TestSources.blockAfter(src, "private fun emitPreview")
        assertTrue("节拍要按上次耗时 ×2 歇拍", fn.contains("lastPreviewCostMs * 2"))
        assertTrue("并保留下限", fn.contains("PREVIEW_MIN_INTERVAL_MS"))
    }

    @Test
    fun `单次解码不超过十秒`() {
        val src = clientSource()
        assertTrue("必须存在分块解码", src.contains("decodeChunked"))
        assertTrue("必须调用 VoiceChunker", src.contains("VoiceChunker.split"))
        assertFalse(
            "decodeSamples 不许单流解整段",
            TestSources.blockAfter(src, "private fun decodeSamples").contains("acceptWaveform(samples, SAMPLE_RATE)"),
        )
    }

    @Test
    fun `识别器用 4 线程`() {
        // 110 秒音频的解码总账 ≈ RTF×时长（真机实测 RTF 0.30），并行度是唯一能直接缩短它的旋钮
        assertTrue(clientSource().contains("numThreads = 4"))
    }
}
