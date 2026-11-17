package com.jinn.inputmethod

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长语音链路的源码守卫（2026-11-17 定版口径）。
 *
 * 定版结构，这几条不许被改回去：
 *  ① **录音期间**：按 `window*5` 整块喂同一个 silero，切出闭合句子就立刻解码记进
 *     [VoiceStreamCommit] —— 解码成本摊在录音过程里；
 *  ② **结束时**：只解最后那句残留（[LocalSenseVoiceClient] 的 decodeRemainder），
 *     一次 decode 绝不超过 10 秒；
 *  ③ **兜底**：流式一句没解出来 / 覆盖不到一半 ⇒ 退回整段解码（宁可慢，不许丢话）；
 *  ④ 切句与兜底必须**共用同一套 VAD 参数**（newVad 两处调用），否则切点会走两套口径。
 */
class SegmentPipelineGuardTest {

    private fun client(): String =
        TestSources.rawSource("src/main/java/com/jinn/inputmethod/LocalSenseVoiceClient.kt")

    @Test
    fun `录音期间就要切句解码`() {
        val src = client()
        assertTrue("必须有流式循环", src.contains("startStreamLoop"))
        assertTrue("必须边喂边取句子", src.contains("drainSegments"))
        assertTrue("解出来的句子要进台账", src.contains("commit.add(piece"))
        assertFalse("不许再出现已删除的分段缓存", src.contains("streamCache"))
    }

    @Test
    fun `结束只补残留，不整段重解`() {
        val src = client()
        val rec = TestSources.blockAfter(src, "override fun recognize")
        assertTrue("结束走残留补解", rec.contains("decodeRemainder"))
        assertTrue("流式不可信才兜底", rec.contains("commit.needsFallback"))
        val idxStream = rec.indexOf("decodeRemainder")
        val idxFallback = rec.indexOf("decodePcm16(pcm16, withVad = true)")
        assertTrue("兜底必须排在流式收尾之后", idxStream >= 0 && idxFallback > idxStream)
        val rest = TestSources.blockAfter(src, "private fun decodeRemainder")
        assertTrue("残留也走 ≤10 秒分块", rest.contains("decodeSamples(slice, withVad = false)"))
    }

    @Test
    fun `切句与兜底共用同一套 VAD 参数`() {
        val src = client()
        assertTrue("必须有统一的 newVad", src.contains("private fun newVad"))
        assertTrue("录音期切句用它", TestSources.blockAfter(src, "private fun openVad").contains("newVad(vadPath)"))
        assertTrue("整段兜底也用它", TestSources.blockAfter(src, "private fun splitByVad").contains("newVad(vadPath)"))
        assertFalse("不许在兜底里另起一套参数", src.contains("maxSpeechDuration = 1e9f"))
    }

    @Test
    fun `预览只解尾巴且节流`() {
        val src = client()
        val fn = TestSources.blockAfter(src, "private fun emitPreview")
        assertTrue("尾巴长度受 PREVIEW_TAIL_SAMPLES 限制", fn.contains("PREVIEW_TAIL_SAMPLES"))
        assertTrue("必须节流", fn.contains("PREVIEW_MIN_INTERVAL_MS"))
        assertTrue("尾巴只解不切句", fn.contains("withVad = false"))
        assertTrue("6 秒尾巴（RTF 0.30 下约 1.8 秒）", src.contains("PREVIEW_TAIL_SAMPLES = 6 * SAMPLE_RATE"))
    }

    @Test
    fun `预览节拍必须随实际耗时自适应`() {
        val src = client()
        val fn = TestSources.blockAfter(src, "private fun emitPreview")
        assertTrue("节拍要按上次耗时 ×2 歇拍", fn.contains("lastPreviewCostMs * 2"))
    }

    @Test
    fun `单次解码不超过十秒`() {
        val src = client()
        assertTrue("必须存在分块解码", src.contains("decodeChunked"))
        assertTrue("必须调用 VoiceChunker", src.contains("VoiceChunker.split"))
        assertFalse(
            "decodeChunked 不许单流解整段",
            TestSources.blockAfter(src, "private fun decodeChunked").contains("acceptWaveform(samples, SAMPLE_RATE)"),
        )
    }

    @Test
    fun `识别器用 4 线程`() {
        assertTrue(client().contains("numThreads = 4"))
    }
}
