package com.padmouse.mapper.core

import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.pow

/**
 * 纯数学工具：摇杆归一化、死区重标定、响应曲线、平滑系数。
 *
 * 单独抽出来有两个理由：
 *  1. 能在 JVM 单元测试里直接验证（不依赖 Android framework）；
 *  2. 死区上限等"安全约束"只有一处定义，避免各处算式漂移。
 */
object InputMath {

    /** 死区上限：必须 < 1，否则 (1 - deadzone) 会除零，光标直接变 NaN */
    const val MAX_DEADZONE = 0.95f

    fun clampDeadzone(d: Float): Float = d.coerceIn(0f, MAX_DEADZONE)

    /** 圆形裁剪：向量长度超过 1 时等比缩放 */
    fun circularClip(x: Float, y: Float): Pair<Float, Float> {
        val m = hypot(x, y)
        return if (m > 1f && m > 0f) (x / m) to (y / m) else x to y
    }

    /** 死区外的重标定：返回 0..1 */
    fun deadzoneNorm(mag: Float, deadzone: Float): Float {
        val dz = clampDeadzone(deadzone)
        if (mag <= dz) return 0f
        val denom = (1f - dz).coerceAtLeast(0.05f)
        return ((mag - dz) / denom).coerceIn(0f, 1f)
    }

    /** 响应曲线指数：越大低速越细腻 */
    fun applyGamma(norm: Float, gamma: Float): Float =
        norm.toDouble().pow(gamma.toDouble().coerceAtLeast(0.01)).toFloat()

    /** 一阶平滑系数：dt 为秒，smoothingMs 为时间常数（毫秒） */
    fun smoothingAlpha(dt: Float, smoothingMs: Float): Float {
        if (smoothingMs <= 0.01f) return 1f
        return (1f - exp(-dt * 1000f / smoothingMs)).coerceIn(0.02f, 1f)
    }
}
