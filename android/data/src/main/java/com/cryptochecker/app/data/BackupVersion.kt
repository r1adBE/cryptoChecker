// Version einer Sicherung (Teil von `BackupCrypto.kt`, gespiegelt in `BackupVersion.swift`).
package com.cryptochecker.app.data

/**
 * Version einer Sicherung (Klartext-Feld «version», gespiegelt in `BackupVersion.swift`): Eine Sicherung einer NEUEREN
 * App-Version wird ganz abgelehnt ([NewerVersionException], Meldung «neuere App-Version») — nie teilweise übernommen,
 * denn ihre Felder könnten anders gemeint sein. Ohne «version» ist die Sicherung unlesbar.
 */
object BackupVersion {
    /** Höchste Version, die diese App liest und schreibt (`BackupManager`: «version»). */
    const val SUPPORTED = 1

    class NewerVersionException(val version: Int) : IllegalStateException("Sicherung Version $version einer neueren App-Version")

    /**
     * [raw] = Wert von «version» (null = fehlt): ganze Zahl ≤ [SUPPORTED] → diese Zahl; grösser →
     * [NewerVersionException]; fehlt, keine Zahl (auch Text) oder keine ganze Zahl ≥ 1 → [IllegalArgumentException]
     * (unlesbar).
     */
    fun check(raw: Any?): Int {
        val number = (raw as? Number)?.toDouble() ?: throw IllegalArgumentException("version $raw")
        if (!number.isFinite() || number % 1.0 != 0.0 || number < 1.0) throw IllegalArgumentException("version $raw")
        if (number > SUPPORTED) throw NewerVersionException(if (number > Int.MAX_VALUE) Int.MAX_VALUE else number.toInt())
        return number.toInt()
    }
}
