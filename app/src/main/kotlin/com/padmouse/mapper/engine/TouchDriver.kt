package com.padmouse.mapper.engine

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import com.padmouse.mapper.capture.PadAccessibilityService
import com.padmouse.mapper.core.Probe
import kotlin.math.abs

/**
 * 「触摸」= 手指按下不放 + 跟随光标移动。
 *
 * 实现要点（都是踩出来的）：
 *  1. 起手用 `StrokeDescription(willContinue = true)`，之后不断 `continueStroke` 续接，
 *     每段 [CHUNK_MS]，终点取光标当前位置 —— App 看到的就是"一根手指按住并在拖动"。
 *  2. `continueStroke` 必须在前一段手势**完成回调之后**调用，且不能在自己的回调栈里直接派发，
 *     统一走 ticker + postDelayed。
 *  3. **不要在光标静止时发"抖动"续接保活**：实测近乎零位移的续接会被系统 CANCELLED。
 *     静止时干脆不发 —— willContinue 的指针本来就一直保持按下。
 *  4. 手指一旦按下就必须有对应抬起，任何异常路径都要能收敛（[forceReset] / [end]）。
 *  5. 触摸期间不能发别的手势（单手势约束），由 MappingEngine 侧屏蔽其它动作。
 */
class TouchDriver(
    private val svc: PadAccessibilityService,
    /** 每次续接时采样当前光标位置，保证手指跟随最新位置 */
    private val positionProvider: () -> Pair<Float, Float>
) {

    companion object {
        /** 每段续接的时长：越小越跟手，但派发次数越多 */
        private const val CHUNK_MS = 50L

        /** 光标位移超过该阈值才值得发一次续接 */
        private const val MOVE_EPS = 1.0f

        /** 轮询间隔 */
        private const val POLL_MS = 16L
    }

    // 0 = 空闲，1 = 按住中，2 = 已请求抬起（等在途手势结束）
    private var state = 0
    private var stroke: GestureDescription.StrokeDescription? = null

    private var endX = 0f
    private var endY = 0f
    private var inFlight = false
    private var tickerRunning = false

    val isActive: Boolean get() = state == 1

    private val ticker = object : Runnable {
        override fun run() {
            tickerRunning = false
            tick()
        }
    }

    fun start(x: Float, y: Float): Boolean {
        if (state != 0) return false
        state = 1
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 0.7f, y)
        }
        endX = x + 0.7f
        endY = y
        val s = GestureDescription.StrokeDescription(path, 0L, CHUNK_MS, true)
        stroke = s
        inFlight = true
        Probe.log("TOUCH", "down (${x.toInt()},${y.toInt()})")
        svc.sendGesture(GestureDescription.Builder().addStroke(s).build(), "touch-down") { ok ->
            inFlight = false
            if (!ok) {
                forceReset("down dispatch failed")
                return@sendGesture
            }
            scheduleTick(POLL_MS)
        }
        return true
    }

    fun end() {
        if (state != 1) return
        state = 2
        if (inFlight) scheduleTick(20L) else release()
    }

    /** 异常兜底：把状态清掉，避免下次 start 被拒绝 */
    fun forceReset(reason: String) {
        if (state != 0 || stroke != null) Probe.log("TOUCH", "forceReset: $reason")
        state = 0
        stroke = null
        inFlight = false
    }

    // ---------------------------------------------------------------- 内部

    private fun scheduleTick(delayMs: Long) {
        if (tickerRunning) return
        tickerRunning = true
        svc.mainHandler.postDelayed(ticker, delayMs)
    }

    private fun tick() {
        if (state == 0) return
        if (inFlight) {
            scheduleTick(20L)
            return
        }
        if (state == 2) {
            release()
            return
        }
        val (tx, ty) = positionProvider()
        if (abs(tx - endX) <= MOVE_EPS && abs(ty - endY) <= MOVE_EPS) {
            // 光标没动：不发续接，指针本来就会保持按下
            scheduleTick(40L)
            return
        }
        sendContinuation(tx, ty)
    }

    private fun sendContinuation(tx: Float, ty: Float) {
        val prev = stroke
        if (prev == null) {
            forceReset("stroke lost")
            return
        }
        val path = Path().apply {
            moveTo(endX, endY)
            lineTo(tx, ty)
        }
        val next = try {
            prev.continueStroke(path, 0L, CHUNK_MS, true)
        } catch (t: Throwable) {
            forceReset("continueStroke threw: $t")
            return
        }
        stroke = next
        endX = tx
        endY = ty
        inFlight = true
        svc.sendGesture(GestureDescription.Builder().addStroke(next).build(), "touch-move") { ok ->
            inFlight = false
            if (!ok) {
                forceReset("continue dispatch failed")
                return@sendGesture
            }
            scheduleTick(POLL_MS)
        }
    }

    private fun release() {
        val prev = stroke
        state = 0
        stroke = null
        if (prev == null) {
            Probe.log("TOUCH", "release: already idle")
            return
        }
        // 收尾：willContinue = false，抬起手指
        val path = Path().apply {
            moveTo(endX, endY)
            lineTo(endX + 0.3f, endY)
        }
        val last = try {
            prev.continueStroke(path, 0L, 24L, false)
        } catch (t: Throwable) {
            Probe.log("TOUCH", "release continueStroke threw: $t")
            return
        }
        Probe.log("TOUCH", "up at (${endX.toInt()},${endY.toInt()})")
        svc.sendGesture(GestureDescription.Builder().addStroke(last).build(), "touch-up") { ok ->
            Probe.log("TOUCH", "up completed=$ok")
        }
    }
}
