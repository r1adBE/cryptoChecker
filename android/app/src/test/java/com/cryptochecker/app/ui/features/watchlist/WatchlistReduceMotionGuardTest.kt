package com.cryptochecker.app.ui.features.watchlist

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * iOS-Merkliste: Jede `.animation(…)` der Liste richtet sich nach «Bewegung reduzieren» (`reduceMotion ? nil : …`),
 * wie die Überlagerungen. Quellwächter über `ios/App/Features/Watchlist/WatchlistList.swift`; im reinen
 * Android-Checkout übersprungen. Seit Build 102.
 */
class WatchlistReduceMotionGuardTest {

    @Test
    fun `Listen-Animationen beachten Bewegung reduzieren`() {
        val file = iosFile("App/Features/Watchlist/WatchlistList.swift") ?: return
        val ungated = file.readLines(Charsets.UTF_8)
            .withIndex()
            .filter { (_, line) -> line.contains(".animation(") && !line.contains("reduceMotion") }
            .map { (index, line) -> "${index + 1}: ${line.trim()}" }
        assertEquals(emptyList<String>(), ungated)
    }

    /** Datei unter `ios/`, vom Arbeitsverzeichnis aus aufwärts gesucht. */
    private fun iosFile(rel: String): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val f = File(dir, "ios/$rel")
            if (f.isFile) return f
            dir = dir.parentFile
        }
        return null
    }
}
