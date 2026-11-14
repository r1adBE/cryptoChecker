package com.cryptochecker.app.domain.logos

/**
 * Letzter Durchgang der Logo-Bilder für den Diagnosebericht («== Coin logos =="): Ende, neu geladen, gescheitert
 * (gesperrt), gebremst, offen (gestört bzw. 403, kommen mit dem nächsten Abgleich), nicht gespeichert. Nur Zahlen, keine
 * Kürzel. Gespeichert als «at;added;failed;paused;open;unsaved». Gleich in iOS `LogoPassSummary.swift`.
 */
data class LogoPassSummary(
    val at: Long,
    val added: Int,
    val failed: Int,
    val paused: Int,
    val open: Int,
    val unsaved: Int,
) {
    fun encode(): String = listOf(at, added, failed, paused, open, unsaved).joinToString(";")

    companion object {
        /** Gespeicherter Stand; null bei fehlendem oder unlesbarem Text. */
        fun decode(text: String?): LogoPassSummary? {
            val parts = text?.trim()?.split(';') ?: return null
            if (parts.size != 6) return null
            val at = parts[0].toLongOrNull() ?: return null
            val n = parts.drop(1).map { it.toIntOrNull()?.coerceAtLeast(0) ?: return null }
            return LogoPassSummary(at, n[0], n[1], n[2], n[3], n[4])
        }
    }
}

/**
 * Logos auf dem Gerät für den Diagnosebericht: Bilder in der Ablage ([onDevice]), nach einem Fehlschlag gerade gesperrt
 * ([locked]) und der letzte Durchgang ([lastPass], null = noch keiner).
 */
data class LogoDiagnostics(val onDevice: Int, val locked: Int, val lastPass: LogoPassSummary?)
