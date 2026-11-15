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

    /**
     * 当前进度 0..100（进程级，不分调用方）。
     *
     * 为什么要有：下载可能由 **IME 进程启动**那条路发起（不打开设置页也会装模型），
     * 而设置页只是「看见有人在跑」。以前进度只通过回调发给发起者，设置页拿不到回调，
     * 于是出现「按钮显示取消下载、进度永远是 0%」——用户看到的就是「显示在下载、其实没下载」。
     */
    @Volatile
    var lastProgress: Int = 0
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
        val all = runCatching { logFile(context).readText() }.getOrDefault("").trim()
        if (all.isEmpty()) return ""
        val list = all.split('\n')
        return if (list.size <= lines) all else list.subList(list.size - lines, list.size).joinToString("\n")
    }

    /** 取消在途任务（幂等） */
    fun cancel() {
        cancelled = true
        if (running) lastStage = "正在停止…"
        runCatching { connection?.disconnect() }
        connection = null
    }

    /**
     * 清掉**下载残留**的临时件（不是模型本身）。
     *
     * 模型落在 `filesDir/local-asr/`（应用数据，用户主动删或卸载才没），而 163 MB 的
     * 归档与解包目录只是过程中的临时件，放 `cacheDir` 才是对的 —— 系统清理缓存时可以随手收回。
     * 但进程被强杀 / 崩溃时这些临时件没人收，会白占一两百 MB，所以每次进语音设置页先扫一次。
     *
     * 有任务在跑时**一律不动**：那正是它正在写的文件。
     */
    fun sweepCache(context: Context) {
        if (isRunning()) return
        runCatching {
            val unpack = File(context.cacheDir, "local-asr-unpack")
            // 先救再删：解包目录里若已有完整的两件、而应用数据目录里没有，说明上次在
            // 「换入」之前就被杀掉了 —— 把它搬进 filesDir 能省掉一次 163 MB 重下。
            if (!LocalAsrModel.isReady(context)) {
                val m = File(unpack, LocalAsrModel.MODEL_FILE)
                val t = File(unpack, LocalAsrModel.TOKENS_FILE)
                if (m.isFile && m.length() > 0L && t.isFile && t.length() > 0L) {
                    val dir = LocalAsrModel.dir(context)
                    if (placeFile(m, dir, LocalAsrModel.MODEL_FILE) &&
                        placeFile(t, dir, LocalAsrModel.TOKENS_FILE)
                    ) {
                        Diagnostics.i("LocalAsr", "从缓存抢救出完整模型，避免重下")
                    }
                }
            }
            File(context.cacheDir, LocalAsrModel.ARCHIVE_NAME).delete()
            File(context.cacheDir, "vad-download.onnx").delete()
            unpack.deleteRecursively()
        }.onFailure { Diagnostics.w("LocalAsr", "临时件清理失败: ${it.javaClass.simpleName}") }
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

    /**
     * 把一个已下好 / 已解好的临时件放进模型目录。
     *
     * 关键点（2026-11-17 真机反馈「解包失败：无法写入模型目录」）：临时件在 `cacheDir`（内部存储），
     * 模型目录已改成**外部专属目录** `Android/data/<pkg>/files/local-asr` —— 两者不是一个分区，
     * `renameTo` 一律返回 false。所以同分区仍走改名（原子、零拷贝），跨分区退化成「先复制成 .part
     * 再在目标目录内改名」，写完核对字节数，任何一步失败都不留半截文件。
     */
    private fun placeFile(src: File, dir: File, name: String): Boolean {
        if (!dir.isDirectory && !dir.mkdirs()) {
            Diagnostics.w("LocalAsr", "模型目录创建失败: " + dir.absolutePath)
            return false
        }
        val dst = File(dir, name)
        if (src.parentFile?.absolutePath == dir.absolutePath && src.renameTo(dst)) return true
        if (runCatching { src.renameTo(dst) }.getOrDefault(false)) return true
        val part = File(dir, "$name.part")
        return runCatching {
            part.delete()
            src.inputStream().use { input ->
                part.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
            }
            if (part.length() != src.length()) {
                part.delete()
                false
            } else {
                dst.delete()
                if (part.renameTo(dst)) {
                    src.delete()
                    true
                } else {
                    part.delete()
                    false
                }
            }
        }.getOrDefault(false)
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
            placeFile(tmp, LocalAsrModel.dir(context), LocalAsrModel.VAD_FILE)
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
            // 已经有人在跑（例如 IME 启动时自己发起的那次）：不回调 onDone ——
            // 回调会让界面把「正在跑」刷成「失败」，用户看到的就是「显示取消下载、其实没下载」。
            Diagnostics.i("LocalAsr", "已有下载任务在跑，本次不重复发起")
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
            // 进度既回给发起者，也记进 lastProgress：设置页可能与发起者不是同一处
            fun report(p: Int) {
                lastProgress = p
                runCatching { onProgress(p) }
            }
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
                                        report(pct.coerceIn(0, PROGRESS_DOWNLOAD_DONE))
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
                        report(0)
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
                    // ── 3. 换入模型目录 ──
                    // 临时件在 cacheDir（内部），模型目录在外部专属目录，跨分区时 renameTo 必失败，
                    // 由 placeFile 退回复制（详见它的注释）。
                    val dir = LocalAsrModel.dir(context)
                    dir.mkdirs()
                    val modelOk = placeFile(modelOut!!, dir, LocalAsrModel.MODEL_FILE)
                    val tokensOk = modelOk && placeFile(tokensOut!!, dir, LocalAsrModel.TOKENS_FILE)
                    if (!modelOk || !tokensOk) {
                        reason = "解包失败：无法写入模型目录（" + dir.absolutePath + "）"
                    } else {
                        // 换入成功后再清掉这次没换的旧件（旧的 .part 残留等），不动刚写好的两件
                        dir.listFiles()?.forEach { f ->
                            if (f.name !in listOf(LocalAsrModel.MODEL_FILE, LocalAsrModel.TOKENS_FILE,
                                    LocalAsrModel.VAD_FILE)
                            ) runCatching { f.delete() }
                        }
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
            report(if (ok) 100 else 0)
            Diagnostics.i("LocalAsr", "模型下载结束 ok=$ok reason=${reason ?: "-"}")
            onDone(ok, reason)
        }, "jinn-local-asr-download").apply { isDaemon = true }.start()
    }
}
