package com.padmouse.mapper.engine

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.KeyEvent
import com.padmouse.mapper.capture.PadAccessibilityService
import com.padmouse.mapper.core.Probe
import com.padmouse.mapper.core.SettingsStore
import com.padmouse.mapper.inject.InjectKit
import com.padmouse.mapper.model.ActionId
import com.padmouse.mapper.model.BindingCfg
import com.padmouse.mapper.model.GestureCfg
import com.padmouse.mapper.model.prettyKeyName

/**
 * 手柄 → 动作。
 *
 * 按键类动作完全由绑定表驱动（[BindingCfg]），每个功能可绑多个键，
 * 默认值见 [ActionId]。摇杆/十字键/扳机是轴输入，走各自通道。
 */
class MappingEngine(
    private val svc: PadAccessibilityService,
    private val gestures: GestureEngine
) {

    private var cachedCfg: BindingCfg? = null
    private var cachedIndex: Map<Int, ActionId> = emptyMap()

    private var lastHatAt = 0L
    private var lastHatDir: String? = null

    private fun index(): Map<Int, ActionId> {
        val cfg = SettingsStore.current.binding
        if (cfg !== cachedCfg) {
            cachedCfg = cfg
            cachedIndex = cfg.reverseIndex()
        }
        return cachedIndex
    }

    /** 返回 true 表示已消费 */
    fun onKey(keyCode: Int, action: Int): Boolean {
        val actionId = index()[keyCode] ?: return false
        val down = action == KeyEvent.ACTION_DOWN

        // 按住/松开型动作
        when (actionId) {
            ActionId.BOOST -> {
                svc.setBoost(down)
                return true
            }
            ActionId.TOUCH_HOLD -> {
                if (down) svc.touchDown() else svc.touchUp()
                return true
            }
            else -> {}
        }

        // 触摸拖动期间屏蔽其它动作：单手势约束下并发会打断手指按住状态
        if (svc.touchActive) return true

        if (!down) return true

        execute(actionId)
        return true
    }

    /**
     * 执行一个动作。
     *
     * 抽成公开方法，是为了让「长按/短按共用同一个键」的场景能复用：
     * 服务在松手时判定「没到长按阈值」→ 调这里执行短按动作。
     */
    fun execute(actionId: ActionId) {
        when (actionId) {
            ActionId.CLICK -> click()
            ActionId.LONGPRESS -> longPress()
            ActionId.DOUBLE -> doubleClick()
            ActionId.BACK -> svc.doBack()
            ActionId.HOME -> svc.global(AccessibilityService.GLOBAL_ACTION_HOME)
            ActionId.RECENTS -> svc.global(AccessibilityService.GLOBAL_ACTION_RECENTS)
            ActionId.NOTIF -> svc.doNotif()
            ActionId.VOL_UP -> svc.adjustVolume(+1)
            ActionId.VOL_DOWN -> svc.adjustVolume(-1)
            // 切换映射键由服务统一按长按处理（handlePauseKey），此处不再动作
            ActionId.PAUSE -> Unit
            ActionId.IME_MODE -> svc.toggleImeMode()
            ActionId.CURSOR_TOGGLE -> svc.toggleCursorVisible()
            ActionId.CURSOR_PULSE -> {
                // 提示圈功能被关掉时退化成"光标开关"。
                // 注意：pulseCursor() 在光标隐藏时已经把它显示出来了，
                // 这里不能再无条件 toggle —— 否则"显示完又隐藏"，等于白按。
                if (!svc.pulseCursor()) {
                    if (svc.cursorVisible) svc.toggleCursorVisible()
                }
            }
            ActionId.SWIPE_UP -> swipe("up")
            ActionId.SWIPE_DOWN -> swipe("down")
            ActionId.SWIPE_LEFT -> swipe("left")
            ActionId.SWIPE_RIGHT -> swipe("right")
            ActionId.TOUCH_HOLD -> svc.touchDown()
            ActionId.BOOST -> Unit
        }
    }

    /** 十字键是 HAT 轴：用边沿触发 + 冷却实现连发式滑动，并支持上下/左右反转 */
    fun onHat(x: Float, y: Float) {
        // 反转已在服务入口按"物理控件"统一处理，这里只做方向判定
        val hx = x
        val hy = y

        val dir = when {
            hy < -0.5f -> "up"
            hy > 0.5f -> "down"
            hx < -0.5f -> "left"
            hx > 0.5f -> "right"
            else -> {
                lastHatDir = null
                return
            }
        }
        val cfg = SettingsStore.current.gesture
        val now = SystemClock.elapsedRealtime()
        val isNewDir = dir != lastHatDir
        if (!isNewDir && now - lastHatAt < cfg.hatRepeatMs) return
        lastHatAt = now
        lastHatDir = dir
        swipe(dir, cfg)
    }

    fun resetHat() {
        lastHatDir = null
    }

    // ---------------- 动作实现 ----------------

    fun click(): Boolean {
        val (x, y) = svc.aimPoint() ?: return false
        val cfg = SettingsStore.current.gesture
        gestures.submit("click(${x.toInt()},${y.toInt()})", InjectKit.tap(x, y, cfg.tapMs))
        return true
    }

    fun longPress(): Boolean {
        val (x, y) = svc.aimPoint() ?: return false
        val cfg = SettingsStore.current.gesture
        gestures.submit("longpress(${x.toInt()},${y.toInt()})", InjectKit.longPress(x, y, cfg.longPressMs))
        return true
    }

    fun doubleClick(): Boolean {
        val (x, y) = svc.aimPoint() ?: return false
        val cfg = SettingsStore.current.gesture
        gestures.submit("dblclick-1", InjectKit.tap(x, y, cfg.tapMs)) {
            svc.mainHandler.postDelayed({
                gestures.submit("dblclick-2", InjectKit.tap(x, y, cfg.tapMs))
            }, cfg.doubleTapMs)
        }
        return true
    }

    fun swipe(dir: String, cfg: GestureCfg = SettingsStore.current.gesture): Boolean {
        val (w, h) = svc.overlays.screenSize()
        val (cx, cy) = svc.aimPoint() ?: return false
        val d = h * cfg.swipeDistanceRatio
        val half = d / 2f
        val gesture = when (dir) {
            "up" -> InjectKit.swipe(cx, (cy + half).coerceAtMost(h - 1f), cx, (cy - half).coerceAtLeast(1f), cfg.swipeMs)
            "down" -> InjectKit.swipe(cx, (cy - half).coerceAtLeast(1f), cx, (cy + half).coerceAtMost(h - 1f), cfg.swipeMs)
            "left" -> InjectKit.swipe((cx + half).coerceAtMost(w - 1f), cy, (cx - half).coerceAtLeast(1f), cy, cfg.swipeMs)
            else -> InjectKit.swipe((cx - half).coerceAtLeast(1f), cy, (cx + half).coerceAtMost(w - 1f), cy, cfg.swipeMs)
        }
        Probe.log("MAP", "swipe $dir from (${cx.toInt()},${cy.toInt()}) d=$d")
        gestures.submit("swipe-$dir", gesture)
        return true
    }
}
