package com.cryptochecker.app.domain.backup

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Automatische Sicherung (Entscheid Inhaber 17.11.2026): wöchentlich, verschlüsselt, in einen gewählten Ordner; die
 * letzten [KEEP] eigenen Dateien bleiben. Fällig beim Öffnen der App ([isDue]); nach einem Fehlversuch frühestens nach
 * [RETRY_AFTER_MILLIS] wieder. Gelöscht werden nur Dateien mit dem eigenen Namensmuster ([isOwnFile]), nie andere.
 * Reine Regeln; wie iOS `AutoBackupPolicy`, gemeinsame Testfälle `auto_backup.json`.
 */
object AutoBackupPolicy {
    const val INTERVAL_MILLIS = 7L * 24 * 60 * 60 * 1000
    const val RETRY_AFTER_MILLIS = 6L * 60 * 60 * 1000
    const val KEEP = 4
    const val PREFIX = "cryptochecker-auto-"
    const val SUFFIX = ".json"
    private val OWN_FILE = Regex("""^cryptochecker-auto-\d{4}-\d{2}-\d{2}-\d{4}\.json$""")

    /**
     * Fällig? Eingeschaltet, und die letzte gelungene Sicherung ist [INTERVAL_MILLIS] alt (oder es gab noch keine, oder
     * sie liegt in der Zukunft — Uhr zurückgestellt), und der letzte Versuch ist [RETRY_AFTER_MILLIS] her.
     */
    fun isDue(enabled: Boolean, lastSuccessAt: Long, lastAttemptAt: Long, now: Long): Boolean {
        if (!enabled) return false
        val successDue = lastSuccessAt <= 0 || lastSuccessAt > now || now - lastSuccessAt >= INTERVAL_MILLIS
        val attemptDue = lastAttemptAt <= 0 || lastAttemptAt > now || now - lastAttemptAt >= RETRY_AFTER_MILLIS
        return successDue && attemptDue
    }

    /** «cryptochecker-auto-2026-11-17-1830.json» (Ortszeit [zone]). */
    fun fileName(millis: Long, zone: ZoneId): String =
        PREFIX + DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm", Locale.ROOT).withZone(zone).format(Instant.ofEpochMilli(millis)) +
            SUFFIX

    fun isOwnFile(name: String): Boolean = OWN_FILE.matches(name)

    /** Eigene Dateien über die neuesten [keep] hinaus (älteste zuerst); fremde Namen nie. */
    fun toDelete(names: List<String>, keep: Int = KEEP): List<String> =
        names.filter(::isOwnFile).distinct().sortedDescending().drop(keep).sorted()
}
