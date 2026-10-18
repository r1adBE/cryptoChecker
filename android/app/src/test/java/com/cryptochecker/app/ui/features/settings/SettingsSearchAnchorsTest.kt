package com.cryptochecker.app.ui.features.settings

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Jedes Ziel der Suche in den Einstellungen ([settingsSearchItems], Composable und darum hier nicht aufrufbar) hat seinen
 * Anker auf der Seite: Sonst öffnete ein Treffer die Seite, ohne zum Punkt zu scrollen. Liest die Quellen von der Platte
 * (wie `SpacingUsageTest`); iOS (`SettingsSearchUi.swift` ↔ `.settingsAnchor`) wird mitgeprüft, wenn vorhanden.
 * Dazu die Einträge «Damit Alarme ankommen», «Diagnose teilen» und «Alarm-Protokoll» auf beiden Plattformen
 * (iOS: `testDeliveryDiagnosticsAndAlarmLogAreSearchable`).
 */
class SettingsSearchAnchorsTest {

    @Test
    fun everyAndroidTargetHasAnAnchor() {
        val root = findUp(listOf("", "app/", "android/app/").map { it + "src/main/java/com/cryptochecker/app" })
        assertNotNull("android/app/src/main/java nicht gefunden (Arbeitsverzeichnis?)", root)
        val search = File(root, "ui/features/settings/SettingsSearchUi.kt").readText()
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
        val search = File(root, "ui/features/settings/SettingsSearchUi.kt").readText().replace(Regex("\\s+"), " ")
        assertTrue(search.contains("""add("alarms.delivery", stringResource(R.string.delivery_title), alarms, SettingsSearchTarget.Page(SettingsPage.ALARMS, "alarms.delivery")"""))
        assertTrue(search.contains("""add("about.diagnostics", stringResource(R.string.diagnostics_share), about, SettingsSearchTarget.Page(SettingsPage.ABOUT, "about.diagnostics")"""))
        // Der Knopf steht in der Karte «Letzte Prüfungen»: dorthin
        assertTrue(search.contains("""add("updates.alarm_log", stringResource(R.string.alarm_log_title), updatesPath, SettingsSearchTarget.Page(SettingsPage.UPDATES, "updates.check_runs")"""))
    }

    @Test
    fun everyIosTargetHasAnAnchor() {
        val app = findUp(listOf("ios/App", "../ios/App", "../../ios/App")) ?: return // reiner Android-Checkout
        val search = File(app, "Features/Settings/SettingsSearchUi.swift").readText()
        val targets = (
            Regex("""anchor:\s*"([^"]+)"""").findAll(search) + Regex("""\.main\("([^"]+)"\)""").findAll(search)
            ).map { it.groupValues[1] }.toSet()
        listOf("alarms.delivery", "about.diagnostics", "updates.check_runs").forEach {
            assertTrue("iOS-Suche ohne $it", it in targets)
        }
        assertTrue(search.contains("add(\"updates.alarm_log\""))
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
