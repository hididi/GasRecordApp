package com.hididi.gaslog

import android.app.Application

class GasLogApp : Application() {
    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    companion object {
        lateinit var container: AppContainer
            private set
    }
}