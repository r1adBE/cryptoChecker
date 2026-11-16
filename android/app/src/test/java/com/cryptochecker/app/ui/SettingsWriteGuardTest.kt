package com.cryptochecker.app.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Einstellungen aus anderen Bildschirmen als «Einstellungen» (G4-04): Gruppe wählen bzw. löschen, Währung, «Beträge
 * verbergen», Zeitraum, Ansage, Hinweise «gesehen», Sperre ohne Displaysperre, eigener Bitcoin-Server … schreiben über
 * `SettingsRepository.guarded` — bei vollem Speicher ein Hinweis statt Absturz (DataStore wirft dann eine IOException,
 * ohne `CoroutineExceptionHandler` beendet sie die App). «Einstellungen» selbst schreibt über `SettingsViewModel.update`.
 * SharedPreferences schreiben mit `apply`/`commit` ohne Ausnahme; Merkliste, Gruppen und Notizen über `guardedWrite`
 * (`GuardedWriteTest`), Portfolio über `safe`. Dass die Oberfläche nur so schreibt, prüft die Regel
 * `settings-write` in `.github/scripts/source_rules.py`.
 */
class SettingsWriteGuardTest {

    @Test
    fun guardedFaengtSchreibfehlerAb() {
        val repo = File(dir("data/src/main/java/com/cryptochecker/app/data/settings"), "SettingsRepository.kt").readText()
        assertTrue(repo.contains("suspend fun guarded(block: suspend SettingsRepository.() -> Unit): Boolean = guardedWrite(false) { block(); true }"))
        // Gruppe löschen: die Einstellung zuerst, aber ein Fehler darin hält das Auflösen der Gruppe nicht auf
        val edit = File(dir("app/src/main/java/com/cryptochecker/app/ui/features/watchlist"), "WatchlistEditController.kt").readText()
        val delete = edit.substringAfter("fun deleteGroup(").substringBefore("\n    }\n")
        assertTrue(delete.indexOf("settingsRepository.guarded { setWatchlistGroup(null) }") in 0 until delete.indexOf("watchRepository.clearGroup(name)"))
        // Sperre ohne Displaysperre: nur nach geschriebener Einstellung entsperren
        val lock = File(dir("app/src/main/java/com/cryptochecker/app/ui/lock"), "PortfolioLock.kt").readText()
        assertTrue(lock.contains("if (settingsRepository.guarded { setAppLock(false) }) appLockState.unlock()"))
    }

    private fun dir(path: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (prefix in listOf("", "android/", "../")) {
                val f = File(dir, prefix + path)
                if (f.isDirectory) return f
            }
            dir = dir.parentFile
        }
        throw AssertionError("$path nicht gefunden")
    }
}
