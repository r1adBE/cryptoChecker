package com.cryptochecker.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import com.cryptochecker.app.R
import com.cryptochecker.app.data.portfolio.FxRateSource
import com.cryptochecker.app.domain.convert.CurrencyConversion
import com.cryptochecker.app.domain.portfolio.PortfolioOff
import com.cryptochecker.app.domain.portfolio.PortfolioSnapshot
import com.cryptochecker.app.domain.refresh.OutdatedRule
import com.cryptochecker.app.domain.watch.ChangeBasis
import com.cryptochecker.app.domain.watch.ChangeBasisMath
import com.cryptochecker.app.lock.PortfolioLockPolicy
import com.cryptochecker.app.settings.HighContrast
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.ui.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Portfolio-Widget: Gesamtwert, Veränderung, Wertverlauf und die grössten Positionen aus der
 * letzten Momentaufnahme ([PortfolioSnapshotStore]) — ohne Netz. Je Widget eine Ansicht
 * ([PortfolioWidgetView]): Übersicht, Chart oder Liste.
 */
@Singleton
class PortfolioWidgetRenderer @Inject constructor(
    @param:ApplicationContext internal val context: Context,
    private val widgetPrefs: WidgetPrefs,
    private val settingsRepository: SettingsRepository,
    private val portfolioSnapshotStore: PortfolioSnapshotStore,
    internal val toolkit: WidgetToolkit,
    private val coinLogos: WidgetCoinLogos,
    /** Devisenkurs, falls noch keiner gemerkt ist ([fxMemoFor]). */
    private val fxSource: FxRateSource,
    /** Live-Kurs USDT/USD für das Umrechnen beim Zeichnen ([PortfolioWidgetChart.usdtFactor]). */
    private val depegReadings: com.cryptochecker.app.domain.alarm.DepegReadingStore,
) {
    /**
     * Zuletzt gezeichneter Wertverlauf je Portfolio-Widget und Grösse (Schlüssel
     * Widget-Id × [MAX_PORTFOLIO_SIZES] + Grösse), beim Löschen entfernt ([forget]).
     */
    internal val portfolioCharts = LatestPerWidget<PortfolioChartKey, android.graphics.Bitmap>()

    /** Stand der Momentaufnahme beim letzten Zeichnen der Portfolio-Widgets ([update]). */
    @Volatile
    var drawn: PortfolioDrawState? = null
        private set

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

    suspend fun update(appWidgetIds: IntArray): Boolean {
        if (appWidgetIds.isEmpty()) return false
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
                it, settings.portfolioCurrency,
                PortfolioWidgetChart.fxRateFor(settings.portfolioCurrency, null, fxMemo, now)
                    ?.let { fx -> PortfolioWidgetChart.usdtFactor(settings.portfolioCurrency, fx, depegReadings.liveUsdtUsd(now)) },
            )
        }?.let {
            if (ChangeBasisMath.isCurrent(it.stamp, settings.changeBasis, System.currentTimeMillis())) it
            else it.copy(
                changeAmount = null,
                changePercent = null,
                positions = it.positions.map { p -> p.copy(change24hPercent = null, pnlAmount = null, pnlPercent = null) },
            )
        }
        val fontScale = context.resources.configuration.fontScale.takeIf { it > 0f } ?: 1f
        val outdated = snapshot != null &&
            OutdatedRule.isOutdated(snapshot.time, System.currentTimeMillis(), WidgetOutdated.afterMillis(settings))
        val locked = PortfolioLockPolicy.widgetLocked(settings.appLock)
        val zone = ZoneId.systemDefault()

        for (appWidgetId in appWidgetIds) {
            val dark = widgetPrefs.isDark(appWidgetId)
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
                    style, message.orEmpty(), state == PortfolioOff.WidgetState.LOCKED,
                    title = if (state == PortfolioOff.WidgetState.OFF) context.getString(R.string.widget_portfolio_off_title) else null,
                )
            } else {
                // Je Widget: Anzeige (Nur USDT / Umrechnung / + USDT), Zeitraum und Chart-Art
                val display = PortfolioWidgetChart.effectiveDisplay(
                    widgetPrefs.getPortfolioDisplay(appWidgetId, settings.portfolioCurrency),
                    snapshot.currency,
                    snapshot.totalUsdt != null,
                )
                val shown = PortfolioWidgetChart.displayed(snapshot, display)
                // «≈ … USDT» nur bei «Umrechnung + USDT» (bei einem USD-Stablecoin fällt die Wahl auf «Nur USDT» zurück)
                val wantUsdt = display == PortfolioDisplay.CONVERSION_USDT
                val plan = PortfolioWidgetChart.plan(
                    snapshot = shown,
                    requested = widgetPrefs.getPortfolioRange(appWidgetId),
                    type = widgetPrefs.getPortfolioChartType(appWidgetId),
                    todayEpochDay = LocalDate.now(zone).toEpochDay(),
                    zone = zone,
                    dayAxis = dayAxis(shown),
                )
                // Ansicht je Widget: Übersicht, Chart (Standard) oder Liste
                val view = widgetPrefs.getPortfolioView(appWidgetId)
                // Liste: Coin-Logos (Schalter «Coin-Logos in Widgets»), einmal für alle Grössen
                val logos = if (view == PortfolioWidgetView.LIST && settings.widgetCoinLogos && shown.positions.isNotEmpty()) {
                    try {
                        coinLogos.bitmaps(shown.positions.map { it.symbol.uppercase() }, style.colors)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.d(e, "Portfolio-Widget %d: Coin-Logos nicht geladen", appWidgetId)
                        null
                    }
                } else null
                val build: suspend (Int, Int, Int) -> RemoteViews = { slot, w, h ->
                    when (view) {
                        PortfolioWidgetView.OVERVIEW -> overviewViews(w, h, shown, plan, style, outdated, fontScale, wantUsdt)
                        PortfolioWidgetView.LIST -> listViews(w, h, shown, plan, style, outdated, fontScale, logos)
                        PortfolioWidgetView.CHART -> portfolioViews(appWidgetId, slot, w, h, shown, plan, style, outdated, fontScale, wantUsdt)
                    }
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
            runCatching { manager.updateAppWidget(appWidgetId, views) }
                .onFailure { Timber.w(it, "Portfolio-Widget %d konnte nicht gezeichnet werden", appWidgetId) }
        }
        return true
    }

    /**
     * Hintergrund, Logo, Titel und Tipp (öffnet den Portfolio-Tab) — in jedem Layout gleich. Die Kopfzeile
     * trägt keine Pille mehr: das Prozent steht als Text in der Betragszeile ([bindValues]).
     */
    internal fun portfolioFrame(views: RemoteViews, style: PortfolioStyle) {
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

    /** Gelöschte Portfolio-Widgets: zwischengespeicherte Charts aller Grössen freigeben. */
    fun forget(appWidgetIds: IntArray) {
        appWidgetIds.forEach { id -> repeat(MAX_PORTFOLIO_SIZES) { portfolioCharts.remove(id * MAX_PORTFOLIO_SIZES + it) } }
    }

    /**
     * Öffnet die App im Portfolio-Tab; ist es aus, unter Einstellungen › Erweitert › Portfolio
     * (entschieden in der App beim Öffnen, [PortfolioOff.widgetTarget]).
     */
    private fun openPortfolio(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            // Eigene Action: Extras zählen nicht zur Identität eines PendingIntent — ohne sie
            // teilte er sich Request-Code 2 mit der Mitteilung von Paar-Id 2 (AppNotifier)
            // und FLAG_UPDATE_CURRENT überschriebe gegenseitig das Ziel.
            action = ACTION_OPEN_PORTFOLIO
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN, "portfolio")
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
internal class PortfolioStyle(
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
internal class ValueTexts(val total: String, val usdt: String?, val missingInLine: Boolean)

/** Wovon das Bild des Portfolio-Charts abhängt (siehe [portfolioCharts]). */
internal data class PortfolioChartKey(
    val plan: PortfolioChartPlan,
    val lineColor: Int,
    val palette: List<Int>,
    val highContrast: Boolean,
    val hideAmounts: Boolean,
    val widthPx: Int,
    val heightPx: Int,
    val density: Float,
    val fontScale: Float,
)

/** Ohne Wert: Strich statt Zahl. */
internal const val NO_VALUE = "—"

/** Höchstens so viele Grössen je Portfolio-Widget (Hoch-/Querformat, Faltgeräte). */
internal const val MAX_PORTFOLIO_SIZES = 4
