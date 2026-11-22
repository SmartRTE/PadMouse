package com.padmouse.mapper.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * 自绘鼠标光标。非 root 无法移动系统鼠标指针，因此必须自己画。
 * 箭头**尖端**（带一个小圆点）即点击点。
 *
 * cursorX/cursorY 用**屏幕坐标**保存（与 dispatchGesture 注入坐标同一坐标系），
 * 绘制时减去窗口左上角偏移，保证"看到的位置 = 点到的位置"。
 */
@SuppressLint("ViewConstructor")
class CursorOverlayView(
    context: Context,
    sizeDp: Float = 44f,
    colorArgb: Int = 0xFFFFFFFF.toInt()
) : View(context) {

    private val density = resources.displayMetrics.density
    private var scale = (sizeDp / 22f) * density
    private var sizeDpNow = sizeDp

    var cursorX = 200f
        private set
    var cursorY = 200f
        private set

    private val loc = IntArray(2)

    /**
     * 窗口在屏幕上的偏移。onDraw 每帧都要用，不能每帧都去问 WindowManager
     * （getLocationOnScreen 会触发一次跨进程查询）。只在布局/滚动位置变化时刷新。
     */
    private var locValid = false

    /**
     * 只重绘这一块矩形（null = 整屏）。
     * 用 API 26+ 的 invalidate(ClipRect) 而不是已废弃的 invalidate(l,t,r,b)。
     */
    private var clipRect: Rect? = null

    private fun refreshLocation() {
        getLocationOnScreen(loc)
        locValid = true
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorArgb
        style = Paint.Style.FILL
    }
    // 常态描边：半透明黑；输入法模式时转成红色，提醒"摇杆暂时移不动光标"
    private val borderColorNormal = 0xB3000000.toInt()
    private val borderColorIme = 0xFFFF3B30.toInt()
    private val borderWidthNormal = 1.8f * density
    private val borderWidthIme = 2.4f * density

    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = borderColorNormal
        style = Paint.Style.STROKE
        strokeWidth = borderWidthNormal
        strokeJoin = Paint.Join.ROUND
    }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x40000000
        style = Paint.Style.FILL
    }

    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF3B30.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }

    private val arrow = Path()

    /** 当前是否处于输入法模式（描边红色） */
    private var imeMode = false

    // 指针位置提示动画
    private var pulseProgress = -1f
    private var pulseMaxRadius = 0f
    private var pulseAnim: ValueAnimator? = null

    init {
        buildArrow()
    }

    private fun buildArrow() {
        arrow.reset()
        // 经典指针轮廓，基准高度 22dp；尖端在 (0,0)
        arrow.moveTo(0f, 0f)
        arrow.lineTo(0f, 22f * scale)
        arrow.lineTo(5.4f * scale, 16.8f * scale)
        arrow.lineTo(8.6f * scale, 23.6f * scale)
        arrow.lineTo(11.8f * scale, 22.1f * scale)
        arrow.lineTo(8.7f * scale, 15.2f * scale)
        arrow.lineTo(15.4f * scale, 14.9f * scale)
        arrow.close()
    }

    /** 实时改大小：小屏上把指针调小，避免遮挡内容 */
    fun setSizeDp(dp: Float) {
        if (dp == sizeDpNow) return
        sizeDpNow = dp
        scale = (dp / 22f) * density
        buildArrow()
        clipRect = null
        invalidate()
    }

    fun setColor(colorArgb: Int) {
        fill.color = colorArgb
        clipRect = null
        invalidate()
    }

    /**
     * 输入法模式：描边转红并稍微加粗，提醒用户"现在是输入法模式，摇杆移不了光标"。
     *
     * 只改描边、不动填充 —— 填充色是用户自己选的光标颜色。
     */
    fun setImeMode(on: Boolean) {
        if (on == imeMode) return
        imeMode = on
        border.color = if (on) borderColorIme else borderColorNormal
        border.strokeWidth = if (on) borderWidthIme else borderWidthNormal
        clipRect = null
        invalidate()
    }

    /** 红色圆圈从大到小收拢到指针处，用于"找不到指针"时提示位置 */
    fun pulse(maxRadiusPx: Float, colorArgb: Int = 0xFFFF3B30.toInt(), times: Int = 2) {
        pulseAnim?.cancel()
        pulsePaint.color = colorArgb
        pulseMaxRadius = maxRadiusPx
        val anim = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 420
            repeatCount = (times - 1).coerceAtLeast(0)
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                pulseProgress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    pulseProgress = -1f
                    invalidate()
                }
            })
        }
        pulseAnim = anim
        anim.start()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        locValid = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        locValid = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (!locValid) refreshLocation()
        val px = cursorX - loc[0]
        val py = cursorY - loc[1]

        // 局部重绘：裁剪到「新旧位置的包围盒」，其余部分不画，省掉整屏 invalidate
        clipRect?.let { canvas.clipRect(it) }

        // 收拢圈（画在指针下面，不遮住指针本体）
        if (pulseProgress >= 0f) {
            val r = pulseMaxRadius * pulseProgress
            val alpha = (255 * (0.35f + 0.65f * pulseProgress)).toInt().coerceIn(0, 255)
            pulsePaint.alpha = alpha
            canvas.drawCircle(px, py, r.coerceAtLeast(1f), pulsePaint)
            // 收拢完成时在中心闪一个小实心点
            if (pulseProgress < 0.12f) {
                pulsePaint.alpha = 255
                pulsePaint.style = Paint.Style.FILL
                canvas.drawCircle(px, py, 4f * density, pulsePaint)
                pulsePaint.style = Paint.Style.STROKE
            }
        }

        canvas.save()
        canvas.translate(px + 1.5f * density, py + 1.5f * density)
        canvas.drawPath(arrow, shadow)
        canvas.restore()

        canvas.save()
        canvas.translate(px, py)
        canvas.drawPath(arrow, fill)
        canvas.drawPath(arrow, border)
        canvas.restore()

        // 本帧画完就清掉裁剪区，下次整屏重绘时不受影响
        clipRect = null
    }

    fun moveTo(x: Float, y: Float) {
        if (x == cursorX && y == cursorY) return
        val ox = cursorX
        val oy = cursorY
        cursorX = x
        cursorY = y

        // 提示圈动画期间无法局部重绘，整屏刷新
        if (pulseProgress >= 0f) {
            clipRect = null
            invalidate()
            return
        }

        // 只重绘「旧位置 ∪ 新位置」的包围盒：全屏 invalidate 在 120Hz 下是很贵的。
        // invalidate(l,t,r,b) 已废弃，改用 API 26+ 的 invalidate(ClipRect) 重载。
        if (!locValid) refreshLocation()
        val w = (24f * scale).toInt().coerceAtLeast(10)
        val h = (28f * scale).toInt().coerceAtLeast(10)
        val pad = 8
        val left = minOf(ox, x).toInt() - loc[0] - pad
        val top = minOf(oy, y).toInt() - loc[1] - pad
        val right = maxOf(ox, x).toInt() - loc[0] + w + pad
        val bottom = maxOf(oy, y).toInt() - loc[1] + h + pad
        clipRect = Rect(left, top, right, bottom)
        invalidate()
    }
}
