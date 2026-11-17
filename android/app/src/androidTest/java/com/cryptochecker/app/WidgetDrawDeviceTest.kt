package com.cryptochecker.app

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.RemoteViews
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import com.cryptochecker.app.data.RefreshStats
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.addWatch
import com.cryptochecker.app.data.local.AppDatabase
import com.cryptochecker.app.data.local.DatabaseEncryptionNotice
import com.cryptochecker.app.data.util.ConnectivityMonitor
import com.cryptochecker.app.domain.model.ExchangeInfo
import com.cryptochecker.app.domain.portfolio.PortfolioSnapshot
import com.cryptochecker.app.domain.refresh.CheckRunStore
import com.cryptochecker.app.domain.refresh.CheckTrigger
import com.cryptochecker.app.domain.refresh.PriceRefresher
import com.cryptochecker.app.domain.settings.AccentColor
import com.cryptochecker.app.domain.widget.WidgetContrast
import com.cryptochecker.app.testing.AppTestEnvironmentRule
import com.cryptochecker.app.widget.ListWidgetRenderer
import com.cryptochecker.app.widget.PortfolioWidgetRenderer
import com.cryptochecker.app.widget.PulseWidgetRenderer
import com.cryptochecker.app.widget.PulseWidgetTexts
import com.cryptochecker.app.widget.SingleWidgetRenderer
import com.cryptochecker.app.widget.WidgetColors
import com.cryptochecker.app.widget.WidgetConnectivity
import com.cryptochecker.app.widget.WidgetOffline
import com.cryptochecker.app.widget.WidgetPrefs
import com.cryptochecker.app.widget.WidgetPublish
import com.cryptochecker.app.widget.WidgetReconnectWorker
import com.cryptochecker.app.widget.timeText
import com.cryptochecker.app.widget.widgetPairText
import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.resources.R
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * Widgets, wie sie gezeichnet werden (Audit A-01, früher Quelltext-Wächter `WidgetOfflineTest` und
 * `WidgetInitialLayoutTest`): Die echten Renderer zeichnen, [WidgetPublish.testSink] fängt die [RemoteViews] ab, der Test
 * bläht sie wie der Launcher auf und liest, was dasteht.
 * - Ohne Internet: «Keine Verbindung · Stand 14:05» bzw. «Keine Verbindung» in der Zeit-Zeile, Neuzeichnen bei Rückkehr
 *   des Netzes geplant (WorkManager, nur mit Netz) und ausgeführt (ohne bestätigtes Netz später erneut, sonst Kurse
 *   holen); eine Netzabfrage je Zeichnen, nicht je Widget.
 * - Erster Auftritt (android:initialLayout): Hintergrund im Thema «System», Skelett mit Kreisel und «Kurse werden
 *   geladen…», Symbol oben verborgen; jedes Zeichnen blendet das Skelett aus. Gespeicherter Stand nicht lesbar: die Liste
 *   zeichnet nicht (der Launcher behält das Skelett statt «Noch keine Paare»).
 */
@HiltAndroidTest
class WidgetDrawDeviceTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val environment = AppTestEnvironmentRule()

    @Inject lateinit var single: SingleWidgetRenderer
    @Inject lateinit var list: ListWidgetRenderer
    @Inject lateinit var portfolio: PortfolioWidgetRenderer
    @Inject lateinit var pulse: PulseWidgetRenderer
    @Inject lateinit var connectivity: WidgetConnectivity
    @Inject lateinit var widgetPrefs: WidgetPrefs
    @Inject lateinit var watchRepository: WatchRepository
    @Inject lateinit var priceRefresher: PriceRefresher
    @Inject lateinit var refreshStats: RefreshStats
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var checkRuns: CheckRunStore

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val drawn = ConcurrentHashMap<Int, RemoteViews>()
    private val ids = intArrayOf(SINGLE_A, SINGLE_B, LIST_A, LIST_B, PORTFOLIO, PULSE)

    @Before
    fun setUp() {
        hiltRule.inject()
        WidgetPublish.testSink = { id, views -> drawn[id] = views }
        runBlocking { watchRepository.deleteAllWatches() }
    }

    @After
    fun tearDown() {
        WidgetPublish.testSink = null
        ConnectivityMonitor.forcedOnlineForTest = null
        DatabaseEncryptionNotice.clearInMemoryForTest(AppDatabase.DB_NAME)
        ids.forEach(widgetPrefs::remove)
        WorkManager.getInstance(context).cancelUniqueWork(WidgetReconnectWorker.WORK_NAME)
        runBlocking { watchRepository.deleteAllWatches() }
    }

    /** Ein Paar mit Kurs (aus [com.cryptochecker.app.testing.FakeExchange]), dem Einzel-Widget [SINGLE_A] zugeordnet. */
    private suspend fun pairWithPrice(): Long {
        val id = checkNotNull(watchRepository.addWatch(ExchangeInfo("Binance", "Binance"), ExchangePair("BTC", "USDT", "BTCUSDT")))
        priceRefresher.refreshOne(id)
        val watch = checkNotNull(watchRepository.getWatches().firstOrNull { it.id == id })
        assertTrue("Testannahme: Kurs geholt", watch.lastUpdate > 0)
        widgetPrefs.setWatchId(SINGLE_A, id, widgetPairText(watch))
        refreshStats.setLastRefresh(0, System.currentTimeMillis() - 60_000)
        return id
    }

    @Test
    fun withoutInternetEveryTimeLineSaysSo() = runBlocking {
        val id = pairWithPrice()
        val watch = checkNotNull(watchRepository.getWatches().firstOrNull { it.id == id })
        ConnectivityMonitor.forcedOnlineForTest = false
        single.update(intArrayOf(SINGLE_A), fetch = false)
        list.update(intArrayOf(LIST_A))
        val short = WidgetOffline.short(context)
        val singleTexts = texts(drawnView(SINGLE_A))
        assertTrue(singleTexts.toString(), singleTexts.any { it.startsWith(WidgetOffline.label(context, watch.lastUpdate)) || it.startsWith(short) })
        val listTexts = texts(drawnView(LIST_A))
        assertTrue(listTexts.toString(), listTexts.any { it.startsWith(WidgetOffline.label(context, refreshStats.lastRefresh())) || it.startsWith(short) })
        // Portfolio und Puls: dieselbe Zeile aus ihren Textregeln (Daten dieser Widgets kommen nicht aus dem Test-Netz)
        val time = System.currentTimeMillis() - 90_000
        portfolio.update(intArrayOf(PORTFOLIO))
        assertTrue("Portfolio-Widget hat das Netz gefragt", portfolio.offline)
        val snapshot = PortfolioSnapshot(total = 1_000.0, changeAmount = null, changePercent = null, currency = "USD", time = time, empty = false)
        assertEquals(WidgetOffline.label(context, time), portfolio.timeText(snapshot, outdated = true))
        assertEquals(WidgetOffline.label(context, time), PulseWidgetTexts.stand(context, time, online = false))
        // Neu zeichnen, sobald das Netz zurück ist: einmal geplant, nur mit Netz
        val work = WorkManager.getInstance(context).getWorkInfosForUniqueWork(WidgetReconnectWorker.WORK_NAME).get()
        assertEquals(1, work.size)
        assertEquals(WorkInfo.State.ENQUEUED, work.single().state)
        assertEquals(NetworkType.CONNECTED, work.single().constraints.requiredNetworkType)
        // Mit Netz: keine Offline-Zeile mehr
        ConnectivityMonitor.forcedOnlineForTest = true
        single.update(intArrayOf(SINGLE_A), fetch = false)
        list.update(intArrayOf(LIST_A))
        for (widget in listOf(SINGLE_A, LIST_A)) {
            val shown = texts(drawnView(widget))
            assertFalse("$widget: $shown", shown.any { it.contains(short) })
        }
        portfolio.update(intArrayOf(PORTFOLIO))
        assertFalse(portfolio.offline)
    }

    /**
     * Neuzeichnen bei Rückkehr des Netzes ([WidgetReconnectWorker]): ohne bestätigtes Netz (Captive Portal) später erneut;
     * mit Netz neu zeichnen und die Kurse holen wie die Hintergrund-Prüfung (Eintrag in «Letzte Prüfungen»).
     */
    @Test
    fun reconnectRetriesWithoutInternetAndChecksWithIt() = runBlocking {
        pairWithPrice()
        fun worker() = TestListenableWorkerBuilder<WidgetReconnectWorker>(context).setWorkerFactory(workerFactory).build()
        ConnectivityMonitor.forcedOnlineForTest = false
        assertEquals(ListenableWorker.Result.retry(), worker().doWork())
        ConnectivityMonitor.forcedOnlineForTest = true
        val before = System.currentTimeMillis()
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertTrue("Kurse nach Rückkehr geprüft", checkRuns.runs.value.any { it.at >= before && it.trigger == CheckTrigger.WORKER })
    }

    /** Eine Netzabfrage je Zeichnen, auch für mehrere Widgets derselben Art (nicht je Widget). */
    @Test
    fun oneNetworkQueryPerDraw() = runBlocking {
        pairWithPrice()
        ConnectivityMonitor.forcedOnlineForTest = true
        fun queries(draw: suspend () -> Unit): Int {
            val before = connectivity.queries.get()
            runBlocking { draw() }
            return connectivity.queries.get() - before
        }
        assertEquals("Einzel", 1, queries { single.update(intArrayOf(SINGLE_A, SINGLE_B), fetch = false) })
        assertEquals("Liste", 1, queries { list.update(intArrayOf(LIST_A, LIST_B)) })
        assertEquals("Portfolio", 1, queries { portfolio.update(intArrayOf(PORTFOLIO)) })
        assertEquals("Puls", 1, queries { pulse.update(intArrayOf(PULSE), fetch = false) })
    }

    /** Erster Auftritt: jedes Widget startet mit seinem Start-Layout (Skelett), nie mit einer leeren Fläche. */
    @Test
    fun initialLayoutsShowTheSkeleton() {
        val expected = mapOf(
            "PriceWidgetProvider" to R.layout.widget_list,
            "SingleWidgetProvider" to R.layout.widget_single,
            "PortfolioWidgetProvider" to R.layout.widget_portfolio,
            "PulseWidgetProvider" to R.layout.widget_pulse,
        )
        val providers = AppWidgetManager.getInstance(context).getInstalledProvidersForPackage(context.packageName, null)
            .associate { it.provider.className.substringAfterLast('.') to it.initialLayout }
        assertEquals(expected, providers.filterKeys { it in expected })
        for ((name, layout) in expected) {
            val root = apply(RemoteViews(context.packageName, layout))
            val skeleton = root.findViewById<View>(R.id.widget_skeleton)
            assertNotNull("$name: Skelett fehlt", skeleton)
            assertEquals(name, View.VISIBLE, skeleton.visibility)
            assertEquals(name, context.getString(R.string.widget_loading_prices), skeleton.contentDescription?.toString())
            // Ladeanzeige in der Skelett-Gruppe: Kreisel (ohne Zahlen) und «Kurse werden geladen…»
            val group = checkNotNull(skeleton as? ViewGroup) { "$name: Skelett ist keine Gruppe (Ladeanzeige gehört hinein)" }
            val spinners = descendants(group).filterIsInstance<ProgressBar>()
            assertEquals(name, 1, spinners.size)
            val spinner = spinners.single()
            assertTrue(name, spinner.isIndeterminate)
            assertEquals(name, dp(16), spinner.layoutParams.width)
            assertEquals(name, dp(16), spinner.layoutParams.height)
            assertEquals(name, context.getColor(R.color.widget_initial_text_secondary), spinner.indeterminateTintList?.defaultColor)
            val label = descendants(group).filterIsInstance<TextView>().single { it.text.toString() == context.getString(R.string.starter_loading_prices) }
            assertEquals(name, context.getColor(R.color.widget_initial_text_secondary), label.currentTextColor)
            assertEquals(name, sp(12), label.textSize, 0.5f)
            // Eng: der Text kürzt (Auslassung), der Kreisel steht vorn
            assertEquals(name, android.text.TextUtils.TruncateAt.END, label.ellipsize)
            assertEquals(name, 1, label.maxLines)
            // Der Behälter spricht «Kurse werden geladen» einmal; Kreisel und Text sind für den Screenreader ausgenommen
            assertEquals(name, View.IMPORTANT_FOR_ACCESSIBILITY_YES, group.importantForAccessibility)
            for (part in listOf(spinner, label)) assertEquals(name, View.IMPORTANT_FOR_ACCESSIBILITY_NO, part.importantForAccessibility)
            if (layout == R.layout.widget_list) {
                assertEquals("Liste bis zum ersten Zeichnen verborgen", View.GONE, root.findViewById<View>(R.id.widget_list).visibility)
                assertEquals("Symbol oben verborgen", View.INVISIBLE, root.findViewById<View>(R.id.widget_refresh).visibility)
                assertEquals(View.GONE, root.findViewById<View>(R.id.widget_refresh_countdown).visibility)
            } else {
                assertNull(name, root.findViewById<View>(R.id.widget_refresh))
                assertTrue(name, descendants(root as ViewGroup).none { it is Chronometer })
            }
        }
        // Vorschauen im Widget-Auswahlmenü bleiben ohne Skelett
        for (preview in listOf(R.layout.widget_preview_list, R.layout.widget_preview_single, R.layout.widget_preview_portfolio, R.layout.widget_preview_pulse)) {
            assertNull(apply(RemoteViews(context.packageName, preview)).findViewById<View>(R.id.widget_skeleton))
        }
    }

    /** Jedes Zeichnen blendet das Skelett aus; die Liste zeigt danach Zeilen bzw. «Noch keine Paare» und das Symbol. */
    @Test
    fun everyDrawHidesTheSkeleton() = runBlocking {
        pairWithPrice()
        ConnectivityMonitor.forcedOnlineForTest = true
        single.update(intArrayOf(SINGLE_A), fetch = false)
        list.update(intArrayOf(LIST_A))
        portfolio.update(intArrayOf(PORTFOLIO))
        pulse.update(intArrayOf(PULSE), fetch = false)
        for (widget in listOf(SINGLE_A, LIST_A, PORTFOLIO, PULSE)) {
            val skeleton = drawnView(widget).findViewById<View>(R.id.widget_skeleton)
            assertTrue("$widget: Skelett noch sichtbar", skeleton == null || skeleton.visibility == View.GONE)
        }
        val listView = drawnView(LIST_A)
        // Symbol (oder in der 15-s-Sperre der Countdown statt seiner), nie mehr das verborgene Symbol des Start-Layouts
        val refresh = listView.findViewById<View>(R.id.widget_refresh).visibility
        val countdown = listView.findViewById<View>(R.id.widget_refresh_countdown).visibility
        assertTrue("Symbol $refresh, Countdown $countdown", refresh == View.VISIBLE || (refresh == View.GONE && countdown == View.VISIBLE))
        assertTrue(View.VISIBLE in listOf(R.id.widget_list, R.id.widget_empty).map { listView.findViewById<View>(it).visibility })
    }

    /** Gespeicherter Stand gerade nicht lesbar (nur im Arbeitsspeicher): die Liste zeichnet nicht, der Launcher behält das Skelett. */
    @Test
    fun keptStoredStateDrawsNoEmptyList() = runBlocking {
        ConnectivityMonitor.forcedOnlineForTest = true
        DatabaseEncryptionNotice.markInMemory(AppDatabase.DB_NAME, storedKept = true)
        try {
            list.update(intArrayOf(LIST_B))
            assertNull("nicht gezeichnet", drawn[LIST_B])
        } finally {
            DatabaseEncryptionNotice.clearInMemoryForTest(AppDatabase.DB_NAME)
        }
    }

    /** Farben des ersten Auftritts = Thema «System» der Widgets (90 %), hell und dunkel. */
    @Test
    fun initialColoursMatchTheSystemTheme() {
        for (dark in listOf(false, true)) {
            val config = Configuration(context.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    (if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
            }
            val themed = context.createConfigurationContext(config)
            val widget = WidgetColors.of(AccentColor.ORANGE, dark)
            assertEquals("dark=$dark", widget.colorWithOpacity(WidgetColors.DEFAULT_OPACITY), themed.getColor(R.color.widget_initial_background))
            assertEquals("dark=$dark", widget.textColor, themed.getColor(R.color.widget_initial_text))
            assertEquals("dark=$dark", widget.secondaryTextColor, themed.getColor(R.color.widget_initial_text_secondary))
            // Lesbar: Text und Nebentext mindestens 4.5:1; Skelett sichtbar, aber ruhiger als jeder Text
            val base = themed.getColor(R.color.widget_initial_background) or 0xFF000000.toInt()
            assertTrue(WidgetContrast.ratio(themed.getColor(R.color.widget_initial_text), base) >= 4.5)
            assertTrue(WidgetContrast.ratio(themed.getColor(R.color.widget_initial_text_secondary), base) >= 4.5)
            val bar = WidgetContrast.ratio(themed.getColor(R.color.widget_initial_skeleton), base)
            assertTrue("dark=$dark: $bar", bar >= 1.3 && bar < 3.0)
        }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density + 0.5f).toInt()

    private fun sp(value: Int): Float =
        android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, value.toFloat(), context.resources.displayMetrics)

    private fun drawnView(id: Int): View = apply(checkNotNull(drawn[id]) { "Widget $id nicht gezeichnet" })

    /** Wie der Launcher: auf dem Hauptthread in einen Elternteil aufblähen. */
    private fun apply(views: RemoteViews): View {
        var view: View? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { view = views.apply(context, FrameLayout(context)) }
        return view!!
    }

    private fun descendants(root: ViewGroup): List<View> =
        (0 until root.childCount).map(root::getChildAt).flatMap { child -> listOf(child) + ((child as? ViewGroup)?.let(::descendants) ?: emptyList()) }

    /** Alle Texte, die ein sichtbarer Teil des Widgets zeigt. */
    private fun texts(root: View): List<String> =
        (if (root is ViewGroup) descendants(root) + root else listOf(root))
            .filterIsInstance<TextView>()
            .filter { it.visibility == View.VISIBLE }
            .map { it.text.toString() }

    private companion object {
        // Ids, die kein echtes Widget trägt (der Launcher kennt sie nicht; gezeichnet wird in testSink)
        const val SINGLE_A = 990_001
        const val SINGLE_B = 990_002
        const val LIST_A = 990_011
        const val LIST_B = 990_012
        const val PORTFOLIO = 990_021
        const val PULSE = 990_031
    }
}
