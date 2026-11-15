package com.jinn.inputmethod

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
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

    override fun prepare(): String? = LocalAsrModel.problem(prefs.context)

    override fun recognize(wav: ByteArray, pcm16: ByteArray): BatchOutcome {
        val model = LocalAsrModel.modelFile(prefs.context)
        val tokens = LocalAsrModel.tokensFile(prefs.context)
        if (!model.isFile || !tokens.isFile) {
            return BatchOutcome.Fail("离线模型未就绪")
        }
        val samples = pcm16ToFloat(pcm16)
        if (samples.isEmpty()) return BatchOutcome.Ok("")

        val recognizer = synchronized(lock) {
            val cached = cachedRecognizer
            if (cached != null && cachedModelPath == model.absolutePath &&
                cachedModelStamp == model.lastModified()
            ) {
                cached
            } else {
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
                        numThreads = 2,
                        debug = false,
                        provider = "cpu",
                    ),
                )
                OfflineRecognizer(config = config).also {
                    cachedRecognizer = it
                    cachedModelPath = model.absolutePath
                    cachedModelStamp = model.lastModified()
                }
            }
        }

        val started = System.currentTimeMillis()
        // 长句先按静音切句：silero VAD 把整段切成若干句话，逐句解码再拼回去。
        // 好处是每句都在 8 秒以内，模型不必把整段话挤进一个上下文窗口（长段最容易丢字/断句错），
        // 顺带单句解码更快。VAD 模型缺失、切不出句子或任何一步抛异常，都退回整段解码 ——
        // 语音可用性不能被一个可选件拖住。
        val vadPath = LocalAsrModel.vadFile(prefs.context)
        val segmented = if (vadPath.isFile && samples.size >= MIN_VAD_SAMPLES) {
            splitByVad(recognizer, samples, vadPath)
        } else {
            null
        }
        val text = segmented ?: synchronized(lock) {
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(samples, SAMPLE_RATE)
                recognizer.decode(stream)
                recognizer.getResult(stream).text
            } finally {
                stream.release()
            }
        }
        Diagnostics.i(
            tag,
            "离线解码完成 音频=${samples.size / SAMPLE_RATE.toFloat()}s " +
                "分句=${if (segmented == null) "整段" else "VAD"} " +
                "耗时=${System.currentTimeMillis() - started}ms 字数=${text.length}",
        )
        return BatchOutcome.Ok(text.trim())
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
            val builder = StringBuilder()
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
                                builder.append(recognizer.getResult(stream).text)
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
            else if (builder.isBlank()) null
            else builder.toString()
        }.getOrNull()

    /** 10ms 一帧的 RMS（0..1，float 域）；不足一帧的尾巴丢掉（对回补判据无影响） */
    private fun frameEnergies(samples: FloatArray): FloatArray {
        val frame = SAMPLE_RATE / 100
        if (frame <= 0 || samples.size < frame) return FloatArray(0)
        val count = samples.size / frame
        val out = FloatArray(count)
        for (f in 0 until count) {
            var sum = 0.0
            val base = f * frame
            for (j in 0 until frame) {
                val v = samples[base + j]
                sum += v * v
            }
            out[f] = kotlin.math.sqrt(sum / frame).toFloat()
        }
        return out
    }

    /**
     * 段首回推到第几帧（返回采样下标）。
     *
     * [floorSample] = 上一段的段尾：两段音频绝不重叠，否则同一句话会被解码两遍、上屏两次。
     * 规则与沙鸥逐项对齐：先无条件往前 0.3 秒（气声没有能量但确属语音），之后沿「语音帧」继续走，
     * 途中允许最多 0.25 秒的低能量间隙，总回补不超过 3 秒。
     */
    private fun onsetBacktrack(frameRms: FloatArray, speechRms: Float, segStart: Int, floorSample: Int): Int {
        val frame = SAMPLE_RATE / 100
        if (frame <= 0 || frameRms.isEmpty()) return segStart
        val startFrame = (segStart / frame).coerceIn(0, frameRms.size)
        val floorFrame = (floorSample / frame).coerceAtLeast(0)
        var idx = startFrame
        var steps = 0
        var gap = 0
        while (idx > floorFrame && idx > 0 && steps <= ONSET_MAX_BACK_FRAMES) {
            val loud = frameRms[idx - 1] >= speechRms
            when {
                steps < ONSET_ALWAYS_FRAMES -> Unit          // 无条件补的 0.3 秒
                loud -> gap = 0                              // 语音继续，间隙清零
                gap < ONSET_GAP_FRAMES -> gap++               // 低能量但还在容忍窗口内
                else -> break                                // 彻底掉出语音段，停
            }
            idx--
            steps++
        }
        return idx * frame
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

    private companion object {
        const val SAMPLE_RATE = 16_000

        /** 段首无条件回补的帧数（0.3s × 100 帧/秒） */
        const val ONSET_ALWAYS_FRAMES = 30

        /** 回补途中容忍的低能量间隙（0.25s） */
        const val ONSET_GAP_FRAMES = 25

        /** 单段最多回补多少帧（3s），防止一口气把整段话都吞进来 */
        const val ONSET_MAX_BACK_FRAMES = 300

        /** 低于这个长度（约 4 秒）不值得走 VAD：切出来的句子和整段解码差别不大 */
        const val MIN_VAD_SAMPLES = 4 * SAMPLE_RATE

        /** 解码串行化（recognizer / stream 都非线程安全） */
        val lock = Any()

        var cachedRecognizer: OfflineRecognizer? = null
        var cachedModelPath: String = ""
        var cachedModelStamp: Long = 0L
    }
}
