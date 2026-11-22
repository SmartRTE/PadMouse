package com.padmouse.mapper.capture

import android.view.MotionEvent
import com.padmouse.mapper.core.InputMath

/**
 * 手柄轴读取与归一化。
 *
 * 实测（GameSir Nova 2 Lite / Xbox 模式）：
 *   左摇杆 ABS_X / ABS_Y
 *   右摇杆 ABS_Z / ABS_RZ
 *   扳机   ABS_GAS(右) / ABS_BRAKE(左)   —— 模拟量 0..1
 *   十字键 ABS_HAT0X / ABS_HAT0Y          —— 是轴，不是按键
 * 兼容部分手柄用 RX/RY 表示右摇杆。
 */
object StickReader {

    /** 返回 -1..1 的向量，已做圆形裁剪 */
    fun left(ev: MotionEvent): Pair<Float, Float> =
        norm(ev.getAxisValue(MotionEvent.AXIS_X), ev.getAxisValue(MotionEvent.AXIS_Y))

    fun right(ev: MotionEvent): Pair<Float, Float> {
        var x = ev.getAxisValue(MotionEvent.AXIS_Z)
        var y = ev.getAxisValue(MotionEvent.AXIS_RZ)
        if (x == 0f && y == 0f) {
            x = ev.getAxisValue(MotionEvent.AXIS_RX)
            y = ev.getAxisValue(MotionEvent.AXIS_RY)
        }
        return norm(x, y)
    }

    fun hat(ev: MotionEvent): Pair<Float, Float> =
        ev.getAxisValue(MotionEvent.AXIS_HAT_X) to ev.getAxisValue(MotionEvent.AXIS_HAT_Y)

    fun leftTrigger(ev: MotionEvent): Float =
        maxOf(ev.getAxisValue(MotionEvent.AXIS_BRAKE), ev.getAxisValue(MotionEvent.AXIS_LTRIGGER))

    fun rightTrigger(ev: MotionEvent): Float =
        maxOf(ev.getAxisValue(MotionEvent.AXIS_GAS), ev.getAxisValue(MotionEvent.AXIS_RTRIGGER))

    private fun norm(x: Float, y: Float): Pair<Float, Float> {
        // 手柄偶尔上报 NaN/Inf，必须在这里挡住，否则一路传到光标
        if (x.isNaN() || y.isNaN() || x.isInfinite() || y.isInfinite()) return 0f to 0f
        return InputMath.circularClip(x, y)
    }
}
