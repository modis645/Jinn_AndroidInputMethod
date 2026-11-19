package com.jinn.inputmethod

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.tukaani.xz.XZInputStream
import java.io.File

/**
 * 单字消费区间必须与**展示序**同口径（`BUG.md` L-12）。
 *
 * 缺陷成因：候选栏展示是「每音节取前 `MAX_CHARS`(60) 条」，而 `consumption` 原先按**全量**字表
 * `contains` 找「首个含该字的音节」。同一个字若挂在两个音节、且**靠前那次被 60 条上限挡在展示窗口之外**，
 * 渲染出来的那一条其实来自后一音节，消费却仍钉在靠前音节 ⇒ 点击后残码多留一个音节。
 *
 * 2026-11-19 起单字只取**首音节**（用户口径「按拼音顺序依次出」），里程碑：
 * 靠后音节单独贡献的单字不会再被渲染 ⇒ 上述错位在整串输入里**不再可能出现**；
 * 这里改为钉住新不变量（候选 = 首音节窗口，消费只覆盖首音节），另两例继续守「窗口内消费靠前音节」。
 *
 * 为什么用**自造字表**：真实资产里这个前提当前**不存在**（离线扫过：`pinyin_chars.txt.xz` 里
 * 「首现下标 ≥ 60 且另有音节 < 60」的字 **0 个**；另有 583 个字挂在两个音节但两处都在 60 以内
 * ⇒ 展示序 = 首现序，不受影响）。要用真表复现就得等资产变化 —— 而测试装置支持注入自定义字表，
 * 于是这里直接把前提造出来，把「口径一致」这条契约钉死。
 *
 * ⚠ 目标字与填充字都取自 `common_chars.txt`：`charsFor` 会过一遍生僻字档位过滤，
 * 自造表若用了档位外的字，会被过滤掉、让断言悄悄变成「两边都找不到」。
 */
class PinyinConsumptionDisplayOrderTest {

    private fun asset(name: String): String {
        for (p in listOf("src/main/assets/$name", "app/src/main/assets/$name")) {
            val f = File(p)
            if (f.isFile) return f.readText()
            val xz = File("$p.xz")
            if (xz.isFile) {
                return XZInputStream(xz.inputStream()).use { String(it.readBytes(), Charsets.UTF_8) }
            }
        }
        throw AssertionError("未找到 asset $name")
    }

    private fun indexBytes(): ByteArray {
        for (p in listOf("src/main/assets/pinyin_index.bin.xz", "app/src/main/assets/pinyin_index.bin.xz")) {
            val f = File(p)
            if (f.isFile) return XZInputStream(f.inputStream()).use { it.readBytes() }
        }
        throw AssertionError("未找到索引资产")
    }

    private fun loadEngine(charsText: String) {
        PinyinEngine.resetForTest()
        PinyinEngine.loadFromIndexBytes(
            indexBytes(),
            charsText,
            asset("pinyin_syllables.txt"),
            asset("common_chars.txt"),
        )
    }

    /** 常用字池（都过得了档位过滤），用来造填充字与目标字 */
    private val pool: List<String> by lazy {
        asset("common_chars.txt").lineSequence()
            .flatMap { line -> line.trim().asSequence() }
            .filter { it.code in 0x4E00..0x9FFF }
            .map { it.toString() }
            .distinct()
            .toList()
    }

    private fun charsText(fu: List<String>, gao: List<String>): String =
        "fu\t" + fu.joinToString(",") + "\ngao\t" + gao.joinToString(",") + "\n"

    @Before
    fun setUp() {
        PinyinEngine.resetForTest()
    }

    @After
    fun tearDown() {
        PinyinEngine.resetForTest()
    }

    @Test
    fun 靠后音节独有的单字不进入候选_跨音节错位不再可能() {
        val target = pool[0]
        val fillers = pool.drop(1).take(60)          // 60 个填充字，正好占满 fu 的展示窗口
        assertTrue("字池太小，无法构造 60 条展示上限之外的条目", fillers.size == 60)
        // fu 里 target 排在第 61 位（窗口之外）；target 只在 gao 里能取到
        loadEngine(charsText(fu = fillers + target, gao = listOf(target, pool[70])))

        // 2026-11-19 口径：单字只取首音节 ⇒ gao 独有的 target 不出现；首音节窗口 60 条都在
        val candidates = PinyinEngine.query("fugao").candidates
        assertTrue(
            "首音节窗口的 60 条单字都必须进候选（实际 ${candidates.size}）",
            fillers.all { it in candidates },
        )
        assertTrue(
            "靠后音节独有的字 $target 不应出现在候选里（旧口径会把它排在第 61 位之后，点击即错位）",
            !candidates.contains(target),
        )
        // 展示的首音节字，消费也只覆盖首音节（2 字母 1 音节）
        val shown = fillers.first { it in candidates }
        assertEquals(
            "首音节候选的消费应停在 fu",
            PinyinEngine.Consumption(quanpinChars = 2, syllables = 1),
            PinyinEngine.consumption("fugao", shown),
        )
    }

    @Test
    fun 靠前音节在展示窗口内时仍消费靠前音节() {
        val target = pool[0]
        // 两个音节都含 target，且 fu 里它在第 1 位（展示窗口内）⇒ 展示序的首个就是 fu
        loadEngine(charsText(fu = listOf(target) + pool.drop(1).take(5), gao = listOf(target, pool[70])))
        assertEquals(
            "展示窗口内能取到时，消费应停在靠前音节（与历史行为一致）",
            PinyinEngine.Consumption(quanpinChars = 2, syllables = 1),
            PinyinEngine.consumption("fugao", target),
        )
    }

    @Test
    fun 单字不在任何音节时应退回消费全部() {
        val target = pool[0]
        loadEngine(charsText(fu = pool.drop(1).take(3), gao = pool.drop(5).take(3)))
        assertEquals(
            "表里没有该字 ⇒ 兜底「消费全部」，不产生错误残码",
            PinyinEngine.Consumption(quanpinChars = 5, syllables = 2),
            PinyinEngine.consumption("fugao", target),
        )
    }
}
