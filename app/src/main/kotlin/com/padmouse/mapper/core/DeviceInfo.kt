package com.padmouse.mapper.core

import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * 手柄/输入设备枚举与 dump，用于 P0-E6 实验。
 */
object DeviceInfo {

    private val GAMEPAD_KEYS = intArrayOf(
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
        KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
        KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
        KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
        KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR,
        KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT,
        KeyEvent.KEYCODE_BUTTON_MODE,
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER
    )

    private val AXES = intArrayOf(
        MotionEvent.AXIS_X, MotionEvent.AXIS_Y,
        MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
        MotionEvent.AXIS_RX, MotionEvent.AXIS_RY,
        MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y,
        MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER,
        MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_GAS,
        MotionEvent.AXIS_THROTTLE, MotionEvent.AXIS_RUDDER,
        MotionEvent.AXIS_WHEEL, MotionEvent.AXIS_GENERIC_1,
        MotionEvent.AXIS_GENERIC_2, MotionEvent.AXIS_GENERIC_3,
        MotionEvent.AXIS_GENERIC_4
    )

    fun axisName(axis: Int): String = MotionEvent.axisToString(axis)

    fun listDevices(im: InputManager): List<InputDevice> =
        im.inputDeviceIds.toList().mapNotNull { im.getInputDevice(it) }

    fun isGamepad(d: InputDevice): Boolean =
        (d.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (d.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

    /** 人类可读的一行摘要 */
    fun brief(d: InputDevice): String {
        val kind = when {
            isGamepad(d) -> "GAMEPAD"
            (d.sources and InputDevice.SOURCE_KEYBOARD) != 0 -> "KEYBOARD"
            (d.sources and InputDevice.SOURCE_TOUCHSCREEN) != 0 -> "TOUCH"
            (d.sources and InputDevice.SOURCE_MOUSE) != 0 -> "MOUSE"
            else -> "OTHER"
        }
        return "id=${d.id} [$kind] ${d.name}"
    }

    fun dump(d: InputDevice): String {
        val sb = StringBuilder()
        sb.appendLine("id=${d.id}  name=${d.name}")
        sb.appendLine("  descriptor=${d.descriptor}")
        sb.appendLine("  vendorId=${d.vendorId} productId=${d.productId} virtual=${d.isVirtual}")
        sb.appendLine("  sources=0x${Integer.toHexString(d.sources)} keyboardType=${d.keyboardType}")
        sb.appendLine("  isGamepad=${isGamepad(d)}")
        val axes = AXES.filter { d.getMotionRange(it) != null }
            .joinToString(", ") { a ->
                val r = d.getMotionRange(a)!!
                "${MotionEvent.axisToString(a)}(${r.min}..${r.max})"
            }
        sb.appendLine("  axes=[${axes.ifEmpty { "none" }}]")
        val keys = GAMEPAD_KEYS.toList().filter { d.hasKeys(it).isNotEmpty() }
            .map { KeyEvent.keyCodeToString(it) }
        sb.appendLine("  keys=[${keys.joinToString(", ")}]")
        return sb.toString()
    }

    /** E6 需要的完整报告 */
    fun dumpAll(im: InputManager): String {
        val sb = StringBuilder()
        sb.appendLine("--- INPUT DEVICES ---")
        for (d in listDevices(im)) {
            sb.append(dump(d))
            sb.appendLine()
        }
        return sb.toString()
    }
}
