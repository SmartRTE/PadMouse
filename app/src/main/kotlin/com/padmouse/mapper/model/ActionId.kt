package com.padmouse.mapper.model

import android.view.KeyEvent

/**
 * 可绑定的功能。每个功能可以绑定**任意多个手柄按键**（用户可在界面里录制）。
 * defaultKeys 是出厂默认，用户改动后存进 BindingCfg。
 */
enum class ActionId(val label: String, val defaultKeys: List<Int>) {

    // 指针动作
    CLICK("点击（左键）", emptyList()),
    TOUCH_HOLD("触摸（按住拖动）", listOf(KeyEvent.KEYCODE_BUTTON_A)),
    LONGPRESS("长按（右键 / 菜单）", emptyList()),
    DOUBLE("双击", emptyList()),

    // 系统动作
    BACK("返回", listOf(KeyEvent.KEYCODE_BUTTON_B)),
    HOME("桌面", listOf(KeyEvent.KEYCODE_BUTTON_MODE)),
    RECENTS("最近任务", listOf(KeyEvent.KEYCODE_BUTTON_X)),
    NOTIF("下拉通知栏", listOf(KeyEvent.KEYCODE_BUTTON_Y)),
    VOL_UP("音量 +", listOf(KeyEvent.KEYCODE_BUTTON_R1)),
    VOL_DOWN("音量 −", listOf(KeyEvent.KEYCODE_BUTTON_L1)),

    // 映射器自身
    //
    // PAUSE 是**长按**语义：按住到阈值才切换映射，短按不触发（可透传给游戏）。
    // 同一个键可以再绑一个普通动作作为**短按**行为 ——
    //   例：Start 短按 = 光标显示/隐藏，长按 = 切换映射。
    // 这种共用不算冲突，服务在松手时按「是否达到长按阈值」分流。
    PAUSE("切换映射（长按）", listOf(KeyEvent.KEYCODE_BUTTON_START)),
    IME_MODE("输入法模式（让出焦点打字）", listOf(KeyEvent.KEYCODE_BUTTON_SELECT)),
    // 短按 Start = 光标显示 / 隐藏（与上面的长按切换映射共存）
    CURSOR_TOGGLE("光标显示 / 隐藏", listOf(KeyEvent.KEYCODE_BUTTON_START)),
    CURSOR_PULSE("指针位置提示（红圈收拢）", listOf(KeyEvent.KEYCODE_BUTTON_THUMBR)),
    // 加速 = LT(104) + L3(106)。L3 之前和「光标显示/隐藏」撞车，
    // 现在 CURSOR_TOGGLE 挪到 Start(108) 了，默认绑定已经没有冲突。
    BOOST("加速（按住）", listOf(KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_THUMBL)),

    // 手势动作（十字键走 HAT 轴，这几项供额外绑定）
    SWIPE_UP("上滑", emptyList()),
    SWIPE_DOWN("下滑", emptyList()),
    SWIPE_LEFT("左滑", emptyList()),
    SWIPE_RIGHT("右滑", emptyList());

    companion object {
        fun of(name: String?): ActionId? = entries.firstOrNull { it.name == name }
    }
}

/** 按键码 → 人类可读名 */
fun prettyKeyName(code: Int): String = when (code) {
    KeyEvent.KEYCODE_BUTTON_A -> "A"
    KeyEvent.KEYCODE_BUTTON_B -> "B"
    KeyEvent.KEYCODE_BUTTON_X -> "X"
    KeyEvent.KEYCODE_BUTTON_Y -> "Y"
    KeyEvent.KEYCODE_BUTTON_L1 -> "LB"
    KeyEvent.KEYCODE_BUTTON_R1 -> "RB"
    KeyEvent.KEYCODE_BUTTON_L2 -> "LT"
    KeyEvent.KEYCODE_BUTTON_R2 -> "RT"
    KeyEvent.KEYCODE_BUTTON_THUMBL -> "L3"
    KeyEvent.KEYCODE_BUTTON_THUMBR -> "R3"
    KeyEvent.KEYCODE_BUTTON_START -> "Start"
    KeyEvent.KEYCODE_BUTTON_SELECT -> "View"
    KeyEvent.KEYCODE_BUTTON_MODE -> "Xbox"
    KeyEvent.KEYCODE_DPAD_UP -> "十字↑"
    KeyEvent.KEYCODE_DPAD_DOWN -> "十字↓"
    KeyEvent.KEYCODE_DPAD_LEFT -> "十字←"
    KeyEvent.KEYCODE_DPAD_RIGHT -> "十字→"
    else -> KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")
}