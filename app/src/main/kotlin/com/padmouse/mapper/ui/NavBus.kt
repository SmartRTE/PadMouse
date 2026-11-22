package com.padmouse.mapper.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 页面跳转总线。
 *
 * 有些操作要跨页：比如「按键」页和「参数」页顶部的入口要跳到
 * 「状态」页底部的「配置导入/导出」区域。
 * 这些页面之间没有父子关系，用一个进程内单例传递"要跳到哪"最简单。
 */
object NavBus {

    /** 状态页里的锚点：跳过去后滚到对应位置 */
    enum class Anchor {
        /** 配置导入 / 导出 */
        IMPORT_EXPORT
    }

    private val _anchor = MutableStateFlow<Anchor?>(null)
    val anchor: StateFlow<Anchor?> = _anchor

    /** 请求跳到某个锚点 */
    fun request(a: Anchor) {
        _anchor.value = a
    }

    /** 目标页消费完锚点后调用，避免重复滚动 */
    fun consume() {
        _anchor.value = null
    }
}
