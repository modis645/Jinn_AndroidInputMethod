package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 随包小件（词表 tokens.txt 与分句模型 silero_vad.onnx）。
 *
 * 这两件加起来不到 1MB，却是「就绪」判定里最容易缺的两件：缺词表识别器加载不起来，
 * 缺 VAD 分句与句间标点全没了（用户反馈的「标点变少」）。塞进 APK 后这两条失败路径
 * 直接从下载链路里消失，只剩 239MB 本件需要下。
 *
 * 这里钉三件事：小件真的在 assets 里、体积没有悄悄缩水、代码里有调用点释放它们。
 */
class LocalAsrModelBundledTest {

    private fun assetDir(): File {
        for (p in listOf("src/main/assets/local-asr", "app/src/main/assets/local-asr")) {
            val d = File(p)
            if (d.isDirectory) return d
        }
        throw AssertionError("未找到 assets/local-asr 目录")
    }

    private fun asset(name: String): File {
        val f = File(assetDir(), name)
        if (!f.isFile) throw AssertionError("assets 里缺少 $name")
        return f
    }

    @Test
    fun `词表随包_体积不得缩水`() {
        val f = asset(LocalAsrModel.TOKENS_FILE)
        assertTrue("词表至少 200KB（实测 316KB），当前 ${f.length()}B",
            f.length() >= LocalAsrModel.BUNDLED_TOKENS_MIN_BYTES)
    }

    @Test
    fun `分句模型随包_体积不得缩水`() {
        val f = asset(LocalAsrModel.VAD_FILE)
        assertTrue("分句模型至少 100KB（实测 644KB），当前 ${f.length()}B",
            f.length() > LocalAsrModel.VAD_MIN_BYTES)
    }

    @Test
    fun `随包清单必须两件都在`() {
        assertEquals("随包清单只有这两件", 2, LocalAsrModel.BUNDLED_PARTS.size)
        assertTrue("词表要在清单里", LocalAsrModel.BUNDLED_PARTS.any { it.second == LocalAsrModel.TOKENS_FILE })
        assertTrue("VAD 要在清单里", LocalAsrModel.BUNDLED_PARTS.any { it.second == LocalAsrModel.VAD_FILE })
        LocalAsrModel.BUNDLED_PARTS.forEach { (assetPath, _) ->
            assertTrue("清单里的 asset 路径必须真实存在：$assetPath",
                File(assetDir(), assetPath.substringAfterLast('/')).isFile)
        }
    }

    @Test
    fun `释放动作必须写入模型目录且不覆盖已有件`() {
        val src = TestSources.codeOf(
            TestSources.rawSource(
                "src/main/java/com/jinn/inputmethod/LocalAsrModel.kt",
                "app/src/main/java/com/jinn/inputmethod/LocalAsrModel.kt",
            ),
        )
        val body = TestSources.blockAfter(src, "private fun copyAssetIfMissing")
        assertTrue("目标已够大就该直接返回，不重写", body.contains("dst.length() >= minBytes) return"))
        assertTrue("必须先写 .part 再改名（中途被杀不留半个词表）", body.contains(".part"))
        assertTrue("体积不够的临时件要删掉", body.contains("tmp.delete()"))
    }

    @Test
    fun `三个入口都必须释放随包小件`() {
        val javaDir = File(assetDir().parentFile.parentFile, "java/com/jinn/inputmethod")
        fun has(file: String) = File(javaDir, file).readText().contains("ensureBundled(")
        assertTrue("IME 启动就该释放", has("JinnIme.kt"))
        assertTrue("语音前要兜一次", has("LocalSenseVoiceClient.kt"))
        assertTrue("语音页自动下载前要兜一次", has("VoiceSettingsActivity.kt"))
    }
}
