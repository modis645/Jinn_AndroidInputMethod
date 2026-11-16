package com.jinn.inputmethod

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * 离线语音识别的模型落点与就绪判定（sherpa-onnx + SenseVoice「快速整句」）。
 *
 * 目录约定：`filesDir/local-asr/`（会被系统的「清除数据」一并清掉，不做任何外部存储依赖）。
 * 只有两件文件是必需的：
 *  - `model.int8.onnx`：SenseVoice small 的 int8 量化模型（整句识别，不是流式）；
 *  - `tokens.txt`：同一目录里的 token 表。
 *
 * 为什么用 int8：全精度模型 230 MB 级，int8 版约 1/4，手机端单句解码仍是百毫秒级，
 * 与「快速整句」（松手后一次性解码、接受一点延迟）的用法匹配。
 */
internal object LocalAsrModel {

    const val DIR_NAME = "local-asr"

    /**
     * 自建 CDN 镜像（2026-11-15）：与 GitHub 上是**同一个字节流**（本机下载后 sha256 核对
     * `7d1efa…` 一致），只是换成国内直达的存储。
     *
     * 为什么需要它：真机上 github.com 的域名解析/连接经常不动（用户两次反馈「长时间没反应」，
     * 而同一台手机上沙鸥的下载很快），加速站又时好时坏（实测 ghfast.top 回 403、
     * hub.gitmirror.com 域名已失效）。把自己核过的整包放到国内 CDN 上，是唯一不依赖
     * GitHub 可达性的通路；sha256 校验仍然照做，内容不对就自动换下一个源。
     */
    private const val CDN_ARCHIVE_URL = "https://aka.doubaocdn.com/s/2S62KwDwWM"
    private const val CDN_VAD_URL = "https://aka.doubaocdn.com/s/t4hRxSVfJj"


    /**
     * silero VAD 模型（约 2 MB）：只用于**长句自动分句**，缺了不影响识别 ——
     * 没有它时长句整段解码，有它就按静音切开逐句解码再拼回去（见 LocalSenseVoiceClient）。
     */
    const val VAD_FILE = "silero_vad.onnx"

    /** VAD 的官方直链（`asr-models` release 下的单个文件） */
    private const val VAD_PATH =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"

    /** VAD 直链大小（官方 643,854 字节）；小于它的响应按「不是这个文件」处理 */
    const val VAD_MIN_BYTES = 100_000L

    /**
     * VAD 的下载顺序：**官方直连优先**，加速前缀只作兜底。
     *
     * 2026-11-15 对照沙鸥（msime）确认：同一台手机上官方直连是通的，先试代理反而会被
     * 代理回的网页/超时拖住，整体表现成「连不上」。代理只在前面的官方源失败时才用。
     *
     * 顺带实测了几家加速站（同一文件，本机直连）：gh-proxy.com、ghproxy.net 正常返回
     * 163,002,883 字节的 octet-stream；ghfast.top 回 403；hub.gitmirror.com 域名已失效
     * ⇒ 死掉的删掉，能用的按可用性排在官方后面。
     */
    val VAD_URLS: List<String> = listOf(
        CDN_VAD_URL,
        VAD_PATH,
        "https://gh-proxy.com/$VAD_PATH",
        "https://ghproxy.net/$VAD_PATH",
        "https://ghfast.top/$VAD_PATH",
    )

    const val MODEL_FILE = "model.int8.onnx"
    const val TOKENS_FILE = "tokens.txt"

    /**
     * 归档：SenseVoice int8 2024-07-17（163,002,883 字节）。
     *
     * 为什么用 2024-07-17 而不是 2025-09-09：沙鸥（msime）在真机上跑的一直是这个包，
     * 大小与 sha256 都已实测过（下方常量），解出来的成员名也确定是
     * `model.int8.onnx` + `tokens.txt`。2025-09-09 虽然存在，但没在真机上验过，
     * 一旦成员名或结构有变，就会表现成「下载完了却说解包失败」。先对齐已知可用的那一版。
     */
    private const val ARCHIVE_PATH =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
            "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2"

    /** 归档的官方字节数与摘要（用于「代理返回网页 / 截断」的判定） */
    const val ARCHIVE_SIZE = 163_002_883L
    const val ARCHIVE_SHA256 = "7d1efa2138a65b0b488df37f8b89e3d91a60676e416f515b952358d83dfd347e"

    /** 低于这个字节数一律判为「拿到的不是模型」（留 5% 余量给不同 CDN 的等同文件） */
    const val ARCHIVE_MIN_BYTES = (ARCHIVE_SIZE * 95 / 100)

    const val ARCHIVE_NAME = "sense-voice-small.tar.bz2"

    /**
     * 模型文件的下限体积（实测 239.2 MB）。
     *
     * 之前就绪判定只看「文件存在且非空」—— 被外部清理工具截断、或写到一半被杀，
     * 一个 0 字节或半截的 model.int8.onnx 也会被判成「已就绪」，然后在识别时莫名失败。
     * 留足余量取 200 MB：正常件永远超过它，被截的件永远达不到。
     */
    const val MODEL_MIN_BYTES = 200_000_000L

    /**
     * 归档的下载顺序：**官方直连优先，加速前缀兜底**（理由同 [VAD_URLS]）。
     *
     * 之前把三个公共加速站放在最前，结果代理常回「200 + 一段 HTML 错误页」，下载线程
     * 见 got>0 就当成功，解包时才报「归档里找不到模型」—— 用户看到的是「下载无效」。
     */
    val ARCHIVE_URLS: List<String> = listOf(
        CDN_ARCHIVE_URL,
        ARCHIVE_PATH,
        "https://gh-proxy.com/$ARCHIVE_PATH",
        "https://ghproxy.net/$ARCHIVE_PATH",
        "https://ghfast.top/$ARCHIVE_PATH",
    )

    /**
     * 模型目录：**就一个** —— 应用私有 `filesDir/local-asr`，与沙鸥（msime）一致。
     *
     * 为什么回到这一条（2026-11-16 用户对照沙鸥实测）：「模型在用户数据、缓存只有几百 k」
     * 本来就是这个布局的自然结果 —— `files/` 归「用户数据」，只有 `cacheDir` 才进「缓存」。
     * 之前为了躲「清缓存删模型」把模型搬到外部专属目录，等于自己造出两套候选目录，随后
     * 一路长出「三态判定 / 迁移 / 跨分区复制 / 目录翻转」这些复杂度，最后表现出来的反而是
     * 「文件明明在、页面说未下载」。一个落点 + 一次 rename 才是最简单也最不容易错的。
     */
    fun dir(context: Context): File = File(context.filesDir, DIR_NAME).apply { mkdirs() }

    /** 历史版本的落点（`Android/data/<pkg>/files/local-asr`）；唯一用途是把旧模型收回私有目录 */
    private fun externalDir(context: Context): File? =
        runCatching { context.getExternalFilesDir(null) }.getOrNull()?.let { File(it, DIR_NAME) }

    /**
     * 收尾：把历史版本留在外部专属目录里的模型**收回**私有目录（幂等，可在任意线程调用）。
     *
     * 只做「搬」，不做下载；内部已就绪时外部那份直接删掉，不占双份空间。
     * 搬动用 [copyInto]（先写 `.part` 再改名），中途被杀时私有目录仍是完整件，
     * 不会把模型判成丢失；搬成功才删外部目录。
     */
    fun migrateIfNeeded(context: Context) {
        val target = dir(context)
        val ext = externalDir(context) ?: return
        if (ext.absolutePath == target.absolutePath) return
        val extFiles = ext.listFiles().orEmpty().filter { it.isFile && it.length() > 0L }
        if (extFiles.isEmpty()) {
            runCatching { ext.deleteRecursively() }
            return
        }
        if (isReady(context)) {
            runCatching { ext.deleteRecursively() }
            Diagnostics.i("LocalAsr", "私有目录已就绪，清掉外部历史残留")
            return
        }
        var all = true
        for (f in extFiles) {
            if (!copyInto(f, File(target, f.name))) {
                all = false
                break
            }
        }
        if (all) {
            runCatching { ext.deleteRecursively() }
            Diagnostics.i("LocalAsr", "已把外部历史模型收回私有目录（" + audit(context).line() + "）")
        } else {
            Diagnostics.w("LocalAsr", "外部历史模型回收失败，保留原样（不影响已有内容）")
        }
    }

    /**
     * 模型目录体检：目录里到底有什么，每个必备件各多少字节。
     *
     * 为什么要有它（2026-11-16 真机反馈「下载解包正常、页面显示未下载、系统存储却显示占了 240MB」）：
     * 「已下载」和「未就绪」两个结论来自同一次判断，一旦它们互相矛盾，界面只能给出一个自相矛盾
     * 的说法。体检结果把**事实**（文件在不在、多大）摊到明面上，界面照抄，就不再需要猜。
     */
    data class Audit(
        val dirPath: String,
        val modelBytes: Long,
        val tokensBytes: Long,
        val vadBytes: Long,
        val stray: List<String>,
    ) {
        val ready: Boolean
            get() = modelBytes > MODEL_MIN_BYTES && tokensBytes > 0

        /** 一行给用户 / 日志看的事实 */
        fun line(): String {
            fun mb(b: Long) = if (b >= 1024 * 1024) "${b / (1024 * 1024)}MB" else "${b / 1024}KB"
            val where = dirPath.substringAfterLast("/files/", dirPath)
            return "目录 …/files/" + where +
                "｜model " + (if (modelBytes > 0) mb(modelBytes) else "缺失") +
                "｜tokens " + (if (tokensBytes > 0) mb(tokensBytes) else "缺失") +
                (if (vadBytes > 0) "" else "｜vad 缺失") +
                (if (stray.isEmpty()) "" else "｜其他 " + stray.joinToString("、"))
        }
    }

    fun audit(context: Context): Audit {
        val d = dir(context)
        val files = d.listFiles().orEmpty().filter { it.isFile }
        fun sizeOf(n: String) = files.firstOrNull { it.name == n }?.length() ?: 0L
        val known = setOf(MODEL_FILE, TOKENS_FILE, VAD_FILE)
        val stray = files.filter { it.name !in known }
            .take(4)
            .map { it.name + " " + if (it.length() >= 1024 * 1024) "${it.length() / (1024 * 1024)}MB" else "${it.length() / 1024}KB" }
        return Audit(d.absolutePath, sizeOf(MODEL_FILE), sizeOf(TOKENS_FILE), sizeOf(VAD_FILE), stray)
    }

    private fun minBytesOf(name: String): Long = when (name) {
        MODEL_FILE -> MODEL_MIN_BYTES
        TOKENS_FILE -> 1L
        else -> VAD_MIN_BYTES
    }

    /**
     * 便宜地问一句「有没有可救的东西」（只看文件名与大小，不搬任何字节）。
     *
     * 用途是决定「先自愈」还是「直接重下」：不先问就重下，会把一个只差改名的 240MB 文件
     * 再下一遍；先问一下，绝大多数残局都能在 1 秒内修好。
     */
    fun canRepair(context: Context): Boolean {
        val d = dir(context)
        if (audit(context).ready) return false
        val names = listOf(MODEL_FILE, TOKENS_FILE)
        val ext = externalDir(context)
        val unpack = File(context.cacheDir, "local-asr-unpack")
        val partHit = d.listFiles().orEmpty().any { f ->
            if (!f.isFile || !f.name.endsWith(".part")) return@any false
            val base = f.name.removeSuffix(".part")
            (base == MODEL_FILE || base == TOKENS_FILE) && f.length() >= minBytesOf(base)
        }
        val elsewhere = names.any { n ->
            val have = File(d, n).let { it.isFile && it.length() >= minBytesOf(n) }
            if (have) return@any false
            val inUnpack = File(unpack, n).let { it.isFile && it.length() >= minBytesOf(n) }
            val inExt = ext?.let { e -> File(e, n).let { it.isFile && it.length() >= minBytesOf(n) } } == true
            inUnpack || inExt
        }
        return partHit || elsewhere
    }

    /**
     * 自愈：把「已经躺在别处 / 只差最后一改名」的模型件补回模型目录。
     *
     * 覆盖三种真实见过的残局：
     *  ① 复制被中断，目录里留下体积完整的 `xxx.part` —— 直接改名即可，不用重下；
     *  ② 历史版本把模型留在了外部专属目录（旧布局），私有目录里缺件；
     *  ③ 上次解包成功但换入前被杀，完整两件还留在 `cacheDir/local-asr-unpack`。
     *
     * 只在**缺件**时才动，任何时候都不会覆盖一个已就绪的模型；会搬大文件，请放在后台线程调用。
     */
    fun repair(context: Context): Boolean {
        val d = dir(context)
        if (!d.isDirectory) d.mkdirs()
        if (audit(context).ready) return true
        var changed = false

        // ① 就地修 .part（同分区改名，毫秒级）
        d.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".part") }.forEach { part ->
            val base = part.name.removeSuffix(".part")
            if (base == MODEL_FILE || base == TOKENS_FILE || base == VAD_FILE) {
                if (part.length() >= minBytesOf(base)) {
                    val dst = File(d, base)
                    dst.delete()
                    if (part.renameTo(dst)) {
                        changed = true
                        Diagnostics.i("LocalAsr", "自愈：$base.part 改名为 $base")
                    }
                } else {
                    runCatching { part.delete() }
                }
            }
        }

        // ②③ 从外部历史目录 / 缓存解包目录补齐
        val ext = externalDir(context)
        val unpack = File(context.cacheDir, "local-asr-unpack")
        for (name in listOf(MODEL_FILE, TOKENS_FILE, VAD_FILE)) {
            val target = File(d, name)
            if (target.isFile && target.length() >= minBytesOf(name)) continue
            for (src in listOfNotNull(File(unpack, name), ext?.let { File(it, name) })) {
                if (src.absolutePath == target.absolutePath) continue
                if (!src.isFile || src.length() < minBytesOf(name)) continue
                if (copyInto(src, target)) {
                    changed = true
                    Diagnostics.i("LocalAsr", "自愈：从 ${src.parentFile?.absolutePath} 补入 $name")
                    break
                }
            }
        }
        if (changed) Diagnostics.i("LocalAsr", "自愈后 " + audit(context).line())
        return audit(context).ready
    }

    /** 复制成 .part 再改名（跨分区也安全），失败不留件 */
    private fun copyInto(src: File, dst: File): Boolean = runCatching {
        val tmp = File(dst.parentFile, dst.name + ".part")
        tmp.delete()
        src.inputStream().use { input -> tmp.outputStream().use { output -> input.copyTo(output, 1 shl 20) } }
        if (tmp.length() != src.length()) {
            tmp.delete()
            false
        } else {
            dst.delete()
            if (tmp.renameTo(dst)) true else { tmp.delete(); false }
        }
    }.getOrDefault(false)

    fun modelFile(context: Context): File = File(dir(context), MODEL_FILE)

    fun tokensFile(context: Context): File = File(dir(context), TOKENS_FILE)

    fun vadFile(context: Context): File = File(dir(context), VAD_FILE)

    /** VAD 是可选件：缺了降级为「整段解码」，所以不参与 [isReady] 判定 */
    fun hasVad(context: Context): Boolean = vadFile(context).let { it.isFile && it.length() > VAD_MIN_BYTES }

    /** 两件文件都在才算就绪（只看存在性，完整性由下载端的 sha256 / 大小校验保证） */
    fun isReady(context: Context): Boolean =
        modelFile(context).isFile && modelFile(context).length() > MODEL_MIN_BYTES &&
            tokensFile(context).isFile && tokensFile(context).length() > 0

    /** 已占用的字节数（设置页「存储占用」与删除入口用） */
    fun usedBytes(context: Context): Long = dir(context).listFiles()?.sumOf { it.length() } ?: 0L

    /**
     * 归档完整性校验：大小落在官方字节数的 95% 以上，且 sha256 完全一致才放行。
     *
     * 只大小的约定挡不住「大小碰巧接近的别的东西」，只 sha256 又要对着一个被截断的
     * 文件读 163 MB；先看大小能省掉绝大多数无效源的摘要计算。
     */
    fun verifyArchive(file: File): Boolean {
        val size = file.length()
        if (size < ARCHIVE_MIN_BYTES) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        val hex = buildString { digest.digest().forEach { append("%02x".format(it)) } }
        return hex.equals(ARCHIVE_SHA256, ignoreCase = true)
    }

    /**
     * 状态条上直接给用户看的一句话：就绪返回 null；缺文件返回「缺什么 + 该怎么办」。
     * 与 [BufferedBatchAsrClient.prepare] 的约定一致（null = 可用）。
     */
    fun problem(context: Context): String? {
        if (!modelFile(context).isFile) return "离线模型未就绪：缺少 model.int8.onnx"
        if (!tokensFile(context).isFile) return "离线模型未就绪：缺少 tokens.txt"
        return null
    }
}
