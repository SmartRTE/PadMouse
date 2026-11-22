package com.padmouse.mapper.ui

import android.content.Context
import android.content.Intent
import android.hardware.input.InputManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.padmouse.mapper.A11yUtil
import com.padmouse.mapper.capture.PadAccessibilityService
import com.padmouse.mapper.core.ConfigIO
import com.padmouse.mapper.core.DeviceInfo
import com.padmouse.mapper.core.SettingsStore
import com.padmouse.mapper.core.LogEntry
import com.padmouse.mapper.core.Probe
import com.padmouse.mapper.inject.InjectKit
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ProbeScreen() {

    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L)
            tick++
        }
    }

    val logs by Probe.logs.collectAsState()
    val a11yOn = A11yUtil.isServiceEnabled(ctx)
    // 服务状态订阅（和状态页一样，不要直接读 instance 当状态来源）
    val uiState by PadAccessibilityService.uiState.collectAsState()
    // 仅用于"执行动作"（点按钮时调用方法）；显示状态一律走 uiState
    val svc = PadAccessibilityService.instance
    val settings by SettingsStore.state.collectAsState()
    val compact = settings.compactMode
    val im = remember { ctx.getSystemService(Context.INPUT_SERVICE) as InputManager }
    val devices = remember(tick) { DeviceInfo.listDevices(im) }
    var exportPath by remember { mutableStateOf<String?>(null) }

    val dm = ctx.resources.displayMetrics
    val screenW = dm.widthPixels.toFloat()
    val screenH = dm.heightPixels.toFloat()
    val cx = screenW / 2f
    val cy = screenH / 2f

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    fun buildExtra(): String = buildString {
        appendLine("--- ENV ---")
        appendLine("a11yEnabled=$a11yOn")
        appendLine("serviceInstance=${svc != null}")
        appendLine("model=${Build.MODEL} brand=${Build.BRAND} manufacturer=${Build.MANUFACTURER}")
        appendLine("sdk=${Build.VERSION.SDK_INT} release=${Build.VERSION.RELEASE} display=${Build.DISPLAY}")
        // scaledDensity 已废弃（API 34 起），用 TypedValue.applyDimension 换算字体缩放
        val scaledDensity = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_SP, 1f, dm
        )
        appendLine("screen=${screenW.toInt()}x${screenH.toInt()} density=${dm.density} scaledDensity=${scaledDensity}")
        appendLine("--- COUNTERS ---")
        appendLine(
            "KEYF=${PadAccessibilityService.keyEventCount.get()} " +
                "CAPTOR_MOTION=${PadAccessibilityService.motionEventCount.get()} " +
                "GEST_OK=${PadAccessibilityService.gestureOk.get()} " +
                "GEST_FAIL=${PadAccessibilityService.gestureFail.get()} " +
                "GEST_WATCHDOG=${PadAccessibilityService.gestureTimeout.get()} " +
                "GEST_LAT_P50=${PadAccessibilityService.gestureLatency.snapshot().p50}ms " +
                "GEST_LAT_P95=${PadAccessibilityService.gestureLatency.snapshot().p95}ms"
        )
        appendLine()
        appendLine("--- BINDINGS (按键 -> 动作) ---")
        appendLine(ConfigIO.summary(SettingsStore.current))
        appendLine("binding=" + SettingsStore.current.binding.toJson())
        appendLine()
        appendLine("--- GAMEPAD AXES SUPPORT ---")
        appendLine("有轴的手柄才能驱动光标/滚动；没有轴的只能靠按键。")
        DeviceInfo.listDevices(im).filter { DeviceInfo.isGamepad(it) }.forEach { d ->
            val axes = DeviceInfo.dump(d).lines()
                .firstOrNull { it.trim().startsWith("axes=") }?.trim() ?: "axes=?"
            appendLine("  " + DeviceInfo.brief(d) + "  " + axes)
        }
        appendLine()
        appendLine(DeviceInfo.dumpAll(im))
    }

    // 左栏：诊断卡片（跟整页滚动）；右栏：日志（自己滚，不拖着左栏走）
    // 日志限高：超出在容器内部滚动，不把页面越撑越高
    AdaptivePanesWithList(
        maxListHeight = 420.dp,
        left = {

        // ---------------- 状态 ----------------
        item {
            SectionCard("PadMouse 探针 v0.1（P0 诊断）") {
                Text(
                    if (a11yOn && svc != null) "无障碍服务：已连接 ✅"
                    else if (a11yOn) "无障碍服务：已开启，但实例未就绪（请重开一次）"
                    else "无障碍服务：未开启 ❌",
                    color = if (a11yOn && svc != null) GOOD else BAD,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "${Build.BRAND} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · " +
                        "${screenW.toInt()}×${screenH.toInt()} @${dm.density}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MUTED
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn("打开无障碍设置", true) {
                        ctx.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                    Btn("清空日志", true) { Probe.clear() }
                }
                SwitchRow(
                    label = "简洁模式",
                    checked = compact
                ) { on ->
                    SettingsStore.update { it.copy(compactMode = on) }
                }
                // 描述始终显示 —— 简洁模式下用户正是靠它知道"关掉会看到什么"
                Text(
                    if (compact) {
                        "当前已隐藏各页面的教程文字与诊断信息。关掉可看到完整界面。"
                    } else {
                        "当前显示完整界面（含教程文字与诊断信息）。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MUTED
                )
            }
        }

        // ---------------- 设备 ----------------
        item {
            SectionCard("输入设备（E6）") {
                if (devices.isEmpty()) {
                    Text("无设备", style = MaterialTheme.typography.bodySmall)
                }
                // 简洁模式：只显示"启用中"的设备（即手柄 / 触摸这类真正在用的），
                // 过滤掉虚拟键盘、电源键、触摸板这些系统设备 —— 它们对用户没意义。
                val shown = if (compact) {
                    devices.filter { d ->
                        DeviceInfo.isGamepad(d) ||
                            (d.sources and android.view.InputDevice.SOURCE_TOUCHSCREEN) != 0
                    }
                } else {
                    devices
                }
                if (shown.isEmpty()) {
                    Text("无启用中的设备", style = MaterialTheme.typography.bodySmall)
                }
                shown.forEach { d ->
                    val isPad = DeviceInfo.isGamepad(d)
                    Text(
                        DeviceInfo.brief(d),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (isPad) GOOD else MUTED
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn("把 dump 写进日志", true) {
                        Probe.log("DEV", "--- input devices dump ---")
                        DeviceInfo.listDevices(im).forEach { Probe.log("DEV", DeviceInfo.dump(it).trim()) }
                        toast("已写入日志")
                    }
                }
            }
        }

        // ---------------- 窗口 ----------------
        item {
            SectionCard("悬浮窗（E2：摇杆必须靠它）") {
                SwitchRow(
                    label = "Captor 焦点窗口（抢输入焦点，读按键+摇杆）",
                    checked = uiState.captorShown,
                    enabled = uiState.connected
                ) { on ->
                    val s = PadAccessibilityService.instance
                    if (on) s?.overlays?.showCaptor() else s?.overlays?.hideCaptor()
                    s?.refreshUiState()
                }
                SwitchRow(
                    label = "显示自绘光标",
                    checked = uiState.cursorOverlayShown,
                    enabled = uiState.connected
                ) { on ->
                    val s = PadAccessibilityService.instance
                    if (on) s?.overlays?.showCursor() else s?.overlays?.hideCursor()
                    s?.refreshUiState()
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn("重新请求焦点", svc != null) { svc?.overlays?.refocusCaptor() }
                    Btn("光标归中心", svc != null) { svc?.overlays?.moveCursorTo(cx, cy) }
                }
                Text(
                    "提示：打开 Captor 后，用摇杆推一推，看下方是否出现 CAPTOR-MOTION 日志。" +
                        "此时下层 App 会失去输入焦点 → 测试输入法是否还能打字（E5）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MUTED
                )
            }
        }

        // ---------------- 手势 ----------------
        item {
            SectionCard("手势注入（E3 / E4）") {
                SwitchRow(
                    label = "音量键触发（↑=点击中心 ↓=上滑 静音=长按）·全局生效",
                    checked = PadAccessibilityService.volumeTrigger,
                    enabled = svc != null
                ) { on ->
                    PadAccessibilityService.volumeTrigger = on
                    tick++
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn("点击中心", svc != null) {
                        svc?.sendGesture(InjectKit.tap(cx, cy), "tap-center")
                    }
                    Btn("长按中心", svc != null) {
                        svc?.sendGesture(InjectKit.longPress(cx, cy, 650), "longpress-center")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn("上滑", svc != null) {
                        svc?.sendGesture(InjectKit.swipe(cx, cy + 400f, cx, cy - 400f, 200), "swipe-up")
                    }
                    Btn("下滑", svc != null) {
                        svc?.sendGesture(InjectKit.swipe(cx, cy - 400f, cx, cy + 400f, 200), "swipe-down")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn("左滑", svc != null) {
                        svc?.sendGesture(InjectKit.swipe(cx + 350f, cy, cx - 350f, cy, 200), "swipe-left")
                    }
                    Btn("右滑", svc != null) {
                        svc?.sendGesture(InjectKit.swipe(cx - 350f, cy, cx + 350f, cy, 200), "swipe-right")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn("连续滚动×5 ↓", svc != null) {
                        svc?.chainScroll(5, cx, cy + 350f, cy - 350f, 260, "scroll-down")
                    }
                    Btn("连续滚动×5 ↑", svc != null) {
                        svc?.chainScroll(5, cx, cy - 350f, cy + 350f, 260, "scroll-up")
                    }
                }
                Text(
                    "注入只作用于当前前台界面。所以要测某个 App：点下方「5 秒后…」按钮 → " +
                        "立刻按 Home 并用最近任务切到目标 App（或在 5 秒内用音量键/手势离开本页）→ " +
                        "等待注入发生，观察效果。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MUTED
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn("5 秒后点击中心", svc != null) {                        Toast.makeText(ctx, "5 秒内切到目标 App", Toast.LENGTH_SHORT).show()
                        svc?.let { s ->
                            s.mainHandler.postDelayed({
                                s.sendGesture(InjectKit.tap(cx, cy), "tap-delayed")
                            }, 5000)
                        }
                    }
                    Btn("5 秒后上滑", svc != null) {
                        Toast.makeText(ctx, "5 秒内切到目标 App", Toast.LENGTH_SHORT).show()
                        svc?.let { s ->
                            s.mainHandler.postDelayed({
                                s.sendGesture(
                                    InjectKit.swipe(cx, cy + 400f, cx, cy - 400f, 200),
                                    "swipe-delayed"
                                )
                            }, 5000)
                        }
                    }
                }
            }
        }

        // ---------------- 计数 ----------------
        item {
            SectionCard("计数（E1/E3 统计）") {
                Text(
                    "按键过滤 KEYF = ${PadAccessibilityService.keyEventCount.get()}\n" +
                        "焦点窗口 MOTION = ${PadAccessibilityService.motionEventCount.get()}\n" +
                        "手势成功 = ${PadAccessibilityService.gestureOk.get()} / 失败 = ${PadAccessibilityService.gestureFail.get()}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn("计数清零", true) { PadAccessibilityService.resetCounters() }
                }
            }
        }

        // ---------------- 导出 ----------------
        item {
            SectionCard("导出报告") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn("导出到文件", true) {
                        val f = Probe.export(ctx, buildExtra())
                        exportPath = f.absolutePath
                        toast("已导出：${f.absolutePath}")
                    }
                }
                exportPath?.let {
                    Text(it, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = GOOD)
                }
            }
        }

        },

        rightList = {

        item {
            Text(
                "日志（新 → 旧，共 ${logs.size} 条）",
                style = MaterialTheme.typography.titleSmall,
                color = ACCENT_BLUE
            )
        }

        items(logs.asReversed()) { e -> LogRow(e) }
        }
    )
}

// ---------------------------------------------------------------- 组件

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = PANEL_BG)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = ACCENT_BLUE)
            content()
        }
    }
}

@Composable
private fun RowScope.Btn(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.weight(1f),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp)
    ) {
        Text(label, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            fontSize = 13.sp,
            color = if (enabled) TEXT_PRIMARY else TEXT_DISABLED
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

private val logFmt: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

@Composable
private fun LogRow(e: LogEntry) {
    val dark = isDarkTheme()
    val color = when (e.tag) {
        "KEYF" -> if (dark) Color(0xFF9CCC65) else Color(0xFF33691E)
        "CAPTOR-KEY" -> if (dark) Color(0xFF4FC3F7) else Color(0xFF01579B)
        "CAPTOR-MOTION" -> if (dark) Color(0xFFFFD54F) else Color(0xFF7A5C00)
        "GEST" -> if (dark) Color(0xFFBA68C8) else Color(0xFF6A1B9A)
        "OVL" -> if (dark) Color(0xFF4DB6AC) else Color(0xFF00695C)
        "SVC" -> if (dark) Color(0xFFF06292) else Color(0xFFAD1457)
        "DEV" -> if (dark) Color(0xFFA1887F) else Color(0xFF5D4037)
        "MAP" -> if (dark) Color(0xFF90CAF9) else Color(0xFF0D47A1)
        "STATE" -> if (dark) Color(0xFFCE93D8) else Color(0xFF4527A0)
        "BIND" -> if (dark) Color(0xFF80CBC4) else Color(0xFF00695C)
        else -> MUTED
    }
    Text(
        text = "${logFmt.format(Instant.ofEpochMilli(e.t))} [${e.tag}] ${e.msg}",
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        color = color,
        modifier = Modifier.fillMaxWidth()
    )
}
