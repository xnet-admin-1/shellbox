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
        rikka.shizuku.Shizuku.addBinderReceivedListenerSticky {
            Log.i("ShellBox", "Shizuku binder received")
            if (rikka.shizuku.Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                try { rikka.shizuku.Shizuku.requestPermission(0) } catch (_: Exception) {}
            }
        }
    }
}
