package com.jinn.inputmethod

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * 录音电平 HUD：浮在键盘上的一条**波形**。
 *
 * 历史（2026-11-17 用户逐条反馈）：
 *  1. 「电平条不明显」→ 先在空格键 / 长语音键里各画一条 3~4dp 的细线；
 *  2. 「能不能浮在整个键盘上，更显眼」→ 加成一条横跨键盘的浮层；
 *  3. 「两侧只有短条，看不见波形」→ 改成滚动波形：每进一个电平值，左移一格，
 *     画出最近 [BARS] 格的柱高，像真机的声波动画，一眼能看出「还在收」。
 *
 * 画法：底层是半透明主色圆角底（定住视觉体量，避免静音时整块消失），
 * 上面按历史值画一列主色竖条（越新越靠右，新值从右侧进入）。
 */
class VoiceLevelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val history = FloatArray(BARS)

    private var head = 0
    private var filled = 0

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val accentColor: Int = runCatching { context.getColor(R.color.accent) }
        .getOrDefault(Color.WHITE)

    private var trackColor: Int = Color.argb(
        0x2E, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor),
    )

    /** 推入一个 0..1 的电平（新的从右侧进入，整体左移一格） */
    fun push(level: Float) {
        head = (head + 1) % BARS
        history[head] = level.coerceIn(0f, 1f)
        if (filled < BARS) filled++
        invalidate()
    }

    /** 清零：波形全部落到底（录音结束后不立刻隐藏也先归零，再靠调用方 GONE） */
    fun reset() {
        for (i in history.indices) history[i] = 0f
        head = 0
        filled = 0
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val h = height.toFloat()
        val w = width.toFloat()
        if (h <= 0f || w <= 0f) return
        val radius = h / 2f
        paint.color = trackColor
        canvas.drawRoundRect(0f, 0f, w, h, radius, radius, paint)

        val gap = h * 0.22f
        val barW = (w / BARS) - gap
        if (barW <= 0f) return
        paint.color = accentColor
        val minH = h * 0.28f
        for (i in 0 until filled) {
            // 最旧的一格在最左：head 指向最新，往回取
            val idx = (head - (filled - 1) + i + BARS * 2) % BARS
            val level = history[idx]
            val barH = minH + (h - 2f - minH) * level
            val left = i * (barW + gap) + gap / 2f
            val top = (h - barH) / 2f
            canvas.drawRoundRect(
                left, top, left + barW, top + barH, barW / 2f, barW / 2f, paint,
            )
        }
    }

    private companion object {
        /** 波形保留多少格（差不多 1.2 秒的历史，16 采样/秒推入） */
        const val BARS = 22
    }
}
