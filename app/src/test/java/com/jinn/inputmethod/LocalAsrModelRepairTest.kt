package com.jinn.inputmethod

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/**
 * 模型自愈的「搬文件」行为（[LocalAsrModel.canRepairIn] / [LocalAsrModel.repairIn]）。
 *
 * 这两条是真机上唯二能救回 163MB 残局的路径：
 *  ① 目录里留下体积完整的 `xxx.part`（同分区改名，毫秒级）；
 *  ② 解包成功但换入前被杀，完整件还在 `cacheDir/local-asr-unpack`。
 * 判错任何一条，用户要么白等一次 163MB 重下，要么被判定「没救」。
 * 用临时目录 + 稀疏文件驱动，不用真的写 200MB。
 */
class LocalAsrModelRepairTest {

    private lateinit var dir: File
    private lateinit var unpack: File

    @Before
    fun setUp() {
        val base = File(System.getProperty("java.io.tmpdir"), "jinn-asr-repair-" + System.nanoTime())
        dir = File(base, "local-asr").apply { mkdirs() }
        unpack = File(base, "local-asr-unpack").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        dir.parentFile?.deleteRecursively()
    }

    /** 稀疏文件：长度到位但不真写盘（与真机「大小够了」等价） */
    private fun sparse(f: File, bytes: Long): File = f.apply {
        parentFile?.mkdirs()
        RandomAccessFile(this, "rw").use { it.setLength(bytes) }
    }

    private fun smallTokens(f: File = File(dir, LocalAsrModel.TOKENS_FILE)): File =
        f.apply { writeBytes(ByteArray(64)) }

    private fun vadBytes() = LocalAsrModel.VAD_MIN_BYTES + 1
    private fun modelBytes() = LocalAsrModel.MODEL_MIN_BYTES + 1

    @Test
    fun `体积完整的part可救_改名即就绪`() {
        sparse(File(dir, LocalAsrModel.MODEL_FILE + ".part"), modelBytes())
        smallTokens()
        val can = LocalAsrModel.canRepairIn(dir, unpack, ready = false)
        assertTrue("完整的 .part 必须判为可救（省掉一次 163MB 重下）", can)
        assertTrue("改名后应就绪", LocalAsrModel.repairIn(dir, unpack))
        assertTrue("改名后 model 必须在位", File(dir, LocalAsrModel.MODEL_FILE).length() > LocalAsrModel.MODEL_MIN_BYTES)
        assertFalse("part 名不该留下", File(dir, LocalAsrModel.MODEL_FILE + ".part").exists())
    }

    @Test
    fun `体积不足的part不可救_且自愈时清掉`() {
        sparse(File(dir, LocalAsrModel.MODEL_FILE + ".part"), 1024)
        smallTokens()
        assertFalse("半截 .part 不该判可救", LocalAsrModel.canRepairIn(dir, unpack, ready = false))
        LocalAsrModel.repairIn(dir, unpack)
        assertFalse("自愈要顺手清掉没用的半截件", File(dir, LocalAsrModel.MODEL_FILE + ".part").exists())
    }

    @Test
    fun `解包目录完整件可救_自愈会补入`() {
        sparse(File(unpack, LocalAsrModel.MODEL_FILE), modelBytes())
        smallTokens(File(unpack, LocalAsrModel.TOKENS_FILE))
        assertTrue("解包目录有完整件就该判可救", LocalAsrModel.canRepairIn(dir, unpack, ready = false))
        assertTrue(LocalAsrModel.repairIn(dir, unpack))
        assertTrue("model 必须补进模型目录", File(dir, LocalAsrModel.MODEL_FILE).isFile)
        assertTrue("tokens 必须补进模型目录", File(dir, LocalAsrModel.TOKENS_FILE).length() > 0)
    }

    @Test
    fun `已就绪时绝不判可救也不动文件`() {
        sparse(File(dir, LocalAsrModel.MODEL_FILE), modelBytes())
        smallTokens()
        val before = File(dir, LocalAsrModel.MODEL_FILE).lastModified()
        assertFalse("就绪后不该再判可救", LocalAsrModel.canRepairIn(dir, unpack, ready = true))
        assertTrue("已就绪直接返回真", LocalAsrModel.repairIn(dir, unpack))
        assertEquals("不许碰已就绪的模型", before, File(dir, LocalAsrModel.MODEL_FILE).lastModified())
    }

    @Test
    fun `什么都没得救时返回假`() {
        assertFalse("空目录没有可救的东西", LocalAsrModel.canRepairIn(dir, unpack, ready = false))
        assertFalse("空目录自愈后仍不就绪", LocalAsrModel.repairIn(dir, unpack))
    }

    @Test
    fun `自愈会补VAD但VAD缺失不影响就绪`() {
        sparse(File(dir, LocalAsrModel.MODEL_FILE), modelBytes())
        smallTokens()
        assertTrue("没有 VAD 也算就绪（VAD 是增强件）", LocalAsrModel.auditDir(dir).ready)
        sparse(File(unpack, LocalAsrModel.VAD_FILE), vadBytes())
        LocalAsrModel.repairIn(dir, unpack)
        assertTrue("VAD 在解包目录时也要补进来", File(dir, LocalAsrModel.VAD_FILE).length() > LocalAsrModel.VAD_MIN_BYTES)
    }
}
