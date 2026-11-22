package com.padmouse.mapper

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.padmouse.mapper.core.SettingsStore
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.padmouse.mapper.ui.AdaptiveAppScaffold
import com.padmouse.mapper.ui.AppsScreen
import com.padmouse.mapper.ui.BindingsScreen
import com.padmouse.mapper.ui.HomeScreen
import com.padmouse.mapper.ui.NavBus
import com.padmouse.mapper.ui.NavDest
import com.padmouse.mapper.ui.PadTheme
import com.padmouse.mapper.ui.ProbeScreen
import com.padmouse.mapper.ui.TuneScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsStore.init(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }

        setContent {
            // 只订阅 themeMode：否则拖动任何滑块（每次都改设置）都会重建整个主题树，造成掉帧
            val mode by remember {
                SettingsStore.state
                    .map { it.themeMode }
                    .distinctUntilChanged()
            }.collectAsState(initial = SettingsStore.current.themeMode)

            PadTheme(mode = mode) {
                AppRoot()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // 拖完滑块立刻切走/被系统回收时，把防抖中的设置落盘，别丢最后一次改动
        SettingsStore.flushPersist()
    }
}

@Composable
private fun AppRoot() {
    // 布局由 AdaptiveAppScaffold 决定：窄屏 = 顶部页签 + 单列；宽屏（含手机横屏）= 左侧文字导航 + 双列
    var dest by remember { mutableStateOf(NavDest.STATUS) }

    // 跨页跳转：别的页面请求跳到状态页的某个锚点时，自动切过去。
    //
    // 注意这里**不** consume —— 由目标页（HomeScreen）滚动到位后再消费。
    // 否则 anchor 会在这里就被清成 null，目标页拿不到"要跳到哪"的信息，
    // 表现就是：切页了但没滚动。
    val anchor by NavBus.anchor.collectAsState()
    LaunchedEffect(anchor) {
        if (anchor != null) {
            dest = NavDest.STATUS
        }
    }

    AdaptiveAppScaffold(selected = dest, onSelect = { dest = it }) {
        when (dest) {
            NavDest.STATUS -> HomeScreen()
            NavDest.KEYS -> BindingsScreen()
            NavDest.TUNE -> TuneScreen()
            NavDest.APPS -> AppsScreen()
            NavDest.DEBUG -> ProbeScreen()
        }
    }
}
