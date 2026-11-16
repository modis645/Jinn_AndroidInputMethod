package com.jinn.inputmethod

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语音链路阶段打点（[VoiceTrace]）。
 *
 * 它存在的理由只有一条：让「语音慢不慢 / 准不准」变成可读的数字，而不是靠用户反复试。
 * 因此要钉的是**阶段齐全、时刻有序、重复打点不串味、汇总能区分「未到」**。
 */
class VoiceTraceTest {

    private val lines = ArrayList<String>()

    @After
    fun tearDown() {
        VoiceTrace.sink = null
        VoiceTrace.reset()
    }

    private fun beginWithSink() {
        lines.clear()
        VoiceTrace.sink = { lines.add(it) }
        VoiceTrace.begin("dictation")
    }

    @Test
    fun `七个阶段都会被记下且顺序单调`() {
        beginWithSink()
        for (stage in VoiceTrace.ORDER) {
            VoiceTrace.mark(stage)
            Thread.sleep(1)
        }
        val marks = VoiceTrace.ORDER.map { VoiceTrace.lastMs(it) }
        assertTrue("每个阶段都必须有时刻：$marks", marks.all { it != null })
        val values = marks.filterNotNull()
        assertEquals("阶段时刻必须单调不减", values.sorted(), values)
        assertEquals("每次都该落一行日志", VoiceTrace.ORDER.size, lines.size)
    }

    @Test
    fun `重复打同一阶段只保留第一次`() {
        beginWithSink()
        VoiceTrace.mark("first frame")
        val first = VoiceTrace.lastMs("first frame")
        Thread.sleep(2)
        VoiceTrace.mark("first frame")
        assertEquals("重复打点不得覆盖首次时刻", first, VoiceTrace.lastMs("first frame"))
        assertEquals("只该落一行", 1, lines.size)
    }

    @Test
    fun `没到的阶段在汇总里标未到`() {
        beginWithSink()
        VoiceTrace.mark("model present")
        val summary = VoiceTrace.summary()
        assertTrue("汇总要含已到阶段：$summary", summary.contains("model present +"))
        assertTrue("汇总要标出未到阶段：$summary", summary.contains("committed 未到"))
    }

    @Test
    fun `未开始时汇总说明未开始`() {
        VoiceTrace.reset()
        VoiceTrace.sink = { lines.add(it) }
        assertNull("未 begin 就没有模型阶段", VoiceTrace.lastMs("model present"))
        assertTrue("此时汇总要写明未开始", VoiceTrace.summary().contains("未开始"))
    }

    @Test
    fun `未 begin 直接打点也不丢记录`() {
        VoiceTrace.reset()
        VoiceTrace.sink = { lines.add(it) }
        VoiceTrace.mark("model present")
        assertTrue("未 begin 时也要安全落一行", lines.isNotEmpty())
        assertNotNull("打点即开始计时，阶段必须可读", VoiceTrace.lastMs("model present"))
    }

    @Test
    fun `阶段上限不丢已有关键阶段`() {
        beginWithSink()
        VoiceTrace.mark("model present")
        // 灌一堆杂阶段，塞满上限后仍能读出关键阶段
        for (i in 0 until 20) VoiceTrace.mark("extra$i")
        assertNotNull("关键阶段必须还在", VoiceTrace.lastMs("model present"))
        assertEquals("超过上限的杂阶段不再记录", null, VoiceTrace.lastMs("extra19"))
    }
}
