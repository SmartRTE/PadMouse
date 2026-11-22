package com.padmouse.mapper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyStatsTest {

    @Test
    fun emptySnapshotIsZero() {
        val s = LatencyStats(8).snapshot()
        assertTrue(s.isEmpty)
        assertEquals(0, s.p50)
        assertEquals(0, s.p95)
    }

    @Test
    fun percentilesOfKnownSamples() {
        val st = LatencyStats(100)
        // 1..100ms
        for (i in 1..100) st.add(i)
        val s = st.snapshot()
        assertEquals(100, s.count)
        assertEquals(100, s.max)
        assertTrue("p50=${s.p50}", s.p50 in 49..51)
        assertTrue("p95=${s.p95}", s.p95 in 94..96)
    }

    @Test
    fun ringBufferKeepsOnlyRecentSamples() {
        val st = LatencyStats(4)
        // 只保留最近 4 个：10 / 20 会被挤出去
        for (v in intArrayOf(10, 20, 30, 40, 100, 200)) st.add(v)
        val s = st.snapshot()
        assertEquals(4, s.count)
        assertEquals(200, s.max)
        assertTrue("p50=${s.p50} 应 >= 30（10/20 已被挤出）", s.p50 >= 30)
    }

    @Test
    fun negativeSamplesAreClamped() {
        val st = LatencyStats(4)
        st.add(-5)
        assertEquals(0, st.snapshot().max)
    }

    @Test
    fun resetClearsWindow() {
        val st = LatencyStats(4)
        st.add(12)
        st.reset()
        assertTrue(st.snapshot().isEmpty)
    }
}
