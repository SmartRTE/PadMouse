package com.padmouse.mapper.inject

import android.accessibilityservice.GestureDescription
import android.graphics.Path

/**
 * 手势构造工具。注意：dispatchGesture 同一时刻只允许一个手势在进行中。
 */
object InjectKit {

    /** 零长度 Path 在部分机型不被识别为点击，这里统一用 +1px 保证有长度 */
    fun tapPath(x: Float, y: Float): Path = Path().apply {
        moveTo(x, y)
        lineTo(x + 1f, y)
    }

    fun linePath(x0: Float, y0: Float, x1: Float, y1: Float): Path = Path().apply {
        moveTo(x0, y0)
        lineTo(x1, y1)
    }

    fun single(path: Path, durationMs: Long): GestureDescription =
        GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()

    fun tap(x: Float, y: Float, durationMs: Long = 45L): GestureDescription =
        single(tapPath(x, y), durationMs)

    fun longPress(x: Float, y: Float, durationMs: Long = 650L): GestureDescription =
        single(tapPath(x, y), durationMs)

    fun swipe(x0: Float, y0: Float, x1: Float, y1: Float, durationMs: Long = 200L): GestureDescription =
        single(linePath(x0, y0, x1, y1), durationMs)
}
