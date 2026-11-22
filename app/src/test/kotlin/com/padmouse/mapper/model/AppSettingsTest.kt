package com.padmouse.mapper.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 配置解析的单元测试。 */
class AppSettingsTest {

    @Test
    fun parseReportsFailureForBrokenJson() {
        val r = AppSettings.parse("{ this is not json")
        assertTrue("坏 JSON 必须失败而不是静默清空设置", r.isFailure)
    }

    @Test
    fun parseRejectsFutureVersion() {
        val r = AppSettings.parse("{\"version\": 999, \"cursor\": {}}")
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("999"))
    }

    @Test
    fun parseAcceptsCurrentVersion() {
        val r = AppSettings.parse("{\"version\": " + AppSettings.VERSION + ", \"cursor\": {}}")
        assertTrue(r.isSuccess)
    }

    @Test
    fun missingFieldsFallBackToDefaults() {
        val s = AppSettings.parse("{\"cursor\": {\"maxSpeedRatio\": 1.5}}").getOrThrow()
        assertEquals(1.5f, s.cursor.maxSpeedRatio, 1e-5f)
        // 没给的字段用默认值，而不是 0
        assertEquals(CursorCfg().gamma, s.cursor.gamma, 1e-5f)
        assertEquals("SYSTEM", s.themeMode)
    }

    @Test
    fun deadzoneIsClampedOnImport() {
        val s = AppSettings.parse("{\"cursor\": {\"deadzone\": 1.0}}").getOrThrow()
        assertTrue("死区必须 < 1", s.cursor.deadzone < 1f)
    }

    @Test
    fun badThemeModeFallsBackToSystem() {
        val s = AppSettings.parse("{\"themeMode\": \"NEON\"}").getOrThrow()
        assertEquals("SYSTEM", s.themeMode)
    }

    @Test
    fun fromJsonNeverThrows() {
        // 宽松接口：坏数据退回默认，而不是抛异常
        assertEquals(AppSettings(), AppSettings.fromJson("garbage"))
        assertEquals(AppSettings(), AppSettings.fromJson(null))
    }

    @Test
    fun roundTripsThroughJson() {
        val a = AppSettings().copy(cursor = CursorCfg(maxSpeedRatio = 1.25f, deadzone = 0.05f))
        val b = AppSettings.fromJson(a.toJson())
        assertEquals(1.25f, b.cursor.maxSpeedRatio, 1e-5f)
        assertEquals(0.05f, b.cursor.deadzone, 1e-5f)
    }

    @Test
    fun cursorStickDefaultsToLeft() {
        assertEquals("LEFT", AppSettings().cursorStick)
    }

    @Test
    fun cursorStickIsParsedFromJson() {
        val s = AppSettings.parse("{\"cursorStick\": \"RIGHT\"}").getOrThrow()
        assertEquals("RIGHT", s.cursorStick)
    }

    @Test
    fun invalidCursorStickFallsBackToLeft() {
        val s = AppSettings.parse("{\"cursorStick\": \"MIDDLE\"}").getOrThrow()
        assertEquals("LEFT", s.cursorStick)
    }

    @Test
    fun cursorStickSurvivesJsonRoundTrip() {
        val a = AppSettings().copy(cursorStick = "RIGHT")
        assertEquals("RIGHT", AppSettings.fromJson(a.toJson()).cursorStick)
    }

    /** 丝滑滚动参数必须能完整导出 / 导入（配置导入导出走的就是 toJson / parse） */
    @Test
    fun smoothScrollFieldsSurviveJsonRoundTrip() {
        val a = AppSettings().copy(
            scroll = ScrollCfg(smooth = false, speedRatio = 2.5f, chunkMs = 180L)
        )
        val b = AppSettings.fromJson(a.toJson())
        assertEquals(false, b.scroll.smooth)
        assertEquals(2.5f, b.scroll.speedRatio, 1e-5f)
        assertEquals(180L, b.scroll.chunkMs)
    }

    @Test
    fun smoothScrollDefaultsAreSane() {
        val d = ScrollCfg()
        assertEquals(true, d.smooth)
        assertEquals(1f, d.speedRatio, 1e-5f)
        assertEquals(300L, d.chunkMs)
    }

}