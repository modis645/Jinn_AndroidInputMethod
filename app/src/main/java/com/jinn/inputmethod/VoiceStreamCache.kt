package com.jinn.inputmethod

import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

/**
 * 录音期间的**分段缓存**（2026-11-17「方案三」）。
 *
 * 为什么要有它：原先结束语音后要把 0~300 秒整段重解，等待时间与说话时长成正比
 * （90 秒≈十几秒、300 秒≈四十秒以上）。分段缓存把「已经说完的句子」在录音过程中就用
 * 流式 VAD 切出来、逐句解好存起来；用户点「结束」时只剩最后那一段尾巴要解，
 * 上屏等待从「总时长」降到「尾巴长度」。
 *
 * 与 [LocalSenseVoiceClient] 的整段 VAD 路径口径必须一致（否则缓存句与尾巴句的标点、
 * 段首回补规则会打架）：同样的 silero 参数、同样的 10ms 帧能量段首回补、同样只按静音切句。
 *
 * 不在这里做上屏：只在缓存里攒句子和游标，落字仍由 IME 一次 commit（用户仍可整体改）。
 */
internal class VoiceStreamCache(
    private val vadPath: File,
    private val recognizer: () -> OfflineRecognizer,
    private val lock: Any,
    private val tag: String,
) {

    /** 已解出的整句（按出现顺序） */
    val texts = ArrayList<String>()

    /** 已消费到的采样下标：结束时的尾巴解码从这里开始 */
    var coveredSamples = 0
        private set

    /** 真正写进 [samples] 的采样数（数组长度是容量，不是长度） */
    private var filled = 0

    private var samples = FloatArray(VoiceDsp.SAMPLE_RATE) { 0f }
    private var frames = FloatArray(0)

    private var vad: Vad? = null
    private var speechRms = 1e-5f * 3.981f

    /** 追加一段 PCM16（只从 [fromByte] 往后取新音频），返回是否解出了新句子 */
    fun append(pcm16: ByteArray, fromByte: Int): Boolean {
        val to = pcm16.size
        if (to <= fromByte) return false
        val add = pcm16ToFloat(pcm16, fromByte / 2, to / 2)
        if (add.isEmpty()) return false
        appendSamples(add)
        updateFrames()
        val v = ensureVad() ?: return false
        try {
            // 单参重载才是公开的（`(samples, sampleRate)` 在 jar 里是 private）
            v.acceptWaveform(add)
        } catch (t: Throwable) {
            Diagnostics.w(tag, "分段缓存: VAD 喂音频失败 ${t.javaClass.simpleName}")
            return false
        }
        return drain(v)
    }

    /** 尾巴（尚未成句的部分）的采样，供「边说边出」预览与结束解码 */
    fun tailSamples(maxSamples: Int = Int.MAX_VALUE): FloatArray {
        val from = coveredSamples.coerceIn(0, filled)
        val start = maxOf(from, filled - maxSamples.coerceAtLeast(0))
        if (start >= filled) return FloatArray(0)
        return samples.copyOfRange(start, filled)
    }

    /** 整段文本（缓存句子拼好）；还没有句子时返回 null */
    fun text(): String? = if (texts.isEmpty()) null else VoiceText.joinSentences(texts)

    fun sentenceCount(): Int = texts.size

    fun release() {
        runCatching { vad?.release() }
        vad = null
    }

    // ── 内部 ────────────────────────────────────────────────────

    private fun appendSamples(add: FloatArray) {
        val old = filled
        val need = old + add.size
        if (need > samples.size) {
            samples = samples.copyOf(maxOf(need, samples.size * 2))
        }
        System.arraycopy(add, 0, samples, old, add.size)
        filled = need
    }

    private fun updateFrames() {
        val count = filled / VoiceDsp.FRAME_SAMPLES
        if (count <= frames.size) return
        val out = FloatArray(count)
        System.arraycopy(frames, 0, out, 0, frames.size)
        for (f in frames.size until count) {
            var sum = 0.0
            val base = f * VoiceDsp.FRAME_SAMPLES
            for (j in 0 until VoiceDsp.FRAME_SAMPLES) {
                val v = samples[base + j]
                sum += v * v
            }
            out[f] = kotlin.math.sqrt(sum / VoiceDsp.FRAME_SAMPLES).toFloat()
        }
        frames = out
        // 噪声底按已有帧的第 10 百分位估计（与整段解码同一公式）
        if (frames.size >= 50) {
            val sorted = frames.copyOf()
            sorted.sort()
            speechRms = maxOf(sorted[(sorted.size - 1) / 10], 1e-5f) * 3.981f
        }
    }

    private fun ensureVad(): Vad? {
        vad?.let { return it }
        if (!vadPath.isFile) return null
        return runCatching {
            Vad(
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = vadPath.absolutePath,
                        // 与整段解码路径逐项一致：这些值是沙鸥真机大样本调出来的
                        threshold = 0.5f,
                        minSilenceDuration = 0.5f,
                        minSpeechDuration = 0.25f,
                        windowSize = 512,
                        maxSpeechDuration = 28.0f,
                    ),
                    sampleRate = VoiceDsp.SAMPLE_RATE,
                    numThreads = 1,
                    provider = "cpu",
                    debug = false,
                ),
            )
        }.onFailure {
            Diagnostics.w(tag, "分段缓存: VAD 创建失败 ${it.javaClass.simpleName}")
        }.getOrNull()?.also { vad = it }
    }

    private fun drain(v: Vad): Boolean {
        var changed = false
        val recognizer = recognizer()
        while (!v.empty()) {
            val segment = v.front()
            val segStart = segment.start.coerceIn(0, filled)
            val segEnd = (segStart + segment.samples.size).coerceAtMost(filled)
            if (segment.samples.size >= VoiceDsp.SAMPLE_RATE / 5 && segEnd > coveredSamples) {
                // 段首回补：与整段解码同一函数、同一判据（长录音丢句首 1~4 字的修法）
                val padStart = VoiceDsp.onsetBacktrack(frames, speechRms, segStart, coveredSamples)
                val from = if (padStart in 1 until segStart) padStart else segStart
                val slice = samples.copyOfRange(from, segEnd)
                val text = decode(recognizer, slice)
                if (text.isNotEmpty()) {
                    texts.add(text)
                    changed = true
                }
                // 解不出字（噪声）也把游标推过去：尾巴不许重复解同一段
                coveredSamples = segEnd
            } else {
                coveredSamples = maxOf(coveredSamples, segEnd)
            }
            runCatching { v.pop() }
        }
        return changed
    }

    private fun decode(recognizer: OfflineRecognizer, slice: FloatArray): String = synchronized(lock) {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(slice, VoiceDsp.SAMPLE_RATE)
            recognizer.decode(stream)
            recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

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
}

/**
 * 结束时的结果拼接与「分段缓存是否可信」的判据 —— 纯函数，真机不可复现的部分靠它兜住。
 */
internal object VoiceTailMerge {

    /**
     * 缓存句 + 尾巴的合并：与整段解码同一条标点规则（句间补「，」、末句补「。」）。
     */
    fun merge(cacheTexts: List<String>, tailText: String): String {
        val list = ArrayList<String>(cacheTexts.size + 1)
        cacheTexts.filter { it.isNotBlank() }.forEach { list.add(it) }
        if (tailText.isNotBlank()) list.add(tailText)
        return if (list.isEmpty()) "" else VoiceText.joinSentences(list)
    }

    /**
     * 缓存能不能当真：缓存句非空，且已消费的音频至少覆盖三分之一。
     *
     * 覆盖不足说明 VAD 把大半段判成了静音（实测 7~9 秒正常说话会这样），
     * 这时必须退回整段解码 —— 宁可慢，不许丢话。
     */
    fun isCacheUsable(count: Int, coveredSamples: Int, totalSamples: Int): Boolean {
        if (count <= 0) return false
        if (totalSamples <= 0) return false
        return coveredSamples >= totalSamples / 3
    }
}
