package com.jinn.inputmethod

import com.k2fsa.sherpa.onnx.OfflineRecognizer

/**
 * 录音期间的**分段缓存**（2026-11-17「方案三」）。
 *
 * 为什么要有它：原先结束语音后要把 0~300 秒整段重解，等待时间与说话时长成正比
 * （90 秒≈十几秒、300 秒≈四十秒以上）。分段缓存把「已经说完的句子」在录音过程中就
 * 用流式切句切出来、逐句解好存起来；用户点「结束」时只剩最后一段尾巴要解，
 * 上屏等待从「总时长」降到「尾巴长度」。
 *
 * ⚠ 为什么不用 sherpa 的流式 `Vad`：真机实测（2026-11-17，200 秒长文，结束仍等 70 秒）
 * 证明它的出段时序不可控 —— 喂任意长度会滞留尾数、喂整窗又受它内部状态影响，
 * 结果就是「缓存迟迟不填 → 结束退回整段重解」。切句改为在**我们自己的 10ms 帧能量**上做
 * （[EnergySegmenter]），条件是纯数学，可以在 JVM 单测里穷举，不用真机试；
 * silero 只保留在「整段解码」的兜底路径里。
 *
 * 不在这里做上屏：只在缓存里攒句子和游标，落字仍由 IME 一次 commit（用户仍可整体改）。
 */
internal class VoiceStreamCache(
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
    private var speechRms = 1e-5f * 3.981f

    /** 已经处理过的帧数（切句状态机从这里往后看） */
    private var scannedFrames = 0

    /** 不足一帧（10ms）的残留采样，下拍接上再算 */
    private var frameCarry = FloatArray(0)

    /** 纯数学的切句器：帧能量 + 最短语音 + 最短静音 + 最长句 */
    private val segmenter = EnergySegmenter()

    /** 追加一段 PCM16（只从 [fromByte] 往后取新音频），返回是否解出了新句子 */
    fun append(pcm16: ByteArray, fromByte: Int): Boolean {
        val to = pcm16.size
        if (to <= fromByte) return false
        val add = pcm16ToFloat(pcm16, fromByte / 2, to / 2)
        if (add.isEmpty()) return false
        appendSamples(add)
        updateFrames()
        updateNoiseFloor()
        return false
    }

    /**
     * 扫一遍新到的帧，按切句器给出的切点把句子解出来。
     * 与 [append] 分开：喂音频和出句子解耦，便于控制「解多久、什么时候解」。
     */
    fun flushDecoded(): Boolean {
        var changed = false
        while (true) {
            if (scannedFrames >= frames.size) break
            val idx = scannedFrames
            val speaking = frames[idx] > speechRms
            scannedFrames++
            val cutEndFrame = segmenter.onFrame(speaking) ?: continue
            if (cutSegment(cutEndFrame)) changed = true
        }
        return changed
    }

    /** 尾巴采样（不超过 [maxSamples]，且不重复已消费部分）；给预览用 */
    fun tailSamples(maxSamples: Int): FloatArray {
        val from = coveredSamples.coerceIn(0, filled)
        val start = maxOf(from, filled - maxSamples.coerceAtLeast(0))
        if (start >= filled) return FloatArray(0)
        return samples.copyOfRange(start, filled)
    }

    /** 整段文本（缓存句子拼好）；还没有句子时返回 null */
    fun text(): String? = if (texts.isEmpty()) null else VoiceText.joinSentences(texts)

    fun sentenceCount(): Int = texts.size

    fun release() {
        texts.clear()
    }

    /** 诊断一行：句数 / 覆盖 / 尾巴（用户报「等太久」时先看这行） */
    fun statsLine(): String =
        "句=${texts.size} 覆盖=${coveredSamples / VoiceDsp.SAMPLE_RATE}s/" +
            "${filled / VoiceDsp.SAMPLE_RATE}s 尾巴=${(filled - coveredSamples) / VoiceDsp.SAMPLE_RATE}s"

    // ── 内部 ────────────────────────────────────────────────────

    private fun cutSegment(endFrame: Int): Boolean {
        val segStart = segmenter.lastSegmentStartFrame()
        if (segStart < 0 || endFrame <= segStart) return false
        val segStartSample = (segStart * VoiceDsp.FRAME_SAMPLES).coerceIn(0, filled)
        val segEndSample = (endFrame * VoiceDsp.FRAME_SAMPLES).coerceIn(0, filled)
        if (segEndSample <= coveredSamples) return false
        // 段首回补：与整段解码同一函数、同一判据（长录音丢句首 1~4 字的修法）
        val padStart = VoiceDsp.onsetBacktrack(frames, speechRms, segStartSample, coveredSamples)
        val from = if (padStart in 1 until segStartSample) padStart else segStartSample
        val slice = samples.copyOfRange(from, segEndSample)
        if (slice.size < VoiceDsp.SAMPLE_RATE / 5) {
            coveredSamples = maxOf(coveredSamples, segEndSample)
            return false
        }
        val text = decode(slice)
        if (text.isNotEmpty()) texts.add(text)
        // 解不出字（噪声）也把游标推过去：尾巴不许重复解同一段
        coveredSamples = segEndSample
        return text.isNotEmpty()
    }

    private fun decode(slice: FloatArray): String = synchronized(lock) {
        runCatching {
            val r = recognizer()
            val stream = r.createStream()
            try {
                stream.acceptWaveform(slice, VoiceDsp.SAMPLE_RATE)
                r.decode(stream)
                r.getResult(stream).text.trim()
            } finally {
                stream.release()
            }
        }.getOrElse { "".also { Diagnostics.w(tag, "分段缓存: 解码失败 ${it.javaClass.simpleName}") } }
    }

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
        val available = filled - frames.size * VoiceDsp.FRAME_SAMPLES
        if (available < VoiceDsp.FRAME_SAMPLES) return
        val newCount = available / VoiceDsp.FRAME_SAMPLES
        val out = FloatArray(frames.size + newCount)
        System.arraycopy(frames, 0, out, 0, frames.size)
        for (k in 0 until newCount) {
            val f = frames.size + k
            var sum = 0.0
            val base = f * VoiceDsp.FRAME_SAMPLES
            for (j in 0 until VoiceDsp.FRAME_SAMPLES) {
                val v = samples[base + j]
                sum += v * v
            }
            out[f] = kotlin.math.sqrt(sum / VoiceDsp.FRAME_SAMPLES).toFloat()
        }
        frames = out
        frameCarry = FloatArray(0)
    }

    /**
     * 噪声底：取已有帧的第 10 百分位 ×3.98（与整段解码同一公式），并给一个绝对下限
     * （0.015）—— 安静房间里百分位会低到 1e-4 量级，若不加下限，喘息声也会被当成语音，
     * 一段都切不出来。
     */
    private fun updateNoiseFloor() {
        if (frames.size < 100) return
        val sorted = frames.copyOf()
        sorted.sort()
        speechRms = maxOf(sorted[(sorted.size - 1) / 10], 0f) * 3.981f
        speechRms = maxOf(speechRms, 0.015f)
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
 * 帧能量的切句状态机（纯数学，无模型、无 Android 依赖）。
 *
 * 判据与整段解码路径一致：连续语音 ≥0.25 秒才算一句话，静音连续 ≥0.5 秒才切句，
 * 一句话最长 28 秒（超过就硬切，避免一口气不停时永远不出句）。
 */
internal class EnergySegmenter(
    private val minSpeechFrames: Int = MIN_SPEECH_FRAMES,
    private val minSilenceFrames: Int = MIN_SILENCE_FRAMES,
    private val maxSpeechFrames: Int = MAX_SPEECH_FRAMES,
) {

    private var inSpeech = false
    private var startFrame = 0
    private var silenceRun = 0
    private var speechFrames = 0

    /** 上一句的起点帧（供段首回补用）；没有句子时为 -1 */
    fun lastSegmentStartFrame(): Int = lastStart

    private var lastStart = -1

    /**
     * 处理一帧，返回「该切了」的结束帧（不含）；不该切返回 null。
     * [speaking] 由调用方按噪声底判好。
     */
    fun onFrame(speaking: Boolean): Int? {
        val idx = currentIndex
        currentIndex++
        if (speaking) {
            if (!inSpeech) {
                inSpeech = true
                startFrame = idx
                speechFrames = 0
            }
            silenceRun = 0
            speechFrames++
            if (speechFrames >= maxSpeechFrames) {
                inSpeech = false
                lastStart = startFrame
                return idx + 1
            }
            return null
        }
        if (!inSpeech) return null
        silenceRun++
        if (silenceRun < minSilenceFrames) return null
        val end = idx - silenceRun + 1
        val lasted = end - startFrame
        inSpeech = false
        silenceRun = 0
        if (lasted < minSpeechFrames) return null
        lastStart = startFrame
        return end
    }

    /** 当前处理的帧下标（内在时钟） */
    var currentIndex = 0
        private set

    private companion object {
        /** 0.25 秒 @10ms 帧 */
        const val MIN_SPEECH_FRAMES = 25

        /** 0.5 秒 @10ms 帧 */
        const val MIN_SILENCE_FRAMES = 50

        /** 28 秒 @10ms 帧：与 silero 的 maxSpeechDuration 同一口径 */
        const val MAX_SPEECH_FRAMES = 2_800
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
     * 覆盖不足说明切句把大半段判成了静音，这时必须退回整段解码 —— 宁可慢，不许丢话。
     */
    fun isCacheUsable(count: Int, coveredSamples: Int, totalSamples: Int): Boolean {
        if (count <= 0) return false
        if (totalSamples <= 0) return false
        return coveredSamples >= totalSamples / 3
    }
}
