package com.jinn.inputmethod

import org.json.JSONObject

/**
 * 文本与凭据清洗的公共工具（2026-11-17 从 `Translation.kt` 抽出）。
 *
 * 在线翻译整体删除后，`jsonText` / `hasVisibleContent` / `cleanCredential` 仍被剪贴板、
 * 转写响应解析与凭据读写使用 —— 这些是**与翻译无关**的通用校验，故单独留在这里。
 */

/**
 * 从 JSON 对象取字符串字段：**显式 `null` 与类型不符都返回 null**。
 *
 * `optString(key)` 对「存在但为 JSON null」返回的是字面量 `"null"`（不是空串），
 * `isNullOrBlank()` 拦不住它 —— 字面量 `null` 会被当译文提交进用户输入框。
 * `optString` 对数组/对象还会回序列化文本，同样是假译文。六家 Provider 统一走这里，
 * 不再各写各的口径（OpenAiTranslator 的 `keyOf` 早就过滤了 `JSONObject.NULL`，
 * 其余各家漏了，属内部不一致 —— 2026-09-30 审查发现）。
 */
internal fun jsonText(json: JSONObject?, key: String): String? = json?.opt(key) as? String

/**
 * 是否含**可见内容**（非空白、且非零宽字符）。
 *
 * ⚠ 判据是 `Char.isWhitespace()`，它在 JVM 上是 `Character.isWhitespace(c) || Character.isSpaceChar(c)`：
 * **ZWSP（U+200B）与 BOM（U+FEFF）返回 false**（`Cf` 类，Java 明列为非空白），而
 * **NBSP（U+00A0）返回 true**（它是 `Zs`，被 `isSpaceChar` 接住）—— 两种都判不出来，要靠
 * [ZERO_WIDTH] 兜。少一个码位就多一条「一个不可见字符也算有内容」的漏洞：用户从网页复制来的
 * 零宽字符会被当成有内容 ⇒ 发出一次真实（计费）请求，而模型面对一个不可见字符**可能编造整句译文**
 * 并把它追加进用户正文（2026-09-30 审查发现）。反向同理：服务端若只回零宽字符，也不能写回。
 *
 * ⚠ 但**判空不等于可写**：`U+202E`（RLO）这类双向文本控制符既非空白也不在 [ZERO_WIDTH] 里，
 * 「有可见内容」成立，写进输入框却会改变**用户自己那段文字**的显示顺序 —— 见 [sanitizedForOutput]。
 */
internal fun CharSequence.hasVisibleContent(): Boolean {
    var i = 0
    while (i < length) {
        // 按**码位**遍历（2026-10-02 修复 L-339）：TAG 字符是代理对，逐 `Char` 判时低代理
        // 既非空白、也不在任何表里 ⇒ 一串纯 TAG 文本会被判「有内容」，一段用户看不见的东西
        // 被写进输入框并随复制传染 —— 正是本条要关掉的那条路。
        val cp = Character.codePointAt(this, i)
        // ⚠ 必须**同时**问 `isWhitespace` 与 `isSpaceChar`（2026-10-02 修复）：JDK 的
        // `Character.isWhitespace(int)` **明确排除非断行空格**（U+00A0 / U+2007 / U+202F），
        // 而本函数的语义（下面 KDoc 与全部调用点）是 Kotlin `Char.isWhitespace()` 那一套
        // （= `isWhitespace || isSpaceChar`）。少了这一半，一个 U+202F（窄 NBSP，从网页复制极常见）
        // 就足以让「纯不可见内容」过检 ⇒ 发出一次真实（计费）请求，而模型面对它可能编造整句译文
        // 并写进用户正文。U+00A0 另有 [ZERO_WIDTH] 兜住，U+2007 / U+202F 依赖这里。
        if (!Character.isWhitespace(cp) && !Character.isSpaceChar(cp) && !isInvisibleCodePoint(cp)) {
            return true
        }
        i += Character.charCount(cp)
    }
    return false
}

/**
 * **判空**用的「不可见全集」（2026-10-01 修复 L-324）。
 *
 * 在 [ZERO_WIDTH] 之外再补几个「本身没有内容、只在依附于基字符时才有意义」的面：
 * 软连字符、不可见运算符、变体选择符、TAG 字符（后者能编码出一串隐藏 ASCII）。
 * 少了它们，服务端只回一个 `U+FE0F` 或一串 TAG 字符也能过「有没有内容」这一关，
 * 于是一段**用户看不见的东西**被写进输入框，并随复制继续传染到邮件与网页表单。
 *
 * ⚠ 只用于**判空，不用于剥除**：变体选择符与 TAG 字符在 emoji 序列（❤️ / 旗帜）里是语义相关的，
 * 剥掉会破坏正文；但一串**只有它们**的「译文」没有任何意义，不能当成有效结果写回。
 */
private fun isInvisibleCodePoint(cp: Int): Boolean = when {
    cp in ZERO_WIDTH_CODEPOINTS -> true
    cp == 0x00AD -> true                      // 软连字符
    cp in 0x2061..0x2064 -> true               // 不可见运算符（U+2060 已在 ZERO_WIDTH 里）
    cp in 0x206A..0x206F -> true               // 废弃格式符（inhibit / symmetry 等，L-704）
    cp == 0x061C -> true                      // ALM（阿拉伯字母标记）：同族，判空侧也要认（L-343）
    cp in 0xFE00..0xFE0F -> true               // 变体选择符（没有基字符时无意义）
    cp in 0xE0000..0xE01EF -> true             // TAG 字符 + VS Supplement（L-704 补齐 Supplement 段）
    cp == 0x2800 -> true                      // 盲文空白（L-343）
    cp == 0x115F || cp == 0xFFA0 -> true       // Hangul 填充符（L-343）
    // ↓↓ L-704（2026-10-03）：以下三类此前漏判，纯它们构成的原文会被判成「有内容」⇒ 发一次
    // **真实计费**请求，模型面对一个不可见字符可能编造整句译文并写进用户正文。
    cp in 0x180B..0x180E -> true              // 蒙古文元音分隔符（U+180E 至今仍被大量编辑器插入）
    cp == 0x3164 -> true                      // Hangul Filler（韩语最常用；上两条只收了 115F / FFA0）
    cp == 0x034F -> true                      // COMBINING GRAPHEME JOINER（Mn 类，不可见）
    cp in 0xD800..0xDFFF -> true              // 孤立代理（**判空侧**；正常代理对不会被拆开遍历，
    //                                            剥除表不得加它 —— 那会破坏 emoji，见 L-578）
    else -> false
}

/** 常见「看起来是空、`isWhitespace()` 却不认」的零宽字符（含 NBSP：网页/文档复制的常客） */
private const val ZERO_WIDTH = "\u200B\u200C\u200D\u2060\uFEFF\u00A0"

/**
 * [ZERO_WIDTH] 的码位形态（判空按码位遍历，与 Char 表同源等值）。
 *
 * ⚠ 必须定义在 [ZERO_WIDTH] **之后**：顶层 `val` 按文件内声明顺序初始化，
 * 反过来写在前面会直接编译失败（"must be initialized"）。
 */
private val ZERO_WIDTH_CODEPOINTS: Set<Int> = ZERO_WIDTH.map { it.code }.toSet()

/**
 * 凭据/端点取值的统一清洗：**剥掉不可见 / 非法头值字符再 trim**（2026-10-03 修复 L-702）。
 *
 * 剥除判据从「枚举 6 个码位」改成**按 Unicode 类别**（`FORMAT` / `LINE_SEPARATOR` /
 * `PARAGRAPH_SEPARATOR` / `SPACE_SEPARATOR` / `CONTROL`）+ `DEL`：
 *  - 原先只剥 `ZERO_WIDTH` 六码位，而**真正会让 okhttp 4.12 抛 `IllegalArgumentException` 的
 *    `U+007F`（DEL）不在其中**（`trim()` 也不删它），带 DEL 的 Key 走到客户端才被归 CREDENTIAL；
 *  - 更常见的一类是**既不抛异常、也不被剥**的那些（U+202F / U+2007 / U+180E / U+00AD / U+2061）：
 *    它们直接进 `Authorization` 头 ⇒ 服务端 401 ⇒ 归 AUTH「请检查凭据」，用户拿肉眼看不出问题的
 *    Key 反复核对仍失败，整条链没有一处指向真因 —— 正是本函数 KDoc 想避免的结局。
 *
 * ⚠ 按**类别**而不是白名单枚举，是为了不再漏下一个：不可见字符是 Unicode 里的一个类，不是 6 个点。
 * ⚠ 逐**码位**处理（`codePointAt` + `charCount`）：按 `Char` 迭代会把代理对拆成两个单元。
 */
internal fun String.cleanCredential(): String {
    val sb = StringBuilder(length)
    var changed = false
    var i = 0
    while (i < length) {
        val cp = codePointAt(i)
        if (isCredentialUnsafeChar(cp)) {
            changed = true
        } else {
            sb.appendCodePoint(cp)
        }
        i += Character.charCount(cp)
    }
    return (if (changed) sb.toString() else this).trim()
}

/**
 * 该码位是否必须从凭据 / 端点取值里剥掉（理由见 [cleanCredential]）。 */
private fun isCredentialUnsafeChar(cp: Int): Boolean {
    if (cp < 0x20 || cp == 0x7F) return true // C0 与 DEL：okhttp 头值校验直接拒（按码位比）
    // ⚠ SPACE_SEPARATOR 里**必须放过 U+0020（普通空格）**：它在凭据内部是合法的（Base URL 的 path、
    // Azure 区域名都可能出现），整类剥掉会把 `https://host/v1/chat` 之类的取值拼坏 ——
    // 首尾空白由 `trim()` 负责，只有「看起来像空白但不是空白」的那些（NBSP / U+202F / U+2007）该剥。
    if (Character.getType(cp).toByte() == Character.SPACE_SEPARATOR) return cp != 0x20
    return when (Character.getType(cp).toByte()) {
        Character.FORMAT,
        Character.LINE_SEPARATOR,
        Character.PARAGRAPH_SEPARATOR,
        Character.CONTROL,
        -> true
        else -> false
    }
}
