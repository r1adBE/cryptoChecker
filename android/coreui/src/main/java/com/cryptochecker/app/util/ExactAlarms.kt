package com.cryptochecker.app.util

import android.app.AlarmManager
import android.content.Context
import android.os.Build
import androidx.core.content.getSystemService

/** Exakte Wecker erlaubt («Wecker und Erinnerungen», Android 12+)? Vorher immer. Für App und Widgets. */
object ExactAlarms {
    fun canSchedule(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val manager = context.getSystemService<AlarmManager>() ?: return false
        return runCatching { manager.canScheduleExactAlarms() }.getOrDefault(false)
    }
}
