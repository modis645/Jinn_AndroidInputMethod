package com.jinn.inputmethod

import java.text.BreakIterator
import java.util.Locale

/**
 * 剪贴板内容的「分词」（2026-11-14）。
 *
 * 目标只有一个：一条记录里**逐段挑出**要粘的那一截 —— 网址、手机号/长数字先整体扣出来，
 * 剩下的中文按 ICU 词边界切词（Android 自带词典，不需要我们带词库）。
 *
 * 设计取舍：
 *  - 只做「切分 + 逐段粘贴」，不改剪贴板的分类、分组、加密与去重（那条链路一行不动）；
 *  - 纯标点、纯空白不成段（粘一个逗号没有意义，列出来只会把有用的段挤走）；
 *  - 段数设上限：一条消息可能有上万字，全列出来既卡又没人翻，超出的截断后由面板提示。
 */
object ClipboardSegments {

    /** 面板一屏最多列这么多段：再长就不是「挑一段粘」，而是拿它当阅读器了 */
    const val MAX_SEGMENTS = 80

    /** 单段最大长度：网址可以有几百字符，超过这个长度只可能是被误判的整段文本 */
    private const val MAX_SEGMENT_CHARS = 512

    /**
     * 整体扣出、不参与切词的片段：网址（http/https/ftp/www）、手机号、长数字串。
     *
     * 网址里的 `/ . ? = &` 都是词边界的「噪音」，交给 [BreakIterator] 会切成一堆碎片，
     * 所以必须在切词之前整体认出来。中文标点作为网址结束符排除在外（「看这个：https://a.com。」）。
     */
    private val ATOMIC = Regex(
        "(https?://[^\\s，。、；：！？（）「」\"'<>]+" +
            "|ftp://[^\\s，。、；：！？（）「」\"'<>]+" +
            "|www\\.[^\\s，。、；：！？（）「」\"'<>]+" +
            "|(?:\\+?86[- ]?)?1[3-9]\\d{9}" +
            "|[0-9][0-9\\- ]{5,}[0-9])"
    )

    /**
     * 切分。返回值保证每段都非空且不含纯标点；空输入返回空列表（面板据此提示「这段没内容」）。
     */
    fun split(text: String): List<String> {
        val out = ArrayList<String>(MAX_SEGMENTS)
        var cursor = 0
        for (match in ATOMIC.findAll(text)) {
            if (out.size >= MAX_SEGMENTS) return out
            if (match.range.first > cursor) addWords(text.substring(cursor, match.range.first), out)
            if (out.size >= MAX_SEGMENTS) return out
            addSegment(match.value, out)
            cursor = match.range.last + 1
        }
        if (cursor < text.length && out.size < MAX_SEGMENTS) addWords(text.substring(cursor), out)
        return out
    }

    /** 中文/英文按词切。ICU 把标点与空白也当一段，这里丢掉它们（只留含字母或数字的） */
    private fun addWords(chunk: String, out: MutableList<String>) {
        if (chunk.isBlank()) return
        val iterator = BreakIterator.getWordInstance(Locale.CHINA)
        iterator.setText(chunk)
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE && out.size < MAX_SEGMENTS) {
            val piece = chunk.substring(start, end)
            if (piece.any { it.isLetterOrDigit() }) addSegment(piece, out)
            start = end
            end = iterator.next()
        }
    }

    private fun addSegment(raw: String, out: MutableList<String>) {
        val piece = raw.trim().trim(',', '.', '。', '，', '、', '；', '：', '！', '？', '"', '\'', '（', '）')
        if (piece.isEmpty() || out.size >= MAX_SEGMENTS) return
        out += if (piece.length > MAX_SEGMENT_CHARS) piece.substring(0, MAX_SEGMENT_CHARS) else piece
    }
}
