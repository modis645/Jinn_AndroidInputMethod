package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 未选词阶段的候选必须是「前缀口径」（2026-11-19，用户实测反馈）。
 *
 * 用户场景：新造词「张麻子」还没进词库，输入 `zhangmazi`，旧候选里除了 zhang / zhangma
 * 的词语外，还混进了 `ma`、`zi` 各自的单字（麻/马、字/自）——看起来像「随便挑一段拼音凑字」。
 * 期望的行为是**按拼音顺序依次出**：先给首音节的字/前缀词，选掉一个音节后缓冲区缩短，
 * 再给下一音节。这条用例把该口径钉住，防止以后有人把「各音节单字叠加」加回来。
 */
class PrefixOnlyCharCandidatesTest {

    @Before
    fun setUp() {
        PinyinEngine.resetForTest()
        PinyinEngine.loadFromTexts(
            chars = listOf(
                "zhang\t张,章,彰",
                "ma\t麻,马,码",
                "zi\t字,自,子",
            ).joinToString("\n"),
            // 「张麻」是词库里已有的前缀词：整串 `zhangmazi` 查不到词时，它仍应作为前缀词出现
            phrases = "zhangma\t张麻",
            syllables = listOf("zhang", "ma", "zi").joinToString("\n"),
        )
    }

    @Test
    fun `整串输入只出首音节单字`() {
        val got = PinyinEngine.query("zhangmazi").candidates
        assertTrue("首音节单字必须出现，实际=$got", got.contains("张"))
        for (c in listOf("麻", "马", "码", "字", "自", "子")) {
            assertFalse("未选词阶段不得出现后续音节单字 $c，实际=$got", got.contains(c))
        }
    }

    @Test
    fun `前缀词仍然出现`() {
        val got = PinyinEngine.query("zhangmazi").candidates
        assertTrue("zhangma 对应的前缀词「张麻」应出现，实际=$got", got.contains("张麻"))
    }

    @Test
    fun `选掉首音节后缓冲缩短_下一音节成为首音节`() {
        val first = PinyinEngine.consumption("zhangmazi", "张")
        assertEquals("选「张」应只消费 zhang 这 5 个字母", 5, first.quanpinChars)

        val rest = "zhangmazi".substring(first.quanpinChars)   // mazi
        val second = PinyinEngine.query(rest).candidates
        assertTrue("残码 mazi 应出 ma 的字，实际=$second", second.contains("麻"))
        assertFalse("残码 mazi 不得提前出 zi 的字，实际=$second", second.contains("字"))

        val third = PinyinEngine.consumption(rest, "麻")
        assertEquals("选「麻」应只消费 ma 这 2 个字母", 2, third.quanpinChars)
        val tail = rest.substring(third.quanpinChars)          // zi
        assertTrue("最后一段 zi 应出 zi 的字", PinyinEngine.query(tail).candidates.contains("字"))
    }

    @Test
    fun `选前缀词后残码保留`() {
        val c = PinyinEngine.consumption("zhangmazi", "张麻")
        assertEquals("选「张麻」应消费 zhangma 这 7 个字母", 7, c.quanpinChars)
        assertEquals("zi", "zhangmazi".substring(c.quanpinChars))
    }
}
