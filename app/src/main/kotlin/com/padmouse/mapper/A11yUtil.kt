package com.padmouse.mapper

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.padmouse.mapper.capture.PadAccessibilityService

object A11yUtil {

    fun isServiceEnabled(ctx: Context): Boolean {
        val expected = ComponentName(ctx, PadAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
    }
}
