package com.padmouse.mapper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 响应式脚手架。
 *
 * 断点用 BoxWithConstraints 的 maxWidth（而不是 orientation）：分屏、平板竖屏、折叠屏都能落对档位。
 *
 * - 窄屏（< 600dp）：顶部 TabRow + 单列
 * - 宽屏（>= 600dp，含手机横屏）：左侧文字导航 + 两列
 *
 * 两列共用一条滚动：一侧滚动另一侧跟着走，找选项不用来回对位置。
 * 例外是调试页的日志列表，它单独滚动（条数不定、内容很长）。
 */

/** 进入宽屏（双列 + 侧边导航）的最小宽度 */
val WIDE_MIN_WIDTH = 600.dp

/** 内容区最大宽度（宽屏时居中） */
val CONTENT_MAX_WIDTH = 1280.dp

/** 卡片纵向间距：窄屏 10dp；宽屏两列收到 8dp（比单列紧 20%，列更窄时不显挤） */
val PANE_GAP_COMPACT = 10.dp
val PANE_GAP_WIDE = 8.dp

/** 五个页签 */
enum class NavDest(val label: String) {
    STATUS("状态"),
    KEYS("按键"),
    TUNE("参数"),
    APPS("应用"),
    DEBUG("调试")
}

/** 当前是否宽屏布局，页面内部据此决定分栏（网格列数、间距等） */
val LocalWideLayout = staticCompositionLocalOf { false }

@Composable
fun AdaptiveAppScaffold(
    selected: NavDest,
    onSelect: (NavDest) -> Unit,
    content: @Composable () -> Unit
) {
    // 简洁模式由这里统一下发：Section / SliderRow / ToggleRow 会自动隐藏说明性文字
    val appSettings by com.padmouse.mapper.core.SettingsStore.state.collectAsState()
    val compactUi = appSettings.compactMode

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(BG)
            .safeDrawingPadding()
    ) {
        val wide = maxWidth >= WIDE_MIN_WIDTH

        CompositionLocalProvider(
            LocalWideLayout provides wide,
            LocalCompactUi provides compactUi
        ) {
            if (!wide) {
                Column(modifier = Modifier.fillMaxSize()) {
                    TabRow(selectedTabIndex = selected.ordinal, containerColor = PANEL_ALT) {
                        NavDest.entries.forEach { d ->
                            Tab(
                                selected = d == selected,
                                onClick = { onSelect(d) },
                                text = { Text(d.label, fontSize = 13.sp) }
                            )
                        }
                    }
                    Box(modifier = Modifier.fillMaxSize()) { content() }
                }
            } else {
                Row(modifier = Modifier.fillMaxSize()) {
                    NavRail(selected = selected, onSelect = onSelect)
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Box(
                            modifier = Modifier
                                .widthIn(max = CONTENT_MAX_WIDTH)
                                .fillMaxSize()
                        ) {
                            content()
                        }
                    }
                }
            }
        }
    }
}

/** 宽屏左侧导航：纯文字，每个页签两个字横排一行 */
@Composable
private fun NavRail(selected: NavDest, onSelect: (NavDest) -> Unit) {
    Column(
        modifier = Modifier
            .width(72.dp)
            .fillMaxHeight()
            .background(PANEL_ALT)
            .padding(horizontal = 6.dp, vertical = 10.dp)
            // 手机横屏竖向空间小，给未来加页签留余地
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        NavDest.entries.forEach { d ->
            val on = d == selected
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) ACCENT_BLUE.copy(alpha = 0.16f) else Color.Transparent)
                    .clickable { onSelect(d) }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = d.label,
                    fontSize = 13.sp,
                    maxLines = 1,
                    color = if (on) ACCENT_BLUE else MUTED
                )
            }
        }
    }
}

/**
 * 一页的两栏内容，左右两栏同步滚动（整页只有一条滚动，滚动挂在外层 Row 上）。
 *
 * 窄屏拼成一列。
 */
@Composable
fun AdaptivePanes(
    scrollState: ScrollState = rememberScrollState(),
    left: @Composable ColumnScope.() -> Unit,
    right: @Composable ColumnScope.() -> Unit
) {
    if (!LocalWideLayout.current) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 12.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(PANE_GAP_COMPACT)
        ) {
            left()
            right()
        }
    } else {
        // 关键：滚动挂在整行上，两列一起动
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp)
                .verticalScroll(scrollState),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(PANE_GAP_WIDE)
            ) {
                left()
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(PANE_GAP_WIDE)
            ) {
                right()
            }
        }
    }
}

/**
 * 左栏静态卡片 + 右栏独立滚动的懒加载列表（调试页日志用）。
 *
 * 日志条数不定，所以右栏保留 LazyColumn，自己滚，不拖着左栏走。
 *
 * maxListHeight：列表的最大高度（null = 撑满）。
 * 给它一个上限后，条目超出就在**列表内部**滚动，
 * 而不会把整个页面越撑越高 —— 日志动辄几百条，不限高的话页面会被顶飞。
 */
@Composable
fun AdaptivePanesWithList(
    maxListHeight: Dp? = null,
    left: @Composable ColumnScope.() -> Unit,
    rightList: LazyListScope.() -> Unit
) {
    if (!LocalWideLayout.current) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(PANE_GAP_COMPACT),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(PANE_GAP_COMPACT)) { left() }
            }
            // 窄屏：列表单独一个 item，内部自己滚，高度受 maxListHeight 约束
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (maxListHeight != null) Modifier.height(maxListHeight) else Modifier)
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(PANE_GAP_COMPACT)
                    ) {
                        rightList()
                    }
                }
            }
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(PANE_GAP_WIDE)
            ) {
                left()
            }
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(if (maxListHeight != null) Modifier.heightIn(max = maxListHeight) else Modifier),
                verticalArrangement = Arrangement.spacedBy(PANE_GAP_WIDE),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                rightList()
            }
        }
    }
}

/**
 * 兼容旧写法：各页内容里原本用 item { ... } 包一块卡片，这里保持同名函数，
 * 实际就是原样调用，纵向间距交给外层 Column 的 verticalArrangement。
 */
@Composable
fun ColumnScope.item(content: @Composable ColumnScope.() -> Unit) {
    content()
}
