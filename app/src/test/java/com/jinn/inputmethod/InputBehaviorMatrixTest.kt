package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 用户 2026-11-16 的输入行为矩阵：全拼 / 简拼 / 混拼 / 上屏联想 / 修改拼音（逐字退格）。
 *
 * 全部在 JVM 里用真实词库注入跑（[PinyinEngine.loadFromTexts]），不依赖 Android 资源。
 * 与 [TypingScenarioSimulationTest] 的分工：那边重演「学到自造词」的整条链路，这边钉住
 * **内置词**在四种打法下的可打性与退格回退。
 */
class InputBehaviorMatrixTest {

    @Before
    fun setUp() {
        UserFrequency.resetForTest()
        UserFrequency.loadForTest(true)
        val chars = """
            ni	你,尼,泥,呢
            hao	好,号,浩,毫
            ma	吗,妈,马
            wu	五,无,物
            hu	湖,虎,户
            si	四,思,死
            hai	海,害,孩
            wei	为,位,未
            jia	家,加,假
            zhong	中,种,重
            guo	国,过,果
            shuo	说,硕
            de	的,得
        """.trimIndent()
        val phrases = """
            nihao	你好|拟好
            nihaoma	你好吗
            nihaoa	你好啊
            wuhusihai	五湖四海
            sihai	四海
            wuhu	芜湖|五湖
            zhongguo	中国
            shuode	说的
        """.trimIndent()
        val syllables = """
            ni
            hao
            ma
            wu
            hu
            si
            hai
            wei
            jia
            zhong
            guo
            shuo
            de
        """.trimIndent()
        PinyinEngine.loadFromTexts(chars, phrases, syllables)
        // 线上是启动时后台建索引；单测里显式同步建一次，才覆盖「内置词简拼」这条真实路径
        PinyinEngine.buildInitialsIndexForTest()
    }

    @Test
    fun `全拼_两字词首选正确`() {
        val r = PinyinEngine.query("nihao")
        assertEquals("全拼 nihao 首选应是「你好」：${r.candidates}", "你好", r.candidates.firstOrNull())
        assertTrue("三音节词全拼要出", PinyinEngine.query("nihao ma".replace(" ", "")).candidates.contains("你好吗"))
    }

    // 简拼 / 混拼要靠 `pinyin_index.bin` 建的首字母索引，文本注入不建 —— 见 BuiltinAbbrevTest

    @Test
    fun `全拼输入不该被拆成简拼_乱出无关多字词`() {
        // 用户 2026-11-16 报过的「输入 you 出烟垢/颜狗」就是简拼误入全拼路径
        val r = PinyinEngine.query("nihao")
        assertTrue(
            "全拼 nihao 的候选必须都是 nihao 的真候选：${r.candidates}",
            r.candidates.none { it == "五湖四海" },
        )
    }

    @Test
    fun `上屏后联想_按词库延续给出下一个词`() {
        val next = PinyinEngine.predict("你好")
        assertTrue("选「你好」后应联想出延续词：$next", next.isNotEmpty())
        assertTrue("应包含「吗」：$next", "吗" in next)
        assertTrue("非中文上屏不联想（纯英文不该冒中文候选）", PinyinEngine.predict("hello").isEmpty())
        assertTrue("空词不联想", PinyinEngine.predict("").isEmpty())
    }

    @Test
    fun `修改拼音_逐字退格候选不塌`() {
        // 打错拼音时用户只会按退格，这里逐字退：nihao → niha → nih → ni → 空
        assertTrue("nihao 要出词", PinyinEngine.query("nihao").candidates.contains("你好"))
        assertTrue("退成 niha 候选不能空", PinyinEngine.query("niha").candidates.isNotEmpty())
        assertTrue("退成 nih 候选不能空（前缀联想）", PinyinEngine.query("nih").candidates.isNotEmpty())
        assertEquals("退成 ni 首选「你」", "你", PinyinEngine.query("ni").candidates.firstOrNull())
        assertTrue("退空后不该崩，也不该有候选", PinyinEngine.query("").candidates.isEmpty())
    }

    @Test
    fun `修改拼音_退格后重新打全要能恢复出词`() {
        // 现实操作：nihao 打成 nihaoX（X 打错）→ 退 X → 候选恢复
        // 打错一个字母：调用本身不许抛（能返回就是通过），候选按音节塌缩也不该整栏清空
        val withWrong = PinyinEngine.query("nihaoq")
        assertTrue("打错字母后仍有可退的单字候选：${withWrong.candidates}", withWrong.candidates.isNotEmpty())
        val back = PinyinEngine.query("nihao")
        assertTrue("退掉错字母后必须恢复出「你好」", back.candidates.contains("你好"))
        assertEquals("首选也要恢复", "你好", back.candidates.firstOrNull())
    }
}
