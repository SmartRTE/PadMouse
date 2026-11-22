package com.padmouse.mapper

import android.app.Application
import com.padmouse.mapper.core.SettingsStore

class PadMouseApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SettingsStore.init(this)
    }
}
