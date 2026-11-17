package com.jinn.inputmethod

/**
 * 录音期间「已经解完的句子」台账（纯逻辑，可 JVM 单测）。
 *
 * 为什么要有它：2026-11-17 真机日志算清了一件事 —— 这台机器的解码速度是 RTF≈0.30，
 * 110 秒音频的**整段解码总账就是 33 秒**，切成小块只是把账分摊、并不能减少。
 * 所以「结束→出字 50 秒」不可能靠调块长解决，唯一的结构性解法是：
 * 录音期间就把已经说完的句子解掉，结束时只解最后那句残留。
 *
 * 台账负责三件事，都不涉及音频与模型，因此可以直接测：
 *  1. 累积句子与**已覆盖的采样数**（覆盖多少 != 出多少字：噪声段也要算已消费）；
 *  2. 预览文本（边录边出的内容）；
 *  3. 判定是否**不可信、要退回整段解码**（一句都没解出来，或覆盖不到一半）。
 */
internal class VoiceStreamCommit {

    private val pieces = ArrayList<String>()

    /** 已经被「某次解码」消费掉的音频采样数（含没解出字的片段） */
    var consumedSamples = 0
        private set

    val pieceCount: Int get() = pieces.size

    fun reset() {
        pieces.clear()
        consumedSamples = 0
    }

    /**
     * 记一句。
     *
     * [text] 允许为空（这一段是喘气 / 环境音）：仍要推进 [consumedSamples]，
     * 否则覆盖率的分子永远长不大，最后被误判成「不可信」而白等一次整段解码。
     */
    fun add(text: String?, coveredSamples: Int) {
        val clean = text?.trim().orEmpty()
        if (clean.isNotEmpty()) pieces.add(clean)
        consumedSamples += maxOf(0, coveredSamples)
    }

    /** 只推进消费量（切出来但太短的噪声片段） */
    fun consume(coveredSamples: Int) = add(null, coveredSamples)

    /** 覆盖到整段音频的比例（0..1） */
    fun coverage(totalSamples: Int): Float =
        if (totalSamples <= 0) 0f else (consumedSamples.toFloat() / totalSamples).coerceIn(0f, 1f)

    /** 是否必须退回整段解码：一句都没有，或覆盖不到一半（宁可慢也不许丢话） */
    fun needsFallback(totalSamples: Int): Boolean =
        pieces.isEmpty() || coverage(totalSamples) < MIN_COVERAGE

    /** 已解出的全文（句间补「，」、末句补「。」） */
    fun text(): String = if (pieces.isEmpty()) "" else VoiceText.joinSentences(pieces)

    /** 预览用：还没有句末标点也一样显示，不额外补 */
    fun previewText(): String = pieces.joinToString("")

    companion object {
        /** 与整段 VAD 兜底同一条判据：覆盖不足一半就不信它 */
        const val MIN_COVERAGE = 0.5f
    }
}
