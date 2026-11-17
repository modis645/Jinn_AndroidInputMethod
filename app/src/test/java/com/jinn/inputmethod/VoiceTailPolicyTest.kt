package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 收尾尾巴策略的回归测试。
 *
 * 每条用例都对着 2026-11-17 那份真机日志写：
 * 8.94 秒剩 0.306 秒 → 解出 5 个错字；17.72 秒剩 0.606 秒 → 2 个错字。
 */
class VoiceTailPolicyTest {

    private val sr = 16_000

    @Test
    fun 零头够近就并回最后一句() {
        // 真机那一条：segEnd 距末尾 306ms
        assertEquals(8 * sr + 306 * 16, VoiceTailPolicy.extendedEnd(8 * sr, 8 * sr + 306 * 16))
        // 606ms 同理
        assertEquals(17 * sr + 606 * 16, VoiceTailPolicy.extendedEnd(17 * sr, 17 * sr + 606 * 16))
    }

    @Test
    fun 已经到末尾就不再动() {
        assertEquals(5 * sr, VoiceTailPolicy.extendedEnd(5 * sr, 5 * sr))
        // 末尾比段尾还小（异常输入）也只回到段尾，绝不产生负长度切片
        assertEquals(5 * sr, VoiceTailPolicy.extendedEnd(5 * sr, 4 * sr))
    }

    @Test
    fun 长静音不硬并() {
        // 后面还有 6 秒没人说话：并进来等于白解 6 秒静音
        assertEquals(3 * sr, VoiceTailPolicy.extendedEnd(3 * sr, 9 * sr))
    }

    @Test
    fun 两秒正好是边界() {
        assertEquals(8 * sr, VoiceTailPolicy.extendedEnd(6 * sr, 8 * sr))
        assertEquals(6 * sr, VoiceTailPolicy.extendedEnd(6 * sr, 8 * sr + 1))
    }

    @Test
    fun 短零头不解() {
        assertFalse(VoiceTailPolicy.shouldDecodeRemainder(0))
        assertFalse(VoiceTailPolicy.shouldDecodeRemainder(306 * 16))
        assertFalse(VoiceTailPolicy.shouldDecodeRemainder(606 * 16))
        assertFalse(VoiceTailPolicy.shouldDecodeRemainder(sr - 1))
    }

    @Test
    fun 够长才解() {
        assertTrue(VoiceTailPolicy.shouldDecodeRemainder(sr))
        assertTrue(VoiceTailPolicy.shouldDecodeRemainder(3 * sr))
    }

    @Test
    fun 收尾走延伸而不是单解零头() {
        val src = TestSources.rawSource("src/main/java/com/jinn/inputmethod/LocalSenseVoiceClient.kt")
        assertTrue(
            "收尾必须调用 drainClosingSegments",
            src.contains("drainClosingSegments(totalSamples)"),
        )
        assertTrue(
            "最后一段必须走 VoiceTailPolicy.extendedEnd",
            src.contains("VoiceTailPolicy.extendedEnd(rawEnd, totalSamples)"),
        )
        assertTrue(
            "残留必须走 VoiceTailPolicy.shouldDecodeRemainder",
            src.contains("VoiceTailPolicy.shouldDecodeRemainder(remain)"),
        )
        assertTrue("收尾要停预览，别抢最后一次解码的核", src.contains("if (closing) return"))
    }
}
