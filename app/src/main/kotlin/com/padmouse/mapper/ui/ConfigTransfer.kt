package com.padmouse.mapper.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.padmouse.mapper.capture.PadAccessibilityService
import com.padmouse.mapper.core.ConfigIO
import com.padmouse.mapper.core.SettingsStore
import com.padmouse.mapper.model.AppSettings

/**
 * 配置导入 / 导出（状态页与参数页共用）。
 *
 * 抽成独立文件是因为它现在出现在两个地方：
 * 状态页底部（主入口）和参数页（跳转过去的入口）。
 */
@Composable
fun ConfigTransferSection(
    subtitle: String? = null,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current

    fun toast(msg: String) =
        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()

    fun applyImported(s: AppSettings) {
        SettingsStore.replaceAll(s)
        val svc = PadAccessibilityService.instance
        svc?.refreshCursorStyle()
        svc?.onSettingsReplaced()
        toast(ConfigIO.summary(s))
    }

    fun doImport(text: String) {
        ConfigIO.import(text)
            .onSuccess { applyImported(it) }
            .onFailure { toast("导入失败：" + it.message) }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            ctx.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(ConfigIO.export(SettingsStore.current).toByteArray(Charsets.UTF_8))
            } ?: error("无法写入该位置")
        }.onSuccess { toast("配置已导出") }
            .onFailure { toast("导出失败：" + it.message) }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        if (text.isNullOrBlank()) toast("文件读取失败或内容为空") else doImport(text)
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (subtitle != null) {
            Text(subtitle, fontSize = 11.sp, color = MUTED)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { exportLauncher.launch("padmouse-config.json") },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
            ) { Text("导出到文件", fontSize = 12.sp, maxLines = 1) }
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
            ) { Text("从文件导入", fontSize = 12.sp, maxLines = 1) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    copyToClipboard(ctx, ConfigIO.export(SettingsStore.current))
                    toast("配置已复制到剪贴板")
                },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
            ) { Text("复制到剪贴板", fontSize = 12.sp, maxLines = 1) }
            OutlinedButton(
                onClick = {
                    val t = pasteFromClipboard(ctx)
                    if (t.isNullOrBlank()) toast("剪贴板里没有文本") else doImport(t)
                },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
            ) { Text("从剪贴板导入", fontSize = 12.sp, maxLines = 1) }
        }
        Text(
            "导入会整体替换当前配置（含按键绑定与应用列表）。",
            fontSize = 10.sp,
            color = MUTED
        )
    }
}

private fun copyToClipboard(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("PadMouse 配置", text))
}

private fun pasteFromClipboard(ctx: Context): String? {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = cm.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(ctx)?.toString()
}
