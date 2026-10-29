// Stärke eines Sicherungs-Passworts (Teil von `BackupCrypto.kt`, gespiegelt in `BackupPasswordStrength.swift`).
package com.cryptochecker.app.data

import java.text.Normalizer
import java.util.Locale

/**
 * Schwache Passwörter im Blatt «Sichern» (gespiegelt in `BackupPasswordStrength.swift`, gemeinsame Beispiele in
 * `testdata/parity/backup_passwords.json`): sperren das Sichern mit Passwort ([BackupCrypto.canExport]).
 * Entschlüsseln prüft das Passwort nicht.
 */
object BackupPasswordStrength {
    /** Häufige Folgen (klein geschrieben); kommt eine davon vor, ist das Passwort schwach. */
    private val COMMON = listOf(
        "password", "passwort", "12345678", "87654321", "abcdefgh",
    )

    /** Tastaturreihen (QWERTZ, QWERTY, AZERTY); 6 Zeichen in Folge (auch rückwärts) gelten als Tastatur-Spaziergang. */
    private val KEYBOARD_ROWS = listOf(
        "qwertzuiop", "qwertyuiop", "azertyuiop", "asdfghjkl", "qsdfghjklm", "yxcvbnm", "zxcvbnm", "wxcvbn",
    )
    private const val WALK = 6

    /** Wörter, die mit Jahr/Zahl und Zeichen geschmückt ein Passwort nicht stark machen («Summer2026!», «Winter2025»). */
    private val WORDS = listOf(
        // Jahreszeiten
        "spring", "summer", "autumn", "fall", "winter", "fruehling", "frühling", "sommer", "herbst",
        "printemps", "ete", "été", "automne", "hiver",
        // Monate
        "january", "february", "march", "april", "may", "june", "july", "august", "september", "october",
        "november", "december", "januar", "februar", "maerz", "märz", "mai", "juni", "juli", "oktober", "dezember",
        // Häufige Wörter
        "password", "passwort", "welcome", "willkommen", "hello", "hallo", "letmein", "admin", "secret", "geheim",
        "iloveyou", "monkey", "dragon", "sunshine", "football", "fussball", "master", "qwerty", "qwertz",
        "bitcoin", "crypto", "ethereum", "test",
        // Name der App
        "cryptochecker", "crypto-checker", "crypto checker",
    )

    /** Höchstens so viele Ziffern/Zeichen um ein Wort herum gelten als Schmuck. */
    private const val DECORATION = 6

    /**
     * Schwach (für neue Sicherungen gesperrt):
     *  - kürzer als [BackupCrypto.MIN_PASSWORD_LENGTH], nur Ziffern oder ein einziges Zeichen wiederholt;
     *  - eine häufige Folge oder ein Tastatur-Spaziergang (6 Zeichen einer Reihe, auch rückwärts);
     *  - ein Wort (Jahreszeit, Monat, häufiges Wort, Name der App) mit höchstens 6 Ziffern/Zeichen davor bzw. danach
     *    («Summer2026!», «Winter2025», «CryptoChecker1!»);
     *  - ein wiederholtes Stück («abcabcabcabc», «Test1Test1»);
     *  - fast nur ein Zeichen: höchstens 3 verschiedene Zeichen, mindestens 75 % dasselbe Zeichen oder ein einziges
     *    wiederholtes Zeichen, wenn man Ziffern/Zeichen vorne und hinten weglässt («aaaaaaaaaaa1», «!!bbbbbbbbbb»).
     * Gleiche Regeln wie iOS `BackupPasswordStrength.isWeak` (Fälle in `testdata/parity/backup_passwords.json`).
     * Eine Regel «höchstens 2 Zeichenklassen und kürzer als 12 Zeichen» braucht es nicht: Die Mindestlänge 12
     * deckt sie ab (Zeichenklassen spielen ab 12 Zeichen keine Rolle).
     */
    fun isWeak(password: String): Boolean {
        val normalized = Normalizer.normalize(password, Normalizer.Form.NFC)
        val codePoints = normalized.codePoints().toArray()
        if (codePoints.size < BackupCrypto.MIN_PASSWORD_LENGTH) return true
        if (codePoints.all { it in '0'.code..'9'.code }) return true
        if (codePoints.distinct().size == 1) return true
        if (mostlyOneChar(codePoints)) return true
        val lower = normalized.lowercase(Locale.ROOT)
        if (COMMON.any { lower.contains(it) }) return true
        if (keyboardWalk(lower)) return true
        if (decoratedWord(lower)) return true
        return repeatedChunk(codePoints)
    }

    /** 6 aufeinanderfolgende Zeichen einer Tastaturreihe (vorwärts oder rückwärts). */
    private fun keyboardWalk(lower: String): Boolean = KEYBOARD_ROWS.any { row ->
        listOf(row, row.reversed()).any { r -> (0..r.length - WALK).any { lower.contains(r.substring(it, it + WALK)) } }
    }

    /** Wort mit Schmuck: vorne und hinten nur Ziffern/Zeichen (keine Buchstaben), zusammen höchstens [DECORATION]. */
    private fun decoratedWord(lower: String): Boolean = WORDS.any { w ->
        var from = lower.indexOf(w)
        var hit = false
        while (from >= 0 && !hit) {
            val rest = lower.substring(0, from) + lower.substring(from + w.length)
            hit = rest.codePointCount(0, rest.length) <= DECORATION && rest.none { it.isLetter() }
            from = lower.indexOf(w, from + 1)
        }
        hit
    }

    /**
     * Fast nur ein Zeichen: höchstens 3 verschiedene Zeichen, mindestens 75 % dasselbe Zeichen, oder ohne
     * Ziffern/Zeichen (alles ausser Buchstaben) vorne und hinten bleibt ein einziges wiederholtes Zeichen.
     * Buchstabe = Unicode-Kategorie L* ([Character.isLetter], wie iOS `isLetterScalar`).
     */
    private fun mostlyOneChar(cp: IntArray): Boolean {
        if (cp.distinct().size <= 3) return true
        val most = cp.groupBy { it }.values.maxOf { it.size }
        if (most * 4 >= cp.size * 3) return true
        val first = cp.indexOfFirst { Character.isLetter(it) }
        if (first < 0) return false
        val last = cp.indexOfLast { Character.isLetter(it) }
        return (first..last).all { cp[it] == cp[first] }
    }

    /** Ganzes Passwort = ein Stück, mindestens zweimal wiederholt. */
    private fun repeatedChunk(cp: IntArray): Boolean {
        val n = cp.size
        for (p in 1..n / 2) {
            if (n % p != 0) continue
            if ((p until n).all { cp[it] == cp[it - p] }) return true
        }
        return false
    }

    /** Hinweis «zu leicht zu erraten» zeigen? Erst ab der Mindestlänge (vorher gilt «mindestens 12 Zeichen»). */
    fun showWeakHint(password: String): Boolean = BackupCrypto.isPasswordLongEnough(password) && isWeak(password)
}
