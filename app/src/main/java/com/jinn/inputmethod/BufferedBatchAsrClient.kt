package com.jinn.inputmethod

import android.os.Handler
import android.os.Looper
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 一次「整段上传」识别的结局（[BufferedBatchAsrClient.recognize] 的返回值）。
 *
 * 只区分「拿到文本」与「为什么没拿到」：空文本也是 [Ok]（链路通、只是没听到声音），
 * 失败原因必须是**能给用户看的那句话**（服务端 code / message、HTTP 状态码）。
 */
sealed class BatchOutcome {

    data class Ok(val text: String) : BatchOutcome()

    data class Fail(val reason: String) : BatchOutcome()
}

/**
 * 缓冲式批量识别基类（[SpeechClient] 的通用实现）：**本地缓冲整段音频 → 松手一次性上传 → 回包落字**。
 *
 * 覆盖的服务商都是「不收流式音频、一次请求换整段文本」那一类：OpenAI 兼容 `/audio/transcriptions`、
 * 腾讯云一句话识别、Azure 短音频识别 REST。三家的差别只在「请求怎么组、回包怎么解析」，
 * 状态机 / 缓冲 / WAV / 线程 / 取消 / 超时完全一样 —— 所以收敛在这里一份，子类只实现
 * [prepare]（校验配置）与 [recognize]（发请求 + 解析）。
 *
 * 对外契约与 [SpeechClient] 其余实现完全一致：[connect] / [beginTask] / [sendChunk] / [endTask] /
 * [cancelTask] / [close]，IME 侧的状态条、录音与落字链路一行都不用改。
 *
 * [connect] 因此只是**校验配置**并置 [LinkState.ONLINE]（配置齐了就算「连得上」，
 * 真连通性要等 [endTask] 才知道）；配置不齐当场落到状态条，不留到上传时才发现。
 *
 * 线程模型：音频转换在采集线程（纯 CPU、无锁）→ 入队；上传跑在 [uploads] 的后台线程；
 * 所有对外回调一律 `post` 回主线程。一次上传期间持有的 [Call] 记在 [call] 上，
 * [cancelTask] / [close] 直接 `cancel()` 打断在飞请求（不 cancel 的话松手取消后它还会跑完、
 * 白花一次配额）。
 *
 * ⚠ 凭据只从 [Prefs] 读，且**只进各自的鉴权头**：日志与异常文本里只有长度与状态码，
 * 绝不出现原文（异常正文可能回显请求，故 [describe] 只取 `message` 且截断）。
 */
abstract class BufferedBatchAsrClient(
    private val onStateCallback: (LinkState, String?) -> Unit,
    private val onResultCallback: (RecognitionMessage) -> Unit,
) : SpeechClient {

    @Volatile
    final override var state: LinkState = LinkState.IDLE
        private set

    /** 主线程回调口（上传在后台线程，回调统一切回主线程） */
    private val main = Handler(Looper.getMainLooper())

    private val idleRunnable = Runnable { onIdleCloseDue() }

    /** 本次缓冲的 PCM16（float32→PCM16 已转换，见 [sendChunk]） */
    private val pcm = PcmBuffer()

    /** 正在收音频的任务 */
    @Volatile
    private var taskId: String? = null

    /** 允许把结果落字的任务；取消 / 关闭后置空，晚到的回包按此丢弃 */
    @Volatile
    private var acceptingTask: String? = null

    @Volatile
    private var taskStartMs = 0L

    /** 缓冲溢出告警只打一次（每次 beginTask 复位）：避免采集线程每 100ms 刷一条 W */
    @Volatile
    private var overflowLogged = false

    /** 在飞的上传请求（取消的唯一抓手） */
    @Volatile
    private var call: Call? = null

    /** 日志 TAG（子类各自一个：排障时要能看出是哪家后端） */
    protected abstract val tag: String

    /** 一句话上传的通用失败文案（子类可覆盖） */
    protected open val textFailed: String get() = TEXT_FAILED

    /**
     * 校验配置并缓存本次要用的取值快照。
     *
     * @return null = 就绪（置 [LinkState.ONLINE]）；非 null = 当场落到状态条的原因
     *         （缺哪件、地址为什么不合法，都要能直接给用户看）
     */
    protected abstract fun prepare(): String?

    /**
     * 一次性识别：**跑在后台上传线程上**（阻塞可以）。
     *
     * @param wav 已经套好 44 字节 WAV 头的完整音频
     * @param pcm16 原始 PCM16（个别服务商要的是未加头的裸流长度）
     */
    protected abstract fun recognize(wav: ByteArray, pcm16: ByteArray): BatchOutcome

    override fun connect(force: Boolean, userInitiated: Boolean) {
        if (userInitiated) cancelIdleClose()
        if (!force && state == LinkState.ONLINE) return

        // 配置不齐当场落到状态条：拖到上传时才失败，用户已经白说了一段
        val bad = prepare()
        if (bad != null) {
            Diagnostics.w(tag, "connect: 配置未就绪")
            updateState(LinkState.OFFLINE, bad)
            return
        }
        updateState(LinkState.ONLINE, null)
    }

    override fun beginTask(): String? {
        cancelIdleClose()
        if (state != LinkState.ONLINE) {
            connect(force = true, userInitiated = true)
            if (state != LinkState.ONLINE) {
                Diagnostics.w(tag, "beginTask: 配置未就绪 state=$state")
                return null
            }
        }
        // 上一段没走完就再来一次：直接丢弃旧缓冲。**在途上传也可能存在**（`endTask` 已把 taskId
        // 交出去、上传还在排队/在飞）—— 换新 id 就让旧上传在闸门处自我了结，旧结果不再落字，
        // 语义是「新的覆盖旧的」（代价：那一次配额已经花掉）。
        synchronized(pcm) { pcm.reset() }
        val id = UUID.randomUUID().toString()
        taskId = id
        acceptingTask = id
        taskStartMs = System.currentTimeMillis()
        overflowLogged = false
        Diagnostics.i(tag, "beginTask: $id")
        return id
    }

    /**
     * float32 小端 → PCM16 小端（16k / 单声道）。
     *
     * 原先放在讯飞的协议工具类里（唯一使用点其实在这里），在线后端整体删除后搬回本类：
     * 采集端给 [MicRecorder] 的 float32，而模型 / WAV 都要 16bit。
     * **不乘增益**（音量交给系统的 `VOICE_RECOGNITION` 源），并饱和钳位到 [-1, 1]：
     * 越界浮点（部分机型偶发）不能回绕成反相爆音。
     */
    private fun float32ToPcm16(src: ByteArray): ByteArray {
        val samples = src.size / 4
        val out = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        val input = ByteBuffer.wrap(src).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until samples) {
            val v = input.getFloat()
            val pcm = if (v.isNaN()) {
                0
            } else {
                val clamped = v.coerceIn(-1f, 1f)
                val scaled = if (clamped >= 0f) {
                    (clamped * 32767f + 0.5f).toInt()
                } else {
                    (clamped * 32768f - 0.5f).toInt()
                }
                scaled.coerceIn(-32768, 32767)
            }
            out.putShort(pcm.toShort())
        }
        return out.array()
    }

    override fun sendChunk(pcmFloat32LittleEndian: ByteArray) {
        if (taskId == null) return
        // 转换在采集线程上做（无锁、纯 CPU），入队瞬时完成，不会卡住麦克风
        val pcm16 = float32ToPcm16(pcmFloat32LittleEndian)
        synchronized(pcm) {
            // 32 KB/s 无上限地攒最终是 OOM，而 OOM 死的是整个输入法进程；
            // 真到上限（收尾定时器没了、采集停不下来）宁可丢尾巴保进程。
            if (pcm.size() + pcm16.size > MAX_PCM_BYTES) {
                if (!overflowLogged) {
                    overflowLogged = true
                    Diagnostics.w(tag, "缓冲到上限($MAX_PCM_BYTES 字节≈310s)，后续音频丢弃")
                }
                return
            }
            pcm.write(pcm16)
        }
    }

    /** 当前缓冲的 PCM16 快照（部分识别用；返回副本，调用方可自由持有） */
    protected fun snapshotPcm16(): ByteArray = synchronized(pcm) { pcm.copy() }

    /**
     * 只取 [from] 之后的尾巴（长录音的部分识别用）：整段缓冲已有 2~10 MB，
     * 每解一次都复制整段毫无意义 —— 「边说边出」追加的只是新的那几秒。
     */
    protected fun snapshotPcm16(from: Int): ByteArray = synchronized(pcm) { pcm.copy(from, pcm.size()) }

    /** 当前缓冲的字节数：录音分段缓存按它算「还有多少新音频」 */
    protected fun pcmByteCount(): Int = synchronized(pcm) { pcm.size() }

    /** 是否仍在收音频：部分识别的循环按它决定继续还是退出 */
    protected fun isCapturing(): Boolean = taskId != null

    /**
     * 中途文本（「边说边出」）：与最终结果走同一个回调，`isFinal=false`。
     * 任务已作废（取消 / 换了新一段）时静默丢弃，不许把上一段的话写进当前输入框。
     */
    protected fun emitPartial(id: String, text: String, seconds: Double) {
        if (text.isEmpty()) return
        main.post {
            if (acceptingTask != id) return@post
            onResultCallback(
                RecognitionMessage(id, isFinal = false, duration = seconds, text = text),
            )
        }
    }

    override fun endTask(): Boolean {
        val id = taskId ?: return false
        taskId = null
        val data = synchronized(pcm) {
            val bytes = pcm.copy()
            pcm.reset()
            bytes
        }
        if (data.isEmpty()) {
            // 一个字节都没录到：上传一个空 WAV 只会换来 400，不如直接告诉调用方拿不到结果
            Diagnostics.w(tag, "endTask: 没有音频，放弃上传 taskId=$id")
            acceptingTask = null
            return false
        }
        submitUpload(id, data)
        Diagnostics.i(tag, "endTask: 提交上传 taskId=$id pcmBytes=${data.size}")
        return true
    }

    override fun cancelTask(): Boolean {
        val id = taskId
        val inFlight = call
        // `acceptingTask` 也算「有活可取消」：`endTask` 已经把 taskId 交出去、上传还在排队或
        // 在飞时若在这里退回 false，下面那道采纳闸门就不会关，晚到的结果照常落字 ——
        // 与 [cancelTask] 的语义（不上传、不发结果）相反。
        if (id == null && inFlight == null && acceptingTask == null) return false
        // 先关采纳闸门再 cancel：回包与 cancel 之间存在竞态，闸门是唯一可靠的判据
        taskId = null
        acceptingTask = null
        synchronized(pcm) { pcm.reset() }
        runCatching { inFlight?.cancel() }
            .onFailure { Diagnostics.w(tag, "cancelTask: 取消失败 ${it.javaClass.simpleName}") }
        Diagnostics.i(tag, "cancelTask: taskId=$id 缓冲已丢弃，在飞请求已取消")
        return true
    }

    override fun close() {
        cancelIdleClose()
        taskId = null
        acceptingTask = null
        synchronized(pcm) { pcm.reset() }
        val inFlight = call
        call = null
        runCatching { inFlight?.cancel() }
            .onFailure { Diagnostics.w(tag, "close: 取消失败 ${it.javaClass.simpleName}") }
        updateState(LinkState.IDLE, null)
        Diagnostics.i(tag, "close: 已释放（无长连接可断）")
    }

    /** 本实现没有连接要保活；排程到点只把**在线态**回落成 [LinkState.IDLE]（有在途任务、非在线态都跳过） */
    override fun scheduleIdleClose(delayMs: Long) {
        main.removeCallbacks(idleRunnable)
        main.postDelayed(idleRunnable, delayMs)
    }

    override fun cancelIdleClose() {
        main.removeCallbacks(idleRunnable)
    }

    private fun onIdleCloseDue() {
        if (taskId != null || acceptingTask != null || call != null) {
            Diagnostics.i(tag, "空闲关闭跳过：仍有在途任务")
            return
        }
        // 只把「在线但没人用」回落成 IDLE。**失败态不许回落**：`updateState(IDLE, null)` 会把
        // 上传失败刚写到状态条上的原因（`HTTP 401 …` / `token 获取失败：invalid_client`）换成
        // 「未连接」—— 用户看着看着提示就没了，诊断包里也只剩前半段（与另三家在服务端拒绝
        // 分支上撤空闲关闭同一条口径：这条兜底只该拆「没人用的在线状态」，不该改写结论）。
        if (state != LinkState.ONLINE) {
            Diagnostics.i(tag, "空闲关闭：当前不是在线态（$state），保留状态条上的原因")
            return
        }
        updateState(LinkState.IDLE, null)
    }

    /**
     * 子类在 [recognize] 里发请求的**唯一入口**：登记在飞请求（[cancelTask] / [close] 直接
     * `cancel()` 打断），返回状态码与正文原文。
     *
     * 网络异常照常抛出，由 [submitUpload] 的 `runCatching` 收口 —— 与组 WAV / 地址构造失败
     * 走同一条出口（异常逃到后台线程上没人接，界面就一直停在「识别中…」等兜底超时）。
     */
    protected fun exchange(request: Request): HttpReply {
        val current = http.newCall(request)
        call = current
        try {
            return current.execute().use { response ->
                HttpReply(response.code, response.body?.string())
            }
        } finally {
            // 只清自己那一条：中途换了新任务时 call 已被下一次上传接管
            if (call === current) call = null
        }
    }

    /** 组 WAV → 后台一次性上传 → 解析；判据只有「任务还是不是我们那个」 */
    private fun submitUpload(id: String, pcm16: ByteArray) {
        uploads.execute {
            if (acceptingTask != id) {
                Diagnostics.i(tag, "上传跳过：任务已作废 taskId=$id")
                return@execute
            }
            // 组 WAV 与建请求都会抛（OOM / 端口非法 / 地址里有 HttpUrl 不认的字符）：必须与网络失败走
            // 同一条出口。闸门关闭到子类发出请求之间也有窗口，那道闸在 [exchange] 登记后由
            // cancelTask 的 `cancel()` 兜住。
            val outcome = runCatching {
                val wav = WavWriter.wrap(pcm16)
                recognize(wav, pcm16)
            }.getOrElse { t -> BatchOutcome.Fail(describe(t)) }
            if (acceptingTask != id) {
                // 已取消 / 已关闭：结果丢弃，状态也不动（否则会把刚复位的状态条又推成 OFFLINE）
                Diagnostics.i(tag, "上传完成但任务已作废 taskId=$id")
                return@execute
            }
            val duration = (System.currentTimeMillis() - taskStartMs) / 1000.0
            // ⚠ 关闸门与投递必须**同在主线程那一个 Runnable 里**完成，且关闸前再判一次归属。
            // 原先在后台线程先 `acceptingTask = null` 再 `main.post`，两者之间被取消 / 新一段听写
            // 插进来时（主线程正忙），排队的那个 Runnable 照样会把**旧任务**的正文交上去：
            // `JinnIme.handleResult` 只比包名、不看 taskId，新一段正在录时旧文本会被 commit 进输入框，
            // 新结果随后追加 ⇒ 用户看到上一段的话重复出现。
            main.post {
                if (acceptingTask != id) {
                    Diagnostics.i(tag, "投递前任务已作废 taskId=$id")
                    return@post
                }
                acceptingTask = null
                when (outcome) {
                    is BatchOutcome.Ok -> {
                        Diagnostics.i(
                            tag,
                            "上传成功 textLen=${outcome.text.length} " +
                                "wavBytes=${pcm16.size + WavWriter.HEADER_BYTES}",
                        )
                        onResultCallback(
                            RecognitionMessage(id, isFinal = true, duration = duration, text = outcome.text),
                        )
                        if (state != LinkState.IDLE) updateState(LinkState.ONLINE, null)
                    }

                    is BatchOutcome.Fail -> {
                        // 先刮再落盘：失败原因可能整条来自回包正文（网关 / 反代回显请求地址时连
                        // `client_secret=` 一起回来）。状态条那条出口在 [updateState] 里刮，
                        // 日志这条会随诊断包外发，两个出口必须同一口径。
                        Diagnostics.w(tag, "上传失败 reason=${SpeechErrorText.scrub(outcome.reason)}")
                        updateState(LinkState.OFFLINE, outcome.reason)
                    }
                }
            }
        }
    }

    /**
     * 状态条文案的唯一出口：**任何来源**（异常正文 / 服务端回包原文 / 子类拼的句子）都过一遍
     * 凭据刮除再交出去。
     *
     * 为什么收在这里而不是各分支各刮一次：五家批量后端的失败原因大多来自**回包正文**
     * （`errorText(code, raw)` 直接取正文前 60 字），这类文案不过 `describe`，
     * 网关 / 反向代理的错误页把请求地址整条回显时，百度换 token 的 `client_secret=` 就这么上屏了。
     * 出口只有一个，漏一个分支就等于没刮。
     */
    private fun updateState(next: LinkState, detail: String?) {
        state = next
        onStateCallback(next, SpeechErrorText.scrub(detail))
    }

    /** 失败原因：网络异常按类型给中文，其余取服务端 / 子类给出的那句话（截断，不含凭据） */
    protected fun describe(t: Throwable?): String = when (t) {
        null -> textFailed
        is ConnectException -> "连不上语音服务"
        is SocketTimeoutException -> "上传超时"
        is UnknownHostException -> "地址无法解析"
        // 异常正文可能带请求 URL / 头（见 [SpeechErrorText]）：先刮凭据再截断，截断在后才不会
        // 把 `client_secret=` 这种键名留下、值被切在 60 字之后反而看不出来
        else -> SpeechErrorText.scrub(t.message)?.take(60) ?: textFailed
    }

    /**
     * 自管理的 PCM16 缓冲（原来用 [java.io.ByteArrayOutputStream]）。
     *
     * 换掉的唯一理由：分段缓存每 0.5~1 秒都要从「上一次读到的位置」往后取新音频，
     * 而 `ByteArrayOutputStream.toByteArray()` 每次都要把整段（最长 10 MB）复制一遍。
     * 自己管一个数组后，取一段只是 `copyOfRange`，代价与新增音频长度成正比。
     */
    protected class PcmBuffer {
        private var buf = ByteArray(1 shl 16)
        private var len = 0

        fun size(): Int = len

        fun reset() {
            len = 0
        }

        fun write(src: ByteArray) {
            val need = len + src.size
            if (need > buf.size) buf = buf.copyOf(maxOf(need, buf.size * 2))
            System.arraycopy(src, 0, buf, len, src.size)
            len = need
        }

        fun copy(): ByteArray = buf.copyOfRange(0, len)

        fun copy(from: Int, to: Int): ByteArray {
            val a = from.coerceIn(0, len)
            val b = to.coerceIn(a, len)
            return buf.copyOfRange(a, b)
        }
    }

    // internal（原 private）：缓冲上限与长语音秒数是「最后几句被吞」的判据，单测要直接钉住
    internal companion object {
        const val TEXT_FAILED = "识别失败"

        /**
         * 长语音键的单次上限（秒），与 [JinnIme.MAX_TOGGLE_MS] 同一口径
         */
        const val MAX_TOGGLE_SECONDS = 300

        /**
         * PCM16 缓冲上限（字节）。
         *
         * 2026-11-17 真机：「长语音说完点结束，最后几句话被吞掉」——根因就是这里原来写死
         * 60 秒（1,920,000 字节）：采集端超过 60 秒后整段丢新音频（`sendChunk` 直接 return），
         * 用户说了 90 秒，后 30 秒根本没进模型。原先写 60 秒的理由是「58 秒自动收尾走不到上限」，
         * 但到点收尾只是兜底、用户通常早就超过 60 秒。
         * 现在按 300 秒上限 + 10 秒余量：310 × 16000 × 2 ≈ 9.9 MB，IME 进程完全吃得住。
         */
        const val MAX_PCM_BYTES = 310 * 16_000 * 2

        /**
         * 上传线程池：**进程级共享**，守护线程。
         *
         * 每个客户端一个单线程池会在「关掉再打开语音面板」时反复新建线程；
         * 而 `close()` 里 shutdown 又会把同一条 IME 连接上的下一次上传打死。
         * 缓存池的空闲线程 60s 自动回收，页面 `onDestroy` 的 `cancel()` 只需要断开请求本身。
         */
        val uploads: ExecutorService = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "jinn-batch-asr-upload").apply { isDaemon = true }
        }

        /** 服务端在公网，连接 10s 足够；读完一整段文本按 [READ_TIMEOUT_SEC] 兜底 */
        const val CONNECT_TIMEOUT_SEC = 10L
        const val READ_TIMEOUT_SEC = 60L

        val http = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .writeTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()
    }
}

/** 一次 HTTP 往返的结果：状态码 + 正文原文（正文可能为 null，如 204） */
class HttpReply(val code: Int, val body: String?)
