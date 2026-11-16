package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 帧能量与段首回补（[VoiceDsp]）——「长录音丢句首 1~4 字」的唯一判据所在。
 *
 * 这组数字原先只能靠真机录音复现，现在给一组合成帧能量就能验证规则：
 * 无条件补 0.3s、语音帧继续沿、允许 0.25s 低能量间隙、总上限 3s、绝不越过上一段段尾。
 */
class VoiceDspTest {

    /** 由「每帧是否语音」合成 RMS：语音 0.5，静音 0.01 */
    private fun frames(vararg speech: Boolean): FloatArray =
        FloatArray(speech.size) { if (speech[it]) 0.5f else 0.01f }

    private val speechRms = 0.05f

    @Test
    fun `帧能量_恒定振幅等于RMS`() {
        val samples = FloatArray(VoiceDsp.FRAME_SAMPLES * 3) { 0.5f }
        val e = VoiceDsp.frameEnergies(samples)
        assertEquals("三帧", 3, e.size)
        e.forEach { assertEquals(0.5f, it, 1e-4f) }
    }

    @Test
    fun `帧能量_静音为零且不足一帧返回空`() {
        val silence = VoiceDsp.frameEnergies(FloatArray(VoiceDsp.FRAME_SAMPLES))
        assertEquals(1, silence.size)
        assertEquals(0f, silence[0], 1e-6f)
        assertTrue("不足一帧不给数据", VoiceDsp.frameEnergies(FloatArray(10)).isEmpty())
    }

    @Test
    fun `段首回补_无条件0点3秒后按间隙窗口再退`() {
        // 第 100 帧起才是语音，前面全是静音：先无条件退 30 帧（0.3s），
        // 之后靠 25 帧的低能量容忍窗口再退 25 帧，合计 55 帧
        val f = frames(*BooleanArray(200) { it >= 100 })
        val startSample = 100 * VoiceDsp.FRAME_SAMPLES
        val out = VoiceDsp.onsetBacktrack(f, speechRms, startSample, 0)
        val backFrames = VoiceDsp.ONSET_ALWAYS_FRAMES + VoiceDsp.ONSET_GAP_FRAMES
        assertEquals("回补步数必须是 30 + 25 帧", backFrames, 100 - out / VoiceDsp.FRAME_SAMPLES)
        assertTrue("无条件那 0.3s 必须在里面", out < 100 * VoiceDsp.FRAME_SAMPLES)
    }

    @Test
    fun `段首回补_沿语音帧继续往前并容忍短间隙`() {
        // 0..120 全是语音，121..200 静音，段起点在 200：应一路回到 120 之后被间隙窗口挡住
        val speech = BooleanArray(300) { it <= 120 }
        val f = frames(*speech)
        val out = VoiceDsp.onsetBacktrack(f, speechRms, 200 * VoiceDsp.FRAME_SAMPLES, 0)
        // 无条件 30 帧 → 170；之后静音，允许 25 帧间隙 → 145 左右停下
        assertTrue("必须越过那 0.3s 继续往前：$out", out < 170 * VoiceDsp.FRAME_SAMPLES)
        assertTrue("但不得被间隙窗口一路吃到底：$out", out > 100 * VoiceDsp.FRAME_SAMPLES)
    }

    @Test
    fun `段首回补_绝不越过上一段段尾`() {
        val f = frames(*BooleanArray(400) { true })
        val floor = 250 * VoiceDsp.FRAME_SAMPLES
        val out = VoiceDsp.onsetBacktrack(f, speechRms, 300 * VoiceDsp.FRAME_SAMPLES, floor)
        assertEquals("两段音频不许重叠", floor, out)
    }

    @Test
    fun `段首回补_总上限3秒`() {
        val f = frames(*BooleanArray(1000) { true })
        val out = VoiceDsp.onsetBacktrack(f, speechRms, 900 * VoiceDsp.FRAME_SAMPLES, 0)
        val maxBack = (VoiceDsp.ONSET_MAX_BACK_FRAMES + 1) * VoiceDsp.FRAME_SAMPLES
        assertTrue("回补不得超过 3 秒上限：$out", 900 * VoiceDsp.FRAME_SAMPLES - out <= maxBack)
    }

    @Test
    fun `段首回补_空帧表原样返回`() {
        assertEquals(1234, VoiceDsp.onsetBacktrack(FloatArray(0), speechRms, 1234, 0))
    }
}
