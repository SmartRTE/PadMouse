package com.padmouse.mapper.capture

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.padmouse.mapper.core.Probe

/**
 * Captor 窗口里的输入接收 View。
 *
 * 所在窗口**不带** FLAG_NOT_FOCUSABLE → 拿到输入焦点 → 能收到手柄按键与摇杆轴。
 * 窗口带 FLAG_NOT_TOUCHABLE → 触摸仍然穿透到下层 App。
 */
@SuppressLint("ViewConstructor")
class CaptureView(
    context: Context,
    private val onStick: (Float, Float) -> Unit,
    private val onRightStick: (Float, Float) -> Unit,
    private val onTrigger: (Float, Float) -> Unit,
    private val onHat: (Float, Float) -> Unit,
    private val onKey: (Int, Int) -> Boolean
) : View(context) {

    private var lastMotionLogAt = 0L
    private var lastAxes = ""

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        PadAccessibilityService.keyEventCount.incrementAndGet()
        Probe.log(
            "CAPTOR-KEY",
            "${KeyEvent.keyCodeToString(keyCode)} DOWN src=0x${Integer.toHexString(event.source)} " +
                "scan=${event.scanCode} dev=${event.deviceId} repeat=${event.repeatCount}"
        )
        // 扳机按下会附带自动重复的 KEYCODE_BUTTON_L2/R2，必须忽略
        if (event.repeatCount == 0) onKey(keyCode, KeyEvent.ACTION_DOWN)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        onKey(keyCode, KeyEvent.ACTION_UP)
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val src = event.source
        val isPad = (src and InputDevice.SOURCE_JOYSTICK) != 0 ||
            (src and InputDevice.SOURCE_GAMEPAD) != 0
        if (!isPad) return super.onGenericMotionEvent(event)

        PadAccessibilityService.motionEventCount.incrementAndGet()

        val (lx, ly) = StickReader.left(event)
        val (rx, ry) = StickReader.right(event)
        val (hx, hy) = StickReader.hat(event)
        val lt = StickReader.leftTrigger(event)
        val rt = StickReader.rightTrigger(event)

        onStick(lx, ly)
        onRightStick(rx, ry)
        onTrigger(lt, rt)
        onHat(hx, hy)

        // 探针：限流记录，避免刷屏
        val now = SystemClock.elapsedRealtime()
        // release 下 CAPTOR-MOTION 不记录：先问一句，免得白拼一堆 "%.2f"
        if (Probe.keep("CAPTOR-MOTION") && now - lastMotionLogAt > 150) {
            lastMotionLogAt = now
            val axes = "L=(${"%.2f".format(lx)},${"%.2f".format(ly)}) " +
                "R=(${"%.2f".format(rx)},${"%.2f".format(ry)}) " +
                "HAT=(${hx.toInt()},${hy.toInt()}) " +
                "LT=${"%.2f".format(lt)} RT=${"%.2f".format(rt)}"
            if (axes != lastAxes) {
                lastAxes = axes
                Probe.log("CAPTOR-MOTION", axes)
            }
        }
        return true
    }

    override fun onFocusChanged(
        gainFocus: Boolean,
        direction: Int,
        previouslyFocusedRect: android.graphics.Rect?
    ) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        Probe.log("CAPTOR", "focusChanged gain=$gainFocus")
    }
}
