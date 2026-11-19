package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 收录口径（2026-11-17 修正）：
 *
 *  - 门槛按**词**计：全拼 / 简拼 / 混拼换着打同一个词，也算连续；
 *  - 自造词读音用「字读音表」回填成完整音节，简拼 / 混拼收录后三条键路都要能打出；
 *  - 回填不出来（生僻字没有读音）就不收，宁可不收也不写死条目。
 *
 * 这些用例对应真机上报：「全拼输入 6 次、词库收了，但首字母打不出来」。
 */
class UserWordAdmissionRulesTest {

    private val chars = """
        liu	刘
        zhi	志
        wu	武
        wo	我
        kan	看
        ma	马
        ma	麻
        fan	烦
        yin	银
        xing	行
        hang	行
    """.trimIndent()
    private val phrases = """
        wokan	我看
    """.trimIndent()

    private val syllables = """
        liu
        zhi
        wu
        wo
        kan
        ma
        fan
        yin
        xing
        hang
    """.trimIndent()

    @Before
    fun setUp() {
        UserFrequency.resetForTest()
        UserFrequency.loadForTest(true)
        PinyinEngine.clearUserWordsForTest()
        PinyinEngine.loadFromTexts(chars = chars, phrases = phrases, syllables = syllables)
    }

    /** 与 PinyinKeyboardView.recordLedgerIfNew 同序：门槛 → 读音（词典查不到就用键入串推导）→ 进池 */
    private fun pickLikeUser(word: String, typed: String): Boolean {
        val admit = PinyinEngine.shouldAdmitUserWord(word, typed)
        if (admit) {
            val reading = PinyinEngine.truePinyinOf(word)
                ?: PinyinEngine.readingFromTyped(word, typed).orEmpty()
            PinyinEngine.noteUserWord(word, reading)
        }
        return admit
    }

    @Test
    fun `简拼连续三次选中即收录_全拼简拼混拼三条键路都能打出`() {
        assertFalse("第 1 次不收", pickLikeUser("刘志武", "lzw"))
        assertFalse("第 2 次不收", pickLikeUser("刘志武", "lzw"))
        assertTrue("简拼连续第 3 次必须收录", pickLikeUser("刘志武", "lzw"))

        assertTrue("全拼 liuzhiwu", PinyinEngine.query("liuzhiwu").candidates.contains("刘志武"))
        assertTrue("简拼 lzw", PinyinEngine.query("lzw").candidates.contains("刘志武"))
        assertTrue("混拼 lzhiwu", PinyinEngine.query("lzhiwu").candidates.contains("刘志武"))
    }

    @Test
    fun `换拼法仍算连续三次_连击按词计而不是按键入键计`() {
        // 老实现按键入键计：换一种拼法连击就归零，只剩累计 6 次那条路
        assertFalse(pickLikeUser("刘志武", "liuzhiwu"))
        assertFalse(pickLikeUser("刘志武", "lzw"))
        assertTrue("第三次（混拼）必须收录", pickLikeUser("刘志武", "lzhiwu"))
        assertTrue(PinyinEngine.query("lzw").candidates.contains("刘志武"))
        assertTrue(PinyinEngine.query("liuzhiwu").candidates.contains("刘志武"))
        assertTrue(PinyinEngine.query("lzhiwu").candidates.contains("刘志武"))
    }

    @Test
    fun `混拼累计六次收录_中间穿插别的词`() {
        // 陪练词必须各打两次（否则它们自己也会满 6 次被收录 —— 那是另一条门槛的正常行为）
        val fillers = listOf("我看" to "wokan", "刘志" to "liuzhi", "志武" to "zhiwu")
        var admitted = false
        repeat(12) { i ->
            if (i % 2 == 0) {
                admitted = pickLikeUser("刘志武", "lzhiwu") || admitted
            } else {
                val filler = fillers[(i / 2) % fillers.size]
                assertFalse("陪练词 ${filler.first} 不该被收录", pickLikeUser(filler.first, filler.second))
            }
        }
        assertTrue("混拼累计满 6 次必须收录", admitted)
        assertTrue(PinyinEngine.query("lzhiwu").candidates.contains("刘志武"))
    }

    @Test
    fun `读音回填_简拼与混拼都落成完整音节`() {
        assertEquals("lzw 应回填成 liu zhi wu", "liu zhi wu", PinyinEngine.readingFromTyped("刘志武", "lzw"))
        assertEquals("lzhiwu 应回填成 liu zhi wu", "liu zhi wu", PinyinEngine.readingFromTyped("刘志武", "lzhiwu"))
        assertEquals("全拼走老路径", "liu zhi wu", PinyinEngine.readingFromTyped("刘志武", "liuzhiwu"))
    }

    @Test
    fun `单字母段回填成整音节_不会把马记成m`() {
        assertEquals("m 段要回填成 ma", "ma fan", PinyinEngine.readingFromTyped("麻烦", "mf"))
    }

    @Test
    fun `读音凑不出来就不收_生僻字没有读音`() {
        assertNull(
            "「甲」不在读音表里，回填不出来 —— 宁可这条词不收，也不写一条打不出来的死记录",
            PinyinEngine.readingFromTyped("刘志武甲", "lzwj"),
        )
        // 字数与段数对不上（键入比字多）同样不收
        assertNull(PinyinEngine.readingFromTyped("刘志武", "lzwx"))
    }
}
