package com.padmouse.mapper.engine

import android.accessibilityservice.GestureDescription
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.padmouse.mapper.capture.PadAccessibilityService
import com.padmouse.mapper.core.Probe

/**
 * 手势调度器。
 *
 * 硬约束：dispatchGesture 同一时刻只允许一个手势在进行中。
 * 因此这里串行化所有注入请求；如果忙碌，只保留**最后一个**待发请求
 * （丢弃中间请求而不是 cancel 当前手势 —— 反复 cancel 会造成"按下未抬起"）。
 *
 * **看门狗**：系统偶尔会不回调（无障碍被系统接管、跨进程调用超时等）。
 * 一旦 inFlight 卡在 true，后面所有注入（点击 / 滚动 / 返回）都会一直排队然后被顶掉，
 * 用户看到的是"手柄完全没反应，但服务还活着"。所以每个手势都挂一个
 * 「手势时长 + 余量」的超时，超时就当它失败收敛，链继续往下走。
 */
class GestureEngine(private val svc: PadAccessibilityService) {

    private val handler = Handler(Looper.getMainLooper())

    private var inFlight = false
    private var inFlightLabel = ""
    private var inFlightOnDone: ((Boolean) -> Unit)? = null
    private var inFlightStartedAt = 0L
    private var pending: Pending? = null
    private var watchdog: Runnable? = null

    /** 看门狗触发次数（诊断用：非 0 说明系统回调丢过） */
    var timeouts = 0
        private set

    /** 排队中的请求：run = 轮到时真正派发；drop = 被新请求顶掉时通知调用方 */
    private class Pending(val run: () -> Unit, val drop: () -> Unit)

    val isBusy: Boolean get() = inFlight

    fun submit(
        label: String,
        gesture: GestureDescription,
        onDone: ((Boolean) -> Unit)? = null
    ) {
        if (inFlight) {
            // 忙时只保留最后一个请求；被顶掉的那次必须回调 onDone(false)，
            // 否则靠回调续链的调用方（滚动）会永久停摆 —— 表现为"滚动突然不动了"。
            pending?.drop?.invoke()
            pending = Pending(
                run = { doSubmit(label, gesture, onDone) },
                drop = { onDone?.invoke(false) }
            )
            Probe.log("GEST", "$label queued (busy)")
            return
        }
        doSubmit(label, gesture, onDone)
    }

    private fun doSubmit(
        label: String,
        gesture: GestureDescription,
        onDone: ((Boolean) -> Unit)?
    ) {
        inFlight = true
        inFlightLabel = label
        inFlightOnDone = onDone
        inFlightStartedAt = SystemClock.elapsedRealtime()
        scheduleWatchdog(label, timeoutMsFor(gesture))
        svc.sendGesture(gesture, label) { ok -> finish(ok) }
    }

    /** 统一收敛：正常回调 / 看门狗超时 / 派发失败都走这里 */
    private fun finish(ok: Boolean) {
        if (!inFlight) return // 已被看门狗处理过，迟到的回调直接忽略
        cancelWatchdog()
        // 派发 → 回调 的耗时（UI 显示 p50/p95；超时的那次也会进来，正好暴露异常值）
        val cost = (SystemClock.elapsedRealtime() - inFlightStartedAt).toInt()
        if (cost in 0..60000) PadAccessibilityService.gestureLatency.add(cost)
        inFlight = false
        inFlightLabel = ""
        val cb = inFlightOnDone
        inFlightOnDone = null
        cb?.invoke(ok)
        val p = pending
        pending = null
        p?.run?.invoke()
    }

    private fun scheduleWatchdog(label: String, timeoutMs: Long) {
        cancelWatchdog()
        val r = Runnable {
            watchdog = null
            if (!inFlight) return@Runnable
            timeouts++
            PadAccessibilityService.gestureTimeout.incrementAndGet()
            Probe.log("GEST", "WATCHDOG 超时（" + timeoutMs + "ms）：" + label + " 未回调，强制复位")
            finish(false)
        }
        watchdog = r
        handler.postDelayed(r, timeoutMs)
    }

    private fun cancelWatchdog() {
        watchdog?.let { handler.removeCallbacks(it) }
        watchdog = null
    }

    /** 超时 = 手势时长 + 余量；拿不到时长时用保守默认值 */
    private fun timeoutMsFor(gesture: GestureDescription): Long {
        val dur = try {
            var max = 0L
            for (i in 0 until gesture.strokeCount) {
                max = maxOf(max, gesture.getStroke(i).duration)
            }
            max
        } catch (t: Throwable) {
            0L
        }
        return (dur + TIMEOUT_MARGIN_MS).coerceAtLeast(MIN_TIMEOUT_MS)
    }

    fun clearPending() {
        pending?.drop?.invoke()
        pending = null
    }

    /** 服务销毁时调用：别让看门狗在服务没了之后才触发 */
    fun shutdown() {
        cancelWatchdog()
        inFlight = false
        inFlightLabel = ""
        inFlightOnDone = null
        pending = null
    }

    private companion object {
        /** 回调余量：正常情况下手势结束立刻回调，900ms 已很宽松 */
        const val TIMEOUT_MARGIN_MS = 900L
        const val MIN_TIMEOUT_MS = 1200L
    }
}
