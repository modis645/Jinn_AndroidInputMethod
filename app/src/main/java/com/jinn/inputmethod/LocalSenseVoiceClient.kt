package com.jinn.inputmethod

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import android.content.Context
import java.io.File

/**
 * 本机离线识别（sherpa-onnx + SenseVoice small int8），不联网。
 *
 * 复用 [BufferedBatchAsrClient] 的整段缓冲 / 状态机 / 收尾 / 取消语义，所以 IME 侧
 * 的状态条、录音与落字链路一行都不用改；这里只做三件事：
 *  - [prepare]：模型文件是否就位（缺件当场写状态条，不要等用户白说一段）；
 *  - 录音期间：**边录边用 silero VAD 切句、每切出一句当场解码并记进 [VoiceStreamCommit]**
 *    （预览文字就是这份台账），见 [startStreamLoop]；
 *  - [recognize]：结束时只解**最后那句残留**，把台账拼起来上屏。
 *
 * ── 为什么长语音要这么改（2026-11-17 真机日志 + 云主机离线复核）──
 *  1. 真机实测 RTF≈0.30（8 秒尾巴 2400ms）⇒ 110 秒音频的整段解码总账 ≈33 秒。
 *     **分块只是分摊，不减少总账**，所以切块再细也解决不了「结束等 50 秒」。
 *  2. 在云主机上用真模型（SenseVoice int8 + silero）跑同一份 105 秒夹具，两种解法文本一致：
 *     整段解 2429ms / 流式（边录边解）结束时只剩 141ms 的尾巴。
 *     有停顿的夹具：17 句 238 字，两版逐字相同；无停顿的夹具：18 句，差异只在
 *     「九点/9点」这类模型随机性，**没有丢句**。
 *  3. 所以唯一的结构性解法是：把解码成本摊到录音过程里，结束时只补残留。
 *
 * ── 为什么不担心准确率（这是 v96 栽过的坑）──
 *  v96 用的是**能量切句**，切点偏一点就把半句话剖开，掉字串字。这里切句用的是
 *  **和整段兜底完全同一套 silero 参数**（threshold 0.5 / minSilence 0.5 / minSpeech 0.25 /
 *  maxSpeech 28 / window 512），段首还带 [VoiceDsp.onsetBacktrack] 回补；
 *  并且保留「覆盖不足一半就退回整段解码」的兜底 —— 宁可慢，不许丢话。
 *
 * 识别器是**进程级单例**：SenseVoice 的模型加载（几百 MB 内存）比解码本身贵得多，
 * 每次松手都新建会让语音面板每次说话都卡两秒。首次解码时才建，[idleClose] 之外不主动释放。
 *
 * 线程：`recognize` 跑在基类的上传线程池上，流式循环跑在自己的线程；sherpa 的
 * recognizer / stream / VAD 都不是线程安全，全部用 [lock] 串起来。
 */
internal class LocalSenseVoiceClient(
    private val prefs: Prefs,
    onState: (LinkState, String?) -> Unit,
    onResult: (RecognitionMessage) -> Unit,
) : BufferedBatchAsrClient(onState, onResult) {

    override val tag: String = "LocalAsr"

    override fun prepare(): String? {
        // 兜一次：IME 起进程时已释放，这里只做「在不在」的便宜检查
        LocalAsrModel.ensureBundled(prefs.context)
        val problem = LocalAsrModel.problem(prefs.context)
        // 分阶段打点：模型在不在、够不够大、VAD 在不在 —— 这三步决定「点了语音为什么没反应」
        val model = LocalAsrModel.modelFile(prefs.context)
        if (model.isFile) VoiceTrace.mark("model present", "bytes=${model.length()}")
        if (model.isFile && model.length() > LocalAsrModel.MODEL_MIN_BYTES) VoiceTrace.mark("size ok")
        if (LocalAsrModel.hasVad(prefs.context)) VoiceTrace.mark("vad ok")
        return problem
    }

    override fun recognize(wav: ByteArray, pcm16: ByteArray): BatchOutcome {
        val model = LocalAsrModel.modelFile(prefs.context)
        val tokens = LocalAsrModel.tokensFile(prefs.context)
        if (!model.isFile || !tokens.isFile) return BatchOutcome.Fail("离线模型未就绪")

        val started = System.currentTimeMillis()
        val totalSamples = pcm16.size / 2
        val gateMs = if (stoppedAtMs > 0) started - stoppedAtMs else -1L
        Diagnostics.i(tag, "结束→解码 间隔=${gateMs}ms 音频=${totalSamples / SAMPLE_RATE}s")

        // 1) 停掉流式线程，回收它没走完的最后一拍
        stopStreamLoop()
        runCatching { streamThread?.join(STREAM_JOIN_MS) }
        streamThread = null

        // 2) 把剩下的音频喂给 VAD，flush 掉最后一句，再把残留解掉
        val closeStart = System.currentTimeMillis()
        var streamed = ""
        val vadPath = LocalAsrModel.vadFile(prefs.context)
        if (vadPath.isFile && totalSamples >= MIN_VAD_SAMPLES) {
            runCatching {
                syncSamples(totalSamples)
                feedVad(totalSamples)
                synchronized(lock) { vadSession?.flush() }
                drainSegments(totalSamples)
                decodeRemainder(totalSamples)
            }.onFailure {
                Diagnostics.w(tag, "流式收尾异常 ${it.javaClass.simpleName}: ${it.message}")
            }
            // 覆盖不足 / 一句没解出来 ⇒ 不信流式结果，退回整段解码（宁可慢，不许丢话）
            if (!commit.needsFallback(totalSamples)) streamed = commit.text()
        }
        val closeCost = System.currentTimeMillis() - closeStart

        // 3) 兜底：整段解码（VAD 不可用 / 流式不可信 / 短句直接走这里）
        val text = if (streamed.isNotEmpty()) {
            streamed
        } else {
            runCatching { decodePcm16(pcm16, withVad = true) }.getOrElse { t ->
                Diagnostics.w(tag, "离线解码异常 ${t.javaClass.simpleName}: ${t.message}")
                releaseVad()
                return BatchOutcome.Fail("离线解码失败")
            }
        }
        Diagnostics.i(
            tag,
            "离线解码完成 音频=${totalSamples / SAMPLE_RATE.toFloat()}s " +
                "总耗时=${System.currentTimeMillis() - started}ms 结束补解=${closeCost}ms " +
                "流式句=${commit.pieceCount} 覆盖=${(commit.coverage(totalSamples) * 100).toInt()}% " +
                "兜底=${streamed.isEmpty()} 字数=${text.length}",
        )
        releaseVad()
        commit.reset()
        return BatchOutcome.Ok(finishText(text))
    }

    // ── 录音期间：边收边切句边解码 ────────────────────────────────────

    private var streamThread: Thread? = null

    @Volatile
    private var streamStop = true

    /** 结束时刻（日志用：结束→解码 的间隔） */
    private var stoppedAtMs = 0L

    /** 上一次预览解尾巴的时刻 */
    private var lastPreviewDecodeAt = 0L

    /** 上一次预览解尾巴的耗时（自适应节拍） */
    @Volatile
    private var lastPreviewCostMs = 0L

    /** 录音期间切句用的 VAD（一次任务一个，结束后释放） */
    private var vadSession: Vad? = null

    /** 已经喂给 VAD 的采样数（VAD 的 segment.start 是相对它自己的起点，双方要对齐） */
    private var vadFedSamples = 0

    /** 已解码句子的台账 */
    private val commit = VoiceStreamCommit()

    /** PCM 转好的 float 全量缓存：切句解码要按采样下标切片，每拍重转整段太浪费 */
    private var samplesBuf = FloatArray(0)
    private var convertedSamples = 0

    override fun beginTask(): String? {
        val id = super.beginTask() ?: return null
        stoppedAtMs = 0L
        lastPreviewDecodeAt = 0L
        lastPreviewCostMs = 0L
        commit.reset()
        samplesBuf = FloatArray(0)
        convertedSamples = 0
        openVad()
        startStreamLoop(id)
        return id
    }

    override fun endTask(): Boolean {
        stopStreamLoop()
        stoppedAtMs = System.currentTimeMillis()
        return super.endTask()
    }

    override fun cancelTask(): Boolean {
        stopStreamLoop()
        releaseVad()
        commit.reset()
        return super.cancelTask()
    }

    private fun openVad() {
        releaseVad()
        val vadPath = LocalAsrModel.vadFile(prefs.context)
        if (!vadPath.isFile) return
        vadSession = runCatching { newVad(vadPath) }.onFailure {
            Diagnostics.w(tag, "VAD 打开失败 ${it.javaClass.simpleName}: ${it.message}")
        }.getOrNull()
        vadFedSamples = 0
    }

    private fun releaseVad() {
        val vad = vadSession ?: return
        vadSession = null
        vadFedSamples = 0
        runCatching { vad.release() }
    }

    private fun stopStreamLoop() {
        streamStop = true
    }

    private fun startStreamLoop(taskId: String) {
        streamStop = false
        val thread = Thread {
            while (!streamStop) {
                try {
                    Thread.sleep(STREAM_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    return@Thread
                }
                if (streamStop || !isCapturing()) return@Thread
                val total = pcmByteCount() / 2
                if (total < MIN_FRAME_SAMPLES) continue
                try {
                    syncSamples(total)
                    feedVad(total)
                    drainSegments(total)
                    emitPreview(taskId, total)
                } catch (e: Throwable) {
                    Diagnostics.w(tag, "流式拍异常 ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }.apply {
            name = "local-asr-stream"
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
        streamThread = thread
        thread.start()
    }

    /** 把新增的 PCM16 转成 float 追加进 [samplesBuf]（只转新增部分） */
    private fun syncSamples(totalSamples: Int) {
        if (totalSamples <= convertedSamples) return
        if (samplesBuf.size < totalSamples) {
            val capacity = maxOf(totalSamples, samplesBuf.size * 2, SAMPLE_RATE * 30)
            samplesBuf = samplesBuf.copyOf(capacity)
        }
        val pcm = snapshotPcm16(convertedSamples * 2)
        val floats = pcm16ToFloat(pcm)
        System.arraycopy(floats, 0, samplesBuf, convertedSamples, floats.size)
        convertedSamples += floats.size
    }

    /**
     * 按 `window*5` 整块喂给 VAD。
     *
     * 教训（2026-11-17 真机日志）：一次把 100 多秒交给 silero，它只处理开头一小截
     * （「音频=106.68s 句数=1 覆盖=1%」），分句与句间标点全丢。所以必须切小口喂。
     */
    private fun feedVad(totalSamples: Int) {
        val vad = vadSession ?: return
        val step = VAD_WINDOW_SAMPLES * 5
        while (totalSamples - vadFedSamples >= step) {
            val to = vadFedSamples + step
            synchronized(lock) { vad.acceptWaveform(samplesBuf.copyOfRange(vadFedSamples, to)) }
            vadFedSamples = to
        }
    }

    /**
     * 把 VAD 里已经闭合的句子取出来解掉。
     *
     * 段首照 [VoiceDsp.onsetBacktrack] 往前回补（「丢句首 1~4 字」的老坑），
     * 且绝不与上一段重叠（重叠会把同一句话上屏两遍）。
     */
    private fun drainSegments(totalSamples: Int) {
        val vad = vadSession ?: return
        while (true) {
            val closed = synchronized(lock) {
                if (vad.empty()) null else vad.front().let { Pair(it.start, it.samples) }
            } ?: return
            val (rawStart, segSamples) = closed
            val segStart = rawStart.coerceIn(0, totalSamples)
            val segEnd = (segStart + segSamples.size).coerceAtMost(totalSamples)
            // 已经被上一段吃掉的部分不再解（否则同一句话会上屏两遍）
            if (segEnd <= commit.consumedSamples) {
                synchronized(lock) { vad.pop() }
                continue
            }
            // 太短的片段（<0.2s）多半是噪声，交给模型只会得到一两个错字
            if (segSamples.size >= SAMPLE_RATE / 5 && segEnd > segStart) {
                val frames = VoiceDsp.frameEnergies(samplesBuf, totalSamples)
                val speechRms = speechRmsOf(frames)
                val pad = VoiceDsp.onsetBacktrack(frames, speechRms, segStart, commit.consumedSamples)
                val from = if (pad in (commit.consumedSamples + 1) until segStart) pad else segStart
                val slice = samplesBuf.copyOfRange(from, segEnd)
                val piece = decodePiece(slice)
                commit.add(piece, segEnd - from)
                if (piece.isNotEmpty()) VoiceTrace.mark("stream sentence", "chars=${piece.length}")
            } else {
                commit.consume(segEnd - commit.consumedSamples)
            }
            synchronized(lock) { vad.pop() }
        }
    }

    /** 结束时把最后那句残留解掉（≤10 秒一块，绝不整段单流解码） */
    private fun decodeRemainder(totalSamples: Int) {
        val from = commit.consumedSamples.coerceIn(0, totalSamples)
        val remain = totalSamples - from
        if (remain <= MIN_TAIL_SAMPLES) return
        val slice = samplesBuf.copyOfRange(from, totalSamples)
        val piece = decodeSamples(slice, withVad = false)
        commit.add(piece, remain)
        Diagnostics.i(
            tag,
            "残留补解 音频=${remain / SAMPLE_RATE.toFloat()}s 字数=${piece.length}",
        )
    }

    /**
     * 「边说边出」：台账里已经解完的句子 + 正在说的这一小段尾巴。
     *
     * 尾巴只取最后 [PREVIEW_TAIL_SAMPLES]（6 秒），节流 [PREVIEW_MIN_INTERVAL_MS] 且按
     * 「上次耗时 ×2」自适应 —— 真机 RTF 0.30，固定 1.2 秒一拍会让预览线程长期占满 CPU，
     * 结束时的收尾反而抢不到核。
     */
    private fun emitPreview(taskId: String, totalSamples: Int) {
        val now = System.currentTimeMillis()
        val intervalMs = maxOf(PREVIEW_MIN_INTERVAL_MS, lastPreviewCostMs * 2)
        if (now - lastPreviewDecodeAt < intervalMs) return
        lastPreviewDecodeAt = now

        val from = maxOf(commit.consumedSamples, totalSamples - PREVIEW_TAIL_SAMPLES)
        if (totalSamples - from < MIN_TAIL_SAMPLES) {
            // 尾巴太短，就只展示台账里已经解完的句子
            if (commit.pieceCount > 0) emitPartial(taskId, commit.previewText(), totalSamples / 2.0 / SAMPLE_RATE)
            return
        }
        val t0 = System.currentTimeMillis()
        val tail = runCatching { decodeSamples(samplesBuf.copyOfRange(from, totalSamples), withVad = false) }
            .getOrNull().orEmpty().trim()
        lastPreviewCostMs = System.currentTimeMillis() - t0
        val tailSeconds = (totalSamples - from) / SAMPLE_RATE.toFloat()
        Diagnostics.i(
            tag,
            "预览 音频=${totalSamples / SAMPLE_RATE}s 尾巴=${tailSeconds}s " +
                "耗时=${lastPreviewCostMs}ms 台账句=${commit.pieceCount} 尾巴字=${tail.length}",
        )
        val text = commit.previewText() + tail
        if (text.isNotEmpty()) emitPartial(taskId, text, totalSamples / 2.0 / SAMPLE_RATE)
    }

    /** 解一小段（切出来的句子 / 残留 / 预览尾巴），串行化在 [lock] 里 */
    private fun decodePiece(slice: FloatArray): String {
        if (slice.isEmpty()) return ""
        val recognizer = recognizerFor(prefs.context)
        return synchronized(lock) {
            runCatching { runChunk(recognizer, slice) }.getOrNull().orEmpty()
        }.trim()
    }

    // ── 整段解码（兜底 & 短句）──────────────────────────────────────

    /** PCM16 → 文本；整段解码（流式不可信 / VAD 不可用 / 短句时的兜底） */
    private fun decodePcm16(pcm16: ByteArray, withVad: Boolean): String {
        VoiceTrace.mark("first frame", "audio=${pcm16.size / 2 / SAMPLE_RATE.toFloat()}s vad=$withVad")
        return decodeSamples(pcm16ToFloat(pcm16), withVad)
    }

    private fun decodeSamples(samples: FloatArray, withVad: Boolean): String {
        if (samples.isEmpty()) return ""
        val recognizer = recognizerFor(prefs.context)
        val vadPath = LocalAsrModel.vadFile(prefs.context)
        val segmented = if (withVad && vadPath.isFile && samples.size >= MIN_VAD_SAMPLES) {
            splitByVad(recognizer, samples, vadPath)
        } else {
            null
        }
        if (segmented != null) return segmented
        return decodeChunked(recognizer, samples)
    }

    /**
     * **任何一次 decode 都不超过 10 秒**。
     *
     * 真机日志反证过：同样一条识别器，8 秒尾巴 2400ms，106.68 秒单流 92 527ms。
     * 超长单流解码在手机上会崩，所以长音频先按 [VoiceChunker] 切块（尽量切在静音处），
     * 逐块解完再按句间逗号拼接。
     */
    private fun decodeChunked(recognizer: OfflineRecognizer, samples: FloatArray): String {
        val frameRms = VoiceDsp.frameEnergies(samples)
        val speechRms = speechRmsOf(frameRms)
        val chunks = VoiceChunker.split(samples, frameRms, speechRms)
        val pieces = ArrayList<String>(chunks.size)
        for (chunk in chunks) {
            val piece = synchronized(lock) {
                runCatching { runChunk(recognizer, samples.copyOfRange(chunk.first, chunk.last + 1)) }
                    .getOrNull().orEmpty()
            }
            if (piece.isNotEmpty()) pieces.add(piece)
        }
        Diagnostics.i(
            tag,
            "分块解码: 音频=${samples.size / SAMPLE_RATE.toFloat()}s 块=${chunks.size} 出句=${pieces.size}",
        )
        return if (pieces.isEmpty()) "" else VoiceText.joinSentences(pieces)
    }

    /** 单条流一次解码（调用方负责持锁） */
    private fun runChunk(recognizer: OfflineRecognizer, samples: FloatArray): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    /**
     * silero VAD 分句 + 逐句解码（整段兜底路径）。返回拼好的整段文本；切不出句子
     * 或覆盖不足一半时返回 null，调用方退回整段解码。
     */
    private fun splitByVad(recognizer: OfflineRecognizer, samples: FloatArray, vadPath: File): String? =
        runCatching {
            val vadStart = System.currentTimeMillis()
            val vad = newVad(vadPath)
            val sentences = ArrayList<String>()
            var pieces = 0
            var coveredSamples = 0L
            val frameRms = VoiceDsp.frameEnergies(samples)
            val speechRms = speechRmsOf(frameRms)
            var prevEnd = 0
            synchronized(lock) {
                try {
                    // ⚠ 必须按小窗口分段喂（一次性喂 100 多秒时 silero 只处理开头一小截）
                    val chunkSamples = VAD_WINDOW_SAMPLES * 5
                    var off = 0
                    while (off < samples.size) {
                        val n = minOf(chunkSamples, samples.size - off)
                        vad.acceptWaveform(samples.copyOfRange(off, off + n))
                        off += n
                    }
                    vad.flush()
                    while (!vad.empty()) {
                        val segment = vad.front()
                        if (segment.samples.size >= SAMPLE_RATE / 5) {
                            val segStart = segment.start.coerceIn(0, samples.size)
                            val segEnd = (segStart + segment.samples.size).coerceAtMost(samples.size)
                            val padStart = VoiceDsp.onsetBacktrack(frameRms, speechRms, segStart, prevEnd)
                            val slice = if (padStart in 1 until segStart && segEnd > padStart) {
                                samples.copyOfRange(padStart, segEnd)
                            } else {
                                segment.samples
                            }
                            coveredSamples += kotlin.math.min(slice.size.toLong(), samples.size.toLong())
                            prevEnd = maxOf(prevEnd, segEnd)
                            val piece = runChunk(recognizer, slice)
                            if (piece.isNotEmpty()) {
                                if (sentences.isEmpty()) VoiceTrace.mark("first sentence", "chars=${piece.length}")
                                sentences.add(piece)
                            }
                            pieces++
                        } else {
                            prevEnd = maxOf(prevEnd, (segment.start + segment.samples.size).coerceAtMost(samples.size))
                        }
                        vad.pop()
                    }
                } finally {
                    vad.release()
                }
            }
            val coverage = if (samples.isEmpty()) 0f else coveredSamples / samples.size.toFloat()
            Diagnostics.i(
                tag,
                "VAD 分句: 音频=${samples.size / SAMPLE_RATE.toFloat()}s 句数=$pieces " +
                    "覆盖=${(coverage * 100).toInt()}% 耗时=${System.currentTimeMillis() - vadStart}ms",
            )
            if (coveredSamples < samples.size / 2L) null
            else if (sentences.isEmpty()) null
            else VoiceText.joinSentences(sentences)
        }.getOrNull()

    private fun finishText(raw: String): String = VoiceText.finishText(raw)

    /** 噪声底（第 10 百分位）× +12dB 作为「语音帧」门限 */
    private fun speechRmsOf(frames: FloatArray): Float {
        if (frames.isEmpty()) return 0f
        val sorted = frames.sortedArray()
        return maxOf(sorted[(sorted.size - 1) / 10], 1e-5f) * 3.981f
    }

    /** 录音期间切句用的 VAD：参数与整段兜底**逐项相同**，切点才不会两套口径 */
    private fun newVad(vadPath: File): Vad = Vad(
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = vadPath.absolutePath,
                threshold = 0.5f,
                minSilenceDuration = 0.5f,
                minSpeechDuration = 0.25f,
                windowSize = 512,
                maxSpeechDuration = 28.0f,
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
            provider = "cpu",
            debug = false,
        ),
    )

    /** PCM16（小端）→ float32 归一化到 [-1,1]；逐采样 1/32768 换算，不做重采样（采集端已是 16k） */
    private fun pcm16ToFloat(pcm16: ByteArray): FloatArray {
        val frames = pcm16.size / 2
        if (frames <= 0) return FloatArray(0)
        val out = FloatArray(frames)
        var i = 0
        var j = 0
        while (i + 1 < pcm16.size) {
            val lo = pcm16[i].toInt() and 0xFF
            val hi = pcm16[i + 1].toInt()
            val s = (hi shl 8) or lo
            out[j] = s / 32768.0f
            i += 2
            j++
        }
        return out
    }

    // 需求：IME 启动时要调 warmup / recognizerFor，所以 companion 不能是 private
    internal companion object {
        const val SAMPLE_RATE = 16_000

        /** silero 的窗口（采样）：`windowSize = 512`，喂 VAD 按它的整数倍分块 */
        const val VAD_WINDOW_SAMPLES = 512

        /** 低于这个长度（约 4 秒）不值得走 VAD */
        const val MIN_VAD_SAMPLES = 4 * SAMPLE_RATE

        /** 流式循环节拍：0.7 秒一拍（切句很便宜，真正贵的是解码） */
        const val STREAM_INTERVAL_MS = 700L

        /** 结束时要等流式线程收拾完的上限 */
        const val STREAM_JOIN_MS = 2_000L

        /** 少于 0.25 秒的尾巴不值得解 */
        const val MIN_TAIL_SAMPLES = SAMPLE_RATE / 4

        /** 太短的缓冲不值得开一拍 */
        const val MIN_FRAME_SAMPLES = SAMPLE_RATE / 2

        /** 预览节拍下限 */
        const val PREVIEW_MIN_INTERVAL_MS = 1_200L

        /** 预览尾巴长度：6 秒（RTF 0.30 下约 1.8 秒） */
        const val PREVIEW_TAIL_SAMPLES = 6 * SAMPLE_RATE

        /** 解码串行化（recognizer / stream / VAD 都非线程安全） */
        val lock = Any()

        /**
         * 取（或懒加载）**进程级**识别器；模型文件被重新下载（lastModified 变了）就重建。
         */
        fun recognizerFor(context: Context): OfflineRecognizer {
            val model = LocalAsrModel.modelFile(context)
            val tokens = LocalAsrModel.tokensFile(context)
            synchronized(lock) {
                val cached = cachedRecognizer
                if (cached != null && cachedModelPath == model.absolutePath &&
                    cachedModelStamp == model.lastModified()
                ) {
                    return cached
                }
                cachedRecognizer?.release()
                val config = OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        senseVoice = OfflineSenseVoiceModelConfig(
                            model = model.absolutePath,
                            language = "auto",
                            useInverseTextNormalization = true,
                        ),
                        tokens = tokens.absolutePath,
                        numThreads = 4,
                        debug = false,
                        provider = "cpu",
                    ),
                )
                return OfflineRecognizer(config = config).also {
                    cachedRecognizer = it
                    cachedModelPath = model.absolutePath
                    cachedModelStamp = model.lastModified()
                }
            }
        }

        /** 冷启动预热：把识别器在后台先建好，第一次说话不用等加载 */
        fun warmup(context: Context) {
            if (!LocalAsrModel.isReady(context)) return
            val started = System.currentTimeMillis()
            runCatching { recognizerFor(context) }
                .onSuccess {
                    Diagnostics.i("LocalAsr", "预热完成 耗时=${System.currentTimeMillis() - started}ms")
                }
                .onFailure {
                    Diagnostics.w("LocalAsr", "预热失败 ${it.javaClass.simpleName}: ${it.message}")
                }
        }

        var cachedRecognizer: OfflineRecognizer? = null
        var cachedModelPath: String = ""
        var cachedModelStamp: Long = 0L
    }
}
