package com.cryptochecker.app.widget

import android.content.Context
import android.os.PowerManager
import com.cryptochecker.app.data.RefreshStats
import com.cryptochecker.app.domain.portfolio.TimedPrice
import com.cryptochecker.app.domain.watch.ChangeBasis
import com.cryptochecker.app.domain.watch.ChangeView
import com.cryptochecker.app.domain.watch.isNotTraded
import com.cryptochecker.app.lock.PortfolioLockPolicy
import com.cryptochecker.app.settings.HighContrast
import com.cryptochecker.app.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Zeichnet die Startbildschirm-Widgets neu — Einstieg für App, Hintergrund-Aktualisierung und
 * Widget-Empfänger. Gezeichnet wird je Art in [ListWidgetRenderer], [SingleWidgetRenderer],
 * [PortfolioWidgetRenderer] und [PulseWidgetRenderer]; hier stehen, was alle betrifft: welche
 * nach einer Aktualisierung neu müssen, hoher Kontrast und der Wecker für «veraltet».
 */
@Singleton
class WidgetUpdater @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val widgetPrefs: WidgetPrefs,
    private val refreshStats: RefreshStats,
    private val settingsRepository: SettingsRepository,
    private val watchRepository: com.cryptochecker.app.data.WatchRepository,
    private val portfolioSnapshotStore: PortfolioSnapshotStore,
    private val toolkit: WidgetToolkit,
    private val listWidgets: ListWidgetRenderer,
    private val singleWidgets: SingleWidgetRenderer,
    private val portfolioWidgets: PortfolioWidgetRenderer,
    private val pulseWidgets: PulseWidgetRenderer,
) {
    fun widgetIds(): IntArray = toolkit.ids(PriceWidgetProvider::class.java)

    fun singleWidgetIds(): IntArray = toolkit.ids(SingleWidgetProvider::class.java)

    fun portfolioWidgetIds(): IntArray = toolkit.ids(PortfolioWidgetProvider::class.java)

    fun pulseWidgetIds(): IntArray = toolkit.ids(PulseWidgetProvider::class.java)

    /** %-Basis der Widgets, siehe [WidgetToolkit.changeView]. */
    fun changeView(basis: ChangeBasis, now: Long = System.currentTimeMillis()): ChangeView =
        toolkit.changeView(basis, now)

    /** Listen-Widgets neu zeichnen ([ListWidgetRenderer]). */
    suspend fun update(appWidgetIds: IntArray) {
        if (listWidgets.update(appWidgetIds)) scheduleOutdatedCheck()
    }

    /** Zeichnet Einzel-Widgets neu: Paar, Kurs, Änderung, Chart (Kerzen/Linie; 24 h / 7 / 30 Tage). */
    suspend fun updateSingle(appWidgetIds: IntArray) {
        if (singleWidgets.update(appWidgetIds)) scheduleOutdatedCheck()
    }

    /** Portfolio-Widgets aus der letzten Momentaufnahme zeichnen ([PortfolioWidgetRenderer]). */
    suspend fun updatePortfolio(appWidgetIds: IntArray) {
        if (portfolioWidgets.update(appWidgetIds)) scheduleOutdatedCheck()
    }

    /** Widgets «Was gerade auffällt» ([PulseWidgetRenderer.update]). */
    suspend fun updatePulse(appWidgetIds: IntArray, fetch: Boolean = true) =
        pulseWidgets.update(appWidgetIds, fetch)

    /**
     * Wecker auf den Moment, an dem der nächste angezeigte Stand veraltet ([WidgetOutdated]):
     * Uhrzeit der Liste, Kurse der Paare (Listen- und Einzel-Widgets), Momentaufnahme des
     * Portfolios. Dann zeichnet [redrawOutdated] die Widgets ohne Netz neu.
     */
    private suspend fun scheduleOutdatedCheck() {
        val hasList = widgetIds().isNotEmpty()
        val hasSingle = singleWidgetIds().isNotEmpty()
        val hasPortfolio = portfolioWidgetIds().isNotEmpty()
        val settings = settingsRepository.current()
        val times = buildList {
            if (hasList) add(refreshStats.lastRefresh())
            if (hasList || hasSingle) {
                watchRepository.getWatches()
                    .filterNot { it.isNotTraded }
                    .forEach { add(it.lastUpdate) }
            }
            if (hasPortfolio && !PortfolioLockPolicy.widgetLocked(settings.appLock)) portfolioSnapshotStore.snapshot()?.let { add(it.time) }
        }
        WidgetOutdated.schedule(context, times, WidgetOutdated.afterMillis(settings))
    }

    /** Vom Wecker ([WidgetOutdated.schedule]): Listen-, Einzel- und Portfolio-Widgets neu zeichnen. */
    suspend fun redrawOutdated() {
        update(widgetIds())
        updateSingle(singleWidgetIds())
        updatePortfolio(portfolioWidgetIds())
    }

    /** Stundenkerzen eines Einzel-Widgets ohne Netz, siehe [SingleWidgetRenderer.cachedHourlyPrices]. */
    fun cachedHourlyPrices(base: String): List<TimedPrice>? = singleWidgets.cachedHourlyPrices(base)

    /** Stundenkerzen laden, siehe [SingleWidgetRenderer.loadHourlyPrices]. */
    suspend fun loadHourlyPrices(base: String): List<TimedPrice>? = singleWidgets.loadHourlyPrices(base)

    /** Gelöschte Einzel-Widgets: zwischengespeicherten Chart freigeben. */
    fun forgetWidgets(appWidgetIds: IntArray) = singleWidgets.forget(appWidgetIds)

    /** Gelöschte Portfolio-Widgets: zwischengespeicherte Charts aller Grössen freigeben. */
    fun forgetPortfolioWidgets(appWidgetIds: IntArray) = portfolioWidgets.forget(appWidgetIds)

    /**
     * Beim Start der App: Hat sich der wirksame hohe Kontrast (Einstellung oder
     * System-Kontrast) seit dem letzten Zeichnen geändert, alle Widgets neu zeichnen.
     */
    suspend fun updateIfContrastChanged() {
        val effective = HighContrast.isEffective(context, settingsRepository.current().highContrast)
        if (widgetPrefs.lastHighContrast == effective) return
        val hasWidgets = widgetIds().isNotEmpty() || singleWidgetIds().isNotEmpty() ||
            portfolioWidgetIds().isNotEmpty() || pulseWidgetIds().isNotEmpty()
        if (hasWidgets) updateAll() else widgetPrefs.lastHighContrast = effective
    }

    /** Bildschirm an (bzw. Gerät bedienbar)? Ohne PowerManager: ja. */
    fun isScreenInteractive(): Boolean =
        context.getSystemService(PowerManager::class.java)?.isInteractive ?: true

    suspend fun updateAll() {
        update(widgetIds())
        updateSingle(singleWidgetIds())
        updatePortfolio(portfolioWidgetIds())
        pulseWidgets.updateWithOthers()
    }

    /**
     * Nach der Aktualisierung EINES Paares: nur, was dieses Paar zeigt — Listen-Widgets,
     * deren Liste es enthält, und Einzel-Widgets dieses Paares. Portfolio-Widgets nur, wenn
     * sich die Momentaufnahme seit dem letzten Zeichnen geändert hat; «Was gerade auffällt»
     * hängt nicht an einem einzelnen Paar und bleibt.
     */
    suspend fun updateForWatch(watchId: Long) {
        val listIds = widgetIds()
        if (listIds.isNotEmpty()) {
            val watches = watchRepository.getWatches()
            val watch = watches.firstOrNull { it.id == watchId }
            val groups = watches.mapTo(HashSet()) { it.groupName }
            // Paar inzwischen gelöscht: alle Listen, damit es überall verschwindet
            update(
                listIds.filter { id ->
                    watch == null || WidgetRefreshScope.listShowsWatch(widgetPrefs.getGroup(id), watch.groupName, groups)
                }.toIntArray()
            )
        }
        updateSingle(singleWidgetIds().filter { widgetPrefs.getWatchId(it) == watchId }.toIntArray())

        val portfolioIds = portfolioWidgetIds()
        if (portfolioIds.isNotEmpty()) {
            val snapshot = portfolioSnapshotStore.snapshot()
            val state = PortfolioDrawState(snapshot?.total, snapshot?.time)
            if (WidgetRefreshScope.portfolioChanged(portfolioWidgets.drawn, state)) updatePortfolio(portfolioIds)
        }
    }
}
