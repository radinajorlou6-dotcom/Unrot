package com.radin.unrot

import android.app.Application

/** Saves any crash so the main screen can show exactly what went wrong. */
class UnrotApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { Store.get(this).lastCrash = e.stackTraceToString().take(1500) }
            previous?.uncaughtException(t, e)
        }
    }
}
