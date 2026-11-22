package com.padmouse.mapper.capture

/**
 * 服务状态快照 —— UI 订阅它来显示"暂停/恢复""显示/隐藏光标"这类文字。
 *
 * 全部是不可变值，服务每次变化时发布一个新实例，
 * UI 用 collectAsState 订阅，Compose 自然会在变化时重组。
 */
data class ServiceUiState(
    /** 服务实例是否已连接（onServiceConnected 之后为 true） */
    val connected: Boolean = false,
    /** 用户主动暂停 */
    val paused: Boolean = false,
    /** 映射当前是否真的在生效（综合 paused / 黑名单 / 总开关） */
    val active: Boolean = false,
    /** 光标是否可见 */
    val cursorVisible: Boolean = true,
    /** 输入法模式（临时让出焦点打字） */
    val imeModeActive: Boolean = false,
    /** 当前前台包名 */
    val foregroundPkg: String = "",
    /** 前台 App 在黑名单里，已自动停用 */
    val blockedByForeground: Boolean = false,
    /** 前台 App 是键盘优先（让出焦点） */
    val keyboardMode: Boolean = false,
    /** 光标 FPS（调试用） */
    val fps: Int = 0,
    /** 当前速度倍率 */
    val speedMultiplier: Float = 1f,
    /** Captor 焦点窗是否显示（调试页用） */
    val captorShown: Boolean = false,
    /** 光标悬浮窗是否显示（调试页用） */
    val cursorOverlayShown: Boolean = false
)
