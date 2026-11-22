package com.padmouse.mapper.engine

import android.os.Handler
import android.os.Looper
import com.padmouse.mapper.core.InputMath
import com.padmouse.mapper.core.ScrollMath
import com.padmouse.mapper.core.SettingsStore
import com.padmouse.mapper.inject.InjectKit
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow

/**
 * 右摇杆 → 滚动手势。
 *
 * 两种模式（参数面板可切）：
 *
 *  - **丝滑（默认）**：把「目标速度」反算成单段的位移与时长（见 [ScrollMath]），
 *    一段结束**立刻**接下一段、中间不留间隔 —— 按住期间内容以近似**恒定速度**移动（等速滚动）。
 *  - **分段（旧行为）**：固定单元位移 + 段间间隔，推得越满位移越大、间隔越小。
 *
 * 硬约束没变：dispatchGesture 同一时刻只允许一个手势，所以这里靠 onDone 串成一条链。
 * 每次 pump() 都读最新的摇杆值 —— 按住不动时速度恒定，推量变化时下一段立刻生效。
 */
class ScrollDriver(
    private val gestures: GestureEngine,
    private val anchorProvider: () -> Pair<Float, Float>,
    private val screenSize: () -> Pair<Int, Int>
) {

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var stickX = 0f

    @Volatile
    private var stickY = 0f

    private var running = false

    /** 轴锁定：换轴需要另一轴明显占优（0.7 倍迟滞），避免两轴推量接近时来回翻转 */
    private var lockedVertical: Boolean? = null

    fun onStick(x: Float, y: Float) {
        if (x.isNaN() || y.isNaN() || x.isInfinite() || y.isInfinite()) return
        stickX = x.coerceIn(-1f, 1f)
        stickY = y.coerceIn(-1f, 1f)
        if (!running) {
            running = true
            lockedVertical = null
            handler.post { pump() }
        }
    }

    fun stop() {
        running = false
        stickX = 0f
        stickY = 0f
        lockedVertical = null
        handler.removeCallbacksAndMessages(null)
    }

    private fun pickVertical(ax: Float, ay: Float): Boolean {
        val locked = lockedVertical
        if (locked == null) {
            val v = ay >= ax
            lockedVertical = v
            return v
        }
        val stillDominant = if (locked) ay >= ax * 0.7f else ax >= ay * 0.7f
        if (stillDominant) return locked
        lockedVertical = !locked
        return !locked
    }

    private fun pump() {
        if (!running) return
        val c = SettingsStore.current.scroll
        val ax = abs(stickX)
        val ay = abs(stickY)
        val vertical = pickVertical(ax, ay)
        val mag = if (vertical) ay else ax
        val norm = InputMath.deadzoneNorm(mag, c.deadzone)
        if (norm <= 0f || (!vertical && !c.horizontal)) {
            // 回中 / 不支持水平滚动 → 结束这条链
            running = false
            lockedVertical = null
            return
        }
        val intensity = InputMath.applyGamma(norm, c.curve)

        val (w, h) = screenSize()
        val (axRaw, ayRaw) = anchorProvider()
        val startX = axRaw.coerceIn(2f, (w - 2).toFloat())
        val startY = ayRaw.coerceIn(2f, (h - 2).toFloat())

        if (c.smooth) {
            val span = min(w, h).toFloat()
            val travelLimit = (if (vertical) h else w) * 0.85f
            val plan = ScrollMath.plan(
                spanPx = span,
                maxTravelPx = travelLimit,
                // 位移小于触摸 slop 会被当成点击，宁可慢一点也不能误触
                minTravelPx = span * 0.06f,
                intensity = intensity,
                speedRatio = c.speedRatio,
                chunkMs = c.chunkMs
            )
            if (plan.distancePx <= 0f || plan.durationMs <= 0L) {
                running = false
                lockedVertical = null
                return
            }
            val half = plan.distancePx / 2f
            val gesture = if (vertical) {
                // 起点/终点围绕锚点对称分布，并整体夹在屏幕内（保证位移不被裁掉）
                val cy = startY.coerceIn(half + 2f, (h - 2f - half).coerceAtLeast(half + 3f))
                if (stickY < 0f) {
                    InjectKit.swipe(startX, cy + half, startX, cy - half, plan.durationMs)
                } else {
                    InjectKit.swipe(startX, cy - half, startX, cy + half, plan.durationMs)
                }
            } else {
                val cx = startX.coerceIn(half + 2f, (w - 2f - half).coerceAtLeast(half + 3f))
                if (stickX < 0f) {
                    InjectKit.swipe(cx + half, startY, cx - half, startY, plan.durationMs)
                } else {
                    InjectKit.swipe(cx - half, startY, cx + half, startY, plan.durationMs)
                }
            }
            gestures.submit("scroll", gesture) { _ ->
                // 立刻接下一段：段间只差注入本身的往返时间 —— 这就是"等速"的来源
                if (running) handler.post { pump() }
            }
            return
        }

        // ---------------- 分段模式（旧行为，保留用于 A/B 对比） ----------------
        val baseDist = h * c.distanceRatio * (0.55f + 0.45f * intensity)
        val halfOld = baseDist / 2f
        val top = (startY - halfOld).coerceAtLeast(2f)
        val bottom = (startY + halfOld).coerceAtMost((h - 2).toFloat())
        val left = (startX - halfOld).coerceAtLeast(2f)
        val right = (startX + halfOld).coerceAtMost((w - 2).toFloat())
        val unitMs = (c.unitMs * (1f - 0.45f * intensity)).toLong().coerceAtLeast(90L)
        val gesture = if (vertical) {
            if (stickY < 0f) {
                InjectKit.swipe(startX, bottom, startX, top, unitMs) // 上推 → 看后面
            } else {
                InjectKit.swipe(startX, top, startX, bottom, unitMs)
            }
        } else {
            if (stickX < 0f) {
                InjectKit.swipe(right, startY, left, startY, unitMs)
            } else {
                InjectKit.swipe(left, startY, right, startY, unitMs)
            }
        }
        val gap = ((1f - intensity) * 120f).toLong().coerceIn(0L, 120L)
        gestures.submit("scroll", gesture) { _ ->
            if (running) handler.postDelayed({ pump() }, 4L + gap)
        }
    }

    /** 供 UI 预览：当前滚动强度 0..1 */
    fun intensity(): Float {
        val c = SettingsStore.current.scroll
        val mag = min(1f, maxOf(abs(stickX), abs(stickY)))
        val n = InputMath.deadzoneNorm(mag, c.deadzone)
        if (n <= 0f) return 0f
        return n.toDouble().pow(c.curve.toDouble().coerceAtLeast(0.01)).toFloat()
    }
}
