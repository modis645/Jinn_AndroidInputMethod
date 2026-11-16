package com.jinn.inputmethod

/**
 * 语音文本收尾的纯函数集合（从 [LocalSenseVoiceClient] 与 [JinnIme] 里抽出来）。
 *
 * 为什么单独成文件：这三条规则（句间补逗号、末句补句号、去掉句尾逗号句号）决定用户
 * 说话后上屏的**标点**，是最容易被反馈「标点太少 / 标点多余」的地方，但原先它们分别是
 * 两个 Android 类里的 private 方法 —— 纯 JVM 单测碰不到，只能靠真机试。
 */
internal object VoiceText {

    /** 视为「句子已经自带结尾」的字符：后面不再补标点 */
    val SENTENCE_END = charArrayOf('。', '！', '？', '.', '!', '?', '…')

    /** 上屏前要剥掉的句尾标点（对齐桌面端 trash_punc：聊天场景里句尾逗号更像噪音） */
    val TRAILING_PUNC = charArrayOf('，', '。', ',', '.')

    /**
     * 分句拼接：VAD 切出来的句子边界就是天然句界，句间补「，」、最后一句补「。」。
     * 模型自己已经说出句末标点的不再补；空句直接跳过，不留孤立标点。
     */
    fun joinSentences(sentences: List<String>): String {
        val sb = StringBuilder()
        val kept = sentences.map { it.trim() }.filter { it.isNotEmpty() }
        for ((i, piece) in kept.withIndex()) {
            sb.append(piece)
            if (sb.last() in SENTENCE_END) continue
            sb.append(if (i == kept.lastIndex) '。' else '，')
        }
        return sb.toString()
    }

    /** 整段解码（不走 VAD）的收尾：模型多数自带句末标点，缺了就补一个「。」 */
    fun finishText(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return t
        return if (t.last() in SENTENCE_END) t else t + "。"
    }

    /** 上屏前去掉句尾逗号 / 句号（只去「收尾」的，不动句中的标点） */
    fun stripTrailingPunc(text: String): String =
        text.trimEnd().trimEnd(*TRAILING_PUNC)
}
