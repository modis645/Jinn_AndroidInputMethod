package com.jinn.inputmethod

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig

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
        val text = synchronized(lock) {
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
                "耗时=${System.currentTimeMillis() - started}ms 字数=${text.length}",
        )
        return BatchOutcome.Ok(text.trim())
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

        /** 解码串行化（recognizer / stream 都非线程安全） */
        val lock = Any()

        var cachedRecognizer: OfflineRecognizer? = null
        var cachedModelPath: String = ""
        var cachedModelStamp: Long = 0L
    }
}
