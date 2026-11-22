package com.padmouse.mapper.core

import com.padmouse.mapper.model.ActionId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 按键录制会话语义：UI 发起 → 服务在按键流里截获 → 回写绑定。
 * 用同进程单例 + StateFlow 传递，避免 UI 与服务耦合。
 */
object BindingBus {

    data class Session(
        val action: ActionId? = null,
        val lastKey: Int? = null,
        val message: String? = null
    ) {
        val recording: Boolean get() = action != null
    }

    private val _state = MutableStateFlow(Session())
    val state: StateFlow<Session> = _state

    val isRecording: Boolean get() = _state.value.action != null
    val recordingAction: ActionId? get() = _state.value.action

    fun begin(action: ActionId) {
        _state.value = Session(action = action, message = "请按手柄上要绑定的键…")
        Probe.log("BIND", "recording start for ${action.name}")
    }

    fun noteKey(code: Int, name: String) {
        _state.value = _state.value.copy(lastKey = code, message = "已捕获：$name")
    }

    fun end() {
        _state.value = Session()
    }
}
