package com.padmouse.mapper.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import com.padmouse.mapper.capture.CaptureView
import com.padmouse.mapper.core.Probe
import com.padmouse.mapper.core.SettingsStore

/**
 * 两个悬浮窗，职责分离：
 *  - Cursor 窗口：只画光标，NOT_FOCUSABLE + NOT_TOUCHABLE，永不干扰触摸与焦点
 *  - Captor 窗口：1x1 px，**可获焦点**（读手柄按键+摇杆），NOT_TOUCHABLE，触摸继续穿透
 *
 * 焦点是拿到摇杆模拟量的前提，代价是下层 App 失去输入焦点（输入法弹不出来），
 * 所以"暂停映射"时必须移除 Captor。
 */
class OverlayManager(private val service: AccessibilityService) {

    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var captor: CaptureView? = null
    private var cursorView: CursorOverlayView? = null

    private var screenW = 0
    private var screenH = 0

    val isCaptorShown: Boolean get() = captor != null
    val isCursorShown: Boolean get() = cursorView != null

    init {
        refreshScreenSize()
    }

    /**
     * 取真实屏幕尺寸。
     *
     * API 30+ 用 currentWindowMetrics（含挖孔/状态栏的真实 bounds），
     * 老版本才退回废弃的 defaultDisplay.getRealMetrics —— 后者在多屏/折叠屏
     * 下可能拿到错误的 display。
     */
    fun refreshScreenSize() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b: Rect = wm.currentWindowMetrics.bounds
            screenW = b.width()
            screenH = b.height()
        } else {
            val m = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            screenW = m.widthPixels
            screenH = m.heightPixels
        }
        if (screenW <= 0 || screenH <= 0) {
            val m = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            screenW = m.widthPixels
            screenH = m.heightPixels
        }
    }

    fun screenSize(): Pair<Int, Int> {
        if (screenW == 0 || screenH == 0) refreshScreenSize()
        return screenW to screenH
    }

    // ---------------- Captor ----------------

    fun showCaptor() {
        if (captor != null) return
        val view = CaptureView(
            service,
            onStick = { lx, ly -> PadBridge.onStick(lx, ly) },
            onRightStick = { rx, ry -> PadBridge.onRightStick(rx, ry) },
            onTrigger = { lt, rt -> PadBridge.onTriggers(lt, rt) },
            onHat = { hx, hy -> PadBridge.onHat(hx, hy) },
            onKey = { code, action -> PadBridge.onKey(code, action, true) }
        )
        val lp = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // 故意不加 FLAG_NOT_FOCUSABLE —— 焦点是拿到摇杆的前提
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 0
        lp.y = 0
        lp.setTitle("PadMouseCaptor")
        lp.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        try {
            wm.addView(view, lp)
            captor = view
            view.requestFocus()
            Probe.log("OVL", "captor shown (focusable)")
        } catch (t: Throwable) {
            Probe.log("OVL", "captor addView FAILED: $t")
        }
    }

    fun hideCaptor() {
        val v = captor ?: return
        captor = null
        try {
            wm.removeViewImmediate(v)
            Probe.log("OVL", "captor hidden")
        } catch (t: Throwable) {
            Probe.log("OVL", "captor removeView failed: $t")
        }
    }

    fun refocusCaptor() {
        captor?.requestFocus()
        Probe.log("OVL", "captor refocus requested")
    }

    /** Captor 焦点状态（给服务的"失焦自愈"用） */
    enum class CaptorFocus { MISSING, UNFOCUSED, FOCUSED }

    fun captorFocusState(): CaptorFocus {
        val v = captor ?: return CaptorFocus.MISSING
        return try {
            if (v.hasWindowFocus()) CaptorFocus.FOCUSED else CaptorFocus.UNFOCUSED
        } catch (t: Throwable) {
            CaptorFocus.UNFOCUSED
        }
    }

    /** 重新抢一次焦点；返回是否受理 */
    fun reassertCaptorFocus(): Boolean {
        val v = captor ?: return false
        return try {
            v.requestFocus()
        } catch (t: Throwable) {
            false
        }
    }

    /** 彻底重建 Captor 窗口（反复抢不到焦点 / 窗口被系统移除时用） */
    fun rebuildCaptor() {
        hideCaptor()
        showCaptor()
    }

    // ---------------- Cursor ----------------

    fun showCursorIfNeeded() {
        if (cursorView != null) return
        showCursor()
    }

    fun showCursor() {
        if (cursorView != null) return
        val c = SettingsStore.current.cursor
        val view = CursorOverlayView(service, c.sizeDp, c.colorArgb)
        view.setImeMode(imeModeForCursor)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.setTitle("PadMouseCursor")
        // 关键：必须延伸到状态栏/挖孔区，否则视图画布原点不在屏幕原点，
        // 会出现"箭头画在这、点击落在别处"的错位
        lp.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        try {
            wm.addView(view, lp)
            refreshScreenSize()
            view.moveTo(screenW / 2f, screenH / 2f)
            cursorView = view
            Probe.log("OVL", "cursor shown at (${screenW / 2}, ${screenH / 2})")
        } catch (t: Throwable) {
            Probe.log("OVL", "cursor addView FAILED: $t")
        }
    }

    fun hideCursor() {
        val v = cursorView ?: return
        cursorView = null
        try {
            wm.removeViewImmediate(v)
            Probe.log("OVL", "cursor hidden")
        } catch (t: Throwable) {
            Probe.log("OVL", "cursor removeView failed: $t")
        }
    }

    fun cursorPosition(): Pair<Float, Float>? = cursorView?.let { it.cursorX to it.cursorY }

    /** 光标大小/颜色实时同步（参数面板改动后调用） */
    fun syncCursorStyle() {
        val v = cursorView ?: return
        val c = SettingsStore.current.cursor
        v.setSizeDp(c.sizeDp)
        v.setColor(c.colorArgb)
    }

    /** 输入法模式下光标描边转红。状态记在这里，光标重新显示时也能保持 */
    private var imeModeForCursor = false

    fun setCursorImeMode(on: Boolean) {
        if (imeModeForCursor == on) return
        imeModeForCursor = on
        cursorView?.setImeMode(on)
        Probe.log("OVL", "cursor imeMode=" + on)
    }

    /**
     * 指针位置提示：红色圆圈从大到小收拢到指针处。
     * 返回 false 表示该功能被关闭。
     */
    fun pulse(): Boolean {
        val c = SettingsStore.current.cursor
        if (!c.pulseEnabled) return false
        if (cursorView == null) showCursor()
        val v = cursorView ?: return false
        val (w, h) = screenSize()
        val dp = service.resources.displayMetrics.density
        val maxR = (c.pulseMaxRadiusDp * dp).coerceAtMost(minOf(w, h) * 0.45f)
        v.pulse(maxR, c.pulseColorArgb)
        Probe.log("OVL", "pulse at (${v.cursorX.toInt()},${v.cursorY.toInt()}) r=$maxR")
        return true
    }

    fun moveCursorTo(x: Float, y: Float) {
        cursorView?.moveTo(x, y)
    }

    fun moveCursorBy(dx: Float, dy: Float) {
        val v = cursorView ?: return
        val nx = (v.cursorX + dx).coerceIn(0f, (screenW - 1).toFloat())
        val ny = (v.cursorY + dy).coerceIn(0f, (screenH - 1).toFloat())
        v.moveTo(nx, ny)
    }

    fun teardown() {
        hideCaptor()
        hideCursor()
    }

    /** 调试：光标坐标与窗口在屏幕上的位置，用于核对"画的"与"点的"是否一致 */
    fun debugInfo(): String {
        val v = cursorView ?: return "cursor not shown"
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return "cursor=(${v.cursorX},${v.cursorY}) viewLoc=(${loc[0]},${loc[1]}) " +
            "viewSize=(${v.width}x${v.height}) screen=${screenW}x${screenH}"
    }
}

/**
 * OverlayManager ↔ Service 的解耦桥（避免循环依赖）。
 * 由 PadAccessibilityService 在 onServiceConnected 时注入实现。
 */
object PadBridge {
    var onStick: (Float, Float) -> Unit = { _, _ -> }
    var onRightStick: (Float, Float) -> Unit = { _, _ -> }
    var onTriggers: (Float, Float) -> Unit = { _, _ -> }
    var onHat: (Float, Float) -> Unit = { _, _ -> }
    var onKey: (Int, Int, Boolean) -> Boolean = { _, _, _ -> false }
}
