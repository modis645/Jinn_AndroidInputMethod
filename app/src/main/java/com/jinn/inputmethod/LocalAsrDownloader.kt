package com.jinn.inputmethod

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.util.concurrent.TimeUnit

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
 *  - **可取消**：`cancel()` 打断在飞请求并置标志，解包循环每 8KB 检查一次，尽快收手。
 *  - **可删**：`delete()` 整个目录删掉，设置页会同步显示占用。
 */
internal object LocalAsrDownloader {

    /** 下载进度 0..100；解包阶段从 [PROGRESS_DOWNLOAD_DONE] 走到 100 */
    const val PROGRESS_DOWNLOAD_DONE = 90

    @Volatile
    private var call: okhttp3.Call? = null

    @Volatile
    private var cancelled = false

    /** 归档里两个成员的文件名（见上：按文件名匹配，路径前缀不作要求） */

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    fun isRunning(): Boolean = call != null

    /** 取消在途任务（幂等） */
    fun cancel() {
        cancelled = true
        runCatching { call?.cancel() }
        call = null
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
     * 可选件：silero VAD 模型（约 2 MB 直链）。进度占 92..99 这一段。
     *
     * 失败一律吞掉：SenseVoice 本体已经就位时，语音功能照常可用，只是长句回到
     * 「整段解码」的老路径 —— 绝不能因为一个 2 MB 的可选件把整次下载判成失败，
     * 那会让用户重新下一遍 163 MB。
     */
    private fun downloadVad(context: Context, onProgress: (Int) -> Unit): Boolean {
        val tmp = File(context.cacheDir, "vad-download.onnx")
        for (url in LocalAsrModel.VAD_URLS) {
            val ok = downloadVadFrom(context, url, tmp, onProgress)
            if (ok) return true
        }
        return false
    }

    /** 从单个地址拉 VAD；代理优先的顺序由 [LocalAsrModel.VAD_URLS] 决定 */
    private fun downloadVadFrom(context: Context, url: String, tmp: File, onProgress: (Int) -> Unit): Boolean {
        var read = 0L
        return try {
            val executing = http.newCall(Request.Builder().url(url).build())
            call = executing
            executing.execute().use { response ->
                if (!response.isSuccessful) return false
                val body = response.body ?: return false
                val total = body.contentLength()
                body.byteStream().use { input ->
                    tmp.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (cancelled) return false
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            read += n
                            if (total > 0) onProgress(92 + (read * 7 / total).toInt())
                        }
                    }
                }
            }
            if (read <= 0L) return false
            tmp.renameTo(LocalAsrModel.vadFile(context))
        } catch (t: Throwable) {
            Diagnostics.i("LocalAsr", "VAD 下载失败（不影响识别）: ${t.message}")
            false
        } finally {
            call = null
            tmp.delete()
        }
    }

    /**
     * 下载 + 解包。回调都在**调用线程**（后台线程）上触发，界面侧自己切主线程。
     *
     * @param onProgress 0..100
     * @param onDone 成功 = (true, null)；失败 = (false, 给用户看的一句话)
     */
    fun download(context: Context, onProgress: (Int) -> Unit, onDone: (Boolean, String?) -> Unit) {
        if (isRunning()) {
            onDone(false, "已有一个下载任务在跑")
            return
        }
        cancelled = false
        Thread({
            val archive = File(context.cacheDir, LocalAsrModel.ARCHIVE_NAME)
            val tmpDir = File(context.cacheDir, "local-asr-unpack")
            var ok = false
            var reason: String? = null
            try {
                archive.delete()
                tmpDir.deleteRecursively()
                tmpDir.mkdirs()

                // ── 1. 下载（按镜像清单逐个试，第一个成功的留下）──
                var lastError: String? = null
                for ((index, url) in LocalAsrModel.ARCHIVE_URLS.withIndex()) {
                    if (cancelled) {
                        reason = "已取消"
                        break
                    }
                    archive.delete()
                    if (index > 0) onProgress(0)
                    val request = Request.Builder().url(url).build()
                    val executing = http.newCall(request)
                    call = executing
                    var got = 0L
                    try {
                        executing.execute().use { response ->
                            if (!response.isSuccessful) {
                                lastError = "HTTP ${response.code}"
                                return@use
                            }
                            val body = response.body ?: run {
                                lastError = "响应为空"
                                return@use
                            }
                            val total = body.contentLength()
                            body.byteStream().use { input ->
                                archive.outputStream().use { output ->
                                    val buf = ByteArray(64 * 1024)
                                    while (true) {
                                        if (cancelled) {
                                            lastError = "已取消"
                                            return@use
                                        }
                                        val n = input.read(buf)
                                        if (n <= 0) break
                                        output.write(buf, 0, n)
                                        got += n
                                        if (total > 0) {
                                            val pct = (got * PROGRESS_DOWNLOAD_DONE / total).toInt()
                                            onProgress(pct.coerceIn(0, PROGRESS_DOWNLOAD_DONE))
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        lastError = e.message ?: "连接失败"
                    } finally {
                        call = null
                    }
                    if (got > 0L) {
                        lastError = null
                        break
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
                            // 只认**文件名**，不认整条路径：上游归档有的带一层目录
                            // （`sherpa-onnx-sense-voice-…/model.int8.onnx`），有的直接平铺，
                            // 按后缀匹配会在平铺时漏掉（表现为「归档里找不到模型」）。
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
                    // ── 4. VAD（可选件，约 2 MB）：失败只降级成「整段解码」，不算下载失败 ──
                    if (ok) downloadVad(context, onProgress)
                }
            } catch (t: Throwable) {
                reason = "下载中断：" + (t.message ?: t.javaClass.simpleName)
            } finally {
                call = null
                archive.delete()
                tmpDir.deleteRecursively()
            }
            onProgress(if (ok) 100 else 0)
            Diagnostics.i(
                "LocalAsr",
                "模型下载结束 ok=$ok reason=${reason ?: "-"} " +
                    "占用=${LocalAsrModel.usedBytes(context)} 字节",
            )
            onDone(ok, reason)
        }, "jinn-local-asr-download").apply { isDaemon = true }.start()
    }
}
