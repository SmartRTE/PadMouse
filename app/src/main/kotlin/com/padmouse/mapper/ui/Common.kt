package com.padmouse.mapper.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.padmouse.mapper.core.SettingsStore

// 颜色统一在 AppTheme.kt 里按主题定义（PANEL_BG / ACCENT_BLUE / MUTED / GOOD / BAD 等）

/**
 * 简洁模式：为 true 时，所有"说明性文字"（卡片副标题、滑块提示、开关提示）一律不画。
 *
 * 做成 CompositionLocal 而不是每个调用点写 `if (compact) null else ...`：
 * 以前只有部分页面接了简洁模式，参数页这类就漏了，说明文字照样全显示。
 * 现在由脚手架统一下发，任何页面里的 Section / SliderRow / ToggleRow 自动跟随。
 */
val LocalCompactUi = staticCompositionLocalOf { false }

/** 统一的卡片分区 */
@Composable
fun Section(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = PANEL_BG)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            // 宽屏两列时行距比单列紧 20%，窄屏维持原样
            verticalArrangement = Arrangement.spacedBy(if (LocalWideLayout.current) PANE_GAP_WIDE else 10.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = ACCENT_BLUE)
            if (subtitle != null && !LocalCompactUi.current) {
                Text(subtitle, fontSize = 11.sp, color = MUTED)
            }
            content()
        }
    }
}

@Composable
fun StatRow(label: String, value: String, valueColor: Color = TEXT_PRIMARY) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, color = MUTED, modifier = Modifier.weight(1f))
        Text(value, fontSize = 13.sp, color = valueColor, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun ToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    hint: String? = null,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 13.sp, color = if (enabled) TEXT_PRIMARY else TEXT_DISABLED)
            if (hint != null && !LocalCompactUi.current) Text(hint, fontSize = 10.sp, color = MUTED)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/**
 * 简洁模式：开启时隐藏教程性/诊断性的说明文字与次要状态行。
 *
 * 做成全局开关而不是逐处判断，是为了让各页面只要问一句
 * "简洁模式开吗" 就能决定要不要画，省得到处散落 if。
 */
@Composable
fun rememberCompactMode(): Boolean {
    val s by SettingsStore.state.collectAsState()
    return s.compactMode
}

/**
 * 可折叠的卡片分区。
 *
 * 标题行可点击展开/收起；默认收起时只占一行高度。
 * 用于内容很长又平时不看的东西（比如「当前配置（可导出）」）。
 */
@Composable
fun CollapsibleSection(
    title: String,
    subtitle: String? = null,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = PANEL_BG)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(if (LocalWideLayout.current) PANE_GAP_WIDE else 10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = ACCENT_BLUE,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    if (expanded) "▲" else "▼",
                    fontSize = 11.sp,
                    color = MUTED
                )
            }
            if (expanded) {
                if (subtitle != null && !LocalCompactUi.current) {
                    Text(subtitle, fontSize = 11.sp, color = MUTED)
                }
                content()
            }
        }
    }
}

/**
 * 带数值显示的滑块。参数全部可调是本项目的核心需求（换设备只靠调参适配）。
 */
@Composable
fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    fmt: (Float) -> String = { "%.2f".format(it) },
    step: Int = 0,
    hint: String? = null,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(fmt(value), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = ACCENT_BLUE)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = step,
            // 宽屏双列时别让一根滑块横跨整列横向过长，拖一格跳太多
            modifier = Modifier.widthIn(max = 520.dp)
        )
        if (hint != null && !LocalCompactUi.current) Text(hint, fontSize = 10.sp, color = MUTED)
    }
}
