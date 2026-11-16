package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语音等待时限（[VoiceTiming]）——「长语音点结束后文字迟迟不上屏」的直接成因。
 *
 * 本地解码比实时慢，固定 60 秒会把正常的长录音先判成超时；超时那一刻又会复位状态条，
 * 用户看到的就是「点了结束、字没上屏」。
 */
class VoiceTimingTest {

    @Test
    fun `在线后端固定60秒`() {
        assertEquals(VoiceTiming.BASE_TIMEOUT_MS, VoiceTiming.recognizeTimeoutMs(0L, online = true))
        assertEquals(VoiceTiming.BASE_TIMEOUT_MS, VoiceTiming.recognizeTimeoutMs(300_000L, online = true))
    }

    @Test
    fun `本地后端按音频长度放宽`() {
        // 3 分钟音频：1.2 倍 + 15 秒 = 231 秒，远大于 60 秒基线
        assertEquals(180_000L + 36_000L + 15_000L, VoiceTiming.recognizeTimeoutMs(180_000L, online = false))
    }

    @Test
    fun `短音频仍不短于基线`() {
        assertEquals(VoiceTiming.BASE_TIMEOUT_MS, VoiceTiming.recognizeTimeoutMs(5_000L, online = false))
        assertEquals(VoiceTiming.BASE_TIMEOUT_MS, VoiceTiming.recognizeTimeoutMs(-1L, online = false))
    }

    @Test
    fun `一直单调不减`() {
        val a = VoiceTiming.recognizeTimeoutMs(60_000L, online = false)
        val b = VoiceTiming.recognizeTimeoutMs(120_000L, online = false)
        assertTrue("音频更长，等待不能更短", b > a)
    }
}
