package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 设置项「有效且一致」的审计：每个开关/选项都得真的被某段代码读，设置页每一行都得真的接线。
 *
 * 用户 2026-11-16 的要求：「每个设置选项，检查是否有效、功能是否一致」。
 *
 * 判据分三向：
 *  1. `activity_settings.xml` 里每个 `@+id` 都要在 [SettingsActivity] 源码出现（否则是摆着不响的行）；
 *  2. `Prefs.kt` 里每个 `var` 设置属性都要在其它源文件被引用（否则存了却没人读 = 开关无效）；
 *  3. 设置页不得绕过 Prefs 自己拿 SharedPreferences（否则键名分裂、备份/导入对不上）。
 *
 * 白名单不是「原谅名单」，而是**已知遗留**的登记表：新增项不得再进白名单。
 */
class SettingsWiringAuditTest {

    private val srcDir: File
        get() = listOf(
            File("src/main/java/com/jinn/inputmethod"),
            File("app/src/main/java/com/jinn/inputmethod"),
        ).first { it.isDirectory }

    private val prefsSource: String
        get() = TestSources.codeOf(TestSources.rawSourceOfShortName("Prefs.kt"))

    private val settingsSource: String
        get() = TestSources.codeOf(TestSources.rawSourceOfShortName("SettingsActivity.kt"))

    private val settingsLayout: String
        get() = TestSources.rawSource(
            "src/main/res/layout/activity_settings.xml",
            "app/src/main/res/layout/activity_settings.xml",
        )

    /** 设置属性白名单：都是「只有备份/导入在用」或已停用的在线凭据，功能上不驱动 UI。 */
    private val legacyProps = setOf(
        // 已停用的在线语音凭据（保留只为老备份能导进来）
        "oaiBaseUrl", "oaiModel", "aliWsUrl", "aliModel", "tencentEngine",
        "azureBaseUrl", "azureLanguage", "baiduDevPid", "deepgramModel", "deepgramLanguage",
        // msime 遗留的本地服务端开关
        "lockServer",
        // 浮动键盘的几何只经 floatingGeometryRaw() 走，属性本身只给备份用
        "floatingGeometryPortrait", "floatingGeometryLandscape",
    )

    /** 布局里故意不受 SettingsActivity 管辖的控件（诊断用的测试输入框）。 */
    private val layoutExempt = setOf("edit_ime_test")

    @Test
    fun `设置页每行都接线了`() {
        val ids = Regex("""android:id="@\+id/([a-z0-9_]+)"""")
            .findAll(settingsLayout).map { it.groupValues[1] }.toSet()
        assertTrue("设置布局不该是空的", ids.size > 20)
        val unbound = ids.filter { it !in layoutExempt && "R.id.$it" !in settingsSource }
        assertEquals("这些行摆了却没接线：$unbound", emptyList<String>(), unbound)
    }

    @Test
    fun `每个设置属性都有代码在读`() {
        val props = Regex("""\n {4}var ([a-zA-Z][A-Za-z0-9_]*): """)
            .findAll(prefsSource).map { it.groupValues[1] }.toSet()
        assertTrue("设置属性数量异常（${props.size}）", props.size >= 60)
        val others = srcDir.listFiles { f -> f.name.endsWith(".kt") && f.name != "Prefs.kt" }
            ?.map { it.readText() } ?: emptyList()
        val dead = props.filter { p ->
            p !in legacyProps && others.none { Regex("""\b${p}\b""").containsMatchIn(it) }
        }
        assertEquals("这些设置存了却没人读（等于无效开关）：$dead", emptyList<String>(), dead)
    }

    @Test
    fun `白名单只许变短`() {
        // 白名单每一条都必须仍然存在，否则说明它已被清理 —— 顺手提醒把名单删掉
        val props = Regex("""\n {4}var ([a-zA-Z][A-Za-z0-9_]*): """)
            .findAll(prefsSource).map { it.groupValues[1] }.toSet()
        val stale = legacyProps.filter { it !in props }
        assertEquals("白名单里有已不存在的属性，请删除：$stale", emptyList<String>(), stale)
    }

    @Test
    fun `设置页不得自己拿 SharedPreferences`() {
        assertTrue(
            "统一走 Prefs，别在设置页里裸拿 SharedPreferences（会分裂键名、备份对不上）",
            "getSharedPreferences" !in settingsSource,
        )
    }

    @Test
    fun `已删入口的页面不许再被打开`() {
        // PunctOutputActivity / TapSoundActivity：入口 2026-11-16 已删除，清单也不再注册。
        // 留存这两个类只为历史代码可查，任何 startActivity 指向它们都是「点了没反应」。
        val retired = listOf("PunctOutputActivity", "TapSoundActivity")
        val manifest = TestSources.codeOf(
            TestSources.rawSource(
                "src/main/AndroidManifest.xml",
                "app/src/main/AndroidManifest.xml",
            ),
        )
        val callers = srcDir.listFiles { f -> f.name.endsWith(".kt") }?.filter { f ->
            retired.any { name -> Regex("""startActivity.*${name}|${name}::class""").containsMatchIn(f.readText()) }
        }?.map { it.name } ?: emptyList()
        assertEquals("这些页面入口已删却仍被打开：$callers", emptyList<String>(), callers)
        assertTrue("已删入口的页面不该在清单里注册",
            retired.none { ".$it" in manifest })
    }

    @Test
    fun `每个设置子页面的入口都能打开`() {
        // 页面类存在 + 在 AndroidManifest 里注册过，否则点了就闪退/无反应
        val pages = listOf(
            "KeyAppearanceActivity", "ClipboardCustomizeActivity",
            "ClipboardHistoryActivity", "GallerySettingsActivity", "VoiceSettingsActivity",
            "FuzzyPinyinActivity", "UserDictActivity",
            "DictManagerActivity", "RareCharsActivity", "SymbolOrderActivity",
            "FavoriteSymbolsActivity", "StorageUsageActivity", "FloatingKeyboardActivity",
        )
        val manifest = TestSources.codeOf(
            TestSources.rawSource(
                "src/main/AndroidManifest.xml",
                "app/src/main/AndroidManifest.xml",
            ),
        )
        val missingFile = pages.filter { p ->
            !File(srcDir, "$p.kt").isFile
        }
        assertTrue("页面源文件不存在：$missingFile", missingFile.isEmpty())
        val unregistered = pages.filter { "$it" !in manifest }
        assertTrue("未在清单注册（点了会失败）：$unregistered", unregistered.isEmpty())
    }
}
