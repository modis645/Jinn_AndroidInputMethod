package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-17 真机三条反馈的代码级守卫：
 *
 * ① 「长语音说着说着自动结束，已输文字全部删除」——录音中途必须收尾的三条路径
 *    （录音器报错 / 音频焦点被抢 / 静音闸门）不许再把整段作废；
 * ② 「下载模型与删除模型应为一个按钮」——语音页只留一个主按钮，三态文案；
 * ③ 「结束后要等很久才上屏」——部分解码必须给最终结果让路。
 *
 * 这几条都发生在 Activity / IME 的生命周期里，JVM 单测跑不到，只能对源码钉行为。
 */
class VoiceLatencyAndButtonTest {

    private fun ime() = codeOf("JinnIme.kt")
    private fun client() = codeOf("LocalSenseVoiceClient.kt")
    private fun page() = codeOf("VoiceSettingsActivity.kt")

    private fun codeOf(name: String): String = TestSources.codeOf(
        TestSources.rawSource(
            "src/main/java/com/jinn/inputmethod/$name",
            "app/src/main/java/com/jinn/inputmethod/$name",
        ),
    )

    // ── ① 录音中途不许作废 ───────────────────────────────────────────

    @Test
    fun `音频焦点瞬断不得终止录音`() {
        val src = ime()
        val focus = TestSources.blockAfter(src, "private val audioFocusListener")
        assertTrue("焦点回调里不该再有作废录音的出口", !focus.contains("stopRecording(commit = false)"))
        assertTrue("瞬断分支要单独存在", focus.contains("AUDIOFOCUS_LOSS_TRANSIENT"))
        assertTrue("真正失去焦点按「有没有内容」决定是否上屏", focus.contains("stopRecording(commit = hasVoiceContent())"))
    }

    @Test
    fun `静音闸门只收会话不丢内容`() {
        val src = ime()
        val gate = TestSources.blockAfter(src, "private val dictationIdleStop")
        assertTrue("闸门不许再 commit = false 作废录音", !gate.contains("stopRecording(commit = false)"))
        assertTrue("要按「已说内容」收尾", gate.contains("stopRecording(commit = true)"))
    }

    @Test
    fun `中途结果一到就撤掉静音闸门`() {
        val src = ime()
        val partial = TestSources.blockAfter(src, "if (!message.isFinal) {")
        assertTrue("解出字就说明有人在说", partial.contains("dictationHeard = true"))
        assertTrue("要撤掉待发的闸门", partial.contains("removeCallbacks(dictationIdleStop)"))
    }

    @Test
    fun `人声门限必须足够低`() {
        val v = JinnIme.DICTATION_VOICE_LEVEL
        assertTrue("Mi 10 上 0.35 长期打不到，门限应 ≤ 0.2，当前 $v", v <= 0.2f)
        assertTrue("也不能低到噪声就触发，当前 $v", v >= 0.05f)
    }

    @Test
    fun `有没有内容要有统一判据`() {
        val src = ime()
        val fn = TestSources.blockAfter(src, "private fun hasVoiceContent()")
        assertTrue("写过组合态即算有内容", fn.contains("voiceComposingShown"))
        assertTrue("录了 1 秒以上也算", fn.contains("1_000L"))
    }

    // ── ③ 预览与上屏分工（准确率优先） ───────────────────────────────

    @Test
    fun `预览只解滚动尾巴，上屏只做一次完整解码`() {
        val src = client()
        assertTrue("录音期间要有预览线程", src.contains("startSegmentLoop"))
        val preview = TestSources.blockAfter(src, "private fun emitPreview")
        assertTrue("预览解的是最后一段尾巴", preview.contains("PREVIEW_TAIL_SAMPLES"))
        assertTrue("预览要节流，别把识别器占满", preview.contains("PREVIEW_MIN_INTERVAL_MS"))
        val rec = TestSources.blockAfter(src, "override fun recognize")
        assertTrue("上屏走整段完整解码", rec.contains("decodePcm16(pcm16, withVad = true)"))
        assertFalse("上屏不许拼分段句（会掉字串字）", rec.contains("VoiceTailMerge"))
    }

    @Test
    fun `录音缓冲必须容得下长语音整段`() {
        // 用户 2026-11-17：「长语音点结束后最后几句话被吞掉」—— 根因是 60 秒缓冲上限
        val need = BufferedBatchAsrClient.MAX_TOGGLE_SECONDS * 32_000
        assertTrue(
            "缓冲 ${BufferedBatchAsrClient.MAX_PCM_BYTES}B 必须容下 ${need}B（300 秒）",
            BufferedBatchAsrClient.MAX_PCM_BYTES > need,
        )
    }

    @Test
    fun `最终解码用 4 线程`() {
        // 实测 RTF 0.30（8 秒尾巴 2400ms）：长文的总账只能靠并行度缩短
        assertTrue(client(), client().contains("numThreads = 4"))
    }

    // ── ② 一个按钮三态 ──────────────────────────────────────────────

    @Test
    fun `语音页只留一个模型按钮`() {
        val src = page()
        assertTrue("删除按钮恒隐藏", src.contains("btnLocalAsrDelete.visibility = View.GONE"))
        assertTrue("有模型时主按钮变成删除", src.contains("btnLocalAsrDownload.text = TEXT_LOCAL_ASR_DELETE"))
        assertTrue("没模型时主按钮是下载", src.contains("btnLocalAsrDownload.text = TEXT_LOCAL_ASR_DOWNLOAD"))
        assertTrue("下载中是取消", src.contains("btnLocalAsrDownload.text = TEXT_LOCAL_ASR_CANCEL"))
    }

    @Test
    fun `就绪态只留两行字`() {
        val src = page()
        assertTrue("第一行是定位句", src.contains("TEXT_LOCAL_ASR_DESC = \"本机离线识别：不联网、不需要任何 Key。\""))
        assertTrue("第二行只报就绪", src.contains("TEXT_LOCAL_ASR_READY = \"模型已就绪。\""))
        val ready = TestSources.blockAfter(src, "ready -> {")
        assertTrue("就绪后不再堆占用体积", !ready.contains("storageSelfCheckLine()"))
        assertTrue("就绪后不再堆存放路径说明", !ready.contains("存放在应用数据目录"))
    }

    @Test
    fun `删除前必须确认`() {
        val src = page()
        val click = TestSources.blockAfter(src, "private fun onLocalAsrDownloadClicked()")
        assertTrue("有模型时点击走删除确认", click.contains("confirmDeleteLocalAsr()"))
        val dialog = TestSources.blockAfter(src, "private fun confirmDeleteLocalAsr()")
        assertTrue("要弹确认框", dialog.contains("AlertDialog"))
        assertTrue("确认后才真删", dialog.contains("onLocalAsrDeleteClicked()"))
    }
}
