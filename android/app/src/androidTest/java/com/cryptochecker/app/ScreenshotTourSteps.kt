package com.cryptochecker.app

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.cryptochecker.app.testing.AppTestEnvironmentRule
import com.cryptochecker.app.ui.MainActivity
import com.cryptochecker.app.ui.UiTestTags

/**
 * Bedienung der App für [ScreenshotTourTest]: Knoten über Kennungen und Texte aus den String-Ressourcen, Warten und
 * der Ausgangspunkt jedes Schritts.
 *
 * Ausgangspunkt ohne Raten: [toWatchlist] und [toSettings] starten die Activity jedes Mal frisch
 * (`NEW_TASK | CLEAR_TASK`, [restart]) — eine neue Activity öffnet immer die Merkliste ohne Blatt oder Unterseite,
 * egal wie der Schritt davor endete. Kein System-Zurück und kein `input tap`: Solche Eingaben laufen über das System
 * und kommen nur an, wenn der Bildschirm an ist und die App den Fokus hat; alle Bedienung hier geht über die
 * Semantik von Compose. Scheitert ein Warten, nennt die Meldung Seite, sichtbare Kennungen und Zustand ([describe]).
 */
internal class TourNavigator(private val compose: ComposeTestRule) {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    // ── Ausgangspunkte ──

    /** Merkliste ohne Blatt, Unterseite oder Bericht darüber. */
    fun onWatchlist(): Boolean = shown(UiTestTags.WATCH_ROW) && !overlayOpen()

    /** Hauptseite der Einstellungen (nicht «Sichern» oder eine andere Unterseite). */
    fun onSettings(): Boolean = exists(UiTestTags.SETTINGS_BACKUP) && !exists(UiTestTags.BACKUP_EXPORT)

    /** Merkliste: frisch gestartete Activity, Startziel des Graphen. */
    fun toWatchlist() {
        restart()
        await("Merkliste nach dem Neustart") { onWatchlist() }
    }

    /** Hauptseite der Einstellungen: frisch gestartete Activity, dann der Tab (ohne gemerkte Unterseite). */
    fun toSettings() {
        toWatchlist()
        clickTag(UiTestTags.TAB_SETTINGS)
        await("Einstellungen") { onSettings() }
    }

    /**
     * Activity neu starten (gleicher Prozess, Daten bleiben): explizit `MainActivity` mit `NEW_TASK | CLEAR_TASK`,
     * ohne Ziel (Merkliste). Absichtlich kein Intent `MAIN`/`LAUNCHER`: Den nähme die `ActivityScenario` der Regel
     * als ihre Activity an. Ältere Instanzen werden beendet, bis keine mehr lebt — sonst stünden zwei Bäume mit
     * denselben Kennungen nebeneinander.
     */
    private fun restart() {
        val before = liveActivities()
        val context = instrumentation.targetContext
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        await("neue Activity vorne") { resumedActivity()?.let { it !in before } == true }
        instrumentation.runOnMainSync { before.forEach { if (!it.isFinishing) it.finish() } }
        await("alte Activity beendet") { liveActivities().none { it in before } }
        compose.waitForIdle()
    }

    /** Am Ende der Tour: alle selbst gestarteten Activities beenden (die der Regel ist dann schon zu). */
    fun finishAll() {
        instrumentation.runOnMainSync { liveActivities().forEach { if (!it.isFinishing) it.finish() } }
    }

    // ── Gerät ──

    /**
     * Bildschirm an, ohne Sperre, und bleibt an (`svc power stayon`): Ohne ihn zeigt `screencap` Schwarz. Gibt den
     * vorherigen Wert von `stay_on_while_plugged_in` zurück ([restoreDevice]).
     */
    fun prepareDevice(): String {
        val stayOn = AppTestEnvironmentRule.shell("settings get global stay_on_while_plugged_in").trim()
        AppTestEnvironmentRule.shell("svc power stayon true")
        AppTestEnvironmentRule.shell("input keyevent KEYCODE_WAKEUP")
        AppTestEnvironmentRule.shell("wm dismiss-keyguard")
        await("Bildschirm an und entsperrt") { interactive() && !keyguardLocked() }
        return stayOn
    }

    fun restoreDevice(stayOn: String) {
        val value = stayOn.toIntOrNull() ?: 0
        AppTestEnvironmentRule.shell("settings put global stay_on_while_plugged_in $value")
    }

    /** Vor jedem Foto: die App vorne, Bildschirm an, keine Sperre darüber — sonst zeigte das Bild etwas anderes. */
    fun requireForeground() {
        val ok = resumedActivity() != null && interactive() && !keyguardLocked()
        if (!ok) throw AssertionError("App nicht im Vordergrund; ${describe()}")
    }

    // ── Warten ──

    /**
     * Wartet bis [timeout], bis [condition] gilt; sonst AssertionError mit [what] und dem gesehenen Zustand. Ein Fehler
     * in [condition] (z. B. gerade kein Compose-Baum zwischen zwei Activities) zählt als «noch nicht».
     */
    fun await(what: String, timeout: Long = TIMEOUT, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (true) {
            if (runCatching(condition).getOrDefault(false)) return
            if (SystemClock.uptimeMillis() > end) throw AssertionError("$what nicht erreicht; ${describe()}")
            SystemClock.sleep(POLL_MILLIS)
        }
    }

    fun waitForTag(tag: String, timeout: Long = TIMEOUT) {
        await("Kennung $tag", timeout) { exists(tag) }
        compose.waitForIdle()
    }

    // ── Zustand für Fehlermeldungen ──

    /** Gesehene Seite, sichtbare Kennungen, Activity, Bildschirm und Fenster mit Fokus. */
    fun describe(): String {
        val page = runCatching { screen() }.getOrDefault("?")
        val tags = runCatching { shownTags() }.getOrDefault("?")
        val stage = runCatching { activityStage() }.getOrDefault("?")
        val focus = runCatching {
            AppTestEnvironmentRule.shell("dumpsys window").lineSequence()
                .firstOrNull { "mCurrentFocus" in it }?.trim().orEmpty()
        }.getOrDefault("").ifEmpty { "mCurrentFocus unbekannt" }
        return "Seite: $page; sichtbar: $tags; Activity: $stage; Bildschirm an: ${interactive()}; " +
            "gesperrt: ${keyguardLocked()}; $focus"
    }

    /** Seite nach ihren Kennungen (die oberste zuerst). */
    private fun screen(): String = when {
        shown(UiTestTags.ALARM_SAVE) -> "Alarm-Editor"
        shown(UiTestTags.ALARM_CARD) -> "Alarme des Paars"
        shown(UiTestTags.ALARMS_OVERVIEW) -> "Alle Alarme"
        shown(UiTestTags.WATCH_ACTION_ALARM) -> "Aktionsblatt"
        count(reportTitle()) > 0 -> "Bericht «Letzte Aktualisierung»"
        shown(UiTestTags.EXPLORER_SEARCH) -> "Paar hinzufügen"
        shown(UiTestTags.BACKUP_EXPORT) -> "Einstellungen › Sichern"
        else -> TABS.firstOrNull { tab ->
            compose.onAllNodes(hasTestTag(tab) and isSelected(), useUnmergedTree = true).fetchSemanticsNodes()
                .isNotEmpty()
        }?.let { "Tab $it" } ?: "Seite ohne Tableiste"
    }

    /** Alle Kennungen aus [UiTestTags], die gerade sichtbar sind. */
    private fun shownTags(): String = UiTestTags::class.java.declaredFields
        .filter { it.type == String::class.java && java.lang.reflect.Modifier.isStatic(it.modifiers) }
        .mapNotNull { runCatching { it.get(null) as String }.getOrNull() }
        .filter { runCatching { shown(it) }.getOrDefault(false) }
        .joinToString(",").ifEmpty { "keine" }

    private fun interactive(): Boolean =
        instrumentation.targetContext.getSystemService(PowerManager::class.java)?.isInteractive == true

    private fun keyguardLocked(): Boolean =
        instrumentation.targetContext.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    // ── Activities ──

    /** Aktuelle Activity (für Texte in der Sprache der App). */
    fun activity(): Activity = checkNotNull(resumedActivity() ?: liveActivities().firstOrNull()) { "keine Activity" }

    private fun resumedActivity(): Activity? = inStages(listOf(Stage.RESUMED)).firstOrNull()

    private fun liveActivities(): List<Activity> = inStages(LIVE_STAGES)

    private fun inStages(stages: List<Stage>): List<Activity> {
        var found = emptyList<Activity>()
        instrumentation.runOnMainSync {
            val registry = ActivityLifecycleMonitorRegistry.getInstance()
            found = stages.flatMap { registry.getActivitiesInStage(it) }.filterIsInstance<MainActivity>()
        }
        return found
    }

    private fun activityStage(): String {
        var stages = ""
        instrumentation.runOnMainSync {
            val registry = ActivityLifecycleMonitorRegistry.getInstance()
            stages = LIVE_STAGES.flatMap { stage ->
                registry.getActivitiesInStage(stage).filterIsInstance<MainActivity>().map { stage.name }
            }.joinToString(",").ifEmpty { "keine" }
        }
        return stages
    }

    // ── Treffer ──

    /** Blatt, Unterseite oder Bericht über der Merkliste offen (sichtbar, nicht nur noch im Baum). */
    private fun overlayOpen(): Boolean = OVERLAY_TAGS.any(::shown) || count(reportTitle()) > 0

    /**
     * Knoten mit [tag] auf dem Bildschirm: platziert, mit Fläche und innerhalb der Bildschirmhöhe (ein weggeschobenes
     * Blatt bleibt platziert, liegt aber unterhalb des Bildschirms).
     */
    fun shown(tag: String): Boolean {
        val screenHeight = instrumentation.targetContext.resources.displayMetrics.heightPixels
        return compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().any { node ->
            val bounds = node.boundsInWindow
            node.layoutInfo.isPlaced && bounds.width > 0f && bounds.height > 0f &&
                bounds.top < screenHeight && bounds.bottom > 0f
        }
    }

    /** Titel des Berichts «Letzte Aktualisierung» im Blatt (der gleich beschriftete Menüeintrag ist antippbar). */
    fun reportTitle(): SemanticsMatcher =
        hasText(activity().getString(R.string.watchlist_refresh_report)) and
            SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick)

    /**
     * Zeile der Einstellungen (`SettingsNavRow`): ihre Semantik ist ersetzt (`clearAndSetSemantics`), es gibt
     * keinen Text, nur die Beschreibung «Titel» bzw. «Titel, Wert». Darum über deren Anfang, nicht `hasText`.
     */
    fun settingsRow(title: Int): SemanticsMatcher {
        val text = activity().getString(title)
        return SemanticsMatcher("Einstellungszeile «$text»") { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                .any { it == text || it.startsWith("$text, ") }
        } and hasClickAction()
    }

    // ── Hilfen (wie UiFlowsTest) ──

    fun count(matcher: SemanticsMatcher): Int = compose.onAllNodes(matcher).fetchSemanticsNodes().size

    fun exists(tag: String): Boolean =
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** Erster Knoten mit [tag], wenn nötig in einen scrollbaren Bereich gescrollt. */
    fun node(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).onFirst().also {
        // Nicht jeder Knoten liegt in einer scrollbaren Liste (Blatt, Tableiste): dann nichts zu tun
        runCatching { it.performScrollTo() }
    }

    /** Wartet, bis der Knopf da und bedienbar ist, und tippt. */
    fun clickTag(tag: String, timeout: Long = TIMEOUT) {
        val enabled = hasTestTag(tag) and isEnabled()
        await("Knopf $tag bedienbar", timeout) {
            compose.onAllNodes(enabled, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        node(tag).performClick()
        compose.waitForIdle()
    }

    /** Erster Knoten zu [matcher] (zusammengeführter Baum, z. B. Zeile mit Titel), wenn nötig gescrollt, antippen. */
    fun click(matcher: SemanticsMatcher) {
        await("Knoten ${matcher.description}") { count(matcher) > 0 }
        val target = compose.onAllNodes(matcher).onFirst()
        runCatching { target.performScrollTo() }
        target.performClick()
        compose.waitForIdle()
    }

    companion object {
        const val TIMEOUT = 15_000L
        private const val POLL_MILLIS = 50L

        /** Kennungen von Blättern und Seiten über der Merkliste. */
        val OVERLAY_TAGS = listOf(
            UiTestTags.WATCH_ACTION_ALARM, UiTestTags.ALARM_SAVE, UiTestTags.ALARM_CARD,
            UiTestTags.ALARMS_OVERVIEW, UiTestTags.EXPLORER_SEARCH,
        )

        private val TABS = listOf(
            UiTestTags.TAB_WATCHLIST, UiTestTags.TAB_CYCLE, UiTestTags.TAB_PORTFOLIO, UiTestTags.TAB_SETTINGS,
        )

        /** Lebende Activity (nicht DESTROYED). */
        private val LIVE_STAGES = listOf(
            Stage.PRE_ON_CREATE, Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED, Stage.STOPPED,
            Stage.RESTARTED,
        )
    }
}
