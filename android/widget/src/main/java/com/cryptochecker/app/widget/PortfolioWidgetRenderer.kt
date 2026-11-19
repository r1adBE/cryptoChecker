package com.cryptochecker.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import com.cryptochecker.app.data.portfolio.FxRateSource
import com.cryptochecker.app.domain.convert.CurrencyConversion
import com.cryptochecker.app.domain.lock.PortfolioLockPolicy
import com.cryptochecker.app.domain.portfolio.PortfolioHistory
import com.cryptochecker.app.domain.portfolio.PortfolioOff
import com.cryptochecker.app.domain.portfolio.PortfolioSnapshot
import com.cryptochecker.app.domain.refresh.OutdatedRule
import com.cryptochecker.app.domain.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.settings.SettingsRepository
import com.cryptochecker.app.domain.watch.ChangeBasis
import com.cryptochecker.app.domain.watch.ChangeBasisMath
import com.cryptochecker.app.domain.widget.LatestPerWidget
import com.cryptochecker.app.domain.widget.PortfolioDrawState
import com.cryptochecker.app.domain.widget.WidgetType
import com.cryptochecker.app.settings.HighContrast
import com.cryptochecker.app.settings.logoRes
import com.cryptochecker.app.util.AppIntents
import com.cryptochecker.app.util.withOpenToken
import com.cryptochecker.resources.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Portfolio-Widget: Gesamtwert, Veränderung, Wertverlauf und die grössten Positionen aus der
 * letzten Momentaufnahme ([PortfolioSnapshotStore]) — ohne Netz. Je Widget eine Ansicht
 * ([PortfolioWidgetView]): Übersicht, Chart oder Liste.
 */
@Singleton
class PortfolioWidgetRenderer @Inject constructor(
    @param:ApplicationContext val context: Context,
    private val widgetPrefs: WidgetPrefs,
    private val settingsRepository: SettingsRepository,
    private val portfolioSnapshotStore: PortfolioSnapshotStore,
    val toolkit: WidgetToolkit,
    private val coinLogos: WidgetCoinLogos,
    /** Devisenkurs, falls noch keiner gemerkt ist ([fxMemoFor]). */
    private val fxSource: FxRateSource,
    /** Live-Kurs USDT/USD für das Umrechnen beim Zeichnen ([PortfolioWidgetChart.usdtFactor]). */
    private val depegReadings: com.cryptochecker.app.domain.alarm.DepegReadingStore,
    private val connectivity: WidgetConnectivity,
) {
    /** Beim letzten Zeichnen ohne Internet: «Keine Verbindung · Stand 15:19» ([timeText], [WidgetStatusRule]). */
    @Volatile var offline = false

    /**
     * Zuletzt gezeichneter Wertverlauf je Portfolio-Widget und Grösse (Schlüssel
     * Widget-Id × [MAX_PORTFOLIO_SIZES] + Grösse), beim Löschen entfernt ([forget]).
     */
    val portfolioCharts = LatestPerWidget<PortfolioChartKey, android.graphics.Bitmap>()

    /** Stand der Momentaufnahme beim letzten Zeichnen der Portfolio-Widgets ([update]). */
    @Volatile
    var drawn: PortfolioDrawState? = null
        private set

    /**
     * Gemerkter Devisenkurs für «Umrechnung». Fehlt er, obwohl die Aufnahme in einer anderen Währung liegt (in USD
     * aufgenommen: keine Aufnahme mit Kurs, z. B. weil zu wenig frische Kurse oder ein frischer Prozess), wird er
     * hier einmal geholt und gemerkt — sonst blieb das Widget trotz «Umrechnung» bei USDT, bis eine Aufnahme gelang.
     */
    private suspend fun fxMemoFor(currency: String, stored: PortfolioSnapshot?): PortfolioFxMemo? {
        val memo = portfolioSnapshotStore.fxMemo()
        val now = System.currentTimeMillis()
        if (stored == null || PortfolioWidgetChart.fxRateFor(currency, null, memo, now) != null) return memo
        if (CurrencyConversion.normalize(stored.currency) == CurrencyConversion.normalize(currency)) return memo
        val fresh = try {
            withTimeoutOrNull(FX_TIMEOUT_MILLIS) { fxSource.usdTo(currency) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d(e, "Portfolio-Widget: Devisenkurs nicht geladen")
            null
        }
        val toSave = PortfolioWidgetChart.fxMemoToSave(currency, fresh, fxSource.cached(currency), fxSource.fetchedAt(currency), now)
            ?: return memo
        portfolioSnapshotStore.saveFxMemo(toSave)
        return toSave
    }

    /**
     * Portfolio-Widgets aus der letzten Momentaufnahme ([PortfolioSnapshotStore]) zeichnen —
     * ohne Netz. Je Widget: Anzeige, Zeitraum und Chart-Art ([WidgetPrefs], [PortfolioWidgetChart.plan]).
     * Layout je Stufe ([PortfolioWidgetMath.size]): klein = Kopfzeile, Gesamtwert, Betrag und Prozent
     * über den Zeitraum; schmal-hoch = dazu der Chart darunter; mittel = Werte links, Chart
     * rechts; gross = dazu die drei grössten Positionen. Ab Android 12 je
     * Grösse des Launchers ein eigenes Layout (RemoteViews mit Grössen-Zuordnung), sonst nach
     * der Grösse aus den Widget-Optionen. Das gilt für die Ansicht «Chart»; «Übersicht» und «Liste»
     * zeigen, was in die Grösse passt ([PortfolioWidgetViews]). Mit Portfolio-Sperre nur Titel, Schloss und
     * «Gesperrt – in der App entsperren», ohne Werte.
     */
    suspend fun update(appWidgetIds: IntArray): Boolean {
        if (appWidgetIds.isEmpty()) return false
        val busy = refreshing
        // 15-s-Sperre nach einer Aktualisierung aus dem Widget: Countdown statt Knopf, wie im Listen-Widget
        val lock = WidgetRefreshHeader.lock(context, widgetPrefs, WidgetRefreshHeader.Kind.PORTFOLIO)
        val manager = AppWidgetManager.getInstance(context) ?: return false
        val settings = settingsRepository.current()
        val accent = settings.accentColor
        val highContrast = HighContrast.isEffective(context, settings.highContrast)
        widgetPrefs.lastHighContrast = highContrast
        val stored = portfolioSnapshotStore.snapshot()
        drawn = PortfolioDrawState(stored?.total, stored?.time)
        // %-Basis: Aufnahme mit anderer Basis oder von einem früheren Tag → Veränderung «—»
        // (bis zur nächsten Aufnahme); der Verlauf bleibt, beschriftet nach seiner eigenen Basis
        val fxMemo = fxMemoFor(settings.portfolioCurrency, stored)
        val snapshot = stored?.let {
            // In der eingestellten Umrechnungswährung (ohne Kurs in USD aufgenommen oder Währung inzwischen
            // gewechselt): mit dem gemerkten Kurs umrechnen, damit «Umrechnung» sofort greift
            val now = System.currentTimeMillis()
            PortfolioWidgetChart.rebased(
                it,
                settings.portfolioCurrency,
                PortfolioWidgetChart.fxRateFor(settings.portfolioCurrency, null, fxMemo, now)
                    ?.let { fx -> PortfolioWidgetChart.usdtFactor(settings.portfolioCurrency, fx, depegReadings.liveUsdtUsd(now)) },
            )
        }?.let {
            if (ChangeBasisMath.isCurrent(it.stamp, settings.changeBasis, System.currentTimeMillis())) {
                it
            } else {
                it.copy(
                    changeAmount = null,
                    changePercent = null,
                    positions = it.positions.map { p -> p.copy(change24hPercent = null, pnlAmount = null, pnlPercent = null) },
                )
            }
        }
        val fontScale = context.resources.configuration.fontScale.takeIf { it > 0f } ?: 1f
        val outdated = snapshot != null &&
            OutdatedRule.isOutdated(snapshot.time, System.currentTimeMillis(), WidgetOutdated.afterMillis(settings))
        val locked = PortfolioLockPolicy.widgetLocked(settings.appLock)
        offline = !connectivity.online()
        // Tage der Balken in derselben Zone wie das Zeitgitter und das Einzel-Widget (Chart-Zeitzone der %-Basis,
        // [ChangeBasisMath.chartZone], nicht die Gerätezone). Wie iOS
        val zone = ChangeBasisMath.chartZone(settings.changeBasis.storage)

        for (appWidgetId in appWidgetIds) {
            val dark = widgetPrefs.isDark(appWidgetId, settings)
            val style = PortfolioStyle(
                colors = WidgetColors.of(accent, dark, settings.priceColorScheme, highContrast, settings.priceColorsInverted),
                dark = dark,
                highContrast = highContrast,
                logoRes = accent.logoRes(dark),
                opacity = widgetPrefs.getOpacity(appWidgetId),
                basis = settings.changeBasis.storage,
                hideAmounts = settings.hidePortfolioAmounts,
            )
            // Portfolio aus: «Portfolio ist aus» und wo man es einschaltet (geht vor Sperre und «leer»)
            val state = PortfolioOff.widgetState(settings.portfolioEnabled, locked, snapshot == null || snapshot.empty)
            val message: String? = when (state) {
                PortfolioOff.WidgetState.OFF -> offHint()
                PortfolioOff.WidgetState.LOCKED -> context.getString(R.string.widget_portfolio_locked_hint)
                PortfolioOff.WidgetState.EMPTY -> context.getString(R.string.widget_portfolio_empty)
                PortfolioOff.WidgetState.VALUES -> null
            }
            val views = if (message != null || snapshot == null) {
                portfolioMessageViews(
                    style,
                    message.orEmpty(),
                    state == PortfolioOff.WidgetState.LOCKED,
                    title = if (state == PortfolioOff.WidgetState.OFF) context.getString(R.string.widget_portfolio_off_title) else null,
                )
            } else {
                // Ansicht je Widget: Übersicht, Chart (Standard) oder Liste
                val view = widgetPrefs.getPortfolioView(appWidgetId)
                // Je Widget: Anzeige (Nur USDT / Umrechnung / + USDT), Zeitraum und Chart-Art — wirksam je Ansicht
                // ([PortfolioWidgetOptions]: Liste ohne «+ USDT», Übersicht nur 30 T / 1 J)
                val chosenDisplay = widgetPrefs.getPortfolioDisplay(appWidgetId, settings.portfolioCurrency)
                val display = PortfolioWidgetChart.effectiveDisplay(
                    PortfolioWidgetOptions.effectiveDisplay(view, chosenDisplay),
                    snapshot.currency,
                    snapshot.totalUsdt != null,
                )
                val shown = PortfolioWidgetChart.displayed(snapshot, display)
                // «≈ … USDT» nur bei «Umrechnung + USDT» (bei einem USD-Stablecoin fällt die Wahl auf «Nur USDT» zurück)
                val wantUsdt = display == PortfolioDisplay.CONVERSION_USDT
                val plan = PortfolioWidgetChart.plan(
                    snapshot = shown,
                    requested = PortfolioWidgetOptions.effectiveRange(view, widgetPrefs.getPortfolioRange(appWidgetId)),
                    type = widgetPrefs.getPortfolioChartType(appWidgetId),
                    // UTC-Tag wie der Tagesverlauf der Aufnahme ([PortfolioWidgetChart.dailyData])
                    todayEpochDay = PortfolioHistory.utcToday(),
                    zone = zone,
                    dayAxis = dayAxis(shown),
                )
                // Liste: Buchungen unter den Coins (Wahl beim Einrichten, voreingestellt aus)
                val entryFilter = widgetPrefs.getPortfolioEntries(appWidgetId)
                // Liste und Positionen der Übersicht: Coin-Logos (Schalter «Coin-Logos in Widgets»), einmal für alle Grössen
                val logos = if (view != PortfolioWidgetView.CHART && settings.widgetCoinLogos && shown.positions.isNotEmpty()) {
                    try {
                        coinLogos.bitmaps(shown.positions.map { it.symbol.uppercase() }, style.colors)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.d(e, "Portfolio-Widget %d: Coin-Logos nicht geladen", appWidgetId)
                        null
                    }
                } else {
                    null
                }
                val build: suspend (Int, Int, Int) -> RemoteViews = { slot, w, h ->
                    val built = when (view) {
                        PortfolioWidgetView.OVERVIEW ->
                            overviewViews(w, h, shown, plan, style, outdated, fontScale, wantUsdt, logos)
                        PortfolioWidgetView.LIST -> listViews(w, h, shown, plan, style, outdated, fontScale, logos, entryFilter)
                        PortfolioWidgetView.CHART ->
                            portfolioViews(appWidgetId, slot, w, h, shown, plan, style, outdated, fontScale, wantUsdt)
                    }
                    // Kopfzeile rechts: Stand und «Aktualisieren» (bzw. Kreisel), wie im Listen- und im Pulse-Widget
                    built.also { bindHeader(it, appWidgetId, w, shown, outdated, style.colors, busy, lock) }
                }
                val sizes = portfolioSizesDp(manager, appWidgetId)
                if (sizes.size > 1 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Je Grösse (Hoch-/Querformat) ein Layout; der Launcher wählt ohne neuen Aufruf
                    RemoteViews(
                        sizes.mapIndexed { slot, (w, h) -> SizeF(w.toFloat(), h.toFloat()) to build(slot, w, h) }.toMap()
                    )
                } else {
                    val (w, h) = sizes.firstOrNull() ?: (0 to 0)
                    build(0, w, h)
                }
            }
            runCatchingCancellable { WidgetPublish.update(manager, appWidgetId, views) }
                .onFailure { Timber.w(it, "Portfolio-Widget %d konnte nicht gezeichnet werden", appWidgetId) }
        }
        return true
    }

    /**
     * Hintergrund, Logo, Titel und Tipp (öffnet den Portfolio-Tab) — in jedem Layout gleich. Die Kopfzeile
     * trägt keine Pille mehr: das Prozent steht als Text in der Betragszeile ([bindValues]).
     */
    fun portfolioFrame(views: RemoteViews, style: PortfolioStyle) {
        toolkit.background(views, R.id.portfolio_bg, style.colors, style.opacity)
        views.setImageViewResource(R.id.portfolio_logo, style.logoRes)
        views.setTextViewText(R.id.portfolio_title, context.getString(R.string.widget_portfolio_name))
        views.setTextColor(R.id.portfolio_title, style.colors.textColor)
        views.setOnClickPendingIntent(R.id.portfolio_root, openPortfolio())
    }

    /**
     * Aus, Sperre oder «leer»: Titel, (Schloss,) Hinweis — keine Werte, kein Chart, keine Positionen.
     * [title] ersetzt «Portfolio» in der Kopfzeile (Portfolio aus: «Portfolio ist aus»).
     */
    private fun portfolioMessageViews(style: PortfolioStyle, message: String, locked: Boolean, title: String? = null): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_portfolio)
        portfolioFrame(views, style)
        // Ohne Werte (aus, gesperrt, leer) nichts zu aktualisieren: Kopfzeile ohne Stand und Knopf
        WidgetRefreshHeader.Kind.PORTFOLIO.ids.let { ids ->
            listOf(ids.status, ids.refresh, ids.busy, ids.countdown).forEach { views.setViewVisibility(it, View.GONE) }
        }
        if (title != null) views.setTextViewText(R.id.portfolio_title, title)
        views.setViewVisibility(R.id.portfolio_values, View.GONE)
        views.setViewVisibility(R.id.portfolio_lock_icon, if (locked) View.VISIBLE else View.GONE)
        if (locked) views.setInt(R.id.portfolio_lock_icon, "setColorFilter", style.colors.secondaryTextColor)
        views.setViewVisibility(R.id.portfolio_message, View.VISIBLE)
        views.setTextViewText(R.id.portfolio_message, message)
        views.setTextColor(R.id.portfolio_message, style.colors.secondaryTextColor)
        views.setContentDescription(
            R.id.portfolio_root,
            listOf(title ?: context.getString(R.string.widget_portfolio_name), message).joinToString(", ")
        )
        return views
    }

    /** Tipp auf «Aktualisieren» läuft: Kreisel statt Knopf, bis [setRefreshing] (false) neu zeichnet. */
    @Volatile
    var refreshing = false
        private set

    /** Kreisel an bzw. aus und neu zeichnen (voll, die Grössen-Layouts erlauben kein Teil-Update). */
    suspend fun setRefreshing(appWidgetIds: IntArray, on: Boolean) {
        refreshing = on
        update(appWidgetIds)
    }

    private fun bindHeader(
        views: RemoteViews,
        appWidgetId: Int,
        widthDp: Int,
        snapshot: PortfolioSnapshot,
        outdated: Boolean,
        colors: WidgetColors,
        busy: Boolean,
        lock: WidgetRefreshHeader.Lock,
    ) {
        val kind = WidgetRefreshHeader.Kind.PORTFOLIO
        WidgetRefreshHeader.bind(
            context = context,
            toolkit = toolkit,
            views = views,
            ids = kind.ids,
            candidates = WidgetRefreshHeader.candidates(
                context,
                snapshot.time,
                widgetPrefs.headerStamp(kind).durationFor(snapshot.time),
                !offline,
                outdated,
            ),
            widthDp = widthDp,
            titleDp = toolkit.textWidthDp(context.getString(R.string.widget_portfolio_name), WidgetType.BODY),
            colors = colors,
            refresh = WidgetRefreshHeader.intent(
                context,
                PortfolioWidgetProvider::class.java,
                PortfolioWidgetProvider.ACTION_REFRESH,
                appWidgetId,
            ),
            busy = busy,
            lock = lock,
        )
    }

    /** Gelöschte Portfolio-Widgets: zwischengespeicherte Charts aller Grössen freigeben. */
    fun forget(appWidgetIds: IntArray) {
        appWidgetIds.forEach { id -> repeat(MAX_PORTFOLIO_SIZES) { portfolioCharts.remove(id * MAX_PORTFOLIO_SIZES + it) } }
    }

    /**
     * Öffnet die App im Portfolio-Tab; ist es aus, unter Einstellungen › Portfolio
     * (entschieden in der App beim Öffnen, [PortfolioOff.widgetTarget]).
     */
    private fun openPortfolio(): PendingIntent {
        val intent = AppIntents.main(context).apply {
            // Eigene Action: Extras zählen nicht zur Identität eines PendingIntent — ohne sie
            // teilte er sich Request-Code 2 mit der Mitteilung von Paar-Id 2 (AppNotifier)
            // und FLAG_UPDATE_CURRENT überschriebe gegenseitig das Ziel.
            action = ACTION_OPEN_PORTFOLIO
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(AppIntents.EXTRA_OPEN, "portfolio")
            // Geheimnis dieser Installation: sonst gilt das Ziel nicht (OpenTargetGuard)
            withOpenToken(context)
        }
        return PendingIntent.getActivity(
            context,
            2,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private companion object {
        /** Höchstens so lange auf den Devisenkurs warten (Widget zeichnet sonst mit USDT). */
        const val FX_TIMEOUT_MILLIS = 5_000L
        const val ACTION_OPEN_PORTFOLIO = "com.cryptochecker.app.action.OPEN_PORTFOLIO"
    }
}

/** Farben und Einstellungen eines Portfolio-Widgets. */
class PortfolioStyle(
    val colors: WidgetColors,
    val dark: Boolean,
    val highContrast: Boolean,
    val logoRes: Int,
    val opacity: Int,
    /** %-Basis (Zeitraum hinter Betrag und Prozent, Screenreader). */
    val basis: ChangeBasis = ChangeBasis.DEFAULT,
    /** «Beträge verbergen»: Gesamtwert und Betrag als «•••», ohne ≈ USDT; Prozente bleiben. */
    val hideAmounts: Boolean = false,
)

/** Gezeigte Werte für Screenreader und Fusszeile ([bindValues]). */
class ValueTexts(val total: String, val usdt: String?, val missingInLine: Boolean)

/** Wovon das Bild des Portfolio-Charts abhängt (siehe [portfolioCharts]). */
data class PortfolioChartKey(
    val plan: PortfolioChartPlan,
    val lineColor: Int,
    val palette: List<Int>,
    val highContrast: Boolean,
    val hideAmounts: Boolean,
    val widthPx: Int,
    val heightPx: Int,
    val density: Float,
    val fontScale: Float,
    /** Zeitgitter-Zone ([ChangeBasisMath.chartZone]): Andere %-Basis, anderes Gitter bei gleichen Balken. */
    val zone: ZoneId,
)

/** Ohne Wert: Strich statt Zahl. */
const val NO_VALUE = "—"

/** Höchstens so viele Grössen je Portfolio-Widget (Hoch-/Querformat, Faltgeräte). */
const val MAX_PORTFOLIO_SIZES = 4
