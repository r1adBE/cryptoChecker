package com.cryptochecker.app.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * Ein Ende des App-Prozesses, wie Android es festhält (ApplicationExitInfo, ab Android 11): Zeit, Grund als Code,
 * Wichtigkeit des Prozesses in dem Moment und Speicher (MB). Bewusst ohne die Beschreibung des Systems (freier Text).
 */
data class ProcessExit(
    val at: Long,
    val reason: String,
    val importance: String,
    val pssMb: Long,
    val rssMb: Long,
)

/** Liest die letzten Prozess-Enden der App; null = auf diesem Gerät nicht verfügbar (vor Android 11). */
internal object ProcessExits {
    /** Höchstens so viele Einträge im Bericht. */
    const val MAX = 5

    fun read(context: Context): List<ProcessExit>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        val infos = runCatching { manager.getHistoricalProcessExitReasons(context.packageName, 0, MAX) }
            .getOrNull() ?: return null
        return infos.map {
            ProcessExit(
                at = it.timestamp,
                reason = reasonName(it.reason),
                importance = importanceName(it.importance),
                pssMb = it.pss / KB_PER_MB,
                rssMb = it.rss / KB_PER_MB,
            )
        }
    }

    /** Code des Grunds (ApplicationExitInfo.REASON_*); unbekannte als «reason_<Zahl>». */
    fun reasonName(reason: Int): String = REASONS[reason] ?: "reason_$reason"

    /** Wichtigkeit (RunningAppProcessInfo.IMPORTANCE_*) als Code; unbekannte als Zahl. */
    fun importanceName(importance: Int): String = IMPORTANCES[importance] ?: "importance_$importance"

    private const val KB_PER_MB = 1024L

    // Werte aus der API (fest, damit die Namen auch ohne Android-Klassen im Test stimmen)
    private val REASONS = mapOf(
        0 to "UNKNOWN",
        1 to "EXIT_SELF",
        2 to "SIGNALED",
        3 to "LOW_MEMORY",
        4 to "CRASH",
        5 to "CRASH_NATIVE",
        6 to "ANR",
        7 to "INITIALIZATION_FAILURE",
        8 to "PERMISSION_CHANGE",
        9 to "EXCESSIVE_RESOURCE_USAGE",
        10 to "USER_REQUESTED",
        11 to "USER_STOPPED",
        12 to "DEPENDENCY_DIED",
        13 to "OTHER",
        14 to "FREEZER",
        15 to "PACKAGE_STATE_CHANGE",
        16 to "PACKAGE_UPDATED",
    )

    private val IMPORTANCES = mapOf(
        100 to "FOREGROUND",
        125 to "FOREGROUND_SERVICE",
        200 to "VISIBLE",
        230 to "PERCEPTIBLE",
        325 to "TOP_SLEEPING",
        350 to "CANT_SAVE_STATE",
        300 to "SERVICE",
        400 to "CACHED",
        1000 to "GONE",
    )
}
