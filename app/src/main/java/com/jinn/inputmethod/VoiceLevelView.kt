package com.jinn.inputmethod

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * 录音电平条（浮在键盘上的一条 HUD）。
 *
 * 为什么单独一个 View 而不是改键内那条（2026-11-17 用户反馈「电平条能不能浮在键盘上，更显眼」）：
 * 键内的条被键宽和 3~4dp 的高度限制死了，视线不在那把键上就看不见。浮层横跨整个键盘、
 * 带半透明轨道与主色填充，一眼就能看出「还在录、声音多大」。
 *
 * 画法：轨道 = 主色 25% 透明度铺满，填充 = 主色按 level 从左向右长。
 * 全都自己画（不依赖背景 drawable），避免换肤时颜色对不上。
 */
class VoiceLevelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var fraction = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val accentColor: Int = runCatching { context.getColor(R.color.accent) }
        .getOrDefault(Color.WHITE)

    fun setFraction(value: Float) {
        val next = value.coerceIn(0f, 1f)
        if (next == fraction) return
        fraction = next
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val h = height.toFloat()
        val w = width.toFloat()
        if (h <= 0f || w <= 0f) return
        // 两端留一点内缩，圆角才不会被裁掉
        val r = h / 2f
        paint.color = Color.argb(0x40, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
        canvas.drawRoundRect(0f, 0f, w, h, r, r, paint)
        paint.color = accentColor
        val fill = w * fraction
        if (fill > h * 0.5f) {
            canvas.drawRoundRect(0f, 0f, fill.coerceAtLeast(h), h, r, r, paint)
        }
    }
}
