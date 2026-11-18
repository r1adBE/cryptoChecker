package com.cryptochecker.app

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import com.cryptochecker.app.testing.AppTestEnvironmentRule
import com.cryptochecker.app.widget.PortfolioWidgetConfigureActivity
import com.cryptochecker.app.widget.PortfolioWidgetProvider
import com.cryptochecker.app.widget.PriceWidgetProvider
import com.cryptochecker.app.widget.PulseWidgetConfigureActivity
import com.cryptochecker.app.widget.PulseWidgetProvider
import com.cryptochecker.app.widget.SingleWidgetConfigureActivity
import com.cryptochecker.app.widget.SingleWidgetProvider
import com.cryptochecker.app.widget.WidgetConfigureActivity

/**
 * Einrichten-Seiten der vier Widgets für die Bildschirmfoto-Tour ([ScreenshotTourTest]): Die Seiten nehmen nur die Id
 * eines eigenen Widgets an (`ownWidgetId`), darum bindet die Tour über einen eigenen [AppWidgetHost] je ein echtes
 * Widget (Erlaubnis per `appwidget grantbind`, sonst mit der Berechtigung der Shell) und öffnet die Seite wie der Launcher. [close] gibt die Ids wieder frei.
 */
class TourWidgetConfig(private val context: Context) {

    /** Eine Einrichten-Seite: Name des Bilds, Widget-Anbieter und Activity. */
    data class Page(val name: String, val provider: Class<out AppWidgetProvider>, val activity: Class<out Activity>)

    private val host = AppWidgetHost(context, HOST_ID)
    private val manager = AppWidgetManager.getInstance(context)
    private val ids = mutableListOf<Int>()

    /** Antwort von `appwidget grantbind` (für die Meldung, falls das Binden trotzdem scheitert). */
    private val grant = AppTestEnvironmentRule.shell(
        "appwidget grantbind --package ${context.packageName} --user ${Process.myUid() / PER_USER_RANGE}",
    ).trim()

    /** Widget des Anbieters von [page] binden und seine Einrichten-Seite öffnen (eigene Aufgabe wie vom Launcher). */
    fun open(page: Page) {
        val id = host.allocateAppWidgetId()
        ids += id
        val provider = ComponentName(context, page.provider)
        // Erst mit der Erlaubnis aus grantbind, sonst mit der Berechtigung der Shell (BIND_APPWIDGET) für diesen Aufruf
        val bound = manager.bindAppWidgetIdIfAllowed(id, provider) || withShellBindPermission {
            manager.bindAppWidgetIdIfAllowed(id, provider)
        }
        check(bound) { "Widget ${page.provider.simpleName} nicht gebunden (grantbind: «$grant»)" }
        val intent = Intent(context, page.activity)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        // startActivitySync: wartet, bis die Activity läuft (startet sie wie der Launcher in einer eigenen Aufgabe)
        val started = InstrumentationRegistry.getInstrumentation().startActivitySync(intent)
        check(!started.isFinishing) { "${page.activity.simpleName} hat sich gleich geschlossen; ${diagnose(page)}" }
    }

    private fun <T> withShellBindPermission(block: () -> T): T {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity(Manifest.permission.BIND_APPWIDGET)
        return try {
            block()
        } finally {
            automation.dropShellPermissionIdentity()
        }
    }

    /**
     * Zustand für die Fehlermeldung: Anbieter des zuletzt gebundenen Widgets laut System und die letzten Zeilen des
     * Systemprotokolls zur Activity (Start, Ende, Absturz).
     */
    fun diagnose(page: Page): String {
        val id = ids.lastOrNull()
        val owner = id?.let { runCatching { manager.getAppWidgetInfo(it)?.provider?.flattenToShortString() }.getOrNull() }
        val log = AppTestEnvironmentRule.shell("logcat -d -t 400").lineSequence()
            .filter { page.activity.simpleName in it || "AndroidRuntime" in it }
            .toList().takeLast(6).joinToString(" | ")
        return "Widget $id, Anbieter ${owner ?: "unbekannt"}; Protokoll: ${log.ifEmpty { "nichts" }}"
    }

    /** Seite [page] vorne und kein Systemfenster darüber? (Fokus laut `dumpsys window`) */
    fun inFront(page: Page): Boolean =
        AppTestEnvironmentRule.shell("dumpsys window").lineSequence()
            .firstOrNull { "mCurrentFocus" in it }.orEmpty().contains(page.activity.simpleName)

    /** Offene Einrichten-Seite schliessen (Zurück) und alle gebundenen Ids freigeben. */
    fun close() {
        AppTestEnvironmentRule.shell("input keyevent KEYCODE_BACK")
        ids.forEach { runCatching { host.deleteAppWidgetId(it) } }
        ids.clear()
    }

    companion object {
        /** Eigene Host-Id der Tour (keine andere im Projekt). */
        private const val HOST_ID = 4711

        /** Uids je Android-Benutzer (Benutzer-Nummer = uid / 100000). */
        private const val PER_USER_RANGE = 100_000

        /** Merkliste, Einzel-Paar, Portfolio und Krypto-Puls — nach den Einstellungen (16–19). */
        val PAGES = listOf(
            Page("16_widget_watchlist", PriceWidgetProvider::class.java, WidgetConfigureActivity::class.java),
            Page("17_widget_single", SingleWidgetProvider::class.java, SingleWidgetConfigureActivity::class.java),
            Page("18_widget_portfolio", PortfolioWidgetProvider::class.java, PortfolioWidgetConfigureActivity::class.java),
            Page("19_widget_pulse", PulseWidgetProvider::class.java, PulseWidgetConfigureActivity::class.java),
        )
    }
}
