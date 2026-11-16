package com.jinn.inputmethod

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 模型落点体检与「算不算就绪」的判定（[LocalAsrModel.auditDir] / [LocalAsrModel.readyOf] /
 * [LocalAsrModel.verifyArchive]）。
 *
 * 为什么这条必须有单测：真机上「下载解包都成功、页面却显示未下载」的根因就是这一条判定；
 * 它一旦放宽或收紧，用户会立刻看到「模型明明占了 240MB，键盘还说没模型」。
 * 用临时目录驱动纯文件逻辑，不需要装机、不需要 Context。
 */
class LocalAsrModelAuditTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "jinn-asr-audit-" + System.nanoTime())
        dir.mkdirs()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun stub(name: String, bytes: Int): File =
        File(dir, name).apply { writeBytes(ByteArray(bytes)) }

    @Test
    fun `空目录_什么都不就绪且列出缺失`() {
        val audit = LocalAsrModel.auditDir(dir)
        assertEquals("空目录三项都该是 0", 0L, audit.modelBytes)
        assertEquals(0L, audit.tokensBytes)
        assertEquals(0L, audit.vadBytes)
        assertFalse("空目录不可能就绪", audit.ready)
        val line = audit.line()
        assertTrue("体检行要写明 model 缺失：$line", line.contains("model 缺失"))
        assertTrue("体检行要写明 vad 缺失：$line", line.contains("vad 缺失"))
    }

    @Test
    fun `文件存在但过小的模型不算就绪`() {
        stub(LocalAsrModel.MODEL_FILE, 1024)
        stub(LocalAsrModel.TOKENS_FILE, 8)
        val audit = LocalAsrModel.auditDir(dir)
        assertEquals("字节数要如实读出", 1024L, audit.modelBytes)
        assertEquals(8L, audit.tokensBytes)
        assertFalse("小于下限的模型不能算就绪", audit.ready)
    }

    @Test
    fun `就绪判据是严格大于下限且 tokens 非空`() {
        val min = LocalAsrModel.MODEL_MIN_BYTES
        assertFalse("恰好等于下限不算就绪（必须是真正完整的文件）",
            LocalAsrModel.readyOf(min, 1))
        assertTrue("下限加一字节即就绪", LocalAsrModel.readyOf(min + 1, 1))
        assertFalse("tokens 为空不算就绪", LocalAsrModel.readyOf(min + 1, 0))
    }

    @Test
    fun `目录里的陌生文件被列进其他`() {
        stub(LocalAsrModel.MODEL_FILE, 1024)
        stub("leftover.zip.part", 2048)
        val audit = LocalAsrModel.auditDir(dir)
        assertTrue("陌生件要出现在体检行里：${audit.stray}", audit.stray.any { it.startsWith("leftover.zip.part") })
        assertTrue("体检行要带上它：${audit.line()}", audit.line().contains("其他"))
    }

    @Test
    fun `归档校验_体积不足直接判假`() {
        val small = stub("s.tar.bz2", 16)
        assertFalse("远小于官方体积的文件不能通过", LocalAsrModel.verifyArchive(small))
    }

    @Test
    fun `归档校验_体积够但内容不对判假`() {
        // 体积下限是官方 163MB 的 95%，测试不能真写 155MB；这里用「大小不足」覆盖，
        // 同时钉住「体积够了也必须比 sha256」这条实现的顺序依赖
        val src = TestSources.codeOf(
            TestSources.rawSource(
                "src/main/java/com/jinn/inputmethod/LocalAsrModel.kt",
                "app/src/main/java/com/jinn/inputmethod/LocalAsrModel.kt",
            ),
        )
        val body = TestSources.blockAfter(src, "fun verifyArchive(file: File): Boolean")
        assertTrue("大小闸必须在算摘要之前", body.indexOf("ARCHIVE_MIN_BYTES") < body.indexOf("SHA-256"))
        assertTrue("必须比对官方 sha256", "ARCHIVE_SHA256" in body)
    }
}
