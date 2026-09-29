package com.shawn.stopscroll

import android.app.Application
import com.shawn.stopscroll.data.PrefManager

class StopScrollApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        PrefManager.init(this)
        com.shawn.stopscroll.data.RecordManager.init(this)
    }

    companion object {
        lateinit var instance: StopScrollApp
            private set
    }
}
