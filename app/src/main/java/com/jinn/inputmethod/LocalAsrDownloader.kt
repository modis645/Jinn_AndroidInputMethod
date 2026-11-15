package com.jinn.inputmethod

import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 离线语音模型的下载与解包（SenseVoice small int8，约 163 MB 的 tar.bz2）。
 *
 * 为什么整包下载而不是逐文件：上游只发布整包（`asr-models` release），没有单文件直链；
 * 下载后在手机上就地解出 `model.int8.onnx` 与 `tokens.txt` 两件（tar.bz2 本身用完即删），
 * 所以落地占用是 230 MB 级而不是 400 MB。
 *
 * 三条纪律：
 *  - **不落半成品**：先写 `cacheDir` 下的临时档，解包也先解到临时目录，两件都齐了才用
 *    `renameTo` 换进 `filesDir/local-asr/` —— 中途失败 / 断网 / 用户取消都不会留下
 *    「看起来就绪、其实解压了一半」的模型（那会让首次说话直接崩在 native 层）。
 *  - **可取消**：`cancel()` 断开在途连接并置标志，解包循环每 8KB 检查一次，尽快收手。
 *  - **可删**：`delete()` 整个目录删掉，设置页会同步显示占用。
 *
 * 传输层为什么从 OkHttp 换成 HttpURLConnection（2026-11-15）：
 * 用户连续三次反馈「点下载长时间毫无反应」，而同一台手机上沙鸥下载很快、且已确认 GitHub
 * 与两个代理站都能正常访问 —— 说明不是网络问题，而是这条下载路径本身没跑起来。本包
 * `isMinifyEnabled = true`，R8 会裁掉只经反射或内部平台类触达的代码，OkHttp 属于高风险
 * 依赖；HttpURLConnection 是系统自带实现，不参与裁剪，在这里更稳。日志同时也改成
 * 「直接显示在卡片里」，出问题时一张截图即可定位。
 */
internal object LocalAsrDownloader {

    /** 下载进度 0..100；解包阶段从 [PROGRESS_DOWNLOAD_DONE] 走到 100 */
    const val PROGRESS_DOWNLOAD_DONE = 90

    /** 在途连接（取消时 disconnect） */
    @Volatile
    private var connection: HttpURLConnection? = null

    /**
     * 任务是否在跑。
     *
     * 以前用 `call != null` 判断，但线程起来到请求真正发出之间是空档：设置页在这期间刷新
     * 会显示「未下载 / 下载」按钮，用户点了没反应。现在开跑即置位。
     */
    @Volatile
    private var running = false

    @Volatile
    private var cancelled = false

    fun isRunning(): Boolean = running

    /** 最近一次失败原因（设置页在「未就绪」态下常态展示，不再一闪而过） */
    @Volatile
    var lastFailure: String? = null
        private set

    /** 当前阶段（卡片里那行状态用） */
    @Volatile
    var lastStage: String? = null
        private set

    /** 下载日志文件：每次下载前清空，逐条追加 */
    private fun logFile(context: Context): File = File(context.filesDir, "local-asr-download.log")

    fun log(context: Context, message: String) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        runCatching { logFile(context).appendText("$stamp $message\n") }
        Diagnostics.i("LocalAsr", message)
    }

    fun logText(context: Context): String =
        runCatching { logFile(context).readText() }.getOrDefault("").ifBlank { "（暂无日志）" }

    /** 日志尾部若干行（卡片里直接显示用） */
    fun logTail(context: Context, lines: Int = 12): String {
        val all = runCatching { logFile(context).readText() }.getOrDefault().trim()
        if (all.isEmpty()) return ""
        val list = all.split('\n')
        return if (list.size <= lines) all else list.subList(list.size - lines, list.size).joinToString("\n")
    }

    /** 取消在途任务（幂等） */
    fun cancel() {
        cancelled = true
        runCatching { connection?.disconnect() }
        connection = null
    }

    /** 删除已下载的模型（幂等）；返回是否真的删掉了东西 */
    fun delete(context: Context): Boolean {
        val dir = LocalAsrModel.dir(context)
        val had = dir.listFiles()?.isNotEmpty() == true
        dir.deleteRecursively()
        File(context.cacheDir, LocalAsrModel.ARCHIVE_NAME).delete()
        Diagnostics.i("LocalAsr", "模型删除: hadFiles=$had")
        return had
    }

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.connectTimeout = 8000
        c.readTimeout = 30000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) PhantomPinyin/1.0")
        // 不让服务端压缩：压缩过的 body 拿不到真实长度，进度条会一直不动
        c.setRequestProperty("Accept-Encoding", "identity")
        return c
    }

    /**
     * 可选件：silero VAD 模型（约 640 KB 直链）。失败一律吞掉 ——
     * 本体已就位时语音照常可用，只是长句回到整段解码的老路径。
     */
    private fun downloadVad(context: Context, onProgress: (Int) -> Unit): Boolean {
        val tmp = File(context.cacheDir, "vad-download.onnx")
        for (url in LocalAsrModel.VAD_URLS) {
            if (downloadVadFrom(context, url, tmp, onProgress)) return true
        }
        return false
    }

    private fun downloadVadFrom(context: Context, url: String, tmp: File, onProgress: (Int) -> Unit): Boolean {
        var read = 0L
        return try {
            val c = open(url)
            connection = c
            if (c.responseCode != 200) {
                log(context, "  VAD ${c.responseCode}: ${url.take(60)}")
                return false
            }
            val total = c.contentLengthLong
            c.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled) return false
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        read += n
                        if (total > 0) onProgress((92 + read * 7 / total).toInt().coerceIn(92, 99))
                    }
                }
            }
            if (read < LocalAsrModel.VAD_MIN_BYTES) return false
            tmp.renameTo(LocalAsrModel.vadFile(context))
        } catch (t: Throwable) {
            log(context, "  VAD 异常 ${t.javaClass.simpleName}: ${t.message}")
            false
        } finally {
            runCatching { connection?.disconnect() }
            connection = null
            tmp.delete()
        }
    }

    /**
     * 下载 + 解包。回调都在**后台线程**上触发，界面侧自己切主线程。
     *
     * @param onProgress 0..100
     * @param onDone 成功 = (true, null)；失败 = (false, 给用户看的一句话)
     */
    fun download(
        context: Context,
        onProgress: (Int) -> Unit,
        onDone: (Boolean, String?) -> Unit,
        onStage: ((String) -> Unit)? = null,
    ) {
        if (isRunning()) {
            onDone(false, "已有一个下载任务在跑")
            return
        }
        cancelled = false
        running = true
        lastFailure = null
        lastStage = "准备开始…"
        runCatching { logFile(context).writeText("") }
        log(context, "开始下载（源数 ${LocalAsrModel.ARCHIVE_URLS.size}，期望 ${LocalAsrModel.ARCHIVE_SIZE} 字节）")
        Thread({
            val archive = File(context.cacheDir, LocalAsrModel.ARCHIVE_NAME)
            val tmpDir = File(context.cacheDir, "local-asr-unpack")
            var ok = false
            var reason: String? = null
            try {
                archive.delete()
                tmpDir.deleteRecursively()
                tmpDir.mkdirs()

                // ── 1. 下载（按清单逐个试，第一个校验通过的留下）──
                var lastError: String? = null
                val urls = LocalAsrModel.ARCHIVE_URLS
                for ((index, url) in urls.withIndex()) {
                    val stage = "正在连接第 ${index + 1}/${urls.size} 个下载源…"
                    lastStage = stage
                    runCatching { onStage?.invoke(stage) }
                    log(context, "源 ${index + 1}/${urls.size}: $url")
                    if (cancelled) {
                        reason = "已取消"
                        break
                    }
                    archive.delete()
                    var got = 0L
                    try {
                        val c = open(url)
                        connection = c
                        val code = c.responseCode
                        val type = c.contentType
                        val len = c.contentLengthLong
                        log(context, "  HTTP $code type=$type len=$len")
                        if (code != 200) {
                            lastError = "HTTP $code"
                            c.disconnect()
                        } else if (type != null && type.contains("html", ignoreCase = true)) {
                            // 代理常回「200 + 一段 HTML 错误页」，字节看着有、其实是网页
                            lastError = "源返回的是网页而不是模型"
                            c.disconnect()
                        } else {
                            val total = if (len > 0) len else LocalAsrModel.ARCHIVE_SIZE
                            c.inputStream.use { input ->
                                archive.outputStream().use { output ->
                                    val buf = ByteArray(64 * 1024)
                                    while (true) {
                                        if (cancelled) {
                                            lastError = "已取消"
                                            break
                                        }
                                        val n = input.read(buf)
                                        if (n <= 0) break
                                        output.write(buf, 0, n)
                                        got += n
                                        val pct = (got * PROGRESS_DOWNLOAD_DONE / total).toInt()
                                        onProgress(pct.coerceIn(0, PROGRESS_DOWNLOAD_DONE))
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        lastError = e.javaClass.simpleName + ": " + (e.message ?: "")
                        log(context, "  异常 ${e.javaClass.simpleName}: ${e.message}")
                    } finally {
                        runCatching { connection?.disconnect() }
                        connection = null
                    }
                    if (got > 0L) {
                        val verifyStage = "正在校验第 ${index + 1}/${urls.size} 个源的文件…"
                        lastStage = verifyStage
                        runCatching { onStage?.invoke(verifyStage) }
                        val size = archive.length()
                        log(context, "  收到 $size 字节")
                        if (size < LocalAsrModel.ARCHIVE_MIN_BYTES) {
                            lastError = "源返回的字节数不对（$size 字节）"
                            log(context, "  判定：字节不足，换下一个源")
                        } else if (LocalAsrModel.verifyArchive(archive)) {
                            lastError = null
                            log(context, "  判定：校验通过，开始解包")
                            break
                        } else {
                            lastError = "模型文件校验不通过"
                            log(context, "  判定：sha256 不符，换下一个源")
                        }
                        archive.delete()
                        onProgress(0)
                    }
                    if (cancelled) break
                }
                if (cancelled) {
                    archive.delete()
                    onDone(false, "已取消")
                    return@Thread
                }
                if (lastError != null || !archive.exists() || archive.length() <= 0L) {
                    archive.delete()
                    onDone(false, "下载失败：${lastError ?: "没有收到数据"}（已试过 ${LocalAsrModel.ARCHIVE_URLS.size} 个下载地址）")
                    return@Thread
                }

                // ── 2. 解包（只取两件必需文件）──
                lastStage = "正在解包…"
                runCatching { onStage?.invoke("正在解包…") }
                var modelOut: File? = null
                var tokensOut: File? = null
                BZip2CompressorInputStream(archive.inputStream().buffered(), true).use { bz ->
                    TarArchiveInputStream(bz).use { tar ->
                        while (true) {
                            if (cancelled) {
                                reason = "已取消"
                                break
                            }
                            val entry = tar.nextEntry ?: break
                            if (entry.isDirectory) continue
                            // 只认**文件名**，不认整条路径：上游归档有的带一层目录，
                            // 有的直接平铺，按后缀匹配会在平铺时漏掉。
                            val base = entry.name.substringAfterLast('/')
                            val isModel = base == LocalAsrModel.MODEL_FILE || base == "model.onnx"
                            val isTokens = base == LocalAsrModel.TOKENS_FILE
                            if (!isModel && !isTokens) continue
                            val dst = File(tmpDir, if (isModel) LocalAsrModel.MODEL_FILE else LocalAsrModel.TOKENS_FILE)
                            dst.outputStream().use { out ->
                                val buf = ByteArray(8 * 1024)
                                while (true) {
                                    if (cancelled) break
                                    val n = tar.read(buf)
                                    if (n <= 0) break
                                    out.write(buf, 0, n)
                                }
                            }
                            if (isModel) modelOut = dst else tokensOut = dst
                        }
                    }
                }
                if (cancelled) {
                    reason = "已取消"
                } else if (modelOut == null || tokensOut == null) {
                    reason = "解包失败：归档里找不到 model.int8.onnx / tokens.txt"
                }

                if (reason == null) {
                    // ── 3. 原子换入 ──
                    val dir = LocalAsrModel.dir(context)
                    dir.listFiles()?.forEach { it.delete() }
                    if (!modelOut!!.renameTo(LocalAsrModel.modelFile(context)) ||
                        !tokensOut!!.renameTo(LocalAsrModel.tokensFile(context))
                    ) {
                        reason = "解包失败：无法写入模型目录"
                    } else {
                        ok = true
                    }
                    // ── 4. VAD（可选件）：失败只降级，不算下载失败 ──
                    if (ok) downloadVad(context, onProgress)
                }
            } catch (t: Throwable) {
                reason = "下载中断：" + (t.message ?: t.javaClass.simpleName)
            } finally {
                connection = null
                running = false
                archive.delete()
                tmpDir.deleteRecursively()
            }
            if (!ok) lastFailure = reason
            lastStage = null
            log(context, "结束 ok=$ok reason=${reason ?: "-"}")
            onProgress(if (ok) 100 else 0)
            Diagnostics.i("LocalAsr", "模型下载结束 ok=$ok reason=${reason ?: "-"}")
            onDone(ok, reason)
        }, "jinn-local-asr-download").apply { isDaemon = true }.start()
    }
}
