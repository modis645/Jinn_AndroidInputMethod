package com.jinn.inputmethod

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自造词参与简拼 / 混拼（[PinyinEngine.abbreviationCandidates] 里的 `collectUserWords`）。
 *
 * 2026-11-16 用户实证：一个词组全拼打 6 次进了用户词库，**首字母却一直打不出来**。
 * 根因是快筛写成了 `if (!initialLettersOf(key).let { it == null || !it.startsWith(p) }) continue` ——
 * 双重取反把判据整体翻过来：首字母**对得上**的自造词被 continue 掉，对不上的才走到 DP。
 * 这条守卫钉住「判据方向」与「自造词不依赖内置索引」两点，别再写回去。
 */
class UserWordAbbrevTest {

    private val code = TestSources.codeOf(TestSources.rawSourceOfShortName("PinyinEngine.kt"))

    @Test
    fun `自造词快筛只允许跳过前缀对不上的词`() {
        assertTrue(
            "自造词快筛必须写成「前缀对不上才跳过」",
            "if (initials != null && !initials.startsWith(projectionLetters)) continue" in code,
        )
        assertFalse(
            "不得再出现双重取反的写法（会把命中的自造词筛掉）",
            "it == null || !it.startsWith(projectionLetters)" in code,
        )
    }

    @Test
    fun `自造词不依赖内置词库索引`() {
        assertTrue(
            "内置索引缺失时不能整段放弃（自造词不在那个索引里）",
            "val index: Array<Pair<String, String>> = initialsIndex ?: emptyArray()" in code,
        )
        assertTrue("每种切法都要跑一遍自造词", "collectUserWords(segs)" in code)
    }
}
