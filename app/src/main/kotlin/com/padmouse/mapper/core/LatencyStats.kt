package com.padmouse.mapper.core

/**
 * 手势「派发 → 回调」耗时的滑动窗口统计。
 *
 * 这是用户能感知到的最大一块延迟（点击/滚动的注入开销），但以前只有"成功/失败"计数，
 * 卡不卡全凭感觉。现在把每次耗时记下来，UI 显示 p50 / p95 —— 有数字才谈得上调。
 *
 * 只保留最近 [capacity] 次；纯 Kotlin，可单测（见 LatencyStatsTest）。
 */
class LatencyStats(private val capacity: Int = 64) {

    private val samples = IntArray(capacity)
    private var count = 0
    private var writeIdx = 0

    data class Snapshot(
        val p50: Int,
        val p95: Int,
        val max: Int,
        val count: Int
    ) {
        val isEmpty: Boolean get() = count == 0
    }

    @Synchronized
    fun add(ms: Int) {
        if (capacity <= 0) return
        samples[writeIdx] = ms.coerceAtLeast(0)
        writeIdx = (writeIdx + 1) % capacity
        if (count < capacity) count++
    }

    @Synchronized
    fun reset() {
        count = 0
        writeIdx = 0
    }

    @Synchronized
    fun snapshot(): Snapshot {
        if (count <= 0) return Snapshot(0, 0, 0, 0)
        val sorted = IntArray(count) { samples[it] }
        sorted.sort()
        return Snapshot(
            p50 = sorted[percentileIndex(count, 50)],
            p95 = sorted[percentileIndex(count, 95)],
            max = sorted[count - 1],
            count = count
        )
    }

    /** 最近邻百分位：n 个样本里取第 ceil(p% × n) 个（0 基） */
    private fun percentileIndex(n: Int, p: Int): Int {
        val raw = ((n - 1L) * p / 100L).toInt()
        return raw.coerceIn(0, n - 1)
    }
}
