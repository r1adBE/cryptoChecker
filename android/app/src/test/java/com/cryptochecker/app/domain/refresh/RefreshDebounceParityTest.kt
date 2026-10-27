package com.cryptochecker.app.domain.refresh

import com.cryptochecker.app.parity.ParityJson
import com.cryptochecker.app.parity.list
import com.cryptochecker.app.parity.num
import com.cryptochecker.app.parity.obj
import com.cryptochecker.app.util.SourceGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 15-s-Sperre für «Aktualisieren» liest nur das Ende der letzten VOLLSTÄNDIGEN Aktualisierung
 * (`RefreshStats.lastFullRefresh`), nicht die Uhrzeit der Live-Takte (`lastRefresh`) — `refresh_debounce.json`,
 * geteilt mit iOS (`RefreshDebounceParityTests.swift`). Dazu ein Quelltext-Scan der Aufrufer.
 */
class RefreshDebounceParityTest {

    private val data = ParityJson.load("refresh_debounce.json")

    @Test
    fun parityCases() {
        val now = data["now"].num()!!.toLong()
        val cases = data["cases"].list()
        assertTrue(cases.size >= 5)
        cases.forEach { raw ->
            val c = raw.obj()
            val name = c["name"] as String
            val full = c["fullAgo"].num()?.let { now - it.toLong() } ?: 0L
            val decision = RefreshDebounce.decide(c["running"] as Boolean, full, now)
            assertEquals(name, c["expected"], decision.name.lowercase())
            assertEquals(name, c["remainingSeconds"].num()!!.toInt(), RefreshDebounce.remainingSeconds(full, now))
        }
    }

    /** Knopf, Ziehen, Menü-Countdown und Widget-Knopf lesen dieselbe Zeit; Live-Kurse setzen sie nie. */
    @Test
    fun callersUseTheFullRefreshTime() {
        val root = SourceGuard.android(sources(), "android/app/src/main/java")
        val callers = listOf(
            "domain/refresh/ManualRefresh.kt",
            "widget/PriceWidgetProvider.kt",
            "widget/ListWidgetRenderer.kt",
        )
        // ManualRefresh liegt seit Build 144 in :domain (gleiches Paket)
        val domainRoot = SourceGuard.android(domainSources(), "android/domain/src/main/java")
        callers.forEach { path ->
            val file = File(root, path).takeIf { it.isFile } ?: File(domainRoot, path)
            assertTrue(path, file.readText().contains("lastFinishedAt = refreshStats.lastFullRefresh()"))
        }
        val controller = File(root, "ui/features/watchlist/WatchlistRefreshController.kt").readText()
        assertTrue(controller.contains("fun lastFullRefreshAt(): Long = refreshStats.lastFullRefresh()"))
        // LivePriceApplier liegt seit Build 140 in :domain (gleiches Paket)
        val live = File(domainRoot, "domain/refresh/LivePriceApplier.kt").readText()
        assertFalse("Live-Takt setzt die Sperre", live.contains("setLastFullRefresh"))
        val all = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.map { it.readText() }.joinToString("\n")
        assertFalse(all.contains(Regex("lastFinishedAt = (refreshStats\\.)?lastRefresh(\\(\\))?[,)]")))
    }

    private fun domainSources(): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "domain/src/main/java/com/cryptochecker/app")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        return null
    }

    private fun sources(): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (path in listOf("src/main/java/com/cryptochecker/app", "app/src/main/java/com/cryptochecker/app")) {
                val candidate = File(dir, path)
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        return null
    }
}
