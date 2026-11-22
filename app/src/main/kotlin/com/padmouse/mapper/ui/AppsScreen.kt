package com.padmouse.mapper.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.padmouse.mapper.capture.PadAccessibilityService
import com.padmouse.mapper.core.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppEntry(val pkg: String, val label: String)

private fun loadInstalledApps(ctx: Context): List<AppEntry> {
    val pm = ctx.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .mapNotNull { it.activityInfo?.applicationInfo }
        .distinctBy { it.packageName }
        .map {
            AppEntry(
                pkg = it.packageName,
                label = runCatching { pm.getApplicationLabel(it).toString() }.getOrDefault(it.packageName)
            )
        }
        .sortedBy { it.label.lowercase() }
}

/**
 * 应用分级：
 *  - 停用：进入该 App 时完全关闭映射（用于银行/支付等检测无障碍的 App）
 *  - 键盘优先：保留光标与按键，但让出输入焦点，保证能弹输入法（用于聊天/搜索类）
 */
@Composable
fun AppsScreen() {

    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    val s by SettingsStore.state.collectAsState()

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadInstalledApps(ctx) }
    }

    val filtered = remember(apps, query) {
        if (query.isBlank()) apps
        else apps.filter { it.label.contains(query, true) || it.pkg.contains(query, true) }
    }

    // 宽屏两列，窄屏一列：一屏能多看一倍的 App
    val wide = LocalWideLayout.current
    LazyVerticalGrid(
        columns = GridCells.Fixed(if (wide) 2 else 1),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {

        item(span = { GridItemSpan(maxLineSpan) }) {
            Section("应用分级", "共 ${apps.size} 个可启动应用") {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索应用", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    "停用：进入即完全关闭映射。键盘优先：让出输入焦点以便打字（此时摇杆不可用，肩键/十字键仍可用）。",
                    fontSize = 10.sp,
                    color = MUTED
                )
            }
        }

        // 表头放进列表里：按当前列数各放一份，跟下面每条 App 行的勾选列同宽（竖屏/横屏都不会错位）
        repeat(if (wide) 2 else 1) {
            item { AppColumnHeader() }
        }

        items(filtered, key = { it.pkg }) { app ->
            val isBlack = s.lists.blacklist.contains(app.pkg)
            val isKb = s.lists.keyboardPriority.contains(app.pkg)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(app.label, fontSize = 13.sp, maxLines = 1, color = TEXT_PRIMARY)
                    Text(
                        app.pkg,
                        fontSize = 9.sp,
                        color = MUTED,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1
                    )
                }
                Box(
                    modifier = Modifier.width(COL_STOP_DP.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Checkbox(
                        checked = isBlack,
                        onCheckedChange = { on ->
                            SettingsStore.update { st ->
                                val nb = if (on) st.lists.blacklist + app.pkg else st.lists.blacklist - app.pkg
                                val nk = if (on) st.lists.keyboardPriority - app.pkg else st.lists.keyboardPriority
                                st.copy(lists = st.lists.copy(blacklist = nb, keyboardPriority = nk))
                            }
                            PadAccessibilityServiceRefresh()
                        }
                    )
                }
                Box(
                    modifier = Modifier.width(COL_KB_DP.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Checkbox(
                        checked = isKb,
                        onCheckedChange = { on ->
                            SettingsStore.update { st ->
                                val nk = if (on) st.lists.keyboardPriority + app.pkg else st.lists.keyboardPriority - app.pkg
                                val nb = if (on) st.lists.blacklist - app.pkg else st.lists.blacklist
                                st.copy(lists = st.lists.copy(keyboardPriority = nk, blacklist = nb))
                            }
                            PadAccessibilityServiceRefresh()
                        }
                    )
                }
            }
        }
    }
}

/** 两个勾选列的宽度：表头与数据行共用，改这里两边一起变，永远不会错位 */
private const val COL_STOP_DP = 52
private const val COL_KB_DP = 64

/** 列表内的表头行：应用 / 停用 / 键盘优先 */
@Composable
private fun AppColumnHeader() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("应用", fontSize = 11.sp, color = MUTED, modifier = Modifier.weight(1f))
        Text(
            "停用",
            fontSize = 10.sp,
            color = MUTED,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(COL_STOP_DP.dp)
        )
        Text(
            "键盘优先",
            fontSize = 10.sp,
            color = MUTED,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(COL_KB_DP.dp)
        )
    }
}

private fun PadAccessibilityServiceRefresh() {
    PadAccessibilityService.instance?.applyState("app list changed")
}
