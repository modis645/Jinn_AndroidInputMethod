package com.jinn.inputmethod

/**
 * 语音识别等待时限的纯函数（原先写死在 [JinnIme] 的 `RECOGNIZE_TIMEOUT_MS`）。
 *
 * 为什么单独成对象：真机上「长语音点结束后文字迟迟不上屏」有两个成因 ——
 * ① 本地解码比实时慢（3 分钟音频要解 60 秒以上），固定的 60 秒等待会先判超时，
 *    状态条回到「未连接」，用户以为没识别；② 超时那一刻如果不清组合态，
 *    输入框里就会永久留一段带下划线的「输入中」文字。
 * 时限按音频长度放宽这条规则必须能单测，否则只能靠真机再听一遍。
 */
internal object VoiceTiming {

    /** 在线后端：60 秒足够（服务端识别通常几秒出结果） */
    const val BASE_TIMEOUT_MS = 60_000L

    /**
     * 等待时限：在线固定 60 秒；本地按音频长度放宽（1.2 倍音频 + 15 秒余量）。
     *
     * 1.2 倍是实测口径：SenseVoice int8 在主流机型约 0.4~0.6 倍实时，留一倍余量足够，
     * 但又不会因为「5 分钟录音要等 10 分钟」把状态条卡死。
     */
    fun recognizeTimeoutMs(audioMs: Long, online: Boolean): Long {
        if (online) return BASE_TIMEOUT_MS
        if (audioMs <= 0L) return BASE_TIMEOUT_MS
        val stretched = audioMs + audioMs / 5 + 15_000L
        return maxOf(BASE_TIMEOUT_MS, stretched)
    }
}
