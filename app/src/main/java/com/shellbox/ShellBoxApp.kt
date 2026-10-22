package com.shellbox

import android.app.Application
import android.util.Log

class ShellBoxApp : Application() {
    companion object {
        lateinit var instance: ShellBoxApp; private set
    }
    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i("ShellBox", "Application started")
        // Elevated shell is provided by the bundled xnix-shelld daemon; no
        // Shizuku binder/permission handshake is required.
    }
}
