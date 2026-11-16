package com.jinn.inputmethod

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File

/**
 * 「离线语音」页：设置页里的语音入口点进来。
 *
 * 2026-11-17 定版后这一页只做一件事 —— 把**内置离线识别**跑起来：
 *  总开关 → 麦克风授权 → 模型下载 / 导入 / 删除 → 语言与默认键盘 → 语音测试。
 *
 * 历史：这一页原本还挂着八种在线后端（自建 CapsWriter、讯飞、OpenAI 兼容、阿里百炼、腾讯、
 * Azure、百度、Deepgram）的各家凭据卡、地址策略校验、`CredentialMask` 掩码回显与
 * `VoiceProviders` 文档目录。在线语音整体删除后那些控件页面上早已不可见，
 * 代码却仍是「用空壳 View 喂给保存逻辑」的死路径（三个编译错误就是这么来的），
 * 所以这里连同空壳一起删除：**界面里没有的字段，代码里也不该还留着写盘入口**。
 *
 * 行为约定：
 *  - 所有可见项都是**改动即写**（总开关、两个下拉），没有文本输入框，因此没有「未保存退出」确认；
 *  - 「重启输入法」按钮只负责重启 IME 进程让语音组件重新装载（[ImeRestart]）；
 *  - 模型下载有看门狗：进度与阶段 [DOWNLOAD_STALL_MS] 不动就停掉，把按钮还给用户；
 *  - 「语音测试」是自包含诊断（[VoiceSelfTest]），不碰 IME 的 asr / recorder，总开关关着也能跑。
 */
class VoiceSettingsActivity : ComponentActivity() {

    private lateinit var prefs: Prefs

    private lateinit var switchVoiceInput: Switch
    private lateinit var cardMicPermission: View
    private lateinit var cardVoiceServer: View
    private lateinit var cardLocalAsr: View
    private lateinit var textLocalAsrDesc: TextView
    private lateinit var textLocalAsrStatus: TextView
    private lateinit var progressLocalAsr: ProgressBar
    private lateinit var btnLocalAsrDownload: Button
    private lateinit var btnLocalAsrDelete: Button
    private lateinit var btnLocalAsrImport: Button
    private lateinit var textLocalAsrLog: TextView
    private lateinit var textMicState: TextView
    private lateinit var btnGrant: Button
    private lateinit var spinnerLanguage: UserAwareSpinner
    private lateinit var spinnerDefaultMode: UserAwareSpinner
    private lateinit var textSaveHint: TextView
    private lateinit var btnSave: Button
    private lateinit var editVoiceTest: EditText
    private lateinit var btnVoiceTest: Button
    private lateinit var textVoiceTestResult: TextView

    /** 最近一次下载进度（0..100）。无参刷新不能把它抹成 0：那会让进度条永远停在 0% */
    private var lastDownloadPct = 0

    /** 用户在本次进程里主动点过「取消下载」：别在他下次进页面时又自动开一次 */
    private var localAsrUserCancelled = false

    /** 卡死看门狗：进度与阶段连续 [DOWNLOAD_STALL_MS] 不动就停掉，把按钮还给用户 */
    private var downloadWatchKey = ""
    private var downloadWatchSince = 0L
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val downloadWatchdog = object : Runnable {
        override fun run() {
            if (!LocalAsrDownloader.isRunning()) {
                downloadWatchKey = ""
                downloadWatchSince = 0L
                return
            }
            val key = "${maxOf(lastDownloadPct, LocalAsrDownloader.lastProgress)}|" +
                LocalAsrDownloader.lastStage.orEmpty()
            val now = System.currentTimeMillis()
            if (key != downloadWatchKey) {
                downloadWatchKey = key
                downloadWatchSince = now
                refreshLocalAsrStatus()
            } else if (downloadWatchSince > 0 && now - downloadWatchSince > DOWNLOAD_STALL_MS) {
                val stage = LocalAsrDownloader.lastStage ?: "-"
                Diagnostics.w(TAG, "离线语音：下载 ${DOWNLOAD_STALL_MS / 1000}s 无进展（$stage），自动停止")
                LocalAsrDownloader.cancel()
                downloadWatchKey = ""
                downloadWatchSince = 0L
                textLocalAsrStatus.text =
                    "下载卡住了（${DOWNLOAD_STALL_MS / 1000} 秒没有进展，停在：$stage），已停止。\n" +
                        "再点一次「${TEXT_LOCAL_ASR_DOWNLOAD}」重试；也可以在电脑上下好模型再用" +
                        "「${TEXT_LOCAL_ASR_IMPORT}」。"
                refreshLocalAsrStatus()
            }
            uiHandler.postDelayed(this, 3_000L)
        }
    }

    /** 定时换色的准点定时器（见 [ThemeManager.ScheduledThemeTicker]） */
    private val themeTicker by lazy { ThemeManager.scheduledRebuildTicker(this) }

    /** 正在跑的语音测试（`onDestroy` 必须取消它：否则漏线程与麦克风占用） */
    private var voiceSelfTest: VoiceSelfTest? = null

    /** 麦克风被永久拒绝（拒绝后系统不再弹窗）：唯一出路是系统应用详情页 */
    private var micDeniedForever = false

    /** 本页是否已发起过权限请求（比 `shouldShowRequestPermissionRationale` 可靠的判据） */
    private var micRequested = false

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) micRequested = false
        micDeniedForever = !granted &&
            !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        refreshMicState()
        if (granted && ::textVoiceTestResult.isInitialized &&
            textVoiceTestResult.text.toString() == TEXT_TEST_NEED_PERMISSION
        ) {
            textVoiceTestResult.text = TEXT_TEST_PERMISSION_OK
        }
    }

    /** 主题应用点：必须早于 `onCreate` 与任何资源解析 */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemeManager.themedContext(newBase, Prefs(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 本页有「语音测试」录到的真实话音：防截屏 / 录屏 / 最近任务预览
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_voice_settings)
        prefs = Prefs(this)
        Diagnostics.i(TAG, "onCreate: 离线语音页启动")

        findViewById<TextView>(R.id.text_voice_title).text = TEXT_TITLE
        findViewById<Button>(R.id.btn_voice_close).apply {
            text = PageChrome.CLOSE
            contentDescription = PageChrome.CLOSE_DESC
            setOnClickListener { finish() }
        }
        // 所有可见项都是改动即写，退出没有可丢的东西：返回键直接走 finish
        onBackPressedDispatcher.addCallback(this) { finish() }

        findViewById<TextView>(R.id.text_voice_master_title).text = TEXT_VOICE_MASTER_TITLE
        findViewById<TextView>(R.id.label_voice_test).text = TEXT_SECTION_TEST

        textSaveHint = findViewById(R.id.text_voice_save_hint)
        // 保存结果会变（正在重启 / 落盘失败），读屏要能听见
        textSaveHint.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        textSaveHint.text = TEXT_SAVE_HINT_IDLE
        btnSave = findViewById<Button>(R.id.btn_voice_save).apply {
            text = TEXT_SAVE
            setOnClickListener { restartIme() }
        }

        switchVoiceInput = findViewById(R.id.switch_voice_input)
        cardMicPermission = findViewById(R.id.card_mic_permission)
        cardVoiceServer = findViewById(R.id.card_voice_server)
        cardLocalAsr = findViewById(R.id.card_local_asr)
        textLocalAsrDesc = findViewById(R.id.text_local_asr_desc)
        textLocalAsrStatus = findViewById(R.id.text_local_asr_status)
        progressLocalAsr = findViewById(R.id.progress_local_asr)
        btnLocalAsrDownload = findViewById(R.id.btn_local_asr_download)
        btnLocalAsrDelete = findViewById(R.id.btn_local_asr_delete)
        btnLocalAsrDelete.text = TEXT_LOCAL_ASR_DELETE
        btnLocalAsrImport = findViewById(R.id.btn_local_asr_import)
        btnLocalAsrImport.text = TEXT_LOCAL_ASR_IMPORT
        textLocalAsrLog = findViewById(R.id.text_local_asr_log)
        textMicState = findViewById(R.id.text_mic_state)
        btnGrant = findViewById(R.id.btn_grant)
        spinnerLanguage = findViewById(R.id.spinner_language)
        spinnerDefaultMode = findViewById(R.id.spinner_default_mode)

        editVoiceTest = findViewById(R.id.edit_voice_test)
        btnVoiceTest = findViewById(R.id.btn_voice_test)
        textVoiceTestResult = findViewById(R.id.text_voice_test_result)
        editVoiceTest.hint = TEXT_TEST_HINT
        btnVoiceTest.text = TEXT_TEST_BUTTON
        textVoiceTestResult.text = TEXT_TEST_IDLE
        // 结论会变（测试中… → 成功 / 失败），读屏要能听见
        textVoiceTestResult.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        btnVoiceTest.setOnClickListener { startVoiceTest() }

        spinnerLanguage.adapter = ArrayAdapter.createFromResource(
            this, R.array.language_entries, R.layout.item_spinner,
        ).also { it.setDropDownViewResource(R.layout.item_spinner_dropdown) }
        spinnerDefaultMode.adapter = ArrayAdapter.createFromResource(
            this, R.array.default_mode_entries, R.layout.item_spinner,
        ).also { it.setDropDownViewResource(R.layout.item_spinner_dropdown) }
        spinnerDefaultMode.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long,
            ) {
                // 初始化 setSelection（以及恢复实例状态）都会回调这里。若 prefs 里的值不在
                // values 中（配置损坏 / 新增了模式），indexOf 会退回第 0 项，
                // 未加保护就会把用户的默认键盘静默改成第 0 项（闸门见 [UserAwareSpinner]）。
                if (!spinnerDefaultMode.userInteracted) return
                val values = resources.getStringArray(R.array.default_mode_values)
                val mode = values.getOrNull(position)?.toIntOrNull() ?: DefaultKeyboardMode.VOICE
                if (mode != prefs.defaultKeyboardMode) {
                    prefs.defaultKeyboardMode = mode
                    Diagnostics.i(TAG, "默认键盘模式: $mode")
                }
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        spinnerLanguage.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long,
            ) {
                // 不闸 userInteracted：重建恢复只能走这条回调（`AbsSpinner` 在第一次 layout 才落地），
                // 「不许改写导入的未知取值」的闸门留在 [readLanguage]。
                prefs.language = readLanguage()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // 本机离线卡片：一个按钮三种语义（下载 / 取消 / 重下），删除只在就绪时出现
        btnLocalAsrDownload.setOnClickListener { onLocalAsrDownloadClicked() }
        btnLocalAsrDelete.setOnClickListener { onLocalAsrDeleteClicked() }
        btnLocalAsrImport.setOnClickListener { importLauncher.launch(arrayOf("*/*")) }
        btnGrant.setOnClickListener { requestMic() }

        val values = resources.getStringArray(R.array.language_values)
        languageAtLoad = values.indexOf(prefs.language).coerceAtLeast(0)
        spinnerLanguage.setSelection(languageAtLoad)
        val modeValues = resources.getStringArray(R.array.default_mode_values)
        spinnerDefaultMode.setSelection(
            modeValues.indexOf(prefs.defaultKeyboardMode.toString()).coerceAtLeast(0),
        )

        // 总开关：关闭时语音在 IME 侧完全沉寂，两张卡整块隐藏（改动即写）
        switchVoiceInput.isChecked = prefs.voiceInputEnabled
        applyVoiceBlocksVisibility(prefs.voiceInputEnabled)
        switchVoiceInput.setOnCheckedChangeListener { _, checked ->
            prefs.voiceInputEnabled = checked
            applyVoiceBlocksVisibility(checked)
        }

        refreshLocalAsrStatus()
        refreshMicState()
        // 进页面就自动开始下载（沙鸥一直是这个行为）
        autoDownloadLocalAsrOnce()
        // 已有任务在跑（典型是 IME 启动时自己发起的）：本页没有它的进度回调，
        // 靠看门狗每 3 秒刷新一次，否则页面永远停在 0%
        if (LocalAsrDownloader.isRunning()) {
            uiHandler.removeCallbacks(downloadWatchdog)
            uiHandler.postDelayed(downloadWatchdog, 3_000L)
        }
        // 清掉上次被强杀 / 崩溃留下的下载临时件（归档 + 解包目录）。只碰 cacheDir，不动已下好的模型
        Thread { LocalAsrDownloader.sweepCache(applicationContext) }
            .apply { name = "local-asr-sweep"; isDaemon = true }.start()
    }

    override fun onStart() {
        super.onStart()
        themeTicker.start()
    }

    override fun onStop() {
        super.onStop()
        themeTicker.stop()
    }

    /** 从系统设置授权麦克风后返回：页面不能还显示「未授权」 */
    override fun onResume() {
        super.onResume()
        refreshMicState()
    }

    /** 总开关 → 卡片显隐（本机离线是唯一后端，这一页不存在「只有在线配置」的状态） */
    private fun applyVoiceBlocksVisibility(voiceEnabled: Boolean) {
        val visibility = if (voiceEnabled) View.VISIBLE else View.GONE
        cardMicPermission.visibility = visibility
        cardVoiceServer.visibility = visibility
        cardLocalAsr.visibility = visibility
        if (voiceEnabled) refreshLocalAsrStatus()
    }

    /**
     * 本页第一次打开时，模型没装就自动开始下载（用户 2026-11-15：「应该安装以后自动下载模型」）。
     *
     * 只在没有现成文件、也没有在跑的任务时触发；用户在本次进程里点过取消就不再自动开。
     * 先自愈再谈重下：常见残局是「模型件已躺在缓存解包目录，或只差最后一次改名」。
     */
    private fun autoDownloadLocalAsrOnce() {
        if (!prefs.voiceInputEnabled) return
        // 先把随包的两件小件落到位：这样「模型未下载」的判定只剩 239MB 本件一条原因
        runCatching { LocalAsrModel.ensureBundled(this) }
        if (LocalAsrModel.isReady(this) || LocalAsrDownloader.isRunning()) return
        if (localAsrUserCancelled) {
            Diagnostics.i(TAG, "离线语音：本次会话已取消过下载，不再自动开始")
            return
        }
        // 模型本件已齐、只缺分句模型：补 640KB 就够，绝不重下 163MB
        if (LocalAsrModel.canRecognize(this) && !LocalAsrModel.hasVad(this)) {
            Diagnostics.i(TAG, "离线语音：只缺分句模型，补下 640KB")
            LocalAsrDownloader.ensureVad(applicationContext)
            refreshLocalAsrStatus()
            return
        }
        if (LocalAsrModel.canRepair(this)) {
            Diagnostics.i(TAG, "离线语音：发现可自愈的模型残留，先修复")
            textLocalAsrStatus.text = "正在修复模型残留…"
            Thread {
                val fixed = LocalAsrModel.repair(applicationContext)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    refreshLocalAsrStatus()
                    if (!fixed) startLocalAsrDownload()
                }
            }.start()
            return
        }
        Diagnostics.i(TAG, "离线语音：模型缺失，自动开始下载")
        startLocalAsrDownload()
    }

    /**
     * 「本机离线」卡片的状态行 / 按钮态：未下载 = 下载；下载中 = 取消；已就绪 = 删除。
     * 进度走同一条状态行 + 进度条，不弹对话框（全屏卡片，弹窗会挡住说明文案）。
     */
    private fun refreshLocalAsrStatus(progress: Int? = null) {
        if (::textLocalAsrStatus.isInitialized.not()) return
        val ready = LocalAsrModel.isReady(this)
        val used = LocalAsrModel.usedBytes(this)
        val running = LocalAsrDownloader.isRunning()
        textLocalAsrDesc.text = TEXT_LOCAL_ASR_DESC
        when {
            running -> {
                // 无参调用不能把进度抹回 0；同时读 lastProgress：任务可能是 IME 启动时发起的，
                // 本页没有它的回调，只认自己的值会一直停在 0%
                val p = progress ?: maxOf(lastDownloadPct, LocalAsrDownloader.lastProgress)
                lastDownloadPct = p
                val stage = LocalAsrDownloader.lastStage
                textLocalAsrStatus.text = if (stage.isNullOrBlank()) {
                    "正在下载模型… $p%（完成后自动解包写入本机）"
                } else {
                    "$stage\n已下载 $p%（完成后自动解包写入本机）"
                }
                progressLocalAsr.visibility = View.VISIBLE
                progressLocalAsr.progress = p
                btnLocalAsrDownload.text = TEXT_LOCAL_ASR_CANCEL
                btnLocalAsrDelete.visibility = View.GONE
            }

            !ready && LocalAsrModel.canRecognize(this) -> {
                // 模型与词表在位、只缺分句模型：语音能用，正在自动补那 640KB
                val usedMb = used / (1024 * 1024)
                textLocalAsrStatus.text =
                    "模型本件已就绪（占用 $usedMb MB），分句模型缺失，正在补下（约 640 KB）\n" +
                        "补齐前长句按整段识别，句间标点会少一些\n" +
                        LocalAsrModel.audit(this).line()
                progressLocalAsr.visibility = View.GONE
                btnLocalAsrDownload.text = TEXT_LOCAL_ASR_DOWNLOAD
                btnLocalAsrDelete.visibility = View.VISIBLE
            }

            ready -> {
                val vad = if (LocalAsrModel.hasVad(this)) {
                    "分句模型已就绪"
                } else {
                    "分句模型缺失（长句整段识别，不影响使用）"
                }
                // 输入法没启用时语音键根本不会被调用 —— 这是「模型明明在、语音用不了」
                // 最常见的真因（2026-11-16 诊断日志: enabled=false ready=false）
                val imeHint = if (isImeEnabledHere()) {
                    ""
                } else {
                    "\n注意：输入法当前未启用（或未被选为当前输入法），语音键不会工作"
                }
                textLocalAsrStatus.text =
                    "模型已就绪（占用 ${used / (1024 * 1024)} MB）\n$vad\n" +
                        "存放在应用数据目录，清理缓存不会删；只有卸载或点「删除模型」才会消失\n" +
                        storageSelfCheckLine() + imeHint
                progressLocalAsr.visibility = View.GONE
                btnLocalAsrDownload.text = TEXT_LOCAL_ASR_DOWNLOAD
                btnLocalAsrDelete.visibility = View.VISIBLE
            }

            else -> {
                val fail = LocalAsrDownloader.lastFailure
                // 状态行附上体检事实：真机上出现过「系统存储说有 240MB、这一行说未下载」，
                // 只有把「哪个文件多大 / 缺哪个」写出来，用户截图一眼就能定位
                textLocalAsrStatus.text =
                    (if (fail.isNullOrBlank()) {
                        "模型未下载（当前占用 ${used / (1024 * 1024)} MB）"
                    } else {
                        "模型未下载（当前占用 ${used / (1024 * 1024)} MB）\n上次失败：$fail"
                    }) + "\n" + LocalAsrModel.audit(this).line()
                progressLocalAsr.visibility = View.GONE
                btnLocalAsrDownload.text = TEXT_LOCAL_ASR_DOWNLOAD
                btnLocalAsrDelete.visibility = View.GONE
            }
        }
        // 日志尾部直接摆在卡片里：用户「没反应」时截一张图，就能看出卡在哪个源、
        // 是 DNS、403、还是校验不过。就绪后也留着上一段日志（用户反馈「下载完日志就没了」）
        val tail = LocalAsrDownloader.logTail(this, if (ready) 4 else 12)
        if (tail.isEmpty()) {
            textLocalAsrLog.visibility = View.GONE
        } else {
            textLocalAsrLog.visibility = View.VISIBLE
            textLocalAsrLog.text = tail
        }
    }

    /** 输入法本机是否已启用（与设置页「启用」按钮同一判据） */
    private fun isImeEnabledHere(): Boolean {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            ?: return false
        return imm.enabledInputMethodList.any { it.packageName == packageName }
    }

    /**
     * 存储自检行：模型目录的真实路径 + cache 里下载临时件的真实大小。
     *
     * 用户反馈「装完模型后 用户数据 248M、缓存 240M」—— 系统「应用信息」的两个笼统数字
     * 说不清是哪个目录被算进了缓存。这一行让 App 自己报出事实。
     */
    private fun storageSelfCheckLine(): String {
        val modelDir = LocalAsrModel.dir(this)
        val modelBytes = modelDir.listFiles()?.sumOf { it.length() } ?: 0L
        val junk = runCatching {
            val names = listOf(LocalAsrModel.ARCHIVE_NAME, "vad-download.onnx", "local-asr-unpack")
            names.sumOf { n ->
                val f = File(cacheDir, n)
                if (!f.exists()) 0L
                else if (f.isDirectory) f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                else f.length()
            }
        }.getOrDefault(0L)
        val m = modelBytes / (1024 * 1024)
        val j = junk / (1024 * 1024)
        return "模型目录 files/${modelDir.name}（$m MB）\n" +
            "缓存临时件 ${if (j == 0L) "已清空" else "残留 $j MB"}"
    }

    /** 下载按钮：未就绪 = 开下；下载中 = 取消 */
    private fun onLocalAsrDownloadClicked() {
        if (LocalAsrDownloader.isRunning()) {
            LocalAsrDownloader.log(this, "用户点了取消")
            localAsrUserCancelled = true
            LocalAsrDownloader.cancel()
            refreshLocalAsrStatus()
            return
        }
        localAsrUserCancelled = false
        // 先在本帧把「已开始」写到界面上：网络请求是异步的，弱网里按钮和文字都不动
        // 观感就是「点了没反应」
        LocalAsrDownloader.log(this, "用户点了下载")
        textLocalAsrStatus.text = "已开始下载，正在准备连接…"
        progressLocalAsr.visibility = View.VISIBLE
        progressLocalAsr.progress = 0
        lastDownloadPct = 0
        btnLocalAsrDownload.text = TEXT_LOCAL_ASR_CANCEL
        startLocalAsrDownload()
    }

    /**
     * 真正发起一次下载（按钮与「打开本页自动下载」共用这一段）。
     *
     * 用 applicationContext：用户可能下载到一半就退出页面，模型仍应下完（也不把 Activity 拖住）。
     */
    private fun startLocalAsrDownload() {
        // 只缺分句模型时补 640KB，不重下整包（用户点「下载」不该等 163MB）
        if (LocalAsrModel.canRecognize(this) && !LocalAsrModel.hasVad(this)) {
            Diagnostics.i(TAG, "离线语音：点击下载，但只缺分句模型 → 只补 VAD")
            LocalAsrDownloader.ensureVad(applicationContext)
            refreshLocalAsrStatus()
            return
        }
        refreshLocalAsrStatus(lastDownloadPct)
        // 看门狗：起跑就排上（自己在「不在跑」时静默退出并停止续排）
        downloadWatchKey = ""
        downloadWatchSince = 0L
        uiHandler.removeCallbacks(downloadWatchdog)
        uiHandler.postDelayed(downloadWatchdog, 3_000L)
        LocalAsrDownloader.download(
            context = applicationContext,
            onProgress = { pct -> runOnUiThread { if (!isFinishing && !isDestroyed) refreshLocalAsrStatus(pct) } },
            onDone = { ok, reason ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    refreshLocalAsrStatus(if (ok) 100 else 0)
                    if (!ok) textLocalAsrStatus.text = reason ?: "下载失败"
                }
            },
            onStage = { stage ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    textLocalAsrStatus.text = stage
                    btnLocalAsrDownload.text = TEXT_LOCAL_ASR_CANCEL
                    // 阶段变化时同步刷新日志尾部：卡在建连时进度回调不会来，
                    // 只有这里能把「卡在第几个源」显示出来
                    val tail = LocalAsrDownloader.logTail(this)
                    if (tail.isNotEmpty()) {
                        textLocalAsrLog.visibility = View.VISIBLE
                        textLocalAsrLog.text = tail
                    }
                }
            },
        )
        // download() 一进来就把任务标记成「在跑」，这里再刷一次，按钮立刻变「取消下载」
        refreshLocalAsrStatus(lastDownloadPct)
    }

    /**
     * 本地导入选择器：一次可选多个文件。
     *
     * 为什么留这条路径：模型放在 GitHub 发行页，部分网络直连和加速镜像都拉不动。
     * 在电脑上下好、拷进手机再导入，是唯一不受手机网络影响的通路。
     * 按文件名归位：含 token → tokens.txt；含 vad → silero_vad.onnx；其余 .onnx → model.int8.onnx。
     */
    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> if (uris.isNotEmpty()) importModelFiles(uris) }

    private fun importModelFiles(uris: List<Uri>) {
        textLocalAsrStatus.text = "正在导入所选文件…"
        Thread {
            var saved = 0
            var failed = 0
            for (uri in uris) {
                if (runCatching { copyModelFile(uri) }.getOrDefault(false)) saved++ else failed++
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                refreshLocalAsrStatus(0)
                val ready = LocalAsrModel.isReady(this)
                textLocalAsrStatus.text = if (ready) {
                    "已导入 $saved 个文件，模型已就绪（占用 ${LocalAsrModel.usedBytes(this) / (1024 * 1024)} MB）"
                } else {
                    "已导入 $saved 个文件，还缺必需文件（model.int8.onnx 与 tokens.txt 两个都要有）" +
                        if (failed > 0) "，$failed 个文件读不了" else ""
                }
            }
        }.start()
    }

    /** 把一个 SAF 文件按文件名归位到模型目录 */
    private fun copyModelFile(uri: Uri): Boolean {
        val raw = uri.lastPathSegment?.substringAfterLast('/')?.lowercase() ?: ""
        val target = when {
            raw.contains("token") -> LocalAsrModel.TOKENS_FILE
            raw.contains("vad") -> LocalAsrModel.VAD_FILE
            raw.endsWith(".onnx") -> LocalAsrModel.MODEL_FILE
            else -> return false
        }
        val file = File(LocalAsrModel.dir(this), target)
        contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { out -> input.copyTo(out) }
        } ?: return false
        return file.length() > 0
    }

    /** 删除按钮：只删本机模型目录，不动任何偏好 */
    private fun onLocalAsrDeleteClicked() {
        LocalAsrDownloader.delete(this)
        refreshLocalAsrStatus(0)
    }

    // ── 语言 ────────────────────────────────────────────────────

    /**
     * 下拉当前选中的语言。
     *
     * 「用户没碰过」的判据是**下标仍等于载入时那个**，不是只看 `userInteracted`：旋转 / 进程回收后
     * 系统把下标放回来时探针仍是 false（程序化恢复不算用户操作），只看探针会把导入的未知取值
     * `indexOf` 退到第 0 项而静默改写（BUG.md L-821）。
     */
    private fun readLanguage(): String {
        val values = resources.getStringArray(R.array.language_values)
        val pos = spinnerLanguage.selectedItemPosition.coerceIn(0, values.lastIndex)
        if (!spinnerLanguage.userInteracted && pos == languageAtLoad) return prefs.language
        return values[pos]
    }

    /** [readLanguage] 的对照下标：进入页面时 `Prefs` 里的语言落在第几项 */
    private var languageAtLoad = 0

    // ── 麦克风授权 ──────────────────────────────────────────────

    private fun requestMic() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            refreshMicState()
            return
        }
        // 已请求过仍未授予（弹窗不会再出现）或已确认永久拒绝 → 只能去应用详情页手动开
        if (micRequested || micDeniedForever) {
            openAppPermissionSettings()
            return
        }
        micRequested = true
        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    /** 跳系统应用详情页：永久拒绝后唯一能重新授权麦克风的地方 */
    private fun openAppPermissionSettings() {
        runCatching {
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null),
                )
            )
        }.onFailure { Diagnostics.w(TAG, "打开应用详情页失败: ${it.message}") }
    }

    private fun refreshMicState() {
        val granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        // 点过一次仍未授权时按钮改为「前往系统设置」：本页再申请不会弹窗
        val text = getString(
            when {
                granted -> R.string.settings_mic_granted
                micRequested || micDeniedForever -> R.string.settings_mic_open_settings
                else -> R.string.settings_grant_mic
            }
        )
        textMicState.text = text
        btnGrant.text = text
        btnGrant.isEnabled = !granted
    }

    // ── 语音测试（自包含，不改动 IME 的语音状态） ────────────────────

    /**
     * 点「语音测试」：一次 3 秒的真实识别 —— 结论写进结论行，识别正文写进多行输入框。
     *
     * 顺序固定为「权限 → 可用性 → 真跑」：没授权就走本页已有的授权入口（弹窗 / 永久拒绝后
     * 转系统设置）；总开关关着也照跑，测试是诊断手段（结论里会注明）。
     */
    private fun startVoiceTest() {
        if (!btnVoiceTest.isEnabled) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            textVoiceTestResult.text = TEXT_TEST_NEED_PERMISSION
            requestMic()
            return
        }
        btnVoiceTest.isEnabled = false
        textVoiceTestResult.text = TEXT_TEST_RUNNING
        Diagnostics.i(TAG, "startVoiceTest: 开始")
        // 上一轮万一还在跑（极快连点）：先收掉，避免两路采集抢同一个麦克风
        voiceSelfTest?.cancel()
        val test = VoiceSelfTest(prefs) { result -> onVoiceTestFinished(result) }
        voiceSelfTest = test
        test.start()
    }

    /** 测试结束（主线程回调）：正文落框、结论落行、按钮恢复 */
    private fun onVoiceTestFinished(result: VoiceSelfTest.Result) {
        voiceSelfTest = null
        if (isFinishing || isDestroyed) return
        // 失败结论不带正文：此时别把用户自己打进框里的样例句清掉（成功才覆盖）
        if (result.text.isNotEmpty()) editVoiceTest.setText(result.text)
        textVoiceTestResult.text = result.verdict
        btnVoiceTest.isEnabled = true
        // 只记结论与长度：识别正文是用户说的话，不进日志
        Diagnostics.i(TAG, "语音测试结论: ${result.verdict} 正文长度=${result.text.length}")
    }

    /**
     * 页面销毁：必须收掉正在跑的测试 —— 它自带麦克风采集线程，
     * 漏掉就是「退出页面后麦克风仍被占用」或线程泄漏。
     */
    override fun onDestroy() {
        voiceSelfTest?.cancel()
        voiceSelfTest = null
        uiHandler.removeCallbacks(downloadWatchdog)
        super.onDestroy()
    }

    /**
     * 「重启输入法」按钮：语音组件在 IME 进程里，改了设置不重启不生效。
     *
     * 防连点：进来先看按钮是否已禁用，再立即禁用自己；`ImeRestart` 的 flush 失败回调
     * 要把按钮复位（进程没被杀就说明设置没落盘）。
     */
    private fun restartIme() {
        if (!btnSave.isEnabled) return
        btnSave.isEnabled = false
        if (ConfigBackupManager.importing) {
            textSaveHint.text = TEXT_SAVE_BLOCKED_IMPORTING
            btnSave.isEnabled = true
            return
        }
        textSaveHint.text = TEXT_RESTARTING
        // 本页没有文本输入框：可见项都是改动即写，这里只需把排队的 apply 确认落盘再杀进程
        ImeRestart.restart(this) { message ->
            textSaveHint.text = message
            btnSave.isEnabled = true
        }
    }

    /** 离开页面：确认此前 `apply()` 过的设置真正落盘 */
    override fun onPause() {
        super.onPause()
        if (prefs.flush()) {
            if (textSaveHint.text.toString() == TEXT_DIRTY) textSaveHint.text = TEXT_SAVE_HINT_IDLE
        } else {
            Diagnostics.w(TAG, "onPause: 配置落盘失败（改动可能回退）")
            Toast.makeText(this, TEXT_FLUSH_FAILED, Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        const val TAG = "VoiceSettings"

        /** 进度与阶段都不动的容忍上限：到点就停掉，把按钮还给用户（见 downloadWatchdog） */
        const val DOWNLOAD_STALL_MS = 60_000L

        /** 页面标题（= 设置页入口按钮上的那句） */
        const val TEXT_TITLE = "离线语音"

        /** 总开关卡片的标题（用户点名的那一句，逐字保留） */
        const val TEXT_VOICE_MASTER_TITLE = "启用离线语音（本机识别，不联网）"

        /** 本机离线识别：模型不随包，按需下载 */
        const val TEXT_LOCAL_ASR_DESC = "本机离线识别：不联网、不需要任何 Key。" +
            "首次使用要在本机下载 SenseVoice small（整句识别，约 163 MB 压缩包 / 落地 230 MB 级），" +
            "松手后一次性解码，长句会有零点几秒到一两秒的滞后。"
        const val TEXT_LOCAL_ASR_IMPORT = "从本地导入模型"
        const val TEXT_LOCAL_ASR_DOWNLOAD = "下载模型"
        const val TEXT_LOCAL_ASR_CANCEL = "取消下载"
        const val TEXT_LOCAL_ASR_DELETE = "删除模型"

        /** 顶部主按钮与状态行：改动即写，按钮只负责重启进程 */
        const val TEXT_SAVE = "重启输入法"
        const val TEXT_SAVE_HINT_IDLE = "改动即保存，重启输入法后生效"
        const val TEXT_RESTARTING = "正在重启输入法…"
        const val TEXT_DIRTY = "有改动尚未写入本机"

        /** 配置导入在途：不回写、不重启，避免覆盖导入线程刚写进 Prefs 的值 */
        const val TEXT_SAVE_BLOCKED_IMPORTING = "配置导入进行中，暂不可重启"

        /** onPause 的 commit() 失败：页面正离开，状态行看不见，只能给 Toast */
        const val TEXT_FLUSH_FAILED = "设置写入失败，请重试或点「重启输入法」"

        /** 语音测试卡（自带一组：一次 3 秒识别，结果直接给用户看有没有效果） */
        const val TEXT_SECTION_TEST = "语音测试"
        const val TEXT_TEST_BUTTON = "语音测试"
        const val TEXT_TEST_HINT = "点这里唤起键盘，或点下方「语音测试」"
        const val TEXT_TEST_IDLE = "点上方「语音测试」跑一次 3 秒识别，结果直接写进上面的框"
        const val TEXT_TEST_RUNNING = "正在录音,请对手机说话,3秒后自动停."
        const val TEXT_TEST_NEED_PERMISSION = "未授权：允许麦克风权限后再点一次「语音测试」"
        const val TEXT_TEST_PERMISSION_OK = "已授权，请再点一次「语音测试」"
    }
}
