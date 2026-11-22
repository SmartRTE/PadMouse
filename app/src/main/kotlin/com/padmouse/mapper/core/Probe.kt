package com.padmouse.mapper.core

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.padmouse.mapper.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class LogEntry(val t: Long, val tag: String, val msg: String)

/**
 * 全局探针日志缓冲。服务与 UI 共享，也用于导出诊断报告。
 *
 * 性能注意：手柄推动时 CURSOR / CAPTOR-MOTION 日志可达每秒十几次。
 * 如果每条日志都 copy 一份列表并 emit StateFlow，界面会每次重组 —— 滚动明显掉帧。
 * 所以这里改成内部环形缓冲 + 最多 3 次/秒发布，UI 看到的仍然是 List。
 *
 * release 版本：高频日志（CURSOR / CAPTOR-MOTION / KEYF / DEV）直接丢弃，
 * 只保留级别较高的日志，且不再进 logcat，避免长时间运行刷爆日志缓冲区。
 */
object Probe {

    private const val MAX = 600

    /** UI 发布节流间隔 */
    private const val PUBLISH_INTERVAL_MS = 300L

    /**
     * release 下保留的 tag（其余一律丢弃）。
     *
     * KEYMAP 是新增的：它记录**按键 -> 动作**的映射结果，
     * 是排查"某个键没反应"时最关键的信息 ——
     * 没有它，看到"没报错"反而无从下手。
     */
    private val RELEASE_KEEP =
        setOf("SVC", "GEST", "STATE", "BIND", "WIN", "IME", "TOUCH", "OVL", "KEYMAP")

    private val fmt: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

    private val buffer = ArrayDeque<LogEntry>()
    private var lastPublish = 0L

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs

    /** 该 tag 在当前构建下是否会被记录 —— 调用方据此跳过昂贵的字符串拼接 */
    fun keep(tag: String): Boolean = BuildConfig.DEBUG || tag in RELEASE_KEEP

    /** 惰性版本：只有真会记录时才求值（release 下高频 tag 连字符串都不拼） */
    fun log(tag: String, msg: () -> String) {
        if (!keep(tag)) return
        log(tag, msg())
    }

    fun log(tag: String, msg: String) {
        if (!BuildConfig.DEBUG && tag !in RELEASE_KEEP) return
        val e = LogEntry(System.currentTimeMillis(), tag, msg)
        synchronized(buffer) {
            buffer.addLast(e)
            while (buffer.size > MAX) buffer.removeFirst()
            val now = SystemClock.elapsedRealtime()
            if (now - lastPublish >= PUBLISH_INTERVAL_MS) {
                lastPublish = now
                _logs.value = buffer.toList()
            }
        }
        Log.i("PadProbe", "[" + tag + "] " + msg)
    }

    /** 立即发布一次（页面刚打开、清空后调用） */
    fun flush() {
        synchronized(buffer) {
            lastPublish = SystemClock.elapsedRealtime()
            _logs.value = buffer.toList()
        }
    }

    fun clear() {
        synchronized(buffer) {
            buffer.clear()
            lastPublish = SystemClock.elapsedRealtime()
            _logs.value = emptyList()
        }
    }

    fun report(extra: String): String {
        val sb = StringBuilder()
        sb.appendLine("=== PadMouse Probe Report ===")
        sb.appendLine("time   : " + Instant.now())
        sb.appendLine("version: " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")")
        sb.appendLine("debug  : " + BuildConfig.DEBUG)
        sb.appendLine()
        sb.appendLine(extra)
        sb.appendLine()
        val snapshot = synchronized(buffer) { buffer.toList() }
        sb.appendLine("--- LOG (" + snapshot.size + " entries) ---")
        snapshot.forEach { sb.appendLine(fmt.format(Instant.ofEpochMilli(it.t)) + " [" + it.tag + "] " + it.msg) }
        return sb.toString()
    }

    /** 写到 应用外部私有目录，adb pull 即可取出 */
    fun export(ctx: Context, extra: String): File {
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val f = File(dir, "padprobe.txt")
        f.writeText(report(extra))
        Log.i("PadProbe", "exported -> " + f.absolutePath)
        return f
    }
}
