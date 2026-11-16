package com.jinn.inputmethod

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.tukaani.xz.XZInputStream
import java.io.File

/**
 * 内置词的简拼 / 混拼（走**二进制索引**这条真实路径）。
 *
 * 为什么不能放进 [InputBehaviorMatrixTest]：简拼命中依赖 `initialsIndex`，而该索引是从
 * `pinyin_index.bin` 派生的；`loadFromTexts`（纯文本注入）不建这个索引，用文本夹具写简拼
 * 断言只会得到空结果。这里改用与 [IndexParityTest] 同一对同源 fixture：
 * `dict_index_fixture.txt` + `dict_index_fixture.bin.xz`，单字表/音节表取真实 asset。
 *
 * 覆盖：纯首字母（`blm`）、混拼（`bailem`）、以及「段数不够不许硬凑」。
 */
class BuiltinAbbrevTest {

    private fun fixtureDir(): File {
        for (p in listOf("src/test/resources", "app/src/test/resources")) {
            val d = File(p)
            if (d.isDirectory) return d
        }
        throw AssertionError("未找到测试资源目录")
    }

    private fun asset(name: String): String {
        for (p in listOf("src/main/assets/$name", "app/src/main/assets/$name")) {
            val f = File(p)
            if (f.isFile) return f.readText()
            val xz = File("$p.xz")
            if (xz.isFile) {
                return XZInputStream(xz.inputStream())
                    .use { String(it.readBytes(), Charsets.UTF_8) }
            }
        }
        throw AssertionError("未找到 asset $name")
    }

    @Before
    fun setUp() {
        UserFrequency.resetForTest()
        UserFrequency.loadForTest(true)
        PinyinEngine.resetForTest()
        val bytes = XZInputStream(File(fixtureDir(), "dict_index_fixture.bin.xz").inputStream())
            .use { it.readBytes() }
        PinyinEngine.loadFromIndexBytes(
            bytes,
            asset("pinyin_chars.txt"),
            asset("pinyin_syllables.txt"),
            asset("common_chars.txt"),
        )
        // 线上是启动时后台建首字母索引，按键路径不等它；测试里同步建一次
        PinyinEngine.buildInitialsIndexForTest()
    }

    @Test
    fun `简拼_三字词首字母`() {
        assertTrue("简拼 blm 应出「百乐门」：${PinyinEngine.query("blm").candidates}",
            PinyinEngine.query("blm").candidates.contains("百乐门"))
        assertTrue("简拼 awt 应出「安万特」",
            PinyinEngine.query("awt").candidates.contains("安万特"))
    }

    @Test
    fun `简拼_四字词首字母`() {
        assertTrue("简拼 bhcd 应出「保护程度」：${PinyinEngine.query("bhcd").candidates}",
            PinyinEngine.query("bhcd").candidates.contains("保护程度"))
    }

    @Test
    fun `混拼_前几段打全_末段留首字母`() {
        assertTrue("混拼 bailem 应出「百乐门」：${PinyinEngine.query("bailem").candidates}",
            PinyinEngine.query("bailem").candidates.contains("百乐门"))
        assertTrue("混拼 anwant 应出「安万特」",
            PinyinEngine.query("anwant").candidates.contains("安万特"))
    }

    @Test
    fun `段数不够不许硬凑`() {
        // 百乐门是 3 个音节：两段输入不该凑出它
        assertFalse("bl 两段不该出三字词",
            PinyinEngine.query("bl").candidates.contains("百乐门"))
        // 四字词不能被三段输入命中
        assertFalse("bhc 三段不该出四字词",
            PinyinEngine.query("bhc").candidates.contains("保护程度"))
    }

    @Test
    fun `简拼与混拼不改变全拼结果`() {
        assertTrue("全拼 bailemen 仍要出「百乐门」",
            PinyinEngine.query("bailemen").candidates.contains("百乐门"))
    }
}
