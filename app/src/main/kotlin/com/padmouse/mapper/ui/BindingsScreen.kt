package com.padmouse.mapper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.padmouse.mapper.capture.PadAccessibilityService
import com.padmouse.mapper.core.BindingBus
import com.padmouse.mapper.core.SettingsStore
import com.padmouse.mapper.model.ActionId
import com.padmouse.mapper.model.BindingCfg
import com.padmouse.mapper.model.prettyKeyName

/**
 * 按键绑定：每个功能可绑定多个手柄按键。
 * 点「＋」→ 按手柄上的键 → 自动录入；点键标签上的 ✕ 删除。
 */
@Composable
fun BindingsScreen() {

    val s by SettingsStore.state.collectAsState()
    val rec by BindingBus.state.collectAsState()
    val compact = rememberCompactMode()

    // 录制对话框
    if (rec.recording) {
        AlertDialog(
            onDismissRequest = { BindingBus.end() },
            title = { Text("录入按键") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("功能：${rec.action?.label}", fontSize = 13.sp)
                    Text(
                        rec.message ?: "请按手柄上要绑定的键…",
                        fontSize = 13.sp,
                        color = ACCENT_BLUE
                    )
                    Text("按下的键会追加到该功能，不会覆盖已有绑定。", fontSize = 11.sp, color = MUTED)
                }
            },
            confirmButton = {
                TextButton(onClick = { BindingBus.end() }) { Text("取消") }
            }
        )
    }

    // 宽屏两列铺卡片，窄屏一列（GridCells.Fixed(1) 与列表视觉一致）
    val wide = LocalWideLayout.current
    LazyVerticalGrid(
        columns = GridCells.Fixed(if (wide) 2 else 1),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {

        // 顶部跳转：去状态页的「配置导入 / 导出」
        item(span = { GridItemSpan(maxLineSpan) }) {
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

        item(span = { GridItemSpan(maxLineSpan) }) {
            Section(
                "按键绑定",
                if (compact) null
                else "点「＋」后按下手柄上的键即可录入。一个功能可绑多个键；点键标签上的 ✕ 删除。"
            ) {
                // 简洁模式：隐藏教程文本
                if (!compact) {
                    Text(
                        "默认：触摸 = A，返回 = B，桌面 = Xbox 键，最近任务 = X，下拉通知栏 = Y，\n" +
                            "音量 + = RB，音量 − = LB，指针位置提示 = R3，加速 = LT。\n" +
                            "「触摸」= 按住当手指贴在屏幕上，松开 = 手指抬起；按住时推摇杆就是拖动。\n" +
                            "十字键与扳机是轴输入，不走这里（十字键已固定为四向滑动）。\n" +
                            "同一个键可以两用：Start 短按 = 光标显示/隐藏，长按 = 切换映射。",
                        fontSize = 11.sp,
                        color = MUTED
                    )
                }

                // 「点击」没绑任何键时，用户按任何键都不会有反应 ——
                // 直接在页面上点出来，别让人去翻日志才发现。
                // 简洁模式下隐藏（默认用 A 键模拟手指按下/抬起，不需要点击功能）。
                if (!compact && s.binding.keysOf(ActionId.CLICK).isEmpty()) {
                    Text(
                        "⚠ 「点击」当前没有绑定任何按键 —— 按手柄上的键不会有反应。" +
                            "点上面的「＋」把某个键绑给「点击（左键）」。",
                        fontSize = 11.sp,
                        color = BAD
                    )
                }
            }
        }

        items(ActionId.entries.toList(), key = { it.name }) { action ->
            val keys = s.binding.keysOf(action)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PANEL_BG, RoundedCornerShape(10.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        action.label,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = TEXT_PRIMARY,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        onClick = { BindingBus.begin(action) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                    ) { Text("＋", fontSize = 15.sp) }
                }

                if (keys.isEmpty()) {
                    Text("未绑定", fontSize = 11.sp, color = MUTED)
                } else {
                    keys.chunked(4).forEach { group ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            group.forEach { code ->
                                KeyChip(prettyKeyName(code)) {
                                    SettingsStore.update { st ->
                                        st.copy(binding = st.binding.withoutKey(action, code))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            Section("恢复默认绑定") {
                OutlinedButton(
                    onClick = {
                        SettingsStore.update { st ->
                            st.copy(binding = BindingCfg())
                        }
                        PadAccessibilityService.instance?.applyState("bindings reset")
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("全部恢复出厂默认") }
            }
        }
    }
}

@Composable
private fun KeyChip(label: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .background(PANEL_ALT, RoundedCornerShape(8.dp))
            .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = TEXT_PRIMARY
        )
        Box(
            modifier = Modifier
                .size(18.dp)
                .clickable { onRemove() },
            contentAlignment = Alignment.Center
        ) {
            Text("✕", fontSize = 11.sp, color = BAD)
        }
    }
}
