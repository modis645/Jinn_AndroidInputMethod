package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语音上屏文本的标点规则。
 *
 * 这些规则直接对应两条真机反馈：「标点符号好像加的比较少」「语音结束后留下一堆多余标点」。
 * 之前它们埋在 [LocalSenseVoiceClient] / [JinnIme] 的 private 方法里，只能靠装机试；
 * 现在集中到 [VoiceText]，由 JVM 单测逐条钉住。
 */
class VoiceTextTest {

    @Test
    fun `分句拼接_句间补逗号_末句补句号`() {
        assertEquals("今天天气不错。", VoiceText.joinSentences(listOf("今天天气不错")))
        assertEquals(
            "先开会，再说别的。",
            VoiceText.joinSentences(listOf("先开会", "再说别的")),
        )
    }

    @Test
    fun `分句拼接_模型自带句末标点的不重复补`() {
        assertEquals(
            "外面下雨了。记得带伞！",
            VoiceText.joinSentences(listOf("外面下雨了。", "记得带伞！")),
        )
        // 问号属于句末：后面不再补逗号
        assertEquals(
            "你是谁？我来找你。",
            VoiceText.joinSentences(listOf("你是谁？", "我来找你")),
        )
    }

    @Test
    fun `分句拼接_空句跳过不留孤立标点`() {
        assertEquals("你好。", VoiceText.joinSentences(listOf("", "  ", "你好")))
        assertEquals("", VoiceText.joinSentences(emptyList()))
    }

    @Test
    fun `整段收尾_缺句号才补`() {
        assertEquals("测试一句话。", VoiceText.finishText("测试一句话"))
        assertEquals("测试一句话！", VoiceText.finishText("测试一句话！"))
        assertEquals("", VoiceText.finishText("   "))
    }

    @Test
    fun `句尾剥离_只去末尾逗号句号`() {
        assertEquals("你好", VoiceText.stripTrailingPunc("你好，"))
        assertEquals("你好", VoiceText.stripTrailingPunc("你好。"))
        assertEquals("你好", VoiceText.stripTrailingPunc("你好，。  "))
        // 句中 / 末尾问号感叹号保留
        assertEquals("你好，世界？", VoiceText.stripTrailingPunc("你好，世界？"))
    }

    @Test
    fun `句尾剥离后为空串不算异常`() {
        assertEquals("", VoiceText.stripTrailingPunc("，。"))
        assertTrue("剥空后必须是空串而不是原文", VoiceText.stripTrailingPunc("。").isEmpty())
    }
}
