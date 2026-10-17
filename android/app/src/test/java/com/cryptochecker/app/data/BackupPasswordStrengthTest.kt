package com.cryptochecker.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wie `BackupCryptoTests.swift` (testPasswordStrength…): gleiche Fälle auf beiden Seiten. */
class BackupPasswordStrengthTest {

    @Test
    fun shortPasswordsAreWeak() {
        assertTrue(BackupPasswordStrength.isWeak("Abc!def9"))
        assertTrue(BackupPasswordStrength.isWeak("Abc!def9x"))        // 9 Zeichen
        assertTrue(BackupPasswordStrength.isWeak("Abc!def9xy"))       // 10 Zeichen (bis Build 68 erlaubt)
        assertTrue(BackupPasswordStrength.isWeak("Abc!def9xyZ"))      // 11 Zeichen
        assertFalse(BackupPasswordStrength.isWeak("Abc!def9xyZ2"))    // 12 Zeichen
        // Emoji zählt als ein Zeichen
        assertTrue(BackupPasswordStrength.isWeak("Abc!def9xy🔒"))     // 11 Zeichen
        assertFalse(BackupPasswordStrength.isWeak("Abc!def9xyZ🔒"))   // 12 Zeichen
    }

    @Test
    fun onlyDigitsOrOneRepeatedCharAreWeak() {
        assertTrue(BackupPasswordStrength.isWeak("982374659102"))
        assertTrue(BackupPasswordStrength.isWeak("aaaaaaaaaaaaaaa"))
        assertTrue(BackupPasswordStrength.isWeak("ööööööööööööö"))
    }

    @Test
    fun commonSequencesAreWeak() {
        assertTrue(BackupPasswordStrength.isWeak("12345678abcd"))
        assertTrue(BackupPasswordStrength.isWeak("MeinPassword!"))
        assertTrue(BackupPasswordStrength.isWeak("PASSWORT2026!"))
        assertTrue(BackupPasswordStrength.isWeak("Qwertz-Sommer"))
        assertTrue(BackupPasswordStrength.isWeak("xQWERTYx9!aa"))
        assertTrue(BackupPasswordStrength.isWeak("abcdefgh1234"))
    }

    @Test
    fun longMixedPasswordsAreNotWeak() {
        assertFalse(BackupPasswordStrength.isWeak("Kryptö-Test 2026!"))
        assertFalse(BackupPasswordStrength.isWeak("blau Haus Fahrrad"))
        assertFalse(BackupPasswordStrength.isWeak("7Tg#kd92!mQz"))
    }

    @Test
    fun hintOnlyFromMinimumLength() {
        assertFalse(BackupPasswordStrength.showWeakHint(""))
        assertFalse(BackupPasswordStrength.showWeakHint("12345678901"))     // noch «mindestens 12 Zeichen»
        assertFalse(BackupPasswordStrength.showWeakHint("Abc!def9"))
        assertTrue(BackupPasswordStrength.showWeakHint("123456789012"))
        assertTrue(BackupPasswordStrength.showWeakHint("Passwort1234"))
        assertFalse(BackupPasswordStrength.showWeakHint("7Tg#kd92!mQz"))
    }
}
