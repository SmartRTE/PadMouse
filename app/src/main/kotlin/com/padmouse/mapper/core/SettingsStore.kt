package com.padmouse.mapper.core

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.padmouse.mapper.model.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 极简设置存储：SharedPreferences 存一段 JSON，StateFlow 供 Compose 订阅。
 * 选择 SharedPreferences 而非 DataStore，是为了零额外依赖、进出无障碍服务无协程负担。
 *
 * 两个关键点：
 *  1. **读-改-写必须原子**：UI 线程拖滑块与服务线程（bindKey / setMappingEnabled）
 *     可能同时调用 update()，不锁就会丢更新。
 *  2. **持久化要防抖**：拖一次滑块会触发几十次 update，每次都全量序列化 JSON
 *     再写盘，太浪费。StateFlow 立即更新（UI 无感），磁盘写入合并成一次。
 */
object SettingsStore {

    private const val FILE = "padmouse_settings"
    private const val KEY = "settings_json"

    /** 持久化防抖间隔：拖动滑块停止后才真正写盘 */
    private const val PERSIST_DEBOUNCE_MS = 400L

    private var prefs: SharedPreferences? = null

    private val _state = MutableStateFlow(AppSettings())
    val state: StateFlow<AppSettings> = _state

    private val handler = Handler(Looper.getMainLooper())
    private var pendingPersist: Runnable? = null

    @Synchronized
    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs = p
        val raw = p.getString(KEY, null)
        val parsed = AppSettings.parse(raw)
        parsed.onFailure { Probe.log("CFG", "本地配置解析失败，回退默认：" + (it.message ?: "")) }
        _state.value = parsed.getOrElse { AppSettings() }
        Probe.log("CFG", "loaded: " + _state.value.toJson())
    }

    val current: AppSettings get() = _state.value

    /**
     * 原子读-改-写。锁覆盖"读旧值 → 应用 block → 写新值"整段，
     * 避免多线程互相覆盖。
     */
    @Synchronized
    fun update(block: (AppSettings) -> AppSettings) {
        val next = block(_state.value)
        if (next == _state.value) return
        _state.value = next
        schedulePersist(next)
    }

    /** 整体替换（导入配置用） */
    @Synchronized
    fun replaceAll(next: AppSettings) {
        _state.value = next
        schedulePersist(next)
        Probe.log("CFG", "replaced by import: " + next.toJson())
    }

    /** 立刻落盘（进程被杀前的保险） */
    @Synchronized
    fun flushPersist() {
        pendingPersist?.let { handler.removeCallbacks(it) }
        pendingPersist = null
        prefs?.edit()?.putString(KEY, _state.value.toJson())?.apply()
    }

    /** 合并写盘：多次 update 只写一次 */
    private fun schedulePersist(next: AppSettings) {
        pendingPersist?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            pendingPersist = null
            prefs?.edit()?.putString(KEY, next.toJson())?.apply()
        }
        pendingPersist = r
        handler.postDelayed(r, PERSIST_DEBOUNCE_MS)
    }
}
