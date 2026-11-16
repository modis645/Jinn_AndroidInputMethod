package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 键盘「每个键都在、每个键都有用」的全量守卫。
 *
 * 用户 2026-11-16 的要求：输入界面每个按键功能是否正常、按键角标是否正常、符号数字是否正常。
 * 键面是代码画的（[KEYBOARD_ROWS] / [DIGIT_MAP] / [SYMBOL_GROUPS]），布局 XML 里既有真实
 * 独立的键也有占位，所以两边都要钉：
 *
 *  - 常量侧：26 键齐全且不重复、数字层每键有映射、符号层每页都有效；
 *  - 布局侧：[KEYBOARD_LAYOUT_IDS] 里每个 id 真的存在于 `keyboard_pinyin.xml`；
 *  - 绑定侧：每个 id 在 [PinyinKeyboardView] 里被 `findViewById` 取到（防止「摆了个键但没接线」）。
 *
 * 加新键时三张表一起加，任一漏掉这条测试就红 —— 这就是它存在的意义。
 */
class KeyboardKeyInventoryTest {

    private val layout: String
        get() = TestSources.rawSource("app/src/main/res/layout/keyboard_pinyin.xml")

    private val viewCode: String
        get() = TestSources.codeOf(TestSources.rawSourceOfShortName("PinyinKeyboardView.kt"))

    /** 功能键 id 清单（不按笔画顺序，按功能分组：编辑 / 层切换 / 输入 / 语音 / 候选区） */
    private val specialKeys = listOf(
        // 编辑与层切换
        "key_shift", "key_backspace", "key_symbol", "key_digit", "key_lang", "key_enter",
        // 输入
        "key_space", "key_comma_period", "key_semicolon",
        // 语音（键面 + 电平条）
        "key_voice_long", "key_voice_label", "key_voice_level", "key_space_level",
        // 候选区
        "candidate_bar", "pinyin_bar", "candidate_pinyin", "candidate_list",
        "btn_expand_candidates", "btn_clear_candidates",
    )

    @Test
    fun `26 键齐全且不重复`() {
        val rows = KEYBOARD_ROWS
        assertEquals("三排字母行", 3, rows.size)
        assertEquals("第一排 10 键", 10, rows[0].length)
        assertEquals("第二排 9 键", 9, rows[1].length)
        assertEquals("第三排 7 键", 7, rows[2].length)
        val all = rows.joinToString("")
        assertEquals("总 26 键", 26, all.length)
        assertEquals("标准 QWERTY 顺序", "qwertyuiopasdfghjklzxcvbnm", all)
        assertEquals("不得有重复键", 26, all.toSet().size)
    }

    @Test
    fun `每个字母键在布局里都有独立 id`() {
        val missing = KEYBOARD_ROWS.joinToString("").filter { "@+id/key_$it" !in layout }
        assertTrue("布局缺这些字母键：$missing", missing.isEmpty())
    }

    @Test
    fun `功能键都在布局里`() {
        val missing = specialKeys.filter { "@+id/$it" !in layout }
        assertTrue("布局缺这些功能键：$missing", missing.isEmpty())
    }

    @Test
    fun `每个功能键都被代码绑定`() {
        // 摆了键却没 findViewById ⇒ 按了没反应，是最隐蔽的「键不好使」
        val unbound = specialKeys.filter { "R.id.$it" !in viewCode }
        assertTrue("这些键没有接线：$unbound", unbound.isEmpty())
    }

    @Test
    fun `数字层覆盖全部 26 键_首排是 1 到 0`() {
        val letters = KEYBOARD_ROWS.joinToString("").toSet()
        assertEquals("数字层键位必须与字母一致", letters, DIGIT_MAP.keys)
        val firstRow = "qwertyuiop"
        assertEquals("首排依次是 1..0", "1234567890", firstRow.map { DIGIT_MAP[it] }.joinToString(""))
        // 每个映射都不得为空，也不得等于键字母本身（那等于没映射）
        DIGIT_MAP.forEach { (k, v) ->
            assertTrue("$k 的数字层不能为空", v.isNotEmpty())
            assertFalse("$k 的数字层不能还是字母", v.equals(k.toString(), ignoreCase = true))
        }
    }

    @Test
    fun `符号层每组至少一页且每页键位合法`() {
        val letters = KEYBOARD_ROWS.joinToString("").toSet()
        assertTrue("符号组不能为空", SYMBOL_GROUPS.isNotEmpty())
        val labels = SYMBOL_GROUPS.map { it.label }
        assertEquals("组名不得重复", labels.size, labels.toSet().size)
        SYMBOL_GROUPS.forEach { g ->
            assertTrue("组「${g.label}」必须有页", g.pages.isNotEmpty())
            g.pages.forEach { page ->
                assertTrue("组「${g.label}」有页是空的", page.isNotEmpty())
                val illegal = page.filterKeys { it !in letters }
                assertTrue("组「${g.label}」出现非法键位 $illegal", illegal.isEmpty())
                val blank = page.filterValues { it.isBlank() }
                assertTrue("组「${g.label}」有空白符号 $blank", blank.isEmpty())
            }
        }
    }

    @Test
    fun `26 键角标齐全且与键字母不冲突`() {
        val letters = KEYBOARD_ROWS.joinToString("")
        val labels = letters.map { CornerLabels.of(it) }
        assertTrue("有键没有角标", labels.none { it.isEmpty() })
        // 角标是「次要文字」，绝不能等于主标签，否则长按与点按上屏同一个字符
        letters.indices.forEach { i ->
            assertFalse(
                "${letters[i]} 的角标与键面重复",
                labels[i].equals(letters[i].toString(), ignoreCase = true),
            )
        }
        assertEquals("角标不得互相重复（长按上屏会撞车）", labels.size, labels.toSet().size)
        assertTrue("has() 与 of() 必须一致", letters.all { CornerLabels.has(it) })
        assertFalse("非字母键没有角标", CornerLabels.has('1'))
        assertFalse("大写也认同一个角标来源", CornerLabels.of('Q').isEmpty())
    }

    @Test
    fun `底行四键宽度与职责正确`() {
        // 用户 2026-11-15 要求「空格加长 + ，。 合并 + 腾键做长语音」
        val spaceBlock = layout.substringAfter("android:id=\"@+id/key_space\"")
            .substringBefore("</LinearLayout>")
        assertTrue("空格必须 weight 拉伸（宽于退格）", "android:layout_weight=\"3.2\"" in spaceBlock)
        assertTrue("空格键内要有电平条", "@+id/key_space_level" in spaceBlock)
        assertTrue("，。 必须合并为一个键", "@+id/key_comma_period" in layout)
        assertTrue("语音键必须独立存在（不再挂在空格长按里）", "@+id/key_voice_long" in layout)
        assertTrue("语音键有自己的电平条", "@+id/key_voice_level" in layout)
    }
}
