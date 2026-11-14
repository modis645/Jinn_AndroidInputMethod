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

    /** 归档里两个成员的文件名（tar 里带目录前缀，按文件名后缀匹配） */
    private const val MEMBER_MODEL = "/" + LocalAsrModel.MODEL_FILE
    private const val MEMBER_TOKENS = "/" + LocalAsrModel.TOKENS_FILE

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

                // ── 1. 下载 ──
                val request = Request.Builder().url(LocalAsrModel.ARCHIVE_URL).build()
                val executing = http.newCall(request)
                call = executing
                executing.execute().use { response ->
                    if (!response.isSuccessful) {
                        reason = "下载失败：HTTP ${response.code}"
                        return@use
                    }
                    val body = response.body ?: run {
                        reason = "下载失败：响应为空"
                        return@use
                    }
                    val total = body.contentLength()
                    var read = 0L
                    body.byteStream().use { input ->
                        archive.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                if (cancelled) {
                                    reason = "已取消"
                                    return@use
                                }
                                val n = input.read(buf)
                                if (n <= 0) break
                                output.write(buf, 0, n)
                                read += n
                                if (total > 0) {
                                    val pct = (read * PROGRESS_DOWNLOAD_DONE / total).toInt()
                                    onProgress(pct.coerceIn(0, PROGRESS_DOWNLOAD_DONE))
                                }
                            }
                        }
                    }
                    if (read <= 0L) reason = "下载失败：没有收到数据"
                }
                call = null
                if (reason != null) {
                    archive.delete()
                    onDone(false, reason)
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
                            val name = entry.name
                            val isModel = name.endsWith(MEMBER_MODEL)
                            val isTokens = name.endsWith(MEMBER_TOKENS)
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
