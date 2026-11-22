package com.padmouse.mapper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.padmouse.mapper.capture.PadAccessibilityService
import com.padmouse.mapper.core.SettingsStore
import com.padmouse.mapper.model.AppSettings
import com.padmouse.mapper.model.CursorCfg
import com.padmouse.mapper.model.GestureCfg
import com.padmouse.mapper.model.InvertCfg
import com.padmouse.mapper.model.ScrollCfg

/**
 * 全部手感参数。所有滑块直接写入 SettingsStore，服务端每帧读取，改完立即生效。
 */
@Composable
fun TuneScreen() {

    val s by SettingsStore.state.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current

    fun updCursor(f: (CursorCfg) -> CursorCfg) =
        SettingsStore.update { it.copy(cursor = f(it.cursor)) }

    fun updScroll(f: (ScrollCfg) -> ScrollCfg) =
        SettingsStore.update { it.copy(scroll = f(it.scroll)) }

    fun updGesture(f: (GestureCfg) -> GestureCfg) =
        SettingsStore.update { it.copy(gesture = f(it.gesture)) }

    fun updInvert(f: (InvertCfg) -> InvertCfg) =
        SettingsStore.update { it.copy(invert = f(it.invert)) }

    // 注：导入 / 导出已移到「状态」页底部，这里只留一个跳转入口。

    // 左栏：指针 / 轴反转 / 位置提示；右栏：滚动 / 返回 / 手势（窄屏自动拼成一列）
    AdaptivePanes(
        left = {

        // 顶部跳转：去状态页的「配置导入 / 导出」
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { NavBus.request(NavBus.Anchor.IMPORT_EXPORT) },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                ) { Text("配置导入 / 导出", fontSize = 12.sp, maxLines = 1) }
            }
        }

        item {
            Section("指针", "摇杆是相对输入，所以用速度控制；这三个参数决定手感。") {
                SliderRow(
                    "最大速度", s.cursor.maxSpeedRatio, 0.2f..2.0f,
                    fmt = { "×%.2f".format(it) },
                    hint = "× min(屏宽,屏高)/秒"
                ) { v -> updCursor { it.copy(maxSpeedRatio = v) } }

                SliderRow(
                    "死区", s.cursor.deadzone, 0f..0.4f,
                    fmt = { "%.2f".format(it) },
                    hint = "防漂移；径向死区，斜向同样生效"
                ) { v -> updCursor { it.copy(deadzone = v) } }

                SliderRow(
                    "响应曲线 γ", s.cursor.gamma, 1f..3f,
                    fmt = { "%.2f".format(it) },
                    hint = "越大：小推更精细、大推更迅猛"
                ) { v -> updCursor { it.copy(gamma = v) } }

                SliderRow(
                    "平滑时间", s.cursor.smoothingMs, 0f..80f,
                    fmt = { "%.0f ms".format(it) },
                    hint = "越小越跟手，过小会抖"
                ) { v -> updCursor { it.copy(smoothingMs = v) } }

                SliderRow(
                    "精准模式倍率", s.cursor.precisionMul, 0.1f..0.6f,
                    fmt = { "×%.2f".format(it) },
                    hint = "按住 RT 时生效"
                ) { v -> updCursor { it.copy(precisionMul = v) } }

                SliderRow(
                    "加速模式倍率", s.cursor.boostMul, 1.2f..4f,
                    fmt = { "×%.2f".format(it) },
                    hint = "按住 L3（左摇杆按下）时生效"
                ) { v -> updCursor { it.copy(boostMul = v) } }

                SliderRow(
                    "光标大小", s.cursor.sizeDp, 12f..80f,
                    fmt = { "%.0f dp".format(it) },
                    hint = "小屏/平板可调小，避免遮挡内容"
                ) { v ->
                    updCursor { it.copy(sizeDp = v) }
                    com.padmouse.mapper.capture.PadAccessibilityService.instance?.refreshCursorStyle()
                }

                SliderRow(
                    "点击点偏移 X", s.cursor.aimOffsetX, -24f..24f,
                    fmt = { "%.0f px".format(it) },
                    hint = "点击落在箭头尖端；若感觉偏了，用这个微调"
                ) { v -> updCursor { it.copy(aimOffsetX = v) } }

                SliderRow(
                    "点击点偏移 Y", s.cursor.aimOffsetY, -24f..24f,
                    fmt = { "%.0f px".format(it) },
                    hint = "正值 = 点击点下移"
                ) { v -> updCursor { it.copy(aimOffsetY = v) } }
            }
        }


        item {
            Section(
                "指针位置提示",
                "按下绑定的键（默认 R3）时，红色圆圈从大到小收拢到指针处 —— 大屏 + 小指针找不到光标时用。"
            ) {
                ToggleRow(
                    "启用红圈提示", s.cursor.pulseEnabled
                ) { on -> updCursor { it.copy(pulseEnabled = on) } }

                SliderRow(
                    "起始半径", s.cursor.pulseMaxRadiusDp, 80f..400f,
                    fmt = { "%.0f dp".format(it) }
                ) { v -> updCursor { it.copy(pulseMaxRadiusDp = v) } }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val svc = com.padmouse.mapper.capture.PadAccessibilityService.instance
                            if (svc == null) {
                                android.widget.Toast
                                    .makeText(ctx, "无障碍服务未连接", android.widget.Toast.LENGTH_SHORT)
                                    .show()
                            } else {
                                svc.pulseCursor()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("试一下（显示提示圈）") }
                }
            }
        }

        },

        right = {

        item {
            Section(
                "滚动",
                "右摇杆 → 一串联的滑动手势。默认「丝滑滚动」按目标速度反算每段位移，" +
                    "按住时内容近似等速移动；关掉则回到旧的分段滑动。"
            ) {
                ToggleRow(
                    "丝滑滚动（等速）",
                    s.scroll.smooth,
                    hint = "按住摇杆时内容以恒定速度连续滚动，段与段之间不留间隔；" +
                        "关掉则回到旧的「分段滑动」，方便对比手感。"
                ) { on -> updScroll { it.copy(smooth = on) } }

                if (s.scroll.smooth) {
                    SliderRow(
                        "滚动速度", s.scroll.speedRatio, 0.2f..4f,
                        fmt = { "%.2f 屏短边/秒".format(it) },
                        hint = "满推时的速度；1.00 ≈ 每秒滚过一个屏幕短边"
                    ) { v -> updScroll { it.copy(speedRatio = v) } }

                    SliderRow(
                        "单段时长", s.scroll.chunkMs.toFloat(), 120f..320f,
                        fmt = { "%.0f ms".format(it) },
                        hint = "越短越跟手、越长段间边界越少（更顺）。上限 320ms：" +
                            "再长单段缓慢位移会被部分 App 当成「长按」"
                    ) { v -> updScroll { it.copy(chunkMs = v.toLong()) } }
                } else {
                    SliderRow(
                        "单元位移", s.scroll.distanceRatio, 0.08f..0.5f,
                        fmt = { "%.0f%% 屏高".format(it * 100) }
                    ) { v -> updScroll { it.copy(distanceRatio = v) } }

                    SliderRow(
                        "单元时长", s.scroll.unitMs.toFloat(), 140f..420f,
                        fmt = { "%.0f ms".format(it) }
                    ) { v -> updScroll { it.copy(unitMs = v.toLong()) } }
                }

                SliderRow(
                    "滚动曲线", s.scroll.curve, 1f..3f,
                    fmt = { "%.2f".format(it) }
                ) { v -> updScroll { it.copy(curve = v) } }

                SliderRow(
                    "滚动死区", s.scroll.deadzone, 0f..0.5f,
                    fmt = { "%.2f".format(it) }
                ) { v -> updScroll { it.copy(deadzone = v) } }

                ToggleRow(
                    "允许左右滚动",
                    s.scroll.horizontal
                ) { on -> updScroll { it.copy(horizontal = on) } }

                Text("滚动锚点", fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { updScroll { it.copy(anchor = "CURSOR") } },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            if (s.scroll.anchor == "CURSOR") "● 光标处" else "光标处",
                            fontSize = 12.sp
                        )
                    }
                    OutlinedButton(
                        onClick = { updScroll { it.copy(anchor = "CENTER") } },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            if (s.scroll.anchor == "CENTER") "● 屏幕中心" else "屏幕中心",
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        item {
            Section(
                "返回方式",
                "默认「释放焦点」：临时让出输入焦点再注入返回，是实测唯一稳定的方案（因为焦点窗会吃掉返回键）。"
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { updGesture { it.copy(backMode = "RELEASE") } },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                    ) {
                        Text(
                            if (s.gesture.backMode == "RELEASE") "● 释放焦点" else "释放焦点",
                            fontSize = 11.sp, maxLines = 1
                        )
                    }
                    OutlinedButton(
                        onClick = { updGesture { it.copy(backMode = "GLOBAL") } },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                    ) {
                        Text(
                            if (s.gesture.backMode == "GLOBAL") "● 全局动作" else "全局动作",
                            fontSize = 11.sp, maxLines = 1
                        )
                    }
                    OutlinedButton(
                        onClick = { updGesture { it.copy(backMode = "GESTURE") } },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                    ) {
                        Text(
                            if (s.gesture.backMode == "GESTURE") "● 边缘手势" else "边缘手势",
                            fontSize = 11.sp, maxLines = 1
                        )
                    }
                }
                if (s.gesture.backMode == "GESTURE") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { updGesture { it.copy(backEdge = "LEFT") } },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                if (s.gesture.backEdge == "LEFT") "● 左侧划入" else "左侧划入",
                                fontSize = 12.sp
                            )
                        }
                        OutlinedButton(
                            onClick = { updGesture { it.copy(backEdge = "RIGHT") } },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                if (s.gesture.backEdge == "RIGHT") "● 右侧划入" else "右侧划入",
                                fontSize = 12.sp
                            )
                        }
                    }
                }
                Button(
                    onClick = {
                        PadAccessibilityService.instance?.doBack()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("试一下（执行一次返回）") }
            }
        }

        item {
            Section("手势", "点击/长按/滑动的时间参数。点不中就先加大点击时长。") {
                SliderRow(
                    "点击时长", s.gesture.tapMs.toFloat(), 20f..150f,
                    fmt = { "%.0f ms".format(it) },
                    hint = "太短部分 App 会丢事件"
                ) { v -> updGesture { it.copy(tapMs = v.toLong()) } }

                SliderRow(
                    "长按（右键）时长", s.gesture.longPressMs.toFloat(), 300f..1200f,
                    fmt = { "%.0f ms".format(it) }
                ) { v -> updGesture { it.copy(longPressMs = v.toLong()) } }

                SliderRow(
                    "双击间隔", s.gesture.doubleTapMs.toFloat(), 60f..300f,
                    fmt = { "%.0f ms".format(it) }
                ) { v -> updGesture { it.copy(doubleTapMs = v.toLong()) } }

                SliderRow(
                    "滑动距离", s.gesture.swipeDistanceRatio, 0.1f..0.6f,
                    fmt = { "%.0f%% 屏高".format(it * 100) },
                    hint = "十字键上下左右滑动的幅度"
                ) { v -> updGesture { it.copy(swipeDistanceRatio = v) } }

                SliderRow(
                    "滑动时长", s.gesture.swipeMs.toFloat(), 80f..500f,
                    fmt = { "%.0f ms".format(it) }
                ) { v -> updGesture { it.copy(swipeMs = v.toLong()) } }
                SliderRow(
                    "切换映射长按阈值", s.gesture.pauseHoldMs.toFloat(), 300f..1500f,
                    fmt = { "%.0f ms".format(it) },
                    hint = "按住达到这个时长就立刻切换（不用松手）；短按不触发，按键会透传给前台 App"
                ) { v -> updGesture { it.copy(pauseHoldMs = v.toLong()) } }

            }
        }


        item {
            Section("恢复默认") {
                Text(
                    "把指针/滚动/手势参数全部恢复为出厂默认（应用列表保留）。",
                    fontSize = 11.sp,
                    color = MUTED
                )
                Button(
                    onClick = {
                        SettingsStore.update {
                            it.copy(
                                cursor = CursorCfg(),
                                scroll = ScrollCfg(),
                                gesture = GestureCfg()
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("恢复默认参数") }
            }
        }

        item {
            CollapsibleSection(
                "当前配置（可导出）",
                "下面是完整的配置 JSON，复制出来可以给别人导入。",
                initiallyExpanded = false
            ) {
                Text(
                    AppSettings.fromJson(s.toJson()).toJson(),
                    fontSize = 9.sp,
                    color = MUTED
                )
            }
        }
        }
    )
}

// 剪贴板工具已移到 ConfigTransfer.kt（状态页与参数页共用）。
