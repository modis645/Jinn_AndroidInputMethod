package com.jinn.inputmethod

/**
 * 剪贴板内容的「分词」（2026-11-14 首版；2026-11-15 改按短语切）。
 *
 * 目标：一条记录里**逐段挑出**要粘的那一截 —— 网址、手机号/长数字整体扣出来，
 * 其余文本按**标点与空白**切成短语级片段（不再逐词切）。
 *
 * 为什么不再逐词（参照沙鸥拼音的分词口径）：
 *  - 上一版用 ICU 词边界逐词切，一句「明天下午三点开会，地点在三楼会议室」会被切成
 *    「明天/下午/三点/开会/地点/在/三楼/会议室」八段，碎到挑不出有意义的单位；
 *  - 沙鸥那边是「按标点与换行切块」：一句话 / 一个短句就是一块，挑一两块粘过去正好；
 *  - 因此本版只保留两类边界：**标点与空白**（成句/成块），以及 ATOMIC（网址/号码）；
 *    太长且无标点的块（如整段无标点长文）按 [MAX_SEGMENT_CHARS] 硬切，防止一段占满屏。
 *
 * 不改的部分：分类、分组、收藏、加密与去重一行不动，本对象只做纯函数切分。
 */
object ClipboardSegments {

    /** 面板一屏最多列这么多段：再长就不是「挑一段粘」，而是拿它当阅读器了 */
    const val MAX_SEGMENTS = 80

    /** 单段最大长度：网址可以有几百字符，超过这个长度只可能是被误判的整段文本 */
    private const val MAX_SEGMENT_CHARS = 300

    /**
     * 整体扣出、不参与切块的片段：网址（http/https/ftp/www）、手机号、长数字串。
     *
     * 网址里的 `/ . ? = &` 都是块的边界噪音，必须先在块边界之前整体认出来。
     * 中文标点作为网址结束符排除在外（「看这个：https://a.com。」）。
     */
    private val ATOMIC = Regex(
        "(https?://[^\\s，。、；：！？（）「」\"'<>]+" +
            "|ftp://[^\\s，。、；：！？（）「」\"'<>]+" +
            "|www\\.[^\\s，。、；：！？（）「」\"'<>]+" +
            "|(?:\\+?86[- ]?)?1[3-9]\\d{9}" +
            "|[0-9][0-9\\- ]{5,}[0-9])"
    )

    /**
     * 块边界：标点 / 空白 / 换行。中英文标点都算（复制来的内容两种混用是常态）。
     * 引号与括号一并当边界 —— 它们几乎总是包着一句话，留在段里只会多两个符号。
     */
    private val BLOCKS = Regex("[\\s\\u3000，。、；：！？,.;:!?…·\\-—_/\\\\|\\[\\]【】{}《》〈〉（）()｛｝\"“”'‘’]+")

    /**
     * 切分。返回值保证每段都非空（不含纯标点）；空输入返回空列表（面板据此提示「这段没内容」）。
     */
    fun split(text: String): List<String> {
        val out = ArrayList<String>(MAX_SEGMENTS)
        var cursor = 0
        for (match in ATOMIC.findAll(text)) {
            if (out.size >= MAX_SEGMENTS) return out
            if (match.range.first > cursor) addBlocks(text.substring(cursor, match.range.first), out)
            if (out.size >= MAX_SEGMENTS) return out
            addSegment(match.value, out)
            cursor = match.range.last + 1
        }
        if (cursor < text.length && out.size < MAX_SEGMENTS) addBlocks(text.substring(cursor), out)
        return out
    }

    /** 按标点/空白切块；块内不再二次切分，过长时才按 [MAX_SEGMENT_CHARS] 硬切 */
    private fun addBlocks(chunk: String, out: MutableList<String>) {
        if (chunk.isBlank()) return
        for (piece in BLOCKS.split(chunk)) {
            if (out.size >= MAX_SEGMENTS) return
            addSegment(piece, out)
        }
    }

    private fun addSegment(raw: String, out: MutableList<String>) {
        val piece = raw.trim().trim(',', '.', '。', '，', '、', '；', '：', '！', '？', '、', '"', '\'', '（', '）')
        if (piece.isEmpty() || out.size >= MAX_SEGMENTS) return
        // 纯标点/纯符号不成段：粘一个逗号没有意义，列出来只会把有用的段挤走
        if (piece.none { it.isLetterOrDigit() }) return
        if (piece.length <= MAX_SEGMENT_CHARS) {
            out += piece
            return
        }
        // 无标点的长文：按上限硬切，尽量切在空格处，避免把英文单词劈开
        var start = 0
        while (start < piece.length && out.size < MAX_SEGMENTS) {
            val end = (start + MAX_SEGMENT_CHARS).coerceAtMost(piece.length)
            val cut = piece.lastIndexOf(' ', end - 1).let { if (it > start + MAX_SEGMENT_CHARS / 2) it else end }
            out += piece.substring(start, cut).trim()
            start = cut
        }
    }

    /**
     * 多段拼接（对应面板的「多选粘贴」）。
     *
     * 拼接规则很短：相邻两段**都是 ASCII 字母/数字结尾与开头**时补一个空格
     * （「abc」「def」直接粘成「abcdef」读不出来），否则直接相接（中文本来不需要空格）。
     */
    fun join(parts: List<String>): String {
        val sb = StringBuilder()
        var prev: String? = null
        for (part in parts) {
            if (part.isEmpty()) continue
            if (prev != null && needsSpace(prev, part)) sb.append(' ')
            sb.append(part)
            prev = part
        }
        return sb.toString()
    }

    private fun needsSpace(left: String, right: String): Boolean =
        left.lastOrNull()?.let { it.code < 128 && it.isLetterOrDigit() } == true &&
            right.firstOrNull()?.let { it.code < 128 && it.isLetterOrDigit() } == true
}
