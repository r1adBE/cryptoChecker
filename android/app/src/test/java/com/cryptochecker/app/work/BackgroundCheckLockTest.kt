package com.cryptochecker.app.work

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Warten auf Pulse-Widget und Ansagen gehört nicht unter die Prüf-Sperre von [BackgroundCheck]: sonst hielte jeder Lauf
 * sie bis zu 20 s länger, und ein geplanter Lauf in dieser Zeit fiele als «läuft bereits» weg. Grobe Quelltext-Prüfung
 * (die Klasse hängt an echten Diensten ohne Schnittstellen — ein Verhaltenstest bräuchte Ports für Abruf, Ansagen und
 * Widget): der Rumpf von `runLocked` darf nicht warten, gewartet wird erst nach `running.withLock`. Die Restzeit-Rechnung
 * fürs Warten prüft `CheckBudgetTest` als Verhalten.
 */
class BackgroundCheckLockTest {

    @Test
    fun sideEffectWaitsAreOutsideTheCheckLock() {
        val file = findUp(listOf("", "app/", "android/app/").map { it + SOURCE })
        assertNotNull("BackgroundCheck.kt nicht gefunden (Arbeitsverzeichnis?)", file)
        val text = file!!.readText(Charsets.UTF_8)
        val locked = functionBody(text, "private suspend fun runLocked(")
        assertNotNull("runLocked nicht gefunden", locked)
        assertFalse("Pulse-Warten unter der Sperre", "awaitBackgroundFetch" in locked!!)
        assertFalse("Ansagen-Warten unter der Sperre", "awaitQueued" in locked)
        // Gewartet wird weiterhin — nur danach: in run() nach dem Block unter der Sperre
        assertTrue("awaitBackgroundFetch" in text && "awaitQueued" in text)
        val lockAt = text.indexOf("running.withLock { runLocked(")
        assertTrue("runLocked läuft nicht unter running.withLock", lockAt >= 0)
        val waitAt = text.indexOf("awaitSideEffects(", lockAt)
        assertTrue("awaitSideEffects nicht nach der Sperre aufgerufen", waitAt > lockAt)
        assertFalse("awaitSideEffects unter der Sperre", "awaitSideEffects" in locked)
    }

    /** Text der Funktion ab [signature] bis zur schliessenden Klammer ihres Rumpfs. */
    private fun functionBody(text: String, signature: String): String? {
        val start = text.indexOf(signature).takeIf { it >= 0 } ?: return null
        // Rumpf beginnt nach der Parameterliste: erste «{» nach «): …»
        val open = text.indexOf("? {", start).takeIf { it >= 0 }?.let { it + 2 } ?: return null
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(open, i + 1)
            }
        }
        return null
    }

    private fun findUp(candidates: List<String>): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (rel in candidates) {
                val f = File(dir, rel)
                if (f.isFile) return f
            }
            dir = dir.parentFile
        }
        return null
    }

    private companion object {
        const val SOURCE = "src/main/java/com/cryptochecker/app/work/BackgroundCheck.kt"
    }
}
