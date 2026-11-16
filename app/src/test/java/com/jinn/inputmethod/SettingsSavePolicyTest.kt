package com.jinn.inputmethod

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 落盘时机与「默认值不许比历史行为更紧」两条策略的守卫（`BUG.md` L-967 / L-980）。
 *
 * 这两条都属于「写错不报错、只在真实场景下悄悄丢数据」：前者要用户粘贴完凭据就被系统回收
 * 才暴露，后者要老库的收藏超过旧默认值才暴露。所以钉源码里的收敛点。
 */
class SettingsSavePolicyTest {

    /**
     * 收藏两个软上限的默认值不许比历史行为更紧（L-980）。
     *
     * 软上限是后加的，老库的收藏条数/体积可能早就超过旧默认值（200 条 / 5MB）——
     * 那样下次复制就会静默删掉最旧的收藏，与「收藏永不删」的既有口径冲突。
     * 所以默认取到各自的上限，方向是单向安全的；用户要收紧自己去调小。
     */
    @Test
    fun 收藏上限的默认值不得比历史行为更紧() {
        val src = TestSources.codeSource("ClipboardPrefs.kt")
        assertTrue(
            "条数默认取上限",
            "intOr(KEY_FAV_MAX_ITEMS, MAX_FAV_ITEMS_CAP)" in src,
        )
        assertTrue(
            "体积默认取联动上限",
            "intOr(KEY_FAV_MAX_MB, favoriteBytesCapMb(maxTotalBytesMb))" in src,
        )
        assertFalse("旧的独立默认常量不再存在", "DEFAULT_FAV_MAX_ITEMS" in src)
        assertFalse("旧的独立默认常量不再存在", "DEFAULT_FAV_MAX_MB" in src)
        // 用户显式设过的值仍优先：键存在时读出来的就是用户值
        assertTrue("用户设置仍可收紧", "coerceIn(1, MAX_FAV_ITEMS_CAP)" in src)
    }
}
