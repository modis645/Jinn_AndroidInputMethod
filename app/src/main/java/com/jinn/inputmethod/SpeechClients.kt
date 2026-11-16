package com.jinn.inputmethod

/**
 * 语音客户端工厂：**唯一**的装配点。
 *
 * 2026-11-17 定版：在线后端（讯飞 / OpenAI 兼容 / 阿里百炼 / 腾讯 / Azure / 百度 /
 * Deepgram / 自建 CapsWriter）连同实现类一起删除 —— 本输入法只保留**内置离线识别**
 * （sherpa-onnx + SenseVoice），所以这里不再有 when 分叉。
 *
 * 保留这个入口而不是让调用方直接 new [LocalSenseVoiceClient]，是因为
 * `JinnIme.ensureVoiceReady()` 与 [VoiceSelfTest] 两边共用一个装配点，
 * 将来真要再接引擎时只改这处。
 */
object SpeechClients {

    fun create(
        prefs: Prefs,
        onState: (LinkState, String?) -> Unit,
        onMessage: (RecognitionMessage) -> Unit,
    ): SpeechClient = LocalSenseVoiceClient(prefs, onState, onMessage)
}
