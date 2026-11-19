package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

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

    /**
     * 用户实测第二轮：`liuzhixia` 仍混出 xia 的字（侠/霞/瑕/狎）。
     *
     * 根因是 `query()` 里还有一段「末尾音节单字联想」：`xia` 同时是 xian/xiao/xiang 的合法前缀，
     * 被 `lastIsFakeComplete` 判定成「残码」，于是把 xia 的整张字表追加到候选尾部。
     * 这段已删除；这里钉住整串输入（含末尾完整音节、末尾残码两种）都只出首音节的字。
     */
    @Test
    fun `末尾音节的字不得在整串输入时出现`() {
        PinyinEngine.resetForTest()
        PinyinEngine.loadFromTexts(
            chars = listOf(
                "liu\t刘,陆,六",
                "zhi\t只,之,知",
                "xia\t侠,霞,瑕,狎",
            ).joinToString("\n"),
            phrases = "",
            syllables = listOf("liu", "zhi", "xia", "xian", "xiao", "xiang").joinToString("\n"),
        )

        // 末尾完整音节：xia 也是 xian/xiao 的前缀（旧路径的触发条件）
        val full = PinyinEngine.query("liuzhixia").candidates
        assertTrue("首音节字必须出现，实际=$full", full.contains("刘"))
        for (c in listOf("侠", "霞", "瑕", "狎", "只", "之", "知")) {
            assertFalse("整串输入不得出现后续音节字 $c，实际=$full", full.contains(c))
        }

        // 末尾残码：zhi 也不得提前出现（此时用户还没选掉 liu）
        val withPartial = PinyinEngine.query("liuzhixi").candidates
        for (c in listOf("只", "之", "知")) {
            assertFalse("末尾残码阶段不得出现第二音节字 $c，实际=$withPartial", withPartial.contains(c))
        }
    }

    /**
     * 字表口径：`陆` 只保留 `lu` 一路，`liu` 里去掉了。
     *
     * 说明：`陆` 确有 `liù`（“六”的大写，账目用），但这是极低频义项；上游字表把 it 排在 liu 第 13 位，
     * 导致打 `liuzhixia` 首屏就能看到「陆」——用户实测判为错误读音。财务大写场景仍可从 `lu` 取到 `陆`。
     */
    @Test
    fun `字表里陆不再挂在liu下_仍保留在lu下`() {
        val chars = run {
            for (p in listOf("src/main/assets/pinyin_chars.txt.xz", "app/src/main/assets/pinyin_chars.txt.xz")) {
                val f = File(p)
                if (f.isFile) {
                    return@run org.tukaani.xz.XZInputStream(f.inputStream()).use {
                        String(it.readBytes(), Charsets.UTF_8)
                    }
                }
            }
            error("找不到 pinyin_chars.txt.xz")
        }
        val liu = chars.lineSequence().first { it.startsWith("liu\t") }
        val lu = chars.lineSequence().first { it.startsWith("lu\t") }
        assertFalse("liu 键里不应再有 陆：$liu", "陆" in liu)
        assertTrue("lu 键里必须保留 陆：$lu", "陆" in lu)
    }
}
