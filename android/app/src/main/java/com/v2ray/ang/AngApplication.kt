package com.v2ray.ang

import android.content.Context
import androidx.multidex.MultiDexApplication
import com.tencent.mmkv.MMKV
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager

class AngApplication : MultiDexApplication() {
    companion object {
        lateinit var application: AngApplication
    }

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        application = this
    }

    override fun onCreate() {
        super.onCreate()

        MMKV.initialize(this)
        MmkvManager.migrateSensitiveStorage()

        // Ensure critical preference defaults are present in MMKV early
        SettingsManager.initApp(this)
        SettingsManager.setNightMode()
    }
}
