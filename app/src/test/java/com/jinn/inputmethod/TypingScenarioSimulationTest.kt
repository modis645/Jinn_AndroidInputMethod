package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 逐条场景模拟：把用户真机上报过的输入流程，在 JVM 里按「敲键 → 选词 → 收录 → 再敲」
 * 完整跑一遍，不依赖 Android 资源（[PinyinEngine.loadFromTexts] 注入小词库）。
 *
 * 这些用例的存在意义：历史上多次出现「单测全绿但真机某个流程走不通」——
 * 因为单元测试只测了单点函数，没有把「学词 → 收录 → 简拼检索」串起来跑。
 * 本类要求：每条用例都对应一条真实上报（注释里写清是哪一条），跑通即代表该流程可用。
 */
class TypingScenarioSimulationTest {

    private val chars = """
        liu	刘,流,留,六
        zhi	志,之,只,纸
        wu	武,五,无,舞
        wo	我
        kan	看
        kan	看
        ni	你
        hao	好
        zhang	张
        san	三
        li	李
        si	四
    """.trimIndent()

    /** 注意：一开始**故意不含**「刘志武」——它就是用户自己造出来的词 */
    private val phrases = """
        wokan	我看
        nihao	你好
        zhangsan	张三
        lisi	李四
    """.trimIndent()

    private val syllables = """
        liu
        zhi
        wu
        wo
        kan
        ni
        hao
        zhang
        san
        li
        si
    """.trimIndent()

    @Before
    fun setUp() {
        UserFrequency.resetForTest()
        UserFrequency.loadForTest(true)
        // 每个用例从「没有自造词」的干净状态开始
        PinyinEngine.clearUserWordsForTest()
        PinyinEngine.loadFromTexts(chars = chars, phrases = phrases, syllables = syllables)
    }

    /** 模拟一次真实点选：门槛判定 → 进台账 → 立刻进候选池（与 PinyinKeyboardView.recordLedgerIfNew 同序） */
    private fun pickLikeUser(word: String, typed: String): Boolean {
        val admit = PinyinEngine.shouldAdmitUserWord(word, typed)
        if (admit) {
            // 与 PinyinKeyboardView.recordLedgerIfNew 同序：先查读音，查不到就用键入串推导
            val reading = PinyinEngine.truePinyinOf(word)
                ?: PinyinEngine.readingFromTyped(word, typed).orEmpty()
            PinyinEngine.noteUserWord(word, reading)
        }
        return admit
    }

    @Test
    fun `上报2026_11_16_累计六次收录后首字母必须能打出`() {
        // 用户原话：「全拼输入词组 6 次，用户词库收录，但是首字母无法打出该词组」
        // 走的是「累计 6 次」那条门槛：中间穿插别的词，连击断了但累计还在涨
        // 12 次选择里「刘志武」占 6 次，另外三词各 2 次（都够不到任何一条门槛，
        // 顺带验证「达标的是目标词、不会被陪练词误收」）
        val fillers = listOf("我看", "你好", "张三")
        var admitted = false
        var fillerIdx = 0
        repeat(12) { i ->
            if (i % 2 == 0) {
                admitted = pickLikeUser("刘志武", "liuzhiwu") || admitted
            } else {
                assertFalse(
                    "陪练词 ${fillers[fillerIdx % fillers.size]} 不该被收录",
                    pickLikeUser(fillers[fillerIdx++ % fillers.size], "liuzhiwu"),
                )
            }
        }
        assertTrue("刘志武 累计满 6 次必须收录", admitted)

        assertTrue(
            "全拼 liuzhiwu 必须出「刘志武」",
            PinyinEngine.query("liuzhiwu").candidates.contains("刘志武"),
        )
        // 首字母：这次修的就是这条
        assertTrue(
            "首字母 lzw 必须出「刘志武」",
            PinyinEngine.query("lzw").candidates.contains("刘志武"),
        )
        // 混拼（首字母 + 打全的音节）也要能出
        assertTrue(
            "混拼 lzhiwu 必须出「刘志武」",
            PinyinEngine.query("lzhiwu").candidates.contains("刘志武"),
        )
    }

    @Test
    fun `上报2026_11_16_连续三次选中同一个词即收录`() {
        assertFalse("第 1 次不收", pickLikeUser("刘志武", "liuzhiwu"))
        assertFalse("第 2 次不收", pickLikeUser("刘志武", "liuzhiwu"))
        assertTrue("连续第 3 次必须收录", pickLikeUser("刘志武", "liuzhiwu"))
        assertTrue("收录后简拼要能打出", PinyinEngine.query("lzw").candidates.contains("刘志武"))
    }

    @Test
    fun `连续中断后不收录_换一个词就断连`() {
        pickLikeUser("刘志武", "liuzhiwu")
        pickLikeUser("刘志武", "liuzhiwu")
        // 手抖点了别的词 → 连击清零
        pickLikeUser("我看", "liuzhiwu")
        assertFalse("断连后第 2 次不该收录", pickLikeUser("刘志武", "liuzhiwu"))
    }

    @Test
    fun `已收录的词_音节数对上才命中`() {
        pickLikeUser("刘志武", "liuzhiwu")
        repeat(2) { pickLikeUser("刘志武", "liuzhiwu") }
        assertTrue("音节数对齐的简拼 lzw 必须命中", PinyinEngine.query("lzw").candidates.contains("刘志武"))
        // 简拼严格按音节数对齐：lz 只有 2 段对 3 个音节，不该硬凑出 3 字词
        assertFalse("lz 段数不够，不该凑出三字词", PinyinEngine.query("lz").candidates.contains("刘志武"))
    }

    @Test
    fun `自造词不影响内置词的候选顺序`() {
        repeat(3) { pickLikeUser("刘志武", "liuzhiwu") }
        val builtinFirst = PinyinEngine.query("nihao").candidates.firstOrNull()
        assertEquals("内置「你好」仍要排第一，不能被自造词的插入序顶掉", "你好", builtinFirst)
    }

    @Test
    fun `内置词全拼仍然正常`() {
        val r = PinyinEngine.query("nihao")
        assertEquals("nihao 的音节切分", listOf("ni", "hao"), r.syllables)
        assertTrue("nihao 必须出「你好」", r.candidates.contains("你好"))
    }

    @Test
    fun `自造词读音缺失时不进候选_但也不崩`() {
        // 台账里可能有「词面有、读音空」的条目（旧版截断），那条必须被安静跳过
        PinyinEngine.noteUserWord("刘志武", "")
        assertTrue("没有读音的自造词不该冒出来", PinyinEngine.query("lzw").candidates.none { it == "刘志武" })
        assertTrue("其它输入不受影响", PinyinEngine.query("nihao").candidates.contains("你好"))
    }
}
