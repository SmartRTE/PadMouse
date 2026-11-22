package com.padmouse.mapper.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.padmouse.mapper.A11yUtil
import com.padmouse.mapper.capture.PadAccessibilityService
import com.padmouse.mapper.core.LivePad
import com.padmouse.mapper.core.Probe
import com.padmouse.mapper.core.SettingsStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

@Composable
fun HomeScreen() {

    val ctx = LocalContext.current

    val settings by SettingsStore.state.collectAsState()
    val compact = rememberCompactMode()

    // 服务状态：订阅服务发布的 StateFlow。
    //
    // 不要拿 PadAccessibilityService.instance 当"状态来源" ——
    // 它是普通静态字段，Compose 追踪不到变化。实测：Activity 创建后约
    // 250ms 服务才连上，UI 会永久卡在"无障碍服务未连接"，
    // 即使每秒重读也不行（组合作用域的跳过优化会让读取根本不发生）。
    //
    // 服务主动 publish，UI 用 collectAsState 订阅：Compose 一等状态，
    // 变化必定触发重组。
    val uiState by PadAccessibilityService.uiState.collectAsState()

    // uiState 是"服务发布"的快照。历史上有过"服务其实活着、快照却停在 connected=false"
    // 的情况（Activity 先组合、服务随后连上，界面就一直不刷新）。
    // 这里用心跳直接问一次服务实例做兜底 —— 只用于"当前能不能用"的判断。
    var aliveTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            aliveTick++
        }
    }
    val instanceAlive = remember(aliveTick) { PadAccessibilityService.instance != null }
    val serviceAlive = uiState.connected || instanceAlive
    val a11yOn = A11yUtil.isServiceEnabled(ctx)

    // 跨页跳转：别的页面请求跳到「配置导入 / 导出」时滚过去。
    //
    // 「配置导入 / 导出」现在固定在页面最底部，所以直接滚到底即可 ——
    // 比记录子视图坐标再换算更可靠（省去了不同布局层级下坐标系不一致的麻烦）。
    val scrollState = rememberScrollState()
    val anchor by NavBus.anchor.collectAsState()
    LaunchedEffect(anchor) {
        if (anchor == NavBus.Anchor.IMPORT_EXPORT) {
            // 等布局测量完成再滚：首帧 maxValue 还是 0，直接滚会滚不动。
            // 最多等 3 秒，超时也照样滚一次（内容不足一屏时 maxValue=0，滚到 0 无害）。
            kotlinx.coroutines.withTimeoutOrNull(3000) {
                androidx.compose.runtime.snapshotFlow { scrollState.maxValue }
                    .first { it > 0 }
            }
            scrollState.animateScrollTo(scrollState.maxValue)
            NavBus.consume()
        }
    }

    // 左栏：快速配置；右栏：摇杆预览 → 轴反转 → 外观 → 最近日志 → 配置导入/导出（最底部）
    AdaptivePanes(
        scrollState = scrollState,
        left = {

        item {
            // 服务不可用时最上面给一条醒目的提示，避免"手柄没反应却不知道原因"
            ServiceOfflineBanner(
                a11yEnabled = a11yOn,
                connected = serviceAlive,
                onOpenSettings = {
                    ctx.startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            )
            Section(
                "快速配置",
                if (compact) null
                else "启用映射、暂停、光标显隐、摇杆角色与手感速调都在这里。"
            ) {
                // ---- 运行状态 ----
                StatRow(
                    "无障碍服务",
                    when {
                        !a11yOn -> "未开启"
                        serviceAlive -> "已连接"
                        else -> "已开启"
                    },
                    if (a11yOn) GOOD else BAD
                )
                LiveServiceStats(
                    uiState = uiState,
                    connected = serviceAlive,
                    a11yEnabled = a11yOn,
                    mappingEnabled = settings.mappingEnabled,
                    compact = compact
                )
                if (!a11yOn) {
                    Button(
                        onClick = {
                            ctx.startActivity(
                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("打开无障碍设置") }
                }

                // ---- 开关 ----
                ToggleRow(
                    label = "启用映射",
                    checked = settings.mappingEnabled,
                    enabled = uiState.connected
                ) { on -> PadAccessibilityService.instance?.setMappingEnabled(on) }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 文本由 uiState 驱动；动作执行时才取 instance（那时必定拿得到）
                    PauseResumeButton(paused = uiState.paused, enabled = uiState.connected)
                    CursorVisibilityButton(
                        visible = uiState.cursorVisible,
                        enabled = uiState.connected
                    )
                }
                if (!compact) {
                    Text(
                        "手柄 Start 键：短按 = 光标显示/隐藏，长按 = 切换映射（长按才切换，短按可透传给游戏）；" +
                            "R3 = 指针位置提示。",
                        fontSize = 10.sp,
                        color = MUTED
                    )
                }

                // ---- 摇杆角色 ----
                Text("移动光标的摇杆", fontSize = 13.sp, color = TEXT_PRIMARY)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("LEFT" to "左摇杆移光标", "RIGHT" to "右摇杆移光标").forEach { (key, label) ->
                        val on = settings.cursorStick == key
                        OutlinedButton(
                            onClick = {
                                SettingsStore.update { it.copy(cursorStick = key) }
                                // 切换后清掉残留输入，否则光标/滚动会停不下来
                                PadAccessibilityService.instance?.applyStickRoles()
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                        ) {
                            Text(
                                if (on) "● $label" else label,
                                fontSize = 11.sp,
                                maxLines = 1
                            )
                        }
                    }
                }
                if (!compact) {
                    Text(
                        if (settings.swapDpadStick) {
                            "已开启「十字键 ⇄ 左摇杆 互换」，光标由十字键驱动，此选项暂不生效。"
                        } else {
                            "另一个摇杆自动改做滚动。选右摇杆移光标时，右摇杆的「轴反转」设置依然作用于光标方向。"
                        },
                        fontSize = 10.sp,
                        color = MUTED
                    )
                }

                // ---- 手感速调（接在控制下面）----
                if (!compact) {
                    Text(
                        "换设备主要就调这两个：推到底时光标够不够快、静止时抖不抖。",
                        fontSize = 11.sp,
                        color = MUTED
                    )
                }
                SliderRow(
                    label = "光标最大速度",
                    value = settings.cursor.maxSpeedRatio,
                    range = 0.2f..2.0f,
                    fmt = { "×%.2f".format(it) },
                    hint = if (compact) null else "相对屏幕短边：0.75 ≈ 1 秒穿越 3/4 屏"
                ) { v ->
                    SettingsStore.update { s -> s.copy(cursor = s.cursor.copy(maxSpeedRatio = v)) }
                }
                SliderRow(
                    label = "摇杆死区",
                    value = settings.cursor.deadzone,
                    range = 0f..0.4f,
                    fmt = { "%.2f".format(it) },
                    hint = if (compact) null else "越大越不容易漂移，但小幅度推动会失效"
                ) { v ->
                    SettingsStore.update { s -> s.copy(cursor = s.cursor.copy(deadzone = v)) }
                }

                // ---- 唤起输入法 ----
                ToggleRow(
                    label = "允许在映射开启时唤起输入法",
                    checked = settings.allowImeWhileMapping,
                    hint = if (compact) null
                    else "开启后可用「输入法模式」键（默认 View 键）临时让出焦点打字：" +
                        "期间摇杆与滚动暂停，但点击/长按/返回等按键映射照常；输入法收起 2.5 秒后自动恢复。"
                ) { on ->
                    SettingsStore.update { it.copy(allowImeWhileMapping = on) }
                }

                if (settings.allowImeWhileMapping) {
                    ImeModeButton(active = uiState.imeModeActive, enabled = uiState.connected)
                }
            }
        }

        },

        right = {

        item {
            Section(
                "手柄实时状态",
                if (compact) null
                else "手柄的原始输入：按下按键就高亮、推动摇杆就显示位置。这里全部是原始值，" +
                    "不做反转 / 互换 / 死区处理 —— 调「轴反转」时可以拿它对照。" +
                    "摇杆盘里的浅色圆是当前生效的死区。暂停映射、或前台 App 被列入停用名单时焦点窗不在，收不到输入。"
            ) {
                var signal by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    while (true) {
                        delay(500)
                        signal = LivePad.hasSignal()
                    }
                }

                // 摇杆盘里的浅色圆 = 当前生效的死区（推过它才有动作）。
                // 与初版一致：按"这个摇杆现在担任的角色"取对应死区。
                val leftDeadzone = if (settings.swapDpadStick || settings.cursorStick == "RIGHT") {
                    settings.scroll.deadzone
                } else {
                    settings.cursor.deadzone
                }
                val rightDeadzone = if (settings.swapDpadStick || settings.cursorStick != "RIGHT") {
                    settings.scroll.deadzone
                } else {
                    settings.cursor.deadzone
                }

                val snap by rememberPadSnapshot()
                GamepadLiveView(
                    snap = snap,
                    leftDeadzone = leftDeadzone,
                    rightDeadzone = rightDeadzone
                )

                Column(modifier = Modifier.fillMaxWidth()) {
                    StatRow("左摇杆（原始）", "%.2f, %.2f".format(snap.lx, snap.ly))
                    StatRow("右摇杆（原始）", "%.2f, %.2f".format(snap.rx, snap.ry))
                    StatRow("十字键（原始）", "%.0f, %.0f".format(snap.hatX, snap.hatY))
                    StatRow("LT / RT（原始）", "%.2f / %.2f".format(snap.lt, snap.rt))
                    StatRow("输入信号", if (signal) "有" else "无")
                    StatRow("已按下", pressedKeysText(snap.buttons))
                }

                LiveCursorStats(uiState = uiState, compact = compact)
            }
        }

        item {
            Section(
                "轴反转",
                if (compact) null
                else if (settings.swapDpadStick)
                    "按个人习惯翻转方向。已开启十字键互换：「左摇杆」那组作用于十字键（光标），" +
                        "「十字键」那组作用于左摇杆（四向滑动）。"
                else
                    "按个人习惯翻转方向。左摇杆=光标，右摇杆=滚动，十字键=四向滑动。"
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        ToggleRow("左摇杆 上下", settings.invert.leftY) { on ->
                            SettingsStore.update { s -> s.copy(invert = s.invert.copy(leftY = on)) }
                        }
                        ToggleRow("左摇杆 左右", settings.invert.leftX) { on ->
                            SettingsStore.update { s -> s.copy(invert = s.invert.copy(leftX = on)) }
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        ToggleRow("右摇杆 上下", settings.invert.rightY) { on ->
                            SettingsStore.update { s -> s.copy(invert = s.invert.copy(rightY = on)) }
                        }
                        ToggleRow("右摇杆 左右", settings.invert.rightX) { on ->
                            SettingsStore.update { s -> s.copy(invert = s.invert.copy(rightX = on)) }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        ToggleRow("十字键 上下", settings.invert.hatY) { on ->
                            SettingsStore.update { s -> s.copy(invert = s.invert.copy(hatY = on)) }
                        }
                        ToggleRow("十字键 左右", settings.invert.hatX) { on ->
                            SettingsStore.update { s -> s.copy(invert = s.invert.copy(hatX = on)) }
                        }
                    }
                }
                ToggleRow(
                    "十字键 ⇄ 左摇杆 互换",
                    settings.swapDpadStick,
                    hint = if (compact) null
                    else "开启后：十字键驱动光标、左摇杆触发四向滑动。给没有摇杆的小手柄用。"
                ) { on ->
                    SettingsStore.update { s -> s.copy(swapDpadStick = on) }
                    PadAccessibilityService.instance?.applyState("swap toggled")
                }
                if (settings.swapDpadStick) {
                    SliderRow(
                        "十字键加速斜坡",
                        settings.gesture.hatRampMs.toFloat(),
                        80f..800f,
                        fmt = { "%.0f ms".format(it) },
                        hint = if (compact) null else "从起步速度加到满速所需时间；太小会难以精确定位"
                    ) { v ->
                        SettingsStore.update { s ->
                            s.copy(gesture = s.gesture.copy(hatRampMs = v.toLong()))
                        }
                    }
                }
            }
        }

        item {
            Section("外观", if (compact) null else "浅色 / 深色主题，也可跟随系统。正文与背景对比度在两种主题下都做过校正。") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        val on = ThemeMode.of(settings.themeMode) == mode
                        OutlinedButton(
                            onClick = { SettingsStore.update { it.copy(themeMode = mode.key) } },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                        ) {
                            Text(
                                if (on) "● ${mode.label}" else mode.label,
                                fontSize = 11.sp,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        // 最近日志：简洁模式下隐藏
        if (!compact) {
            item {
                Section("最近日志（调试）") {
                    RecentLogs()
                }
            }
        }

        item {
            Section(
                "配置导入 / 导出",
                if (compact) "导出的 JSON 含全部参数、按键绑定、应用列表与主题。"
                else "导出的 JSON 含全部参数、按键绑定、应用列表与主题，可直接发给另一台设备导入。"
            ) {
                ConfigTransferSection()
            }
        }
        }
    )
}
/** 「暂停映射」↔「恢复映射」按钮 */
@Composable
private fun RowScope.PauseResumeButton(paused: Boolean, enabled: Boolean) {
    Button(
        onClick = { PadAccessibilityService.instance?.togglePause("ui") },
        enabled = enabled,
        modifier = Modifier.weight(1f)
    ) { Text(if (paused) "恢复映射" else "暂停映射") }
}

/** 「显示光标」↔「隐藏光标」按钮 */
@Composable
private fun RowScope.CursorVisibilityButton(visible: Boolean, enabled: Boolean) {
    Button(
        onClick = { PadAccessibilityService.instance?.toggleCursorVisible() },
        enabled = enabled,
        modifier = Modifier.weight(1f)
    ) { Text(if (visible) "隐藏光标" else "显示光标") }
}

/** 「进入输入法模式」↔「退出输入法模式」按钮 */
@Composable
private fun ImeModeButton(active: Boolean, enabled: Boolean) {
    Button(
        onClick = { PadAccessibilityService.instance?.toggleImeMode() },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth()
    ) { Text(if (active) "退出输入法模式" else "进入输入法模式") }
}

/**
 * 服务相关的活数值（映射状态 / 前台 App）。
 *
 * 全部来自 uiState —— 服务每次状态变化都会发布新快照，
 * Compose 订阅后自动重组，不需要定时轮询。
 */
@Composable
private fun LiveServiceStats(
    uiState: com.padmouse.mapper.capture.ServiceUiState,
    connected: Boolean,
    a11yEnabled: Boolean,
    mappingEnabled: Boolean,
    compact: Boolean
) {
    // 注意：不拿 "connected" 直接报"未连接" —— 本机会出现"服务活着但快照没刷新"，
    // 那种情况下更该显示映射本身的状态，而不是把人吓一跳。
    val stateText = when {
        !a11yEnabled -> "无障碍服务未开启"
        uiState.paused -> "已暂停"
        uiState.blockedByForeground -> "当前 App 已自动停用"
        uiState.keyboardMode -> "键盘优先（摇杆让出焦点）"
        !mappingEnabled -> "映射已关闭"
        uiState.active -> "运行中"
        else -> "待机"
    }
    val stateColor = if (stateText == "运行中") GOOD else BAD
    StatRow("映射", stateText, stateColor)
    // 简洁模式：隐藏诊断性的次要状态行
    if (!compact) {
        StatRow("前台 App", uiState.foregroundPkg.ifBlank { "-" })
        GestureCountRow()
        GestureLatencyRow()
    }
}

/** 手势成功 / 失败计数。AtomicInteger 不是 Compose 状态，只能靠心跳刷新。 */
@Composable
private fun GestureCountRow() {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick++
        }
    }
    val text = remember(tick) {
        PadAccessibilityService.gestureOk.get().toString() + " / " +
            PadAccessibilityService.gestureFail.get().toString() + " / " +
            PadAccessibilityService.gestureTimeout.get().toString()
    }
    StatRow("手势 成功/失败/超时", text)
}

/** 注入延迟 p50 / p95：把"卡不卡"从感觉变成数字 */
@Composable
private fun GestureLatencyRow() {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick++
        }
    }
    val text = remember(tick) {
        val s = PadAccessibilityService.gestureLatency.snapshot()
        if (s.isEmpty) "—" else (s.p50.toString() + " / " + s.p95 + " ms")
    }
    StatRow("注入延迟 p50/p95", text)
}

/**
 * 无障碍服务不可用时的醒目提示条。
 *
 * 状态行里已经有"已连接/未连接"，但字小、容易被忽略 ——
 * 而一旦服务没开，整个 App 就是"什么都没反应"，所以值得单独顶一条。
 */
@Composable
private fun ServiceOfflineBanner(
    a11yEnabled: Boolean,
    connected: Boolean,
    onOpenSettings: () -> Unit
) {
    // 只以"系统里是否勾选了本服务"为准。
    // 本机实测：Activity 先组合、服务随后连上时，uiState / instance 可能一直停在"未连接"
    //（服务其实活着），拿它判断就会误报成红条 —— 宁可少报，也不能天天吓人。
    if (a11yEnabled) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = BAD.copy(alpha = 0.14f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                if (!a11yEnabled) "无障碍服务未开启：摇杆、按键、光标都不会工作"
                else "无障碍服务未连接：系统可能刚重启了它，通常几秒内自动恢复",
                fontSize = 12.sp,
                color = BAD
            )
            Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text("打开无障碍设置")
            }
        }
    }
}

/** 光标 FPS / 速度倍率。同样自带心跳，不牵动整页。 */
@Composable
private fun LiveCursorStats(
    uiState: com.padmouse.mapper.capture.ServiceUiState,
    compact: Boolean
) {
    // 数据来自 uiState（服务发布），不需要轮询
    if (!compact) {
        StatRow("光标 FPS", uiState.fps.toString())
    }
    StatRow("速度倍率", "×%.2f".format(uiState.speedMultiplier))
}
/** 单独成一个 composable：日志每 300ms 才发布一次，重组只影响这一小块 */
@Composable
private fun RecentLogs() {
    val logs by Probe.logs.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        logs.takeLast(6).reversed().forEach { e ->
            Text("${e.tag}: ${e.msg}", fontSize = 9.sp, color = MUTED, maxLines = 1)
        }
    }
}

/**
 * 手柄实时状态的一次采样快照。
 *
 * 摇杆事件可能高达 100+ Hz，直接绑 Compose 状态会让整页疯狂重组，
 * 所以统一按 ~30Hz 采样 [LivePad]，只有数值真的变了才发布新快照（data class 相等性）。
 */
private data class PadSnapshot(
    val lx: Float = 0f,
    val ly: Float = 0f,
    val rx: Float = 0f,
    val ry: Float = 0f,
    val hatX: Float = 0f,
    val hatY: Float = 0f,
    val lt: Float = 0f,
    val rt: Float = 0f,
    val buttons: Int = 0
)

/** 有变化时 30Hz 跟手，静止后降频 —— 避免空转占帧 */
@Composable
private fun rememberPadSnapshot(): State<PadSnapshot> {
    val st = remember { mutableStateOf(PadSnapshot()) }
    LaunchedEffect(Unit) {
        var idle = 0
        while (true) {
            val now = PadSnapshot(
                lx = LivePad.leftX,
                ly = LivePad.leftY,
                rx = LivePad.rightX,
                ry = LivePad.rightY,
                hatX = LivePad.hatX,
                hatY = LivePad.hatY,
                lt = LivePad.triggerL,
                rt = LivePad.triggerR,
                buttons = LivePad.buttonsMask()
            )
            if (now != st.value) {
                st.value = now
                idle = 0
            } else {
                idle++
            }
            delay(if (idle > 6) 200 else 33)
        }
    }
    return st
}

// A / B / X / Y 按下时用各自的常见配色，其余按键统一用主题强调色
private val FACE_A = Color(0xFF35B24A)
private val FACE_B = Color(0xFFE0473F)
private val FACE_X = Color(0xFF2C7BE5)
private val FACE_Y = Color(0xFFE0A21B)

/**
 * 手柄实时状态图：按常见手柄的排布画一个示意图 —— 按下的键点亮，摇杆跟着物理位置走。
 *
 * 数据全部来自 [LivePad]（原始输入），不反映反转 / 互换之后的映射值。
 */
@Composable
private fun GamepadLiveView(snap: PadSnapshot, leftDeadzone: Float, rightDeadzone: Float) {
    fun down(bit: Int) = ((snap.buttons shr bit) and 1) == 1
    // 十字键有的手柄报按键事件、有的只报 HAT 轴，两边都认
    val up = down(LivePad.BIT_UP) || snap.hatY < -0.5f
    val dn = down(LivePad.BIT_DOWN) || snap.hatY > 0.5f
    val lf = down(LivePad.BIT_LEFT) || snap.hatX < -0.5f
    val rg = down(LivePad.BIT_RIGHT) || snap.hatX > 0.5f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PANEL_ALT, RoundedCornerShape(18.dp))
            .border(1.dp, DIVIDER, RoundedCornerShape(18.dp))
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TriggerBar("LT", snap.lt, down(LivePad.BIT_LT), Modifier.weight(1f))
            ShoulderKey("LB", down(LivePad.BIT_LB))
            ShoulderKey("RB", down(LivePad.BIT_RB))
            TriggerBar("RT", snap.rt, down(LivePad.BIT_RT), Modifier.weight(1f))
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StickDial("左摇杆", snap.lx, snap.ly, down(LivePad.BIT_L3), leftDeadzone)
                DPad(up, dn, lf, rg)
            }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                SystemPill("View", down(LivePad.BIT_SELECT))
                SystemPill("Xbox", down(LivePad.BIT_MODE))
                SystemPill("Start", down(LivePad.BIT_START))
            }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FaceCluster(
                    a = down(LivePad.BIT_A),
                    b = down(LivePad.BIT_B),
                    x = down(LivePad.BIT_X),
                    y = down(LivePad.BIT_Y)
                )
                StickDial("右摇杆", snap.rx, snap.ry, down(LivePad.BIT_R3), rightDeadzone)
            }
        }
    }
}

/**
 * 摇杆盘：圆盘 + 十字参考线 + 死区圆 + 跟着原始值走的推杆位置。
 *
 * 死区圆沿用初版的画法（半透明填充），一眼能看出"推多少才开始有动作"。
 */
@Composable
private fun StickDial(label: String, x: Float, y: Float, pressed: Boolean, deadzone: Float) {
    val ring = if (pressed) ACCENT_BLUE else DIVIDER
    val cross = DIVIDER.copy(alpha = 0.7f)
    val dot = ACCENT_BLUE
    val plate = PANEL_BG
    val zone = MUTED.copy(alpha = 0.30f)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .background(plate, CircleShape)
                .border(2.dp, ring, CircleShape)
                .drawBehind {
                    val r = size.minDimension / 2f
                    val c = Offset(size.width / 2f, size.height / 2f)
                    drawLine(cross, Offset(c.x - r * 0.85f, c.y), Offset(c.x + r * 0.85f, c.y), 1f)
                    drawLine(cross, Offset(c.x, c.y - r * 0.85f), Offset(c.x, c.y + r * 0.85f), 1f)
                    // 死区：半透明填充圆（初版同款）
                    drawCircle(zone, radius = r * deadzone.coerceIn(0f, 0.9f), center = c)
                    val px = c.x + x.coerceIn(-1f, 1f) * (r - 10f)
                    val py = c.y + y.coerceIn(-1f, 1f) * (r - 10f)
                    drawLine(dot, c, Offset(px, py), 2f)
                    drawCircle(dot, radius = 8f, center = Offset(px, py))
                }
        )
        Text(
            label,
            fontSize = 9.sp,
            color = if (pressed) ACCENT_BLUE else MUTED,
            modifier = Modifier.padding(top = 3.dp)
        )
        Text(
            "%.2f, %.2f".format(x, y),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            color = MUTED
        )
    }
}

/** 十字键：四个方向键，按下（或 HAT 轴推到对应方向）就点亮 */
@Composable
private fun DPad(up: Boolean, down: Boolean, left: Boolean, right: Boolean) {
    Box(modifier = Modifier.size(88.dp)) {
        PadKey("▲", up, Modifier.align(Alignment.TopCenter))
        PadKey("▼", down, Modifier.align(Alignment.BottomCenter))
        PadKey("◀", left, Modifier.align(Alignment.CenterStart))
        PadKey("▶", right, Modifier.align(Alignment.CenterEnd))
    }
}

@Composable
private fun PadKey(glyph: String, pressed: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = modifier
            .size(width = 28.dp, height = 24.dp)
            .background(if (pressed) ACCENT_BLUE else PANEL_BG, shape)
            .border(1.dp, if (pressed) ACCENT_BLUE else DIVIDER, shape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            glyph,
            fontSize = 10.sp,
            color = if (pressed) LocalPadColors.current.onAccent else MUTED
        )
    }
}

/** A / B / X / Y 菱形排布 */
@Composable
private fun FaceCluster(a: Boolean, b: Boolean, x: Boolean, y: Boolean) {
    Box(modifier = Modifier.size(88.dp)) {
        FaceKey("Y", FACE_Y, y, Modifier.align(Alignment.TopCenter))
        FaceKey("X", FACE_X, x, Modifier.align(Alignment.CenterStart))
        FaceKey("B", FACE_B, b, Modifier.align(Alignment.CenterEnd))
        FaceKey("A", FACE_A, a, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun FaceKey(label: String, tint: Color, pressed: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(28.dp)
            .background(if (pressed) tint else PANEL_BG, CircleShape)
            .border(2.dp, if (pressed) tint else DIVIDER, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = if (pressed) Color(0xFF0B1014) else tint
        )
    }
}

/** 扳机：模拟量看条，数字给出原始值 */
@Composable
private fun TriggerBar(label: String, value: Float, pressed: Boolean, modifier: Modifier = Modifier) {
    val v = value.coerceIn(0f, 1f)
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                fontSize = 9.sp,
                color = if (pressed || v > 0.02f) ACCENT_BLUE else MUTED,
                modifier = Modifier.weight(1f)
            )
            Text("%.2f".format(v), fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = MUTED)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(7.dp)
                .background(DIVIDER.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
        ) {
            if (v > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(v)
                        .background(ACCENT_BLUE, RoundedCornerShape(4.dp))
                )
            }
        }
    }
}

@Composable
private fun ShoulderKey(label: String, pressed: Boolean) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .size(width = 36.dp, height = 24.dp)
            .background(if (pressed) ACCENT_BLUE else PANEL_BG, shape)
            .border(1.dp, if (pressed) ACCENT_BLUE else DIVIDER, shape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 9.sp,
            color = if (pressed) LocalPadColors.current.onAccent else MUTED
        )
    }
}

/** View / Xbox / Start 三个小系统键 */
@Composable
private fun SystemPill(label: String, pressed: Boolean) {
    val shape = RoundedCornerShape(11.dp)
    Box(
        modifier = Modifier
            .size(width = 58.dp, height = 22.dp)
            .background(if (pressed) ACCENT_BLUE else PANEL_BG, shape)
            .border(1.dp, if (pressed) ACCENT_BLUE else DIVIDER, shape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 9.sp,
            color = if (pressed) LocalPadColors.current.onAccent else MUTED
        )
    }
}

/** 已按下按键的文字列表（按手柄上的排布顺序） */
private fun pressedKeysText(mask: Int): String {
    if (mask == 0) return "—"
    val names = listOf(
        LivePad.BIT_A to "A", LivePad.BIT_B to "B", LivePad.BIT_X to "X", LivePad.BIT_Y to "Y",
        LivePad.BIT_LB to "LB", LivePad.BIT_RB to "RB", LivePad.BIT_LT to "LT", LivePad.BIT_RT to "RT",
        LivePad.BIT_L3 to "L3", LivePad.BIT_R3 to "R3",
        LivePad.BIT_SELECT to "View", LivePad.BIT_START to "Start", LivePad.BIT_MODE to "Xbox",
        LivePad.BIT_UP to "↑", LivePad.BIT_DOWN to "↓",
        LivePad.BIT_LEFT to "←", LivePad.BIT_RIGHT to "→"
    )
    return names.filter { (bit, _) -> ((mask shr bit) and 1) == 1 }
        .joinToString(" ") { it.second }
}