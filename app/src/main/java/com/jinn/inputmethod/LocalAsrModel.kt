package com.jinn.inputmethod

import android.content.Context
import java.io.File

/**
 * 离线语音识别的模型落点与就绪判定（sherpa-onnx + SenseVoice「快速整句」）。
 *
 * 目录约定：`filesDir/local-asr/`（会被系统的「清除数据」一并清掉，不做任何外部存储依赖）。
 * 只有两件文件是必需的：
 *  - `model.int8.onnx`：SenseVoice small 的 int8 量化模型（整句识别，不是流式）；
 *  - `tokens.txt`：同一目录里的 token 表。
 *
 * 为什么用 int8：全精度模型 230 MB 级，int8 版约 1/4，手机端单句解码仍是百毫秒级，
 * 与「快速整句」（松手后一次性解码、接受一点延迟）的用法匹配。
 */
internal object LocalAsrModel {

    const val DIR_NAME = "local-asr"

    /**
     * silero VAD 模型（约 2 MB）：只用于**长句自动分句**，缺了不影响识别 ——
     * 没有它时长句整段解码，有它就按静音切开逐句解码再拼回去（见 LocalSenseVoiceClient）。
     */
    const val VAD_FILE = "silero_vad.onnx"
    const val VAD_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
    const val MODEL_FILE = "model.int8.onnx"
    const val TOKENS_FILE = "tokens.txt"

    /** SenseVoice 官方打包（tar.bz2）：下载后解出上面两件文件，压缩包本身不保留 */
    const val ARCHIVE_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
            "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17.tar.bz2"
    const val ARCHIVE_NAME = "sense-voice-small.tar.bz2"

    fun dir(context: Context): File = File(context.filesDir, DIR_NAME).apply { mkdirs() }

    fun modelFile(context: Context): File = File(dir(context), MODEL_FILE)

    fun tokensFile(context: Context): File = File(dir(context), TOKENS_FILE)

    fun vadFile(context: Context): File = File(dir(context), VAD_FILE)

    /** VAD 是可选件：缺了降级为「整段解码」，所以不参与 [isReady] 判定 */
    fun hasVad(context: Context): Boolean = vadFile(context).let { it.isFile && it.length() > 0 }

    /** 两件文件都在才算就绪（只看存在性，完整性由下载端的 sha256 / 大小校验保证） */
    fun isReady(context: Context): Boolean =
        modelFile(context).isFile && modelFile(context).length() > 0 &&
            tokensFile(context).isFile && tokensFile(context).length() > 0

    /** 已占用的字节数（设置页「存储占用」与删除入口用） */
    fun usedBytes(context: Context): Long = dir(context).listFiles()?.sumOf { it.length() } ?: 0L

    /**
     * 状态条上直接给用户看的一句话：就绪返回 null；缺文件返回「缺什么 + 该怎么办」。
     * 与 [BufferedBatchAsrClient.prepare] 的约定一致（null = 可用）。
     */
    fun problem(context: Context): String? {
        if (!modelFile(context).isFile) return "离线模型未就绪：缺少 model.int8.onnx"
        if (!tokensFile(context).isFile) return "离线模型未就绪：缺少 tokens.txt"
        return null
    }
}
