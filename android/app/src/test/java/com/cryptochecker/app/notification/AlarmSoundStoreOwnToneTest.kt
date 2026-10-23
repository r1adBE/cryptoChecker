package com.cryptochecker.app.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Welche Töne die App aus der Mediathek wieder löschen darf ([AlarmSoundStore.isOwnTone]). */
class AlarmSoundStoreOwnToneTest {

    private val own = "content://media/external_primary/audio/media/42"

    @Test
    fun eigenerOrdnerMitUndOhneSchraegstrich() {
        assertTrue(AlarmSoundStore.isOwnRelativePath("Notifications/CryptoChecker/"))
        assertTrue(AlarmSoundStore.isOwnRelativePath("Notifications/CryptoChecker"))
        assertFalse(AlarmSoundStore.isOwnRelativePath("Notifications/"))
        assertFalse(AlarmSoundStore.isOwnRelativePath("Notifications/CryptoChecker/Unterordner/"))
        assertFalse(AlarmSoundStore.isOwnRelativePath("Music/CryptoChecker/"))
        assertFalse(AlarmSoundStore.isOwnRelativePath(null))
    }

    @Test
    fun systemUndStandardtonSindNieEigene() {
        assertFalse(AlarmSoundStore.isOwnTone("content://settings/system/notification_sound", "Notifications/CryptoChecker/"))
        assertFalse(AlarmSoundStore.isOwnTone(null, "Notifications/CryptoChecker/"))
        // Systemton in der Mediathek, aber nicht in unserem Ordner
        assertFalse(AlarmSoundStore.isOwnTone("content://media/internal/audio/media/7", "Notifications/"))
        assertTrue(AlarmSoundStore.isOwnTone(own, "Notifications/CryptoChecker/"))
    }

    @Test
    fun vorigerTonNurBeiEchtemWechsel() {
        val path = "Notifications/CryptoChecker/"
        assertTrue(AlarmSoundStore.shouldDeletePrevious(own, "content://media/external_primary/audio/media/43", path))
        assertTrue(AlarmSoundStore.shouldDeletePrevious(own, null, path))
        // Gleicher Ton (auch über «external» statt «external_primary»): bleibt
        assertFalse(AlarmSoundStore.shouldDeletePrevious(own, own, path))
        assertFalse(AlarmSoundStore.shouldDeletePrevious(own, "content://media/external/audio/media/42", path))
        // Kein voriger bzw. fremder Ton
        assertFalse(AlarmSoundStore.shouldDeletePrevious(null, own, path))
        assertFalse(AlarmSoundStore.shouldDeletePrevious(own, null, "Ringtones/"))
    }
}
