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
 * 本机离线识别（sherpa-onnx + SenseVoice small int8）：**松手后整段解码**，不联网。
 *
 * 复用 [BufferedBatchAsrClient] 的整段缓冲 / 状态机 / 收尾 / 取消语义，所以 IME 侧
 * 的状态条、录音与落字链路一行都不用改；这里只做两件事：
 *  - [prepare]：模型文件是否就位（缺件当场写状态条，不要等用户白说一段）；
 *  - [recognize]：PCM16 → float32 → 一次解码 → 文本。
 *
 * 识别器是**进程级单例**：SenseVoice 的模型加载（几百 MB 内存）比解码本身贵得多，
 * 每次松手都新建会让语音面板每次说话都卡两秒。首次解码时才建，[idleClose] 之外不主动释放；
 * 输入法进程被回收自然跟着释放。
 *
 * 线程：`recognize` 跑在基类的上传线程池上，而 sherpa 的 recognizer / stream 都不是线程安全的，
 * 所以解码这一段用 [lock] 串起来 —— 理论上一次只有一个在途任务，加锁是为了防「松手立刻再按」
 * 的竞态，代价可以忽略。
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
        if (!LocalAsrModel.modelFile(prefs.context).isFile ||
            !LocalAsrModel.tokensFile(prefs.context).isFile
        ) {
            return BatchOutcome.Fail("离线模型未就绪")
        }
        val started = System.currentTimeMillis()
        val totalSamples = pcm16.size / 2
        val cache = streamCache
        val text = runCatching {
            val cached = cache?.texts?.toList().orEmpty()
            // 方案三：录音期间已把「说完的句子」解好，这里只补最后那段尾巴
            if (cache != null &&
                VoiceTailMerge.isCacheUsable(cached.size, cache.coveredSamples, totalSamples)
            ) {
                val tailFloats = pcm16ToFloat(pcm16, cache.coveredSamples, totalSamples)
                val tailText = if (tailFloats.size >= MIN_TAIL_SAMPLES) {
                    decodeSamples(tailFloats, withVad = false)
                } else {
                    ""
                }
                Diagnostics.i(
                    tag,
                    "分段缓存命中: 句=${cached.size} 覆盖=${cache.coveredSamples / SAMPLE_RATE}s/" +
                        "${totalSamples / SAMPLE_RATE}s 尾巴=${tailFloats.size / SAMPLE_RATE}s",
                )
                VoiceTailMerge.merge(cached, tailText)
            } else {
                Diagnostics.i(
                    tag,
                    "分段缓存未命中（句=${cached.size} 覆盖=${cache?.coveredSamples ?: 0}），退回整段解码",
                )
                decodePcm16(pcm16, withVad = true)
            }
        }.getOrElse { t ->
            Diagnostics.w(tag, "离线解码异常 ${t.javaClass.simpleName}: ${t.message}")
            return BatchOutcome.Fail("离线解码失败")
        }
        Diagnostics.i(
            tag,
            "离线解码完成 音频=${totalSamples / SAMPLE_RATE.toFloat()}s " +
                "耗时=${System.currentTimeMillis() - started}ms 字数=${text.length}",
        )
        releaseStreamCache()
        return BatchOutcome.Ok(finishText(text))
    }

    // ── 分段缓存（2026-11-17「方案三」：录音期间就按 VAD 切句、逐句解好）────────
    //
    // 原先「边说边出」靠每 0.5 秒把整段缓冲重解一遍来模拟流式，音频越长越慢，结束时还要
    // 再整段重解一次。现在同一条流式 VAD 把「已经说完的句子」当场解出来存住，
    // 用户点结束只解尾巴 → 上屏等待从「总时长」降到「尾巴长度」。
    // 上屏方式**没变**：结束仍是一次性 commitText，用户仍可整体改。

    @Volatile
    private var segmentStop = true
    private var segmentThread: Thread? = null
    private var streamCache: VoiceStreamCache? = null

    override fun beginTask(): String? {
        val id = super.beginTask() ?: return null
        startSegmentLoop(id)
        return id
    }

    override fun endTask(): Boolean {
        stopSegmentLoop()
        return super.endTask()
    }

    override fun cancelTask(): Boolean {
        stopSegmentLoop()
        releaseStreamCache()
        return super.cancelTask()
    }

    private fun stopSegmentLoop() {
        segmentStop = true
        segmentThread = null
    }

    private fun releaseStreamCache() {
        streamCache?.release()
        streamCache = null
    }

    private fun startSegmentLoop(taskId: String) {
        stopSegmentLoop()
        releaseStreamCache()
        segmentStop = false
        val cache = VoiceStreamCache(
            vadPath = LocalAsrModel.vadFile(prefs.context),
            recognizer = { recognizerFor(prefs.context) },
            lock = lock,
            tag = tag,
        )
        streamCache = cache
        val thread = Thread {
            var fedBytes = 0
            while (!segmentStop) {
                try {
                    Thread.sleep(SEGMENT_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    return@Thread
                }
                if (segmentStop || !isCapturing()) return@Thread
                val total = pcmByteCount()
                if (total - fedBytes < SEGMENT_MIN_NEW_BYTES) continue
                val chunk = snapshotPcm16(fedBytes)
                fedBytes = total
                val t0 = System.currentTimeMillis()
                runCatching { cache.append(chunk, 0) }
                    .onFailure { Diagnostics.w(tag, "分段缓存异常 ${it.javaClass.simpleName}: ${it.message}") }
                emitPreview(taskId, cache, total, System.currentTimeMillis() - t0)
            }
        }.apply {
            name = "local-asr-segments"
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
        segmentThread = thread
        thread.start()
    }

    /** 「边说边出」：缓存句 + 未成句尾巴的预览，写进组合态（最终 commit 会整段覆盖） */
    private fun emitPreview(taskId: String, cache: VoiceStreamCache, totalBytes: Int, cost: Long) {
        val cached = cache.texts.toList()
        val tail = cache.tailSamples(PREVIEW_TAIL_SAMPLES)
        val tailText = if (tail.size >= MIN_TAIL_SAMPLES) {
            runCatching { decodeSamples(tail, withVad = false) }.getOrNull().orEmpty()
        } else {
            ""
        }
        val text = if (cached.isEmpty() && tailText.isBlank()) {
            ""
        } else {
            VoiceTailMerge.merge(cached, tailText).trim()
        }
        Diagnostics.i(
            tag,
            "分段缓存 句=${cached.size} 音频=${totalBytes / 2 / SAMPLE_RATE}s 本拍=${cost}ms 字数=${text.length}",
        )
        if (text.isNotEmpty()) emitPartial(taskId, text, totalBytes / 2.0 / SAMPLE_RATE)
    }

    /** PCM16 → 文本；整段解码（分段缓存不可信时的兜底） */
    private fun decodePcm16(pcm16: ByteArray, withVad: Boolean): String {
        VoiceTrace.mark("first frame", "audio=${pcm16.size / 2 / SAMPLE_RATE.toFloat()}s vad=$withVad")
        return decodeSamples(pcm16ToFloat(pcm16), withVad)
    }

    /** float32 采样 → 文本（尾巴解码 / 预览直接调用，不再来回转 PCM） */
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
        return synchronized(lock) {
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(samples, SAMPLE_RATE)
                recognizer.decode(stream)
                recognizer.getResult(stream).text
            } finally {
                stream.release()
            }
        }
    }

    /**
     * silero VAD 分句 + 逐句解码。返回拼好的整段文本；切不出句子（全是静音 / 太短）
     * 或任一步抛异常时返回 null，调用方退回整段解码。
     *
     * `maxSpeechDuration` 给到极大值：**只按静音切**，不硬切句子（硬切会把半句话剖开，
     * 反倒多丢字）。`minSpeechDuration` 0.2 秒是防咳嗽/按键声成句的下限。
     */
    private fun splitByVad(recognizer: OfflineRecognizer, samples: FloatArray, vadPath: File): String? =
        runCatching {
            val vad = Vad(
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = vadPath.absolutePath,
                        threshold = 0.5f,
                        // 与沙鸥（msime shared/voice/LocalAsr.cpp）逐项对齐：这三个值不是随手调的，
                        // 那边是大样本真机调出来的（见其 note 2026-10-28-local-asr-vad-onset）
                        minSilenceDuration = 0.5f,
                        minSpeechDuration = 0.25f,
                        windowSize = 512,
                        // 沙鸥给的 28 秒：既够长句，又不会让 sherpa 内部的预留缓冲溢出。
                        // 之前这里写 1e9f（「只按静音切、永不硬切」）—— 7~9 秒的正常说话被切成
                        // 一句、解出 1~2 个字，就是它的直接后果。
                        maxSpeechDuration = 28.0f,
                    ),
                    sampleRate = SAMPLE_RATE,
                    numThreads = 1,
                    provider = "cpu",
                    debug = false,
                ),
            )
            val sentences = ArrayList<String>()
            var pieces = 0
            var coveredSamples = 0L
            // ── 段首能量回补（用户 2026-11-16：「长录音丢句首 1~4 字」）──
            // VAD 按阈值切起点，软起音（气声、轻声、连读的首字）常被判成静音 ⇒ 整句第一个字消失。
            // 沙鸥（msime shared/voice/LocalAsr.cpp + note 2026-10-28-local-asr-vad-onset）的做法：
            // 10ms 帧 RMS、噪声底取帧能量的第 10 百分位、高出底噪 12dB 视为语音、允许 0.25s 低能量
            // 间隙、再往前最多回补 3s、且不得早于上一段段尾。这里逐项照搬。
            val frameRms = frameEnergies(samples)
            val noiseFloor = if (frameRms.isEmpty()) {
                0f
            } else {
                // 第 10 百分位；再兜一个绝对下限，避免整段静音时噪声底为 0 把一切都判成语音
                val sorted = frameRms.sortedArray()
                maxOf(sorted[(sorted.size - 1) / 10], 1e-5f)
            }
            val speechRms = noiseFloor * 3.981f // +12dB = 10^(12/20)
            var prevEnd = 0
            synchronized(lock) {
                try {
                    vad.acceptWaveform(samples)
                    vad.flush()
                    while (!vad.empty()) {
                        val segment = vad.front()
                        // 太短的片段（<0.2s）多半是噪声，交给模型只会得到一两个错字
                        if (segment.samples.size >= SAMPLE_RATE / 5) {
                            val segStart = segment.start.coerceIn(0, samples.size)
                            val segEnd = (segStart + segment.samples.size).coerceAtMost(samples.size)
                            // 段首回补：往前多带 0.3~3 秒的「疑似语音」头，宁可让模型多听一点静音
                            val padStart = onsetBacktrack(frameRms, speechRms, segStart, prevEnd)
                            val slice = if (padStart in 1 until segStart && segEnd > padStart) {
                                samples.copyOfRange(padStart, segEnd)
                            } else {
                                segment.samples
                            }
                            coveredSamples += kotlin.math.min(slice.size.toLong(), samples.size.toLong())
                            prevEnd = maxOf(prevEnd, segEnd)
                            val stream = recognizer.createStream()
                            try {
                                stream.acceptWaveform(slice, SAMPLE_RATE)
                                recognizer.decode(stream)
                                val piece = recognizer.getResult(stream).text.trim()
                                if (piece.isNotEmpty()) {
                                    if (sentences.isEmpty()) VoiceTrace.mark("first sentence", "chars=${piece.length}")
                                    sentences.add(piece)
                                }
                                pieces++
                            } finally {
                                stream.release()
                            }
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
                    "覆盖=${(coverage * 100).toInt()}%",
            )
            // ── 关键：覆盖不足就**不信 VAD** ──
            // 实测（2026-11-16 用户诊断包）：7~9 秒的正常说话，VAD 只切出一句、覆盖率极低，
            // 逐句解码后只剩 1~2 个字（用户看到的就是「说了一长段，只上屏一个『あ』」）。
            // 根因是 VAD 把大半段低音量语音判成静音，送去解码的只是开头一小段。
            // 覆盖不到一半时直接返回 null，调用方退回整段解码 —— 宁可慢一点，不许丢话。
            if (coveredSamples < samples.size / 2L) null
            else if (sentences.isEmpty()) null
            else joinSentences(sentences)
        }.getOrNull()

    /**
     * 分句拼接：SenseVoice 逐句解码时，句子之间**没有任何分隔**，拼出来是一整串没有逗号的长句
     * （用户 2026-11-17「标点加得比较少」的主因）。VAD 切出来的句边界就是天然的句子边界，
     * 所以句尾缺标点的补「，」，最后一句补「。」—— 只依据「模型自己说出了句子边界」，
     * 不猜语义。
     */
    private fun joinSentences(sentences: List<String>): String = VoiceText.joinSentences(sentences)

    private fun finishText(raw: String): String = VoiceText.finishText(raw)

    /** 10ms 一帧的 RMS（0..1，float 域）；不足一帧的尾巴丢掉（对回补判据无影响） */
    private fun frameEnergies(samples: FloatArray): FloatArray = VoiceDsp.frameEnergies(samples)

    private fun onsetBacktrack(
        frameRms: FloatArray,
        speechRms: Float,
        segStart: Int,
        floorSample: Int,
    ): Int = VoiceDsp.onsetBacktrack(frameRms, speechRms, segStart, floorSample)

    /** 只转 [fromSample]..[toSample) 这一段（尾巴解码用，避免把 10 MB 整段再转一遍） */
    private fun pcm16ToFloat(pcm16: ByteArray, fromSample: Int, toSample: Int): FloatArray {
        val from = (fromSample * 2).coerceIn(0, pcm16.size)
        val to = (toSample * 2).coerceIn(from, pcm16.size)
        val count = (to - from) / 2
        if (count <= 0) return FloatArray(0)
        val out = FloatArray(count)
        var i = from
        var j = 0
        while (j < count && i + 1 < to) {
            val lo = pcm16[i].toInt() and 0xFF
            val hi = pcm16[i + 1].toInt()
            out[j] = ((hi shl 8) or lo) / 32768.0f
            i += 2
            j++
        }
        return out
    }

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
            // 小端拼成有符号 16 位
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

        /** 段首回补参数与帧能量计算都在 [VoiceDsp]，这里只保留对外引用 */
        const val ONSET_ALWAYS_FRAMES = VoiceDsp.ONSET_ALWAYS_FRAMES
        const val ONSET_GAP_FRAMES = VoiceDsp.ONSET_GAP_FRAMES
        const val ONSET_MAX_BACK_FRAMES = VoiceDsp.ONSET_MAX_BACK_FRAMES

        /** 低于这个长度（约 4 秒）不值得走 VAD：切出来的句子和整段解码差别不大 */
        const val MIN_VAD_SAMPLES = 4 * SAMPLE_RATE

        /** 分段缓存的节拍：0.7 秒一拍，比「整段重解」便宜得多 */
        const val SEGMENT_INTERVAL_MS = 700L

        /** 新增音频不足 0.2 秒就跳过这一拍 */
        const val SEGMENT_MIN_NEW_BYTES = 6_400

        /** 少于 0.25 秒的尾巴不值得解（预览与结束解码同一判据） */
        const val MIN_TAIL_SAMPLES = SAMPLE_RATE / 4

        /** 「边说边出」预览最多带 8 秒尾巴，耗时有上限 */
        const val PREVIEW_TAIL_SAMPLES = 8 * SAMPLE_RATE

        /** 视为「这句话已经说完」的字符：出现它就不要再补标点 */
    
        /** 解码串行化（recognizer / stream 都非线程安全） */
        val lock = Any()

        /**
         * 取（或懒加载）**进程级**识别器；模型文件被重新下载（lastModified 变了）就重建。
         *
         * 抽成函数是为了两处共用：整段识别与「冷启动预热」。
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
                            // 中英混说是常态，交给模型自己判语种
                            language = "auto",
                            useInverseTextNormalization = true,
                        ),
                        tokens = tokens.absolutePath,
                        // 回到 2 线程：v88 提到 4 后用户反馈「比之前还慢」——
                        // int8 量化模型在手机大核上 2 线程已经够，多线程反而抢 CPU / 掉频。
                        numThreads = 2,
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

        /**
         * 冷启动预热：把识别器（几百 MB 的内存与算子初始化）在后台先建好。
         *
         * 用户反馈「第一次冷启动语音较慢」—— 原先第一次解码才建识别器，那 2~4 秒全算在
         * 用户说完等结果的时间里；现在 IME 进程一启动就预热，第一次说话直接可用。
         */
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
