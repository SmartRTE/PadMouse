package com.padmouse.mapper.core

import com.padmouse.mapper.model.ActionId
import com.padmouse.mapper.model.AppSettings
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 配置导入 / 导出。
 *
 * 导出格式（带一层信封，便于识别与版本演进）：
 * {
 *   "app": "PadMouse",
 *   "schema": 6,
 *   "exportedAt": "2026-11-15 14:30:00",
 *   "settings": { ...完整配置... }
 * }
 *
 * 导入时也接受裸的 settings 对象（即只有里面那层），方便手改的配置。
 *
 * schema 与 AppSettings.VERSION 是同一个值 —— 不再各写一套版本号。
 */
object ConfigIO {

    const val APP = "PadMouse"

    /** 信封版本 = 配置结构版本，唯一事实来源在 AppSettings.VERSION */
    const val SCHEMA = AppSettings.VERSION

    private val fmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun export(settings: AppSettings): String {
        val o = JSONObject()
        o.put("app", APP)
        o.put("schema", SCHEMA)
        o.put("exportedAt", LocalDateTime.now().format(fmt))
        o.put("settings", JSONObject(settings.toJson()))
        return o.toString(2)
    }

    /**
     * 导入。失败时给出**明确原因**，不再"吃下坏 JSON 然后把用户设置清零"。
     */
    fun import(text: String): Result<AppSettings> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("内容为空"))
        }
        if (!trimmed.startsWith("{")) {
            return Result.failure(IllegalArgumentException("不是有效的 JSON 对象"))
        }
        val o = try {
            JSONObject(trimmed)
        } catch (t: Throwable) {
            return Result.failure(
                IllegalArgumentException("JSON 解析失败：" + (t.message ?: t.javaClass.simpleName))
            )
        }

        // 信封里的 schema 比当前新 → 明确拒绝，避免"部分字段被静默丢弃"
        val schema = o.optInt("schema", -1)
        if (schema > SCHEMA) {
            return Result.failure(
                IllegalArgumentException("配置版本 $schema 高于当前支持的 $SCHEMA，请先升级应用")
            )
        }

        val inner = o.optJSONObject("settings") ?: o
        // 至少要有我们认识的字段，避免把随便一个 JSON 当配置吃掉
        val known = listOf(
            "cursor", "scroll", "gesture", "invert", "binding", "lists",
            "mappingEnabled", "hud", "themeMode", "cursorStick", "swapDpadStick"
        )
        if (known.none { inner.has(it) }) {
            return Result.failure(IllegalArgumentException("不是 PadMouse 配置（缺少已知字段）"))
        }
        // 解析交给 AppSettings.parse，坏 JSON 在这里就失败，不会变成"全默认"
        return AppSettings.parse(inner.toString())
    }

    /** 给用户看的导入摘要 */
    fun summary(s: AppSettings): String {
        val bound = ActionId.entries.count { s.binding.keysOf(it).isNotEmpty() }
        val keys = s.binding.reverseIndex().size
        val scrollMode = if (s.scroll.smooth) {
            "丝滑（" + "%.2f".format(s.scroll.speedRatio) + " 屏短边/秒，" + s.scroll.chunkMs + "ms/段）"
        } else {
            "分段"
        }
        return "已导入：主题=" + s.themeMode + "，滚动=" + scrollMode + "，已绑定功能=" + bound +
            " 个（共 " + keys + " 个按键），黑名单=" + s.lists.blacklist.size + " 个 App"
    }
}
