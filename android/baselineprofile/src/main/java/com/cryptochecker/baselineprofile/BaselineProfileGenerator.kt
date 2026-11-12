package com.cryptochecker.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * Zwei Wege, beide fliessen ins Baseline Profile (`baseline-prof.txt`, ART kompiliert sie bei der Installation vor):
 * - [startup]: nur Kaltstart bis zur sichtbaren Merkliste – allein dieser Weg bildet das Start-Profil
 *   (`startup-prof.txt`, `includeInStartupProfile = true`), nach dem R8 die Klassen des Starts in die erste
 *   DEX-Datei legt. Enthielte es den ganzen Rundgang, landete fast alles dort und der Nutzen fürs Starten verpuffte.
 * - [generate]: der ganze Rundgang – Merkliste scrollen, eine Paarseite öffnen, dann die Tabs «Markt», «Portfolio»
 *   und «Einstellungen». Ohne Paare (frische Installation) zuerst die Start-Coins übernehmen; der Portfolio-Tab ist
 *   ab Werk aus und wird einmal unter Einstellungen › Portfolio eingeschaltet.
 *
 *   ./gradlew :app:generateBaselineProfile      (in der CI: Workflow «Baseline profile»)
 *
 * Die App hat keinen Demo-Modus im Release-Build (die Fake-Börsen leben in androidTest): Die Start-Coins und der
 * Tab «Markt» brauchen Internet. Jeder Schritt ist darum nachsichtig – fehlt ein Element, geht der Lauf weiter,
 * statt zu scheitern; Start, Navigation und Einstellungen sind auch ohne Netz im Profil.
 *
 * Die Kennungen sind Compose-testTags (`UiTestTags`), die die App als Ressourcen-Id zeigt
 * (testTagsAsResourceId im Scaffold von AppNavHost). Die zwei Zeilen der Einstellungen haben keine Kennung:
 * ihr Text kommt aus den Ressourcen der App (Sprache des Geräts), sonst gilt der englische.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    private val packageName: String
        get() = InstrumentationRegistry.getArguments().getString("targetAppId") ?: PACKAGE

    /**
     * Start-Profil: kalt starten, bis die Merkliste steht – Zeilen der gespeicherten Paare oder, nach frischer
     * Installation, die Start-Auswahl. Mehr nicht: was erst nach dem ersten Bild läuft, gehört nicht in den Start.
     */
    @Test
    fun startup() = rule.collect(
        packageName = packageName,
        includeInStartupProfile = true,
    ) {
        grantNotifications()
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.res(Pattern.compile("$ROW|$STARTER_ADD"))), LONG_WAIT)
    }

    /** Ganzer Rundgang: nur fürs Baseline Profile, nicht fürs Start-Profil. */
    @Test
    fun generate() = rule.collect(
        packageName = packageName,
        includeInStartupProfile = false,
    ) {
        grantNotifications()
        pressHome()
        startActivityAndWait()

        seedWatchlist()
        scrollWatchlist()
        openPairPage()
        openTab(TAB_MARKET)
        enablePortfolioTab()
        if (openTab(TAB_PORTFOLIO)) scrollDown()
        if (openTab(TAB_SETTINGS)) scrollDown()
        openTab(TAB_WATCHLIST)
    }

    /** Erlaubnis vorab: sonst legt sich nach den Start-Coins die Erklärung samt System-Abfrage über die App. */
    private fun MacrobenchmarkScope.grantNotifications() {
        runCatching { device.executeShellCommand("pm grant $packageName android.permission.POST_NOTIFICATIONS") }
    }

    /** Frische Installation: Start-Coins übernehmen (Knopf wird aktiv, sobald die Kurse da sind). */
    private fun MacrobenchmarkScope.seedWatchlist() {
        if (device.wait(Until.hasObject(By.res(ROW)), SHORT_WAIT)) return
        device.wait(Until.hasObject(By.res(STARTER_ADD).enabled(true)), LONG_WAIT)
        device.findObject(By.res(STARTER_ADD))?.click()
        device.wait(Until.hasObject(By.res(ROW)), LONG_WAIT)
    }

    private fun MacrobenchmarkScope.scrollWatchlist() {
        device.findObject(By.res(LIST))?.let { list ->
            list.setGestureMargin(device.displayWidth / 5)
            list.fling(Direction.DOWN)
            device.waitForIdle()
            list.fling(Direction.UP)
            device.waitForIdle()
        }
    }

    /** Tipp auf eine Zeile öffnet die Paarseite; zurück zur Merkliste. */
    private fun MacrobenchmarkScope.openPairPage() {
        val row = device.findObject(By.res(ROW)) ?: return
        row.click()
        if (device.wait(Until.hasObject(By.res(PAIR_SCREEN)), SHORT_WAIT)) {
            settle()
            scrollDown()
        }
        backToTabs()
    }

    /** Wählt einen Tab der Tableiste; false, wenn es ihn (noch) nicht gibt. */
    private fun MacrobenchmarkScope.openTab(tag: String): Boolean {
        backToTabs()
        val tab = device.findObject(By.res(tag)) ?: return false
        tab.click()
        settle()
        return true
    }

    /** Portfolio-Tab einschalten (Einstellungen › Portfolio › «Portfolio-Tab anzeigen»), falls er fehlt. */
    private fun MacrobenchmarkScope.enablePortfolioTab() {
        backToTabs()
        if (device.hasObject(By.res(TAB_PORTFOLIO)) || !openTab(TAB_SETTINGS)) return
        val page = appString("portfolio_title") ?: "Portfolio"
        val switch = appString("settings_portfolio_tab") ?: "Show Portfolio tab"
        // Zeilen fassen Titel und Wert in einem Knoten zusammen: Anfang des Texts vergleichen
        (device.findObject(By.textStartsWith(page).clickable(true)) ?: device.findObject(By.textStartsWith(page)))?.click()
        if (device.wait(Until.hasObject(By.textStartsWith(switch)), SHORT_WAIT)) {
            device.findObject(By.textStartsWith(switch))?.click()
            settle()
        }
        backToTabs()
    }

    /** Unterseiten schliessen, bis die Tableiste wieder da ist (höchstens drei Schritte zurück). */
    private fun MacrobenchmarkScope.backToTabs() {
        repeat(MAX_BACK) {
            if (device.hasObject(By.res(TAB_WATCHLIST))) return
            device.pressBack()
            device.wait(Until.hasObject(By.res(TAB_WATCHLIST)), SHORT_WAIT)
        }
    }

    private fun MacrobenchmarkScope.scrollDown() {
        val x = device.displayWidth / 2
        val height = device.displayHeight
        device.swipe(x, height * 2 / 3, x, height / 3, SWIPE_STEPS)
        device.waitForIdle()
    }

    private fun MacrobenchmarkScope.settle() {
        device.waitForIdle()
        Thread.sleep(SETTLE_MILLIS)
    }

    /** Text der App in der Sprache des Geräts (Ressourcen des Zielpakets); null, wenn nicht lesbar. */
    private fun appString(name: String): String? = runCatching {
        val target = packageName
        val resources = InstrumentationRegistry.getInstrumentation().context.packageManager
            .getResourcesForApplication(target)
        // Ressourcen liegen im Namensraum der App, auch wenn die applicationId ein Suffix trägt
        val id = sequenceOf(target, PACKAGE).map { resources.getIdentifier(name, "string", it) }.firstOrNull { it != 0 }
        id?.let { resources.getString(it) }
    }.getOrNull()

    private companion object {
        const val PACKAGE = "com.cryptochecker.app"

        // Gleiche Namen wie UiTestTags der App
        const val LIST = "watchlist"
        const val ROW = "watch_row"
        const val STARTER_ADD = "starter_add"
        const val PAIR_SCREEN = "pair_screen"
        const val TAB_WATCHLIST = "tab_watchlist"
        const val TAB_MARKET = "tab_cycle"
        const val TAB_PORTFOLIO = "tab_portfolio"
        const val TAB_SETTINGS = "tab_settings"

        const val SHORT_WAIT = 3_000L
        const val LONG_WAIT = 15_000L
        const val SETTLE_MILLIS = 1_000L
        const val SWIPE_STEPS = 20
        const val MAX_BACK = 3
    }
}
