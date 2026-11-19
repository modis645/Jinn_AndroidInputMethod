package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「本机离线模型卡」可见性守卫（源码对拍，2026-11-19）。
 *
 * 用户口径：语音总开关关着时，模型卡**跟着一起隐藏**（默认关 → 首次进页面看不到模型块）；
 * 开关一打开就把卡片放出来。曾有人把卡片改成「恒显示」，用户当场判为退化并否决 ——
 * 这条用例把该口径钉成机械判据，避免下一个人再改回去。
 *
 * 走源码文本核对而不是 UI 测试：这一页的显隐在 `applyVoiceBlocksVisibility` 一个函数里，
 * 纯 View 操作，JVM 单测没有 Activity 可跑（与本仓 `PageStyleParityTest` 同一套做法）。
 */
class VoiceModelCardVisibilityGuardTest {

    private val sourceFile: File = listOf(
        File("src/main/java/com/jinn/inputmethod/VoiceSettingsActivity.kt"),
        File("app/src/main/java/com/jinn/inputmethod/VoiceSettingsActivity.kt"),
    ).firstOrNull { it.isFile } ?: error("找不到 VoiceSettingsActivity.kt（当前工作目录=${File("").absolutePath}）")

    /** 剥注释后再核对：注释里出现同名字符串不应满足判据 */
    private val source: String = TestSources.codeOf(sourceFile.readText())

    private fun bodyOf(funName: String): String {
        val start = source.indexOf("private fun $funName(")
        assertTrue("源码里没找到 $funName", start > 0)
        val tail = source.substring(start + 1)
        val cuts = listOf(
            "\n    private fun ", "\n    fun ", "\n    private class ", "\n    override fun ", "\n}",
        ).map { tail.indexOf(it) }.filter { it > 0 }
        val end = cuts.minOrNull() ?: tail.length
        return tail.substring(0, end)
    }

    @Test
    fun `模型卡必须跟语音总开关一起显隐`() {
        val body = bodyOf("applyVoiceBlocksVisibility")
        assertTrue(
            "模型卡必须用总开关算出的 visibility（即跟着开关隐藏），不能写死 VISIBLE",
            "cardLocalAsr.visibility = visibility" in body,
        )
        assertFalse(
            "模型卡不得恒显示：用户 2026-11-19 明确否决「常显」改法",
            "cardLocalAsr.visibility = View.VISIBLE" in body,
        )
    }

    @Test
    fun `开关关着时不刷新模型状态`() {
        val body = bodyOf("applyVoiceBlocksVisibility")
        assertTrue(
            "refreshLocalAsrStatus 必须受开关约束（关着时不刷模型卡，避免隐藏后仍触发下载/文案刷新）",
            "if (voiceEnabled) refreshLocalAsrStatus()" in body,
        )
    }

    @Test
    fun `总开关的初始值来自 Prefs`() {
        // 出厂默认关：这里保证页面一打开就按真实偏好算一次显隐，而不是布局里写死
        assertTrue("必须有一次 applyVoiceBlocksVisibility(prefs.voiceInputEnabled)", "applyVoiceBlocksVisibility(prefs.voiceInputEnabled)" in source)
    }

    @Test
    fun `语音总开关默认关`() {
        // 与隐藏口径配套：默认关 ⇒ 首次进页面看不到模型块（用户接受的行为）
        val prefs = listOf(
            File("src/main/java/com/jinn/inputmethod/Prefs.kt"),
            File("app/src/main/java/com/jinn/inputmethod/Prefs.kt"),
        ).firstOrNull { it.isFile } ?: error("找不到 Prefs.kt")
        val text = TestSources.codeOf(prefs.readText())
        assertEquals(
            "语音总开关默认必须关（改成默认开会把隐藏的模型卡变成首屏常驻，与本次口径不符）",
            true,
            Regex("""var voiceInputEnabled: Boolean\s*\n\s*get\(\) = boolOr\(KEY_VOICE_INPUT, false\)""").containsMatchIn(text),
        )
    }
}
