package com.cryptochecker.app.domain.backup

import java.text.Normalizer

/** Passwort neuer Sicherungen: Mindestlänge und Formular «Mit Passwort schützen» (`BackupCrypto` nutzt dieselben Regeln). */
object BackupPassword {
    /** Mindestlänge des Passworts für neue Sicherungen (Unicode-Zeichen). Gilt nur beim Sichern. */
    const val MIN_LENGTH = 12

    /** Länge in Unicode-Zeichen (nicht UTF-16-Einheiten), wie `unicodeScalars.count` unter iOS; NFC wie beim Ableiten. */
    fun length(password: String): Int = Normalizer.normalize(password, Normalizer.Form.NFC).let { it.codePointCount(0, it.length) }

    fun isLongEnough(password: String): Boolean = length(password) >= MIN_LENGTH

    /** Passwort für eine neue Sicherung erlaubt: lang genug und nicht leicht zu erraten ([BackupPasswordStrength]). */
    fun isAllowed(password: String): Boolean = !BackupPasswordStrength.isWeak(password)

    /** Formular «Mit Passwort schützen»: aus, oder erlaubtes Passwort und beide Felder gleich. */
    fun canExport(protect: Boolean, password: String, repeat: String): Boolean =
        !protect || (isAllowed(password) && password == repeat)
}
