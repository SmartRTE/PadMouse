package com.padmouse.mapper.engine

import android.view.Choreographer
import com.padmouse.mapper.core.InputMath
import com.padmouse.mapper.core.Probe
import com.padmouse.mapper.core.SettingsStore
import com.padmouse.mapper.overlay.OverlayManager
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * 摇杆 → 光标物理模型。
 *
 * 摇杆是相对输入，所以用速度控制而不是位置控制：
 *   mag    = |stick|                         裁剪到 1
 *   norm   = (mag - deadzone) / (1 - deadzone)   死区外重标定，避免起跳
 *   speed  = vmax * norm^gamma * speedMul
 *   pos   += dir * speed * dt                    每帧推进
 *
 * 帧驱动用 Choreographer，静止时立即停帧（不耗电）。
 */
class CursorController(private val overlays: OverlayManager) {

    private val choreographer = Choreographer.getInstance()

    private var scheduled = false
    private var lastNs = 0L
    private var vx = 0f
    private var vy = 0f

    @Volatile
    private var inX = 0f

    @Volatile
    private var inY = 0f

    @Volatile
    private var speedMul = 1f

    @Volatile
    private var active = false

    /** 最近一秒的帧数，用于调试 HUD */
    var fps: Int = 0
        private set

    private var frameCount = 0
    private var fpsWindowStart = 0L
    private var lastLogNs = 0L

    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            scheduled = false
            step(frameTimeNanos)
        }
    }

    fun start() {
        active = true
    }

    fun stop() {
        active = false
        inX = 0f
        inY = 0f
        vx = 0f
        vy = 0f
    }

    /** 摇杆输入，x/y 为 -1..1；松手传 (0,0) */
    fun setStick(x: Float, y: Float) {
        // NaN / Inf 会污染速度与坐标，一次坏事件就能让光标永久消失
        if (x.isNaN() || y.isNaN() || x.isInfinite() || y.isInfinite()) return
        inX = x.coerceIn(-1f, 1f)
        inY = y.coerceIn(-1f, 1f)
        if (!active) return
        if (inX == 0f && inY == 0f && !scheduled) return
        overlays.showCursorIfNeeded()
        if (!scheduled) {
            lastNs = 0L
            scheduled = true
            choreographer.postFrameCallback(callback)
        }
    }

    /**
     * 硬复位：立刻停止移动并清掉所有残留速度。
     *
     * setStick(0,0) 只是把输入归零，速度还要靠平滑慢慢衰减 ——
     * 切换摇杆角色时这会导致光标"继续飘一会儿"。
     * 角色切换这种明确的断点，应该直接归零。
     */
    fun reset() {
        inX = 0f
        inY = 0f
        vx = 0f
        vy = 0f
        scheduled = false
    }

    fun setSpeedMultiplier(m: Float) {
        speedMul = m
    }

    fun currentMultiplier(): Float = speedMul

    /** 供 UI 实时预览 */
    fun stickSnapshot(): Pair<Float, Float> = inX to inY

    private fun step(nowNs: Long) {
        val dt = if (lastNs == 0L) {
            1f / 60f
        } else {
            ((nowNs - lastNs) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
        }
        lastNs = nowNs

        val c = SettingsStore.current.cursor
        val (w, h) = overlays.screenSize()
        val vmax = c.maxSpeedRatio * min(w, h).toFloat()

        val mag = hypot(inX, inY).coerceIn(0f, 1f)
        var tvx = 0f
        var tvy = 0f
        if (mag > 0f) {
            // 死区先做上限收敛，避免 (1 - deadzone) 除零导致 NaN
            val norm = InputMath.deadzoneNorm(mag, c.deadzone)
            if (norm > 0f) {
                val speed = vmax * InputMath.applyGamma(norm, c.gamma) * speedMul
                tvx = inX / mag * speed
                tvy = inY / mag * speed
            }
        }

        val alpha = InputMath.smoothingAlpha(dt, c.smoothingMs)
        vx += (tvx - vx) * alpha
        vy += (tvy - vy) * alpha

        val dx = vx * dt
        val dy = vy * dt
        if (abs(dx) > 0.01f || abs(dy) > 0.01f) {
            overlays.moveCursorBy(dx, dy)
        }

        frameCount++
        if (nowNs - fpsWindowStart > 1_000_000_000L) {
            fps = frameCount
            frameCount = 0
            fpsWindowStart = nowNs
        }

        // 调试采样：每 400ms 打印一次
        if (nowNs - lastLogNs > 400_000_000L) {
            lastLogNs = nowNs
            val (x, y) = overlays.cursorPosition() ?: (0f to 0f)
            Probe.log("CURSOR") {
                "pos=(" + x.toInt() + "," + y.toInt() + ") v=(" + vx.toInt() + "," +
                    vy.toInt() + ")px/s fps=" + fps + " in=(" + inX + "," + inY + ") mul=" + speedMul
            }
        }

        // 目标速度为 0（摇杆已回中）时，光速收敛而不是指数衰减：
        // 否则 vx/vy 会以 alpha 的尾巴缓慢逼近 0，在阈值附近空转几十帧。
        val targetIdle = mag <= c.deadzone
        if (targetIdle && abs(vx) < 8f && abs(vy) < 8f) {
            vx = 0f
            vy = 0f
        }

        val stillMoving = abs(vx) > 2f || abs(vy) > 2f || !targetIdle
        if (stillMoving && active) {
            scheduled = true
            choreographer.postFrameCallback(callback)
        } else {
            vx = 0f
            vy = 0f
        }
    }
}
