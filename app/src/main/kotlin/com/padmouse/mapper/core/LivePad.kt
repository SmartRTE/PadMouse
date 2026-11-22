package com.padmouse.mapper.core

import android.os.SystemClock
import android.view.KeyEvent
import java.util.concurrent.atomic.AtomicInteger

/**
 * 手柄「物理实时状态」—— 给「状态」页的手柄实时预览用。
 *
 * 这里记录的全是**原始输入**：没有反转、没有「十字键 ⇄ 左摇杆 互换」、没有死区、
 * 没有斜坡加速。手柄报上来什么就是什么 —— 调「轴反转」时可以直接对照，
 * 看清楚自己按的到底是哪一个方向。
 *
 * 为什么用 @Volatile 普通字段而不是 StateFlow / Compose State：
 * 摇杆事件可能高达 100+ Hz，逐事件触发重组会把 UI 拖垮。
 * UI 侧用 ~30Hz 采样读取，只有数值真的变了才重组。
 */
object LivePad {

    // ------------------------------------------------ 按键位（buttonsMask 的 bit）

    const val BIT_A = 0
    const val BIT_B = 1
    const val BIT_X = 2
    const val BIT_Y = 3
    const val BIT_LB = 4
    const val BIT_RB = 5
    const val BIT_LT = 6
    const val BIT_RT = 7
    const val BIT_L3 = 8
    const val BIT_R3 = 9
    const val BIT_START = 10
    const val BIT_SELECT = 11
    const val BIT_MODE = 12
    const val BIT_UP = 13
    const val BIT_DOWN = 14
    const val BIT_LEFT = 15
    const val BIT_RIGHT = 16

    // ------------------------------------------------ 轴（原始值，未反转 / 未互换）

    @Volatile
    var leftX = 0f

    @Volatile
    var leftY = 0f

    @Volatile
    var rightX = 0f

    @Volatile
    var rightY = 0f

    /** 十字键轴值（AXIS_HAT_X / AXIS_HAT_Y，-1..1；Y 为 -1 表示向上） */
    @Volatile
    var hatX = 0f

    @Volatile
    var hatY = 0f

    /** 扳机模拟量 0..1 */
    @Volatile
    var triggerL = 0f

    @Volatile
    var triggerR = 0f

    /** 最近一次收到任何手柄输入的时刻 */
    @Volatile
    var lastEventAt = 0L
        private set

    private val mask = AtomicInteger(0)

    /** 当前按下的按键集合（位掩码，位定义见 BIT_*） */
    fun buttonsMask(): Int = mask.get()

    fun isDown(bit: Int): Boolean = ((mask.get() shr bit) and 1) == 1

    fun setLeft(x: Float, y: Float) {
        leftX = x
        leftY = y
        touch()
    }

    fun setRight(x: Float, y: Float) {
        rightX = x
        rightY = y
        touch()
    }

    fun setHat(x: Float, y: Float) {
        hatX = x
        hatY = y
        touch()
    }

    fun setTriggers(lt: Float, rt: Float) {
        triggerL = lt
        triggerR = rt
        touch()
    }

    /**
     * 记录一次物理按键按下 / 抬起。
     *
     * 与映射无关：这个键就算被映射消费掉、或者当前暂停了映射，也照样记下来 ——
     * 预览要显示的是「手柄上真的被按住了什么」。
     */
    fun onKey(keyCode: Int, action: Int) {
        val bit = bitOf(keyCode)
        if (bit < 0) return
        val flag = 1 shl bit
        if (action == KeyEvent.ACTION_DOWN) {
            mask.set(mask.get() or flag)
        } else if (action == KeyEvent.ACTION_UP) {
            mask.set(mask.get() and flag.inv())
        } else {
            return
        }
        touch()
    }

    fun reset() {
        leftX = 0f
        leftY = 0f
        rightX = 0f
        rightY = 0f
        hatX = 0f
        hatY = 0f
        triggerL = 0f
        triggerR = 0f
        mask.set(0)
        lastEventAt = 0L
    }

    /** 最近 2 秒内有输入 */
    fun hasSignal(): Boolean = SystemClock.elapsedRealtime() - lastEventAt < 2000L

    private fun touch() {
        lastEventAt = SystemClock.elapsedRealtime()
    }

    /** 手柄按键 → 位号；不是手柄按键的返回 -1（触摸、键盘等一律忽略） */
    private fun bitOf(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> BIT_A
        KeyEvent.KEYCODE_BUTTON_B -> BIT_B
        KeyEvent.KEYCODE_BUTTON_X -> BIT_X
        KeyEvent.KEYCODE_BUTTON_Y -> BIT_Y
        KeyEvent.KEYCODE_BUTTON_L1 -> BIT_LB
        KeyEvent.KEYCODE_BUTTON_R1 -> BIT_RB
        KeyEvent.KEYCODE_BUTTON_L2 -> BIT_LT
        KeyEvent.KEYCODE_BUTTON_R2 -> BIT_RT
        KeyEvent.KEYCODE_BUTTON_THUMBL -> BIT_L3
        KeyEvent.KEYCODE_BUTTON_THUMBR -> BIT_R3
        KeyEvent.KEYCODE_BUTTON_START -> BIT_START
        KeyEvent.KEYCODE_BUTTON_SELECT -> BIT_SELECT
        KeyEvent.KEYCODE_BUTTON_MODE -> BIT_MODE
        KeyEvent.KEYCODE_DPAD_UP -> BIT_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> BIT_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> BIT_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> BIT_RIGHT
        else -> -1
    }
}
