package com.jinn.inputmethod

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 底栏标点输出页的接线守卫（2026-10-16 纠正：该参数从键盘外观页移出，
 * 改由首页「空格左右按钮」**按钮**打开的独立页 [PunctOutputActivity] 承载）。
 *
 * 这条只能在源码上对拍（Activity + 视图进不了 JVM 单测）：入口必须仍是**按钮**（不是开关）、
 * 四个参数键落在新页、旧页不再残留、新页已在清单注册。
 */
class PunctOutputPageTest {

    @Test
    fun `首页不再有标点输出页入口`() {
        // 2026-11-16：作者把「空格左右标点」入口从设置页删掉（标点改为随句子上屏自动带出），
        // 页面类还在（死代码，最终定版统一清理），但首页**不许再有任何入口** ——
        // 否则会出现「点进去是一个不再维护的页面」这种半死状态。
        val code = TestSources.codeSource("SettingsActivity.kt")
        assertFalse(
            "首页不得再有「空格左右标点」入口",
            "btn_space_cursor" in code,
        )
        assertFalse(
            "首页不得再打开 PunctOutputActivity",
            "PunctOutputActivity::class.java" in code,
        )
        assertFalse(
            "首页不得再把它做成开关（switch_space_cursor 已迁出）",
            "R.id.switch_space_cursor" in code,
        )
    }

    @Test
    fun `空格移光标开关已彻底移除`() {
        // 该功能整体下线（作者明确不要）：源码与布局都不许再残留任何痕迹，
        // 用原文对拍（连注释也不许再提），反向钉静默方向才守得住。
        val punct = TestSources.rawSourceOfShortName("PunctOutputActivity.kt")
        assertFalse("标点页仍在接线空格移光标开关", "switch_space_cursor" in punct)
        assertFalse("标点页仍残留开关文案常量", "TEXT_SPACE_CURSOR" in punct)

        val layout = File("src/main/res/layout/activity_punct_output.xml").let {
            if (it.isFile) it else File("app/src/main/res/layout/activity_punct_output.xml")
        }.readText()
        assertFalse("标点页布局仍留着开关", "switch_space_cursor" in layout)

        val prefs = TestSources.rawSourceOfShortName("Prefs.kt")
        assertFalse("Prefs 仍残留 spaceCursorMove 属性", "spaceCursorMove" in prefs)
        assertFalse("Prefs 仍残留 KEY_SPACE_CURSOR_MOVE 常量", "KEY_SPACE_CURSOR_MOVE" in prefs)

        val kb = TestSources.rawSourceOfShortName("PinyinKeyboardView.kt")
        assertFalse("键盘仍残留 handleSpaceTouch", "handleSpaceTouch" in kb)
        assertFalse("键盘仍残留 SPACE_CURSOR_STEP_DP", "SPACE_CURSOR_STEP_DP" in kb)
        assertFalse("键盘仍残留空格滑动状态字段", "spaceTouchLastX" in kb || "spaceTouchConsumed" in kb)
    }

    @Test
    fun `标点页绑定四个输入项`() {
        val code = TestSources.codeSource("PunctOutputActivity.kt")
        for (id in listOf(
            "R.id.edit_punct_comma_cn", "R.id.edit_punct_period_cn",
            "R.id.edit_punct_comma_en", "R.id.edit_punct_period_en",
        )) {
            assertTrue("标点页缺少输入项 $id", id in code)
        }
        assertTrue(
            "输入框要走光标感知归一（BUG.md L-1382）",
            Regex("""PunctOutput\.sanitizeFieldAt\(raw, caret, fallback\)""").containsMatchIn(code),
        )
        // 页面留在返回栈里时，导入配置或回收重建都会让框内停在旧快照（L-48/L-74 同型第三页）：
        // 关掉 View 态保存，避免恢复触发 watcher 把旧值写回；回前台再从 prefs 重读。
        val assigns = Regex("""isSaveEnabled\s*=\s*(\w+)""").findAll(code)
            .map { it.groupValues[1] }.toList()
        assertTrue("四框须关且只关一次视图态保存（实际 $assigns）", assigns == listOf("false"))
        val onStart = TestSources.blockAfter(code, "override fun onStart()")
        assertTrue("回前台必须重读 prefs", "fieldRefreshers" in onStart)
    }

    @Test
    fun `标点参数已移出键盘外观页`() {
        assertFalse(
            "键盘外观页仍在绑定标点输入框",
            "R.id.edit_punct_comma_cn" in TestSources.codeSource("KeyAppearanceActivity.kt"),
        )
        val layout = TestSources.codeOf(
            listOf(
                File("src/main/res/layout/activity_key_appearance.xml"),
                File("app/src/main/res/layout/activity_key_appearance.xml"),
            ).first { it.isFile }.readText(),
        )
        assertFalse("键盘外观页布局仍留着标点卡片", "edit_punct_comma_cn" in layout)
    }

    @Test
    fun `标点输出页已从清单注销`() {
        // 2026-11-16 起标点输出页没有入口，清单里同步注销 —— 未注册的 Activity 即使被别的
        // 代码 startActivity 也会抛 ActivityNotFoundException，这条断言正好钉住「别复活半套」。
        val manifest = TestSources.codeOf(
            listOf(
                File("src/main/AndroidManifest.xml"),
                File("app/src/main/AndroidManifest.xml"),
            ).first { it.isFile }.readText(),
        )
        assertFalse(
            "AndroidManifest 不得再注册 PunctOutputActivity",
            "android:name=\".PunctOutputActivity\"" in manifest,
        )
    }
}
