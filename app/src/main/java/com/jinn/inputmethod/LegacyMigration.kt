package com.jinn.inputmethod

import android.content.Context
import java.io.File

/**
 * 旧包 → 新包的一次性数据迁移（`com.jinn.inputmethod` → `app.modis.phantom.inputmethod`）。
 *
 * 为什么需要它：换了 applicationId 之后，Android 把两个包当成两个应用 —— 旧包里的拼音偏好、
 * 剪贴板历史、模糊音/角标等设置全都留在了旧包的 `shared_prefs/jinn_inputmethod.xml` 里，
 * 更肉疼的是 163 MB 的离线语音模型（`filesDir/local-asr/`）也要重新下一遍。
 *
 * 迁移的边界，说清楚：
 *  - **只补缺，不覆盖**：新包里已经存在的键一律不动（用户在新包改过的设置优先）；
 *  - 旧包不存在（没装过 / 已卸载）就直接跳过，把标记落下，之后不再尝试；
 *  - 只搬 `jinn_inputmethod` 这一份 prefs 和 `local-asr/` 里的模型两件文件，
 *    不搬诊断日志、图库绑定目录这类和旧包环境强绑定的东西。
 *
 * 整个过程在主线程里跑一次（prefs 与模型都是小文件；模型命中时也只做同盘拷贝）。
 */
internal object LegacyMigration {

    /** 旧版精灵输入法的包名（本应用 fork 之前的 applicationId） */
    private const val LEGACY_PACKAGE = "com.jinn.inputmethod"

    /** 与 [Prefs] 里那份 SharedPreferences 同名 */
    private const val PREFS_NAME = "jinn_inputmethod"

    /** 迁移完成标记；写在**新包**的 prefs 里，名字带版本号以便日后换策略再迁一次 */
    private const val FLAG_KEY = "legacy_migration_v1"

    @Volatile
    private var attempted = false

    /**
     * 首次进入应用时调用（设置页 onCreate）。重复调用是空操作。
     *
     * @return 迁过来的偏好键数量；没迁移（已迁过 / 无旧包）返回 0
     */
    fun runOnce(context: Context): Int {
        if (attempted) return 0
        attempted = true
        val app = context.applicationContext
        // 调试时可能直接用旧包名装这个 fork：此时「迁移」就是自己搬自己，必须跳过
        if (app.packageName == LEGACY_PACKAGE) return 0

        val target = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (target.getBoolean(FLAG_KEY, false)) return 0

        var copiedKeys = 0
        var copiedModel = false
        try {
            val legacy = app.createPackageContext(LEGACY_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
            val source = legacy.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val editor = target.edit()
            for ((key, value) in source.all) {
                // 私钥、临时态和迁移标记本身不搬
                if (key == FLAG_KEY || value == null) continue
                if (target.contains(key)) continue
                when (value) {
                    is String -> editor.putString(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Set<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        val set = value as? Set<String> ?: continue
                        editor.putStringSet(key, set)
                    }
                    else -> continue
                }
                copiedKeys++
            }
            if (copiedKeys > 0) editor.commit()
            copiedModel = copyModel(app, legacy)
            Diagnostics.i(
                TAG,
                "旧包迁移: 偏好 $copiedKeys 键, 离线模型 ${if (copiedModel) "已搬" else "无/已就绪"}",
            )
        } catch (e: Exception) {
            // 旧包不存在（NameNotFound）或读取被拒：都当「没有可迁的东西」，不再重试
            Diagnostics.i(TAG, "旧包迁移: 跳过（${e.javaClass.simpleName}）")
        }
        target.edit().putBoolean(FLAG_KEY, true).commit()
        return copiedKeys
    }

    /** 把旧包的 `local-asr/` 两件文件搬过来；新包已就绪时不覆盖。 */
    private fun copyModel(app: Context, legacy: Context): Boolean {
        if (LocalAsrModel.isReady(app)) return false
        val sourceDir = File(legacy.filesDir, LocalAsrModel.DIR_NAME)
        if (!sourceDir.isDirectory) return false
        val targetDir = LocalAsrModel.dir(app)
        var copied = false
        for (name in listOf(LocalAsrModel.MODEL_FILE, LocalAsrModel.TOKENS_FILE)) {
            val from = File(sourceDir, name)
            if (!from.isFile || from.length() <= 0) continue
            val to = File(targetDir, name)
            if (to.isFile && to.length() == from.length()) continue
            runCatching { from.copyTo(to, overwrite = true) }.onSuccess { copied = true }
        }
        return copied
    }

    private const val TAG = "LegacyMigration"
}
