package com.cryptochecker.app

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.cryptochecker.app.testing.AppTestEnvironmentRule
import com.cryptochecker.app.ui.UiTestTags
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue

/**
 * Bedienung der App für [ScreenshotTourTest]: Knoten über Kennungen und Texte aus den String-Ressourcen, Warten,
 * Zurück und der Weg zurück zur Merkliste bzw. zur Hauptseite der Einstellungen. Jeder Schritt der Tour beginnt mit
 * [toWatchlist] oder [toSettings] — so findet er seinen Ausgangspunkt auch, wenn der Schritt davor mittendrin
 * abgebrochen ist.
 */
internal class TourNavigator(private val compose: ComposeTestRule, private val string: (Int) -> String) {

    // ── Ausgangspunkte ──

    /** Merkliste ohne Blatt, Unterseite oder Bericht darüber. */
    fun onWatchlist(): Boolean = shown(UiTestTags.WATCH_ROW) && !overlayOpen()

    /** Hauptseite der Einstellungen (nicht «Sichern» oder eine andere Unterseite). */
    fun onSettings(): Boolean = exists(UiTestTags.SETTINGS_BACKUP) && !exists(UiTestTags.BACKUP_EXPORT)

    /**
     * Zur Merkliste: Blatt, Tastatur oder Seite über ihr per System-Zurück schliessen, sonst den Tab «Merkliste»
     * tippen (anderer Tab). Zurück nur, wenn etwas darüber liegt oder die Tab-Leiste fehlt — vorher geprüft, damit
     * kein Zurück zu viel die App verlässt.
     */
    fun toWatchlist() {
        var backs = 0
        repeat(MAX_STEPS) {
            compose.waitForIdle()
            if (onWatchlist()) return
            when {
                // App verlassen (ein Zurück zu viel): wieder starten, sie öffnet mit der Merkliste
                !exists(UiTestTags.TAB_WATCHLIST) && !overlayOpen() -> relaunch()
                // Aktionsblatt sichtbar: Zurück schliesst es (auch wenn es nach einer Unterseite wieder erscheint)
                shown(UiTestTags.WATCH_ACTION_ALARM) -> { back(); backs++ }
                // Nach drei Zurück ohne Erfolg lieber den Tab tippen
                (!overlayOpen() || backs >= 3) && shown(UiTestTags.TAB_WATCHLIST) -> clickTag(UiTestTags.TAB_WATCHLIST)
                else -> { back(); backs++ }
            }
            runCatching { compose.waitUntil(BACK_TIMEOUT) { onWatchlist() } }
        }
        compose.waitForIdle()
        assertTrue("Merkliste nicht erreicht", onWatchlist())
    }

    /**
     * Zur Hauptseite der Einstellungen: einmal den Tab tippen (er kann eine offene Unterseite wiederherstellen),
     * danach Zurück bis zur Hauptseite.
     */
    fun toSettings() {
        var tabTapped = false
        repeat(MAX_STEPS) {
            compose.waitForIdle()
            if (onSettings()) return
            if (!tabTapped && !overlayOpen() && exists(UiTestTags.TAB_SETTINGS)) {
                clickTag(UiTestTags.TAB_SETTINGS)
                tabTapped = true
            } else {
                back()
            }
            runCatching { compose.waitUntil(BACK_TIMEOUT) { onSettings() } }
        }
        compose.waitForIdle()
        assertTrue("Einstellungen nicht erreicht", onSettings())
    }

    /** System-Zurück (Tastatur, Blatt, Seite), bis [done] gilt — vorher geprüft. Höchstens [MAX_STEPS] Mal. */
    fun backUntil(done: () -> Boolean) {
        repeat(MAX_STEPS) {
            compose.waitForIdle()
            if (done()) return
            back()
            runCatching { compose.waitUntil(BACK_TIMEOUT) { done() } }
        }
        compose.waitForIdle()
        assertTrue("Zurück führte nicht zur erwarteten Seite", done())
    }

    private fun back() {
        AppTestEnvironmentRule.shell("input keyevent KEYCODE_BACK")
    }

    /** Blatt, Unterseite oder Bericht über der Merkliste offen (sichtbar, nicht nur noch im Baum). */
    private fun overlayOpen(): Boolean = OVERLAY_TAGS.any(::shown) || count(reportTitle()) > 0

    /** Knoten mit [tag] auf dem Bildschirm: platziert und mit Fläche im Fenster. */
    fun shown(tag: String): Boolean =
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().any { node ->
            val bounds = node.boundsInWindow
            node.layoutInfo.isPlaced && bounds.width > 0f && bounds.height > 0f
        }

    /** Alle Kennungen aus [UiTestTags], die gerade sichtbar sind (Fehlerbericht der Tour). */
    fun shownTags(): String = UiTestTags::class.java.declaredFields
        .filter { it.type == String::class.java && java.lang.reflect.Modifier.isStatic(it.modifiers) }
        .mapNotNull { runCatching { it.get(null) as String }.getOrNull() }
        .filter { runCatching { shown(it) }.getOrDefault(false) }
        .joinToString(",").ifEmpty { "keine" }

    /** App neu in den Vordergrund holen (gleicher Prozess, Daten bleiben). */
    private fun relaunch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    // ── Treffer ──

    /** Titel des Berichts «Letzte Aktualisierung» im Blatt (der gleich beschriftete Menüeintrag ist antippbar). */
    fun reportTitle(): SemanticsMatcher =
        hasText(string(R.string.watchlist_refresh_report)) and SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick)

    /**
     * Zeile der Einstellungen (`SettingsNavRow`): ihre Semantik ist ersetzt (`clearAndSetSemantics`), es gibt
     * keinen Text, nur die Beschreibung «Titel» bzw. «Titel, Wert». Darum über deren Anfang, nicht `hasText`.
     */
    fun settingsRow(title: Int): SemanticsMatcher {
        val text = string(title)
        return SemanticsMatcher("Einstellungszeile «$text»") { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                .any { it == text || it.startsWith("$text, ") }
        } and hasClickAction()
    }

    // ── Hilfen (wie UiFlowsTest) ──

    fun count(matcher: SemanticsMatcher): Int = compose.onAllNodes(matcher).fetchSemanticsNodes().size

    fun exists(tag: String): Boolean =
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    fun waitForTag(tag: String, timeout: Long = TIMEOUT) {
        compose.waitUntil(timeout) { exists(tag) }
        compose.waitForIdle()
    }

    /** Erster Knoten mit [tag], wenn nötig in einen scrollbaren Bereich gescrollt. */
    fun node(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).onFirst().also {
        // Nicht jeder Knoten liegt in einer scrollbaren Liste (Blatt, Tableiste): dann nichts zu tun
        runCatching { it.performScrollTo() }
    }

    /** Wartet, bis der Knopf da und bedienbar ist, und tippt. */
    fun clickTag(tag: String, timeout: Long = TIMEOUT) {
        val enabled = hasTestTag(tag) and isEnabled()
        compose.waitUntil(timeout) {
            compose.onAllNodes(enabled, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        node(tag).performClick()
        compose.waitForIdle()
    }

    /** Erster Knoten zu [matcher] (zusammengeführter Baum, z. B. Zeile mit Titel), wenn nötig gescrollt, antippen. */
    fun click(matcher: SemanticsMatcher) {
        compose.waitUntil(TIMEOUT) { count(matcher) > 0 }
        val target = compose.onAllNodes(matcher).onFirst()
        runCatching { target.performScrollTo() }
        target.performClick()
        compose.waitForIdle()
    }

    companion object {
        const val TIMEOUT = 15_000L
        const val BACK_TIMEOUT = 4_000L
        /** Tastatur, Suche, Blatt, Unterseite, Tab: so viele Schritte höchstens. */
        const val MAX_STEPS = 10

        /** Kennungen von Blättern und Seiten über der Merkliste. */
        val OVERLAY_TAGS = listOf(
            UiTestTags.WATCH_ACTION_ALARM, UiTestTags.ALARM_SAVE, UiTestTags.ALARM_CARD,
            UiTestTags.ALARMS_OVERVIEW, UiTestTags.EXPLORER_SEARCH,
        )
    }
}
