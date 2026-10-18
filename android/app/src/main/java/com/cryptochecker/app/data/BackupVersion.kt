// Version einer Sicherung (Teil von `BackupCrypto.kt`, gespiegelt in `BackupVersion.swift`).
package com.cryptochecker.app.data

/**
 * Version einer Sicherung (Klartext-Feld «version», gespiegelt in `BackupVersion.swift`): Eine Sicherung einer NEUEREN
 * App-Version wird ganz abgelehnt ([NewerVersionException], Meldung «neuere App-Version») — nie teilweise übernommen,
 * denn ihre Felder könnten anders gemeint sein. Ältere Versionen (und fehlende Angabe = älteste Sicherungen) bleiben lesbar.
 */
object BackupVersion {
    /** Höchste Version, die diese App liest und schreibt (`BackupManager`: «version»). */
    const val SUPPORTED = 1

    class NewerVersionException(val version: Int) : IllegalStateException("Sicherung Version $version einer neueren App-Version")

    /**
     * [raw] = Wert von «version» (null = fehlt): ganze Zahl (auch als Text) ≤ [SUPPORTED] → diese Zahl, fehlt → 1;
     * grösser → [NewerVersionException]; keine ganze Zahl ≥ 1 → [IllegalArgumentException] (unlesbar).
     */
    fun check(raw: Any?): Int {
        val number: Double = when (raw) {
            null -> return 1
            is Number -> raw.toDouble()
            is String -> raw.trim().toDoubleOrNull() ?: throw IllegalArgumentException("version $raw")
            else -> throw IllegalArgumentException("version $raw")
        }
        if (!number.isFinite() || number % 1.0 != 0.0 || number < 1.0) throw IllegalArgumentException("version $raw")
        if (number > SUPPORTED) throw NewerVersionException(if (number > Int.MAX_VALUE) Int.MAX_VALUE else number.toInt())
        return number.toInt()
    }
}
