package com.padmouse.mapper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.input.InputManager
import android.util.Base64
import com.padmouse.mapper.capture.PadAccessibilityService
import com.padmouse.mapper.core.BindingBus
import com.padmouse.mapper.core.ConfigIO
import com.padmouse.mapper.core.DeviceInfo
import com.padmouse.mapper.core.Probe
import com.padmouse.mapper.core.SettingsStore
import com.padmouse.mapper.inject.InjectKit
import com.padmouse.mapper.model.ActionId

/**
 * 调试控制通道（adb 与通知栏按钮共用）：
 *
 *   adb shell am broadcast -n com.padmouse.mapper/.ProbeReceiver -a com.padmouse.probe.CMD --es cmd tap
 *
 * 命令：pause | resume | cursor_toggle | enable(on=0|1) | tap | long | double |
 *       swipe(dir) | scroll(dir,n) | captor(on=0|1) | cursor(on=0|1) |
 *       move(dx,dy) | stick(x,y) | rstick(x,y) | global(action) | dump | export | clear
 *
 * 注意：HyperOS 上隐式广播不投递，必须带 -n 指定组件。
 *
 * 安全：主 Manifest 里 exported="false"（release 版第三方 App 无法调用），
 * debug 版由 app/src/debug/AndroidManifest.xml 覆盖为 exported="true" 供 adb 使用。
 */
class ProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: return
        val svc = PadAccessibilityService.instance
        Probe.log("RADIO", "cmd=" + cmd + " delay=" + intent.getIntExtra("delay", 0) + " svc=" + (svc != null))

        val run = Runnable {
            // 不依赖服务的命令先处理
            when (cmd) {
                "clear" -> {
                    Probe.clear(); return@Runnable
                }
                "dump" -> {
                    val im = context.getSystemService(Context.INPUT_SERVICE) as InputManager
                    Probe.log("DEV", "--- dump ---")
                    DeviceInfo.listDevices(im).forEach { Probe.log("DEV", DeviceInfo.dump(it).trim()) }
                    return@Runnable
                }
                "export" -> {
                    val im = context.getSystemService(Context.INPUT_SERVICE) as InputManager
                    val extra = buildString {
                        appendLine("--- VIA RADIO EXPORT ---")
                        appendLine(
                            "COUNT key=" + PadAccessibilityService.keyEventCount.get() +
                                " motion=" + PadAccessibilityService.motionEventCount.get() +
                                " ok=" + PadAccessibilityService.gestureOk.get() +
                                " fail=" + PadAccessibilityService.gestureFail.get()
                        )
                        appendLine("settings=" + SettingsStore.current.toJson())
                        appendLine("uiState=" + PadAccessibilityService.uiState.value)
                        appendLine(DeviceInfo.dumpAll(im))
                    }
                    val f = Probe.export(context, extra)
                    Probe.log("RADIO", "exported -> " + f.absolutePath)
                    return@Runnable
                }
            }

            if (svc == null) {
                Probe.log("RADIO", "服务未连接，无法执行 " + cmd)
                return@Runnable
            }

            val ovl = svc.overlays
            val (w, h) = ovl.screenSize()
            val x = intent.getIntExtra("x", w / 2).toFloat()
            val y = intent.getIntExtra("y", h / 2).toFloat()

            when (cmd) {
                "pause" -> if (!svc.paused) svc.togglePause("radio")
                "resume" -> if (svc.paused) svc.togglePause("radio")
                "cursor_toggle" -> svc.toggleCursorVisible()
                "enable" -> svc.setMappingEnabled(intent.getStringExtra("on") != "0")

                "tap" -> svc.gestures.submit("radio-tap", InjectKit.tap(x, y))
                "long" -> svc.gestures.submit("radio-long", InjectKit.longPress(x, y, 650))
                "double" -> svc.mapping.doubleClick()
                // 走与 LB/RB 完全相同的代码路径（aimPoint 取光标位置）
                "click" -> {
                    Probe.log("RADIO", "click via aimPoint=" + svc.aimPoint())
                    svc.mapping.click()
                }
                "rbclick" -> {
                    Probe.log("RADIO", "longpress via aimPoint=" + svc.aimPoint())
                    svc.mapping.longPress()
                }

                "swipe" -> svc.mapping.swipe(intent.getStringExtra("dir") ?: "up")

                "scroll" -> {
                    val dir = intent.getStringExtra("dir") ?: "down"
                    val n = intent.getIntExtra("n", 3)
                    val dist = h * 0.28f
                    if (dir == "down") svc.chainScroll(n, x, y + dist, y - dist, 260, "radio-sd")
                    else svc.chainScroll(n, x, y - dist, y + dist, 260, "radio-su")
                }

                "captor" -> if (intent.getStringExtra("on") != "0") ovl.showCaptor() else ovl.hideCaptor()
                "captor_refocus" -> ovl.refocusCaptor()
                "cursor" -> if (intent.getStringExtra("on") != "0") ovl.showCursor() else ovl.hideCursor()
                "cursorset" -> ovl.moveCursorTo(x, y)
                "cursorinfo" -> Probe.log("OVL", "cursorinfo: " + ovl.debugInfo())
                "move" -> ovl.moveCursorBy(
                    intent.getIntExtra("dx", 0).toFloat(),
                    intent.getIntExtra("dy", 0).toFloat()
                )

                // 合成摇杆输入：走真实路径（含反转），用于没有手柄时验证算法
                "stick" -> svc.onStick(
                    intent.getFloatExtra("x", 0f),
                    intent.getFloatExtra("y", 0f)
                )
                "rstick" -> svc.onRightStick(
                    intent.getFloatExtra("x", 0f),
                    intent.getFloatExtra("y", 0f)
                )
                "boost" -> svc.setBoost(intent.getStringExtra("on") != "0")
                "pulse" -> Probe.log("RADIO", "pulse -> " + svc.pulseCursor())
                "ime" -> svc.toggleImeMode()
                "hat" -> svc.onHatInput(intent.getFloatExtra("x", 0f), intent.getFloatExtra("y", 0f))
                "imeinfo" -> Probe.log("IME", "visible=" + svc.isImeVisible() + " imeMode=" + svc.imeModeActive)
                "back" -> svc.doBack()
                "touchdown" -> Probe.log("RADIO", "touchDown -> " + svc.touchDown())
                "touchup" -> {
                    svc.touchUp()
                    Probe.log("RADIO", "touchUp")
                }
                "cfgexport" -> Probe.log("CFG", "EXPORT=" + ConfigIO.export(SettingsStore.current))
                "importjson" -> {
                    val b64 = intent.getStringExtra("b64")
                    if (b64 == null) {
                        Probe.log("RADIO", "importjson: 缺少 b64")
                    } else {
                        val text = String(Base64.decode(b64, Base64.NO_WRAP))
                        ConfigIO.import(text)
                            .onSuccess { s ->
                                SettingsStore.replaceAll(s)
                                svc.refreshCursorStyle()
                                svc.onSettingsReplaced()
                                Probe.log("RADIO", "import OK: " + ConfigIO.summary(s))
                            }
                            .onFailure { Probe.log("RADIO", "import FAILED: " + it.message) }
                    }
                }
                // 任意坐标的滑动，用于测试边缘返回等
                "rawswipe" -> {
                    val x0 = intent.getIntExtra("x0", 1).toFloat()
                    val y0 = intent.getIntExtra("y0", h / 2).toFloat()
                    val x1 = intent.getIntExtra("x1", w / 3).toFloat()
                    val y1 = intent.getIntExtra("y1", h / 2).toFloat()
                    val ms = intent.getIntExtra("ms", 260).toLong()
                    svc.gestures.submit(
                        "rawswipe(" + x0 + "," + y0 + "->" + x1 + "," + y1 + ")",
                        InjectKit.swipe(x0, y0, x1, y1, ms)
                    )
                }
                // 自测：走真实分发路径的模拟按键（code = Android keyCode）
                "fakekey" -> {
                    val code = intent.getIntExtra("code", 0)
                    val up = intent.getStringExtra("up") == "1"
                    svc.simulateKey(
                        code,
                        if (up) android.view.KeyEvent.ACTION_UP else android.view.KeyEvent.ACTION_DOWN
                    )
                }
                // 自测：一次广播内完成「按下 → 等待 holdMs → 松开」，
                // 用于精确验证短按/长按的分流（分两次 am broadcast 会有几百毫秒开销，
                // 无法可靠地测出 300ms 以下的按压）。
                "fakekeyseq" -> {
                    val code = intent.getIntExtra("code", 0)
                    val holdMs = intent.getIntExtra("hold", 100).toLong()
                    svc.simulateKey(code, android.view.KeyEvent.ACTION_DOWN)
                    svc.mainHandler.postDelayed({
                        svc.simulateKey(code, android.view.KeyEvent.ACTION_UP)
                    }, holdMs)
                }
                // 自测：**模拟真实手柄的双路径投递**。
                //
                // 真实手柄的按键事件会同时到达两处：
                //   1. KeyFilter 的 onKeyEvent（全局按键过滤）
                //   2. 焦点窗口 CaptureView.onKeyDown/onKeyUp
                // 因为 handlePauseKey 不消费事件，两边都会走到短按处理 ——
                // 不做去重的话光标开关会被切换两次（表现为"闪一下又归中"）。
                //
                // 这个命令把同一个按键同时投递给两条路径，用来验证去重逻辑。
                // gap 模拟两条路径之间的到达间隔（真实手柄约 5~15ms）。
                "fakekeyboth" -> {
                    val code = intent.getIntExtra("code", 0)
                    val holdMs = intent.getIntExtra("hold", 100).toLong()
                    val gap = intent.getIntExtra("gap", 10).toLong()
                    svc.simulateKey(code, android.view.KeyEvent.ACTION_DOWN)
                    svc.mainHandler.postDelayed({
                        svc.simulateKeyViaCaptor(code, android.view.KeyEvent.ACTION_DOWN)
                    }, gap)
                    svc.mainHandler.postDelayed({
                        svc.simulateKey(code, android.view.KeyEvent.ACTION_UP)
                        svc.mainHandler.postDelayed({
                            svc.simulateKeyViaCaptor(code, android.view.KeyEvent.ACTION_UP)
                        }, gap)
                    }, holdMs)
                }
                // 自测：开始录制某个功能
                "record" -> {
                    val a = ActionId.of(intent.getStringExtra("action"))
                    if (a == null) Probe.log("RADIO", "record: 未知 action")
                    else BindingBus.begin(a)
                }
                // 自测：打印当前绑定表
                "cfg" -> Probe.log("CFG", "bindings=" + SettingsStore.current.binding.toJson())
                "startscreen" -> svc.startActivity(
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )

                "global" -> {
                    val a = intent.getStringExtra("action") ?: "back"
                    val code = when (a) {
                        "home" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
                        "recents" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
                        "notif" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                        else -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
                    }
                    Probe.log("RADIO", "global " + a + " -> " + svc.performGlobalAction(code))
                }

                else -> Probe.log("RADIO", "未知 cmd=" + cmd)
            }
        }

        val delay = intent.getIntExtra("delay", 0)
        if (delay > 0 && svc != null) svc.mainHandler.postDelayed(run, delay.toLong()) else run.run()
    }
}
