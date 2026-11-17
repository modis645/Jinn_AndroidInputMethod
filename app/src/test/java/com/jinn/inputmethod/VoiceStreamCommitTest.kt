package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式提交台账的纯逻辑单测（不需要模型、不需要真机）。
 *
 * 这些断言守的是「结束→出字」与「不丢话」这两条真机口径：
 *  · 录音期间解出来的句子必须真的进台账（否则结束还是要整段重解，等于没做）；
 *  · 噪声段（解不出字）也必须推进消费量，否则覆盖率虚低、被误判成不可信；
 *  · 覆盖不足一半必须退回整段解码（2026-11-16 的「只上屏一个字」教训）。
 */
class VoiceStreamCommitTest {

    private val sr = 16_000

    @Test
    fun `解出的句子按句界补标点`() {
        val log = VoiceStreamCommit()
        log.add("今天天气不错", sr * 3)
        log.add("我们出去走走", sr * 2)
        assertEquals("今天天气不错，我们出去走走。", log.text())
    }

    @Test
    fun `模型自带句末标点的不再补`() {
        val log = VoiceStreamCommit()
        log.add("今天天气不错。", sr * 3)
        log.add("我们出去走走？", sr * 2)
        assertEquals("今天天气不错。我们出去走走？", log.text())
    }

    @Test
    fun `空片也要推进消费量，不能只算有字的`() {
        val log = VoiceStreamCommit()
        log.add(null, sr * 2)          // 喘气段
        log.consume(sr * 1)            // 太短的噪声
        log.add("真正的一句话", sr * 4)
        assertEquals(sr * 7, log.consumedSamples)
        assertEquals(1, log.pieceCount)
        assertFalse("覆盖 7/10 不该退回整段解码", log.needsFallback(sr * 10))
    }

    @Test
    fun `一句都没解出来时必须退回整段解码`() {
        val log = VoiceStreamCommit()
        log.consume(sr * 20)
        assertTrue(log.needsFallback(sr * 30))
        assertEquals("", log.text())
    }

    @Test
    fun `覆盖不到一半时必须退回整段解码`() {
        val log = VoiceStreamCommit()
        log.add("只听清开头", sr * 5)
        assertTrue("覆盖 5/30 = 17%，不可信", log.needsFallback(sr * 30))
        log.add("后半段也听清了", sr * 12)
        assertFalse("覆盖 17/30 = 57%，可信", log.needsFallback(sr * 30))
    }

    @Test
    fun `覆盖判据的边界就是一半`() {
        val log = VoiceStreamCommit()
        log.add("刚好一半", sr * 5)
        assertTrue("恰好 50% 仍按可信处理（< 才退回）", !log.needsFallback(sr * 10))
        log.reset()
        log.add("差一点", sr * 5)
        assertTrue(log.needsFallback(sr * 11))
    }

    @Test
    fun `reset 之后覆盖量与句子都要清空`() {
        val log = VoiceStreamCommit()
        log.add("一句话", sr * 3)
        log.reset()
        assertEquals(0, log.consumedSamples)
        assertEquals(0, log.pieceCount)
        assertEquals("", log.text())
        assertTrue(log.needsFallback(sr * 3))
    }

    @Test
    fun `预览文本不补标点，转录不重复`() {
        val log = VoiceStreamCommit()
        log.add("第一句", sr * 2)
        log.add("第二句", sr * 2)
        assertEquals("第一句第二句", log.previewText())
        // 同一段音频绝不重复进台账（两段重叠会把同一句话上屏两次）
        assertEquals(4 * sr, log.consumedSamples)
    }
}
