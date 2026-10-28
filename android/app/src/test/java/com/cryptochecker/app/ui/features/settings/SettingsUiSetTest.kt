package com.cryptochecker.app.ui.features.settings

import com.cryptochecker.app.ui.components.ScreenEvents
import com.cryptochecker.app.util.SourceGuard
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Kleine Testklassen dieses Themas in einer Datei; jede Klasse unverändert.

// ---- aus SettingsPlacementTest

/**
 * Kurs-Aktualisierung unter «Allgemein», Widgets und Coin-Logos unter «Darstellung»; kein «Erweitert» mehr
 *: %-Änderung und Token & TradFi stehen auf «Merkliste», Entwickler auf «Über die App» (Markt-Meldungen
 * und Sprachausgabe haben eigene Routen, Zeilen auf «Alarme»). Portfolio hat genau einen Ort, die Zeile unter
 * «Allgemein» (Einstellungen › Portfolio). Die Suche zeigt den Pfad aus diesem Ort — wie
 * `testPagesSitWhereTheMainPageShowsThem` (iOS, SettingsSearchTests).
 */
class SettingsPlacementTest {

    @Test
    fun pagesSitWhereTheMainPageShowsThem() {
        assertEquals(SettingsHome.GENERAL, SettingsPage.UPDATES.home)
        assertEquals(SettingsHome.GENERAL, SettingsPage.ALARMS.home)
        assertEquals(SettingsHome.APPEARANCE, SettingsPage.WIDGETS.home)
        assertEquals(SettingsHome.APPEARANCE, SettingsPage.PRICE_COLORS.home)
        assertEquals(SettingsHome.GENERAL, SettingsPage.PORTFOLIO.home)
        assertEquals(SettingsHome.APPEARANCE, SettingsPage.COIN_LOGOS.home)
        assertEquals(setOf(SettingsHome.GENERAL, SettingsHome.APPEARANCE, SettingsHome.MORE), SettingsHome.entries.toSet())
        assertNull("frühere Route «Erweitert» führt zurück", SettingsPage.fromName("ADVANCED"))
    }

    @Test
    fun subpagesOfSubpagesHaveNoHome() {
        // Preis-Paare stehen auf «Alarme», Lizenzen und Entwickler auf «Über die App», %-Änderung und Token & TradFi
        // auf «Merkliste»
        assertNull(SettingsPage.PRICE_PAIRS.home)
        assertNull(SettingsPage.CHANGE_BASIS.home)
        assertNull(SettingsPage.MARKETS.home)
        assertNull(SettingsPage.LICENSES.home)
        assertNull(SettingsPage.DEVELOPER.home)
    }
}

// ---- aus SettingsSearchAnchorsTest

/**
 * Jedes Ziel der Suche in den Einstellungen ([settingsSearchItems], Composable und darum hier nicht aufrufbar) hat seinen
 * Anker auf der Seite: Sonst öffnete ein Treffer die Seite, ohne zum Punkt zu scrollen. Liest die Quellen von der Platte
 * (wie `SpacingUsageTest`); iOS (`SettingsSearchCatalog.swift` ↔ `.settingsAnchor`) wird mitgeprüft, wenn vorhanden.
 * Dazu die Einträge «Damit Alarme ankommen», «Diagnose teilen» und «Alarm-Protokoll» auf beiden Plattformen
 * (iOS: `testDeliveryDiagnosticsAndAlarmLogAreSearchable`).
 */
class SettingsSearchAnchorsTest {

    @Test
    fun everyAndroidTargetHasAnAnchor() {
        val root = findUp(listOf("", "app/", "android/app/").map { it + "src/main/java/com/cryptochecker/app" })
        assertNotNull("android/app/src/main/java nicht gefunden (Arbeitsverzeichnis?)", root)
        val search = File(root, "ui/features/settings/SettingsSearchCatalog.kt").readText()
        val targets = Regex("""SettingsSearchTarget\.\w+\((?:SettingsPage\.\w+,\s*)?"([^"]+)"\)""")
            .findAll(search).map { it.groupValues[1] }.toSet()
        assertTrue(targets.size > 30)
        val anchorLines = root!!.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .flatMap { it.readLines().asSequence() }
            .filter { "SettingsAnchor(" in it }
            .toList()
        val missing = targets.filter { id -> anchorLines.none { "\"$id\"" in it } }
        assertTrue("Ziele ohne SettingsAnchor: $missing", missing.isEmpty())
    }

    @Test
    fun deliveryDiagnosticsAndAlarmLogAreSearchable() {
        val root = findUp(listOf("", "app/", "android/app/").map { it + "src/main/java/com/cryptochecker/app" })!!
        val search = File(root, "ui/features/settings/SettingsSearchCatalog.kt").readText().replace(Regex("\\s+"), " ")
        assertTrue(search.contains("""add("alarms.delivery", stringResource(R.string.delivery_title), alarms, SettingsSearchTarget.Page(SettingsPage.ALARMS, "alarms.delivery")"""))
        assertTrue(search.contains("""add("about.diagnostics", stringResource(R.string.diagnostics_share), about, SettingsSearchTarget.Page(SettingsPage.ABOUT, "about.diagnostics")"""))
        // Der Knopf steht in der Karte «Letzte Prüfungen»: dorthin
        assertTrue(search.contains("""add("updates.alarm_log", stringResource(R.string.alarm_log_title), updatesPath, SettingsSearchTarget.Page(SettingsPage.UPDATES, "updates.check_runs")"""))
        // «Pünktlich prüfen» und «Letzte Prüfungen» mit eigenem Treffer
        assertTrue(search.contains("""add("updates.exact", stringResource(R.string.settings_exact_checks), updatesPath, SettingsSearchTarget.Page(SettingsPage.UPDATES, "updates.exact")"""))
        assertTrue(search.contains("""add("updates.check_runs", stringResource(R.string.settings_check_runs_title), updatesPath, SettingsSearchTarget.Page(SettingsPage.UPDATES, "updates.check_runs")"""))
    }

    @Test
    fun everyIosTargetHasAnAnchor() {
        val app = SourceGuard.ios(findUp(listOf("ios/App", "../ios/App", "../../ios/App")), "ios/App")
        val search = listOf("SettingsSearchUi.swift", "SettingsSearchCatalog.swift").joinToString("\n") { File(app, "Features/Settings/$it").readText() }
        val targets = (
            Regex("""anchor:\s*"([^"]+)"""").findAll(search) + Regex("""\.main\("([^"]+)"\)""").findAll(search)
            ).map { it.groupValues[1] }.toSet()
        listOf("alarms.delivery", "about.diagnostics", "updates.check_runs").forEach {
            assertTrue("iOS-Suche ohne $it", it in targets)
        }
        assertTrue(search.contains("add(\"updates.alarm_log\""))
        assertTrue(search.contains("add(\"updates.check_runs\""))
        val anchorLines = app.walkTopDown().filter { it.isFile && it.extension == "swift" }
            .flatMap { it.readLines().asSequence() }
            .filter { ".settingsAnchor(" in it }
            .toList()
        val missing = targets.filter { id -> anchorLines.none { "\"$id\"" in it } }
        assertTrue("iOS-Ziele ohne .settingsAnchor: $missing", missing.isEmpty())
    }

    /** Sucht [candidates] vom Arbeitsverzeichnis aus aufwärts. */
    private fun findUp(candidates: List<String>): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (rel in candidates) {
                val f = File(dir, rel)
                if (f.isDirectory) return f
            }
            dir = dir.parentFile
        }
        return null
    }
}

// ---- aus SettingsUiEventsTest

/**
 * Einstellungen › Kursmitteilungen (Review ARC6-R01/UX6-R1): Erlaubnis-Abfrage nur nach «Trotzdem» und nur beim Einschalten,
 * als Ereignis für den aktuellen Bildschirm statt als Rückruf in die alte Komposition.
 */
class SettingsUiEventsTest {

    @Test
    fun proceedWhileSwitchingOnAsksExactlyOnce() = runTest {
        val events = ScreenEvents<Unit>()
        askPermissionAfterWrite(events, enabled = true)(true)
        assertEquals(listOf(Unit), events.events.take(1).toList())
        assertNull(withTimeoutOrNull(50) { events.events.first() })
    }

    @Test
    fun cancelLockedOrSwitchingOffAsksNothing() = runTest {
        val events = ScreenEvents<Unit>()
        // «Abbrechen» bzw. gesperrt: nicht geschrieben
        askPermissionAfterWrite(events, enabled = true)(false)
        // Ausschalten: nie fragen
        askPermissionAfterWrite(events, enabled = false)(true)
        askPermissionAfterWrite(events, enabled = false)(false)
        assertNull(withTimeoutOrNull(50) { events.events.first() })
    }

    @Test
    fun pairsPageSendsEventsInsteadOfCallingTheLauncher() {
        val dir = findUp(listOf("", "app/", "android/app/").map { it + "src/main/java/com/cryptochecker/app/ui/features/settings" })
        assertNotNull("settings-Quellen nicht gefunden (Arbeitsverzeichnis?)", dir)
        val page = File(dir, "SettingsAlarmPages.kt").readText()
        // Kein Rückruf nach F-08, der den Launcher der Komposition beim Tippen aufruft
        assertFalse(Regex("""written\s*->[^}]*requestNotifications\(\)""").containsMatchIn(page))
        assertEquals(2, Regex("""askPermissionAfterWrite\(uiEvents\.requestNotifications""").findAll(page).count())
        assertEquals(1, Regex("""CollectScreenEvents\(uiEvents\.requestNotifications\)""").findAll(page).count())
    }

    private fun findUp(candidates: List<String>): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (rel in candidates) {
                val f = File(dir, rel)
                if (f.isDirectory) return f
            }
            dir = dir.parentFile
        }
        return null
    }
}
