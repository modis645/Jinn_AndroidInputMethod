package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分段缓存（2026-11-17「方案三」）的两条纯判据。
 *
 * 真机上「结束要等多久」取决于缓存命中与否，而命中判据与标点拼接都是纯函数：
 * 这里钉住「覆盖不足必须退回整段解码」和「缓存句 + 尾巴按同一条标点规则拼」。
 */
class VoiceTailMergeTest {

    @Test
    fun `缓存句与尾巴按同一标点规则拼接`() {
        val text = VoiceTailMerge.merge(listOf("今天天气不错", "我们去公园"), "然后吃饭")
        assertEquals("今天天气不错，我们去公园，然后吃饭。", text)
    }

    @Test
    fun `尾巴为空时只拼缓存句`() {
        assertEquals("今天天气不错，我们去公园。", VoiceTailMerge.merge(listOf("今天天气不错", "我们去公园"), ""))
    }

    @Test
    fun `缓存为空时只剩尾巴`() {
        assertEquals("然后吃饭。", VoiceTailMerge.merge(emptyList(), "然后吃饭"))
    }

    @Test
    fun `空白句不参与拼接`() {
        assertEquals("第一句，第二句。", VoiceTailMerge.merge(listOf("第一句", "  ", "第二句"), ""))
        assertEquals("", VoiceTailMerge.merge(emptyList(), ""))
    }

    @Test
    fun `覆盖不足三分之一不许用缓存`() {
        // 7~9 秒正常说话被 VAD 判成大半静音，覆盖率极低 —— 宁可慢，不许丢话
        assertFalse(VoiceTailMerge.isCacheUsable(count = 3, coveredSamples = 1_000, totalSamples = 16_000 * 10))
        assertTrue(VoiceTailMerge.isCacheUsable(count = 3, coveredSamples = 16_000 * 5, totalSamples = 16_000 * 10))
    }

    @Test
    fun `没有缓存句就一律不走缓存`() {
        assertFalse(VoiceTailMerge.isCacheUsable(count = 0, coveredSamples = 16_000 * 9, totalSamples = 16_000 * 10))
        assertFalse(VoiceTailMerge.isCacheUsable(count = 2, coveredSamples = 0, totalSamples = 0))
    }
}
