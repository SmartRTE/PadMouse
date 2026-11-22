package com.padmouse.mapper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 输入数学的单元测试。
 *
 * 这些是"改起来最容易出回归、又不依赖 Android framework"的部分：
 * 死区重标定、圆形裁剪、响应曲线、平滑系数。
 */
class InputMathTest {

    @Test
    fun deadzoneInsideOutputsZero() {
        assertEquals(0f, InputMath.deadzoneNorm(0.1f, 0.15f), 1e-6f)
        assertEquals(0f, InputMath.deadzoneNorm(0.15f, 0.15f), 1e-6f)
    }

    @Test
    fun deadzoneOutsideRescalesToZeroOne() {
        assertEquals(0f, InputMath.deadzoneNorm(0.15f, 0.15f), 1e-6f)
        assertEquals(1f, InputMath.deadzoneNorm(1.0f, 0.15f), 1e-6f)
        // 中点：(0.575-0.15)/(1-0.15) = 0.5
        assertEquals(0.5f, InputMath.deadzoneNorm(0.575f, 0.15f), 1e-5f)
    }

    @Test
    fun deadzoneOfOneDoesNotDivideByZero() {
        val n = InputMath.deadzoneNorm(0.9f, 1.0f)
        assertTrue("不应为 NaN", !n.isNaN())
        assertTrue("应在合法区间", n in 0f..1f)
    }

    @Test
    fun deadzoneIsClamped() {
        assertEquals(InputMath.MAX_DEADZONE, InputMath.clampDeadzone(5f), 1e-6f)
        assertEquals(0f, InputMath.clampDeadzone(-1f), 1e-6f)
        assertEquals(0.3f, InputMath.clampDeadzone(0.3f), 1e-6f)
    }

    @Test
    fun circularClipShrinksToUnitCircle() {
        val (x, y) = InputMath.circularClip(3f, 4f)
        assertEquals(1f, hypot(x, y), 1e-5f)
        assertTrue(abs(x - 0.6f) < 1e-5f)
        assertTrue(abs(y - 0.8f) < 1e-5f)
    }

    @Test
    fun circularClipKeepsInsideVectors() {
        val (x, y) = InputMath.circularClip(0.3f, 0.4f)
        assertEquals(0.3f, x, 1e-6f)
        assertEquals(0.4f, y, 1e-6f)
    }

    @Test
    fun gammaAboveOneMakesLowSpeedFiner() {
        val n = 0.5f
        val g1 = InputMath.applyGamma(n, 1f)
        val g2 = InputMath.applyGamma(n, 2f)
        assertEquals(n, g1, 1e-5f)
        assertTrue("gamma=2 应小于 gamma=1", g2 < g1)
        assertEquals(1f, InputMath.applyGamma(1f, 2f), 1e-5f)
        assertEquals(0f, InputMath.applyGamma(0f, 2f), 1e-6f)
    }

    @Test
    fun smoothingAlphaShrinksWithTimeConstant() {
        val a1 = InputMath.smoothingAlpha(0.016f, 10f)
        val a2 = InputMath.smoothingAlpha(0.016f, 100f)
        assertTrue(a1 > a2)
        assertTrue(a1 <= 1f && a1 >= 0.02f)
    }

    @Test
    fun zeroSmoothingTimeMeansNoSmoothing() {
        assertEquals(1f, InputMath.smoothingAlpha(0.016f, 0f), 1e-6f)
    }
}
