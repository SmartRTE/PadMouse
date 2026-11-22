package com.padmouse.mapper.core

/**
 * 滚动"单元"的纯计算：把「目标速度」反算成「单段位移 + 单段时长」。
 *
 * 丝滑滚动的核心：**每段手势内的速度 = 目标速度**，且段与段之间不留间隔，
 * 于是按住期间内容以近似**恒定速度**移动，而不是"一段一段地跳"。
 *
 * 三个边界约束：
 *  1. 单段位移不能超过屏幕可用距离（手指必须留在屏幕内）→ 超了就缩短时长，速度不变；
 *  2. 单段位移不能小于触摸 slop（太小的滑动会被系统当成**点击**，误触目标 App）→ 抬到下限，靠时长还原速度；
 *  3. 时长夹在 [MIN_DURATION_MS, MAX_DURATION_MS]：太短会退化成高频小段（抖），
 *     太长则不只是"不跟手" —— 长时间缓慢位移会被 App 当成**长按**。
 *
 * 全是纯函数，便于在 JVM 单测里验证（见 ScrollMathTest）。
 */
object ScrollMath {

    /** 手势时长下限：再短就成了"高频小段"，反而抖 */
    const val MIN_DURATION_MS = 120L

    /**
     * 手势时长上限。
     *
     * 关键约束：**单段时长必须明显小于系统/App 的长按阈值（通常 500ms）**。
     * 早期的 1200ms 上限在轻推时（目标速度很低）会算出一段"几乎不动的长按"，
     * 表现就是「启动很慢 + 触发长按菜单」。压到 320ms 后：
     *  - 任何一段都不会被当成长按（每段都是新的按下沿，长按计时器会被重置）；
     *  - 最低滚动速度被抬到 slop / 320ms（≈ 0.19 屏幕短边/秒），一过死区就动。
     */
    const val MAX_DURATION_MS = 320L

    data class Plan(
        /** 单段位移（px，正数） */
        val distancePx: Float,
        /** 单段时长（ms） */
        val durationMs: Long
    ) {
        /** 这一段对应的实际速度（px/s）；0 表示不滚 */
        val speedPxPerSec: Float
            get() = if (durationMs <= 0L) 0f else distancePx * 1000f / durationMs
    }

    val NONE = Plan(0f, 0L)

    /**
     * @param spanPx      速度基准：屏幕短边（px）。目标速度 = speedRatio × spanPx × intensity
     * @param maxTravelPx 单段可用的最大位移（px，受屏幕边界限制）
     * @param minTravelPx 单段最小位移（px，触摸 slop 保护）
     * @param intensity   0..1，已包含死区与响应曲线
     * @param speedRatio  满推时每秒滚动的"屏幕短边倍数"
     * @param chunkMs     单段基准时长（ms）
     */
    fun plan(
        spanPx: Float,
        maxTravelPx: Float,
        minTravelPx: Float,
        intensity: Float,
        speedRatio: Float,
        chunkMs: Long
    ): Plan {
        val i = intensity.coerceIn(0f, 1f)
        if (i <= 0f || spanPx <= 0f || maxTravelPx <= 0f || chunkMs <= 0L) return NONE

        val target = speedRatio.coerceAtLeast(0.01f) * spanPx * i
        if (target <= 0.01f) return NONE

        val baseDur = chunkMs.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
        var dist = target * baseDur / 1000f
        var dur = baseDur

        // 约束 1：位移超出可用距离 → 截断位移、缩短时长（速度保持不变）
        if (dist > maxTravelPx) {
            dist = maxTravelPx
            dur = (dist / target * 1000f).toLong().coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
        }

        // 约束 2：位移小于触摸 slop 会被当成点击 → 抬到下限，用时长把速度还原
        if (dist < minTravelPx) {
            dist = minTravelPx.coerceAtMost(maxTravelPx)
            dur = (dist / target * 1000f).toLong().coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
        }

        return Plan(dist, dur)
    }
}
