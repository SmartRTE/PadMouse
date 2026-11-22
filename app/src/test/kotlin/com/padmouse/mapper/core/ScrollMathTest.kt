package com.padmouse.mapper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 丝滑滚动（等速）的纯计算验证：速度、位移、时长的三个边界约束 */
class ScrollMathTest {

    private val span = 1280f
    private val maxTravel = 2000f
    private val minTravel = span * 0.06f

    private fun plan(i: Float, ratio: Float = 1f, chunk: Long = 300L, travel: Float = maxTravel) =
        ScrollMath.plan(span, travel, minTravel, i, ratio, chunk)

    @Test
    fun zeroIntensityDoesNothing() {
        val p = plan(0f)
        assertEquals(0f, p.distancePx, 0f)
        assertEquals(0L, p.durationMs)
        assertEquals(0f, p.speedPxPerSec, 0f)
    }

    @Test
    fun speedMatchesTargetWhenUnclamped() {
        val p = plan(0.5f)
        // 目标速度 = 1.0 × 短边 × 0.5
        assertEquals(span * 0.5f, p.speedPxPerSec, span * 0.01f)
        assertEquals(300L, p.durationMs)
    }

    @Test
    fun speedIsMonotonic() {
        var last = -1f
        for (i in 1..20) {
            val v = plan(i / 20f).speedPxPerSec
            assertTrue("intensity=$i v=$v last=$last", v >= last - 0.01f)
            last = v
        }
    }

    @Test
    fun distanceNeverExceedsTravelLimit() {
        // 位移被可用距离截断时，靠缩短时长保住速度（本例 400/1920 ≈ 208ms，仍在 [MIN, MAX] 内）
        val p = plan(1f, ratio = 1.5f, travel = 400f)
        assertTrue("dist=${p.distancePx}", p.distancePx <= 400f + 0.01f)
        assertEquals(span * 1.5f, p.speedPxPerSec, span * 1.5f * 0.02f)
    }

    @Test
    fun minDurationBoundsTheFastestSegment() {
        // 速度极高 + 可用距离很短时，时长会撞到 MIN 下限（再短会抖），此时速度只能低于目标
        val p = plan(1f, ratio = 4f, travel = 400f)
        assertEquals(ScrollMath.MIN_DURATION_MS, p.durationMs)
        assertTrue("dist=${p.distancePx}", p.distancePx <= 400f + 0.01f)
        assertTrue("speed=${p.speedPxPerSec}", p.speedPxPerSec > 0f)
    }

    @Test
    fun distanceNeverBelowTouchSlop() {
        val p = plan(0.05f)
        assertTrue("dist=${p.distancePx} slop=$minTravel", p.distancePx >= minTravel - 0.01f)
        assertTrue(p.durationMs in ScrollMath.MIN_DURATION_MS..ScrollMath.MAX_DURATION_MS)
    }

    @Test
    fun durationAlwaysWithinBounds() {
        for (i in 1..200) {
            val d = plan(i / 200f).durationMs
            assertTrue("intensity=$i dur=$d", d in ScrollMath.MIN_DURATION_MS..ScrollMath.MAX_DURATION_MS)
        }
    }

    @Test
    fun durationNeverReachesLongPressThreshold() {
        // 单段必须明显短于长按阈值（通常 500ms），否则轻推会变成"长按"
        assertTrue("MAX=${ScrollMath.MAX_DURATION_MS}", ScrollMath.MAX_DURATION_MS <= 350L)
        // 极轻推也要在最长一段内走够 slop，避免"按住不动 / 启动很慢"
        val p = plan(0.02f)
        assertTrue("dist=${p.distancePx}", p.distancePx >= minTravel - 0.01f)
        assertTrue("speed=${p.speedPxPerSec}", p.speedPxPerSec > span * 0.1f)
    }

    @Test
    fun higherSpeedRatioRollsFaster() {
        val slow = plan(0.6f, ratio = 0.5f)
        val fast = plan(0.6f, ratio = 2f)
        assertTrue(fast.speedPxPerSec > slow.speedPxPerSec * 3f)
    }
}
