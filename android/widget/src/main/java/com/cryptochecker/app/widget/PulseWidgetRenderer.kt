package com.cryptochecker.app.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.cryptochecker.app.data.cycle.CycleCacheCodecs
import com.cryptochecker.app.data.cycle.CycleCacheStore
import com.cryptochecker.app.data.remote.PulseDataSource
import com.cryptochecker.app.domain.market.CachedValue
import com.cryptochecker.app.domain.market.CryptoPulse
import com.cryptochecker.app.domain.market.CycleSource
import com.cryptochecker.app.domain.market.MarketPlainSummary
import com.cryptochecker.app.domain.market.PulseInput
import com.cryptochecker.app.domain.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.settings.SettingsRepository
import com.cryptochecker.app.domain.widget.PulseBackgroundFetch
import com.cryptochecker.app.domain.widget.WidgetType
import com.cryptochecker.app.settings.HighContrast
import com.cryptochecker.app.settings.logoRes
import com.cryptochecker.app.util.AppIntents
import com.cryptochecker.resources.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Widget «Was gerade auffällt» (Crypto Pulse): Schlagzeile, Leitsatz, Coin-Chips und Stand —
 * aus dem Zwischenspeicher des Markt-Tabs, bei Bedarf frisch geladen. Dazu «Kurz gesagt»
 * ([PulseWidgetPlain]): mittel der erste Satz statt des Leitsatzes, gross ein eigener Block.
 */
@Singleton
class PulseWidgetRenderer @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val widgetPrefs: WidgetPrefs,
    private val settingsRepository: SettingsRepository,
    private val pulseData: PulseWidgetData,
    private val cycleCacheStore: CycleCacheStore,
    private val toolkit: WidgetToolkit,
    private val connectivity: WidgetConnectivity,
) {
    /** Abruf im Hintergrund, damit [WidgetUpdater.updateAll] (Kurs-Aktualisierung) nicht auf das Netz wartet. */
    private val backgroundFetch = PulseBackgroundFetch()

    /** Hintergrundlauf: den losgelösten Abruf aus [updateWithOthers] höchstens [timeoutMillis] abwarten. */
    suspend fun awaitBackgroundFetch(timeoutMillis: Long): Boolean = backgroundFetch.await(timeoutMillis)
    private fun pulseWidgetIds(): IntArray = toolkit.ids(PulseWidgetProvider::class.java)

    /**
     * Zeichnet die Widgets «Was gerade auffällt»: sofort aus dem Zwischenspeicher des
     * Markt-Tabs ([CycleCacheStore], Eintrag «pulse»). Ist er älter als dessen Gültigkeit
     * (5 Min., [CycleSource.PULSE]) und [fetch], frisch über [PulseDataSource] — dieselbe
     * Quelle wie die Karte —, speichern (die Karte zeigt ihn dann auch) und neu zeichnen.
     */
    suspend fun update(appWidgetIds: IntArray, fetch: Boolean = true) {
        if (appWidgetIds.isEmpty()) return
        val cached = pulseData.readPulse()
        // Ohne gespeicherte Daten erst nach dem Abruf zeichnen (sonst kurz «nicht verfügbar»)
        if (cached != null || !fetch) drawPulse(appWidgetIds, cached)
        if (!fetch || pulseData.isPulseFresh(cached)) return
        loadAndDraw(appWidgetIds, cached, PulseWidgetData.TTL)
    }

    /**
     * Tipp auf «Aktualisieren»: Kreisel (aus dem Zwischenspeicher neu gezeichnet), dann frisch laden — ausser die Daten sind jünger als
     * [PulseWidgetData.TAP_MIN_AGE_MILLIS] (schont die freie Quelle) — und neu zeichnen (Kreisel weg).
     */
    suspend fun refreshByTap(appWidgetIds: IntArray) {
        if (appWidgetIds.isEmpty()) return
        val cached = pulseData.readPulse()
        drawPulse(appWidgetIds, cached, busy = true)
        if (!connectivity.online()) {
            drawPulse(appWidgetIds, cached)
            return
        }
        loadAndDraw(appWidgetIds, cached, PulseWidgetData.TAP_MIN_AGE_MILLIS)
    }

    private suspend fun loadAndDraw(appWidgetIds: IntArray, cached: CachedValue<PulseInput>?, maxAgeMillis: Long) {
        // Auch ohne frisches Ergebnis neu zeichnen: so verschwindet der Kreisel eines Tipps
        drawPulse(appWidgetIds, pulseData.loadPulse(maxAgeMillis) ?: cached)
    }

    /**
     * Mit den anderen Widgets ([WidgetUpdater.updateAll], gleicher Zeitplan): sofort aus dem Zwischenspeicher
     * zeichnen; ist er alt, im Hintergrund nachladen und dann neu zeichnen.
     */
    suspend fun updateWithOthers() {
        val ids = pulseWidgetIds()
        if (ids.isEmpty()) return
        val cached = pulseData.readPulse()
        if (cached != null) drawPulse(ids, cached)
        if (pulseData.isPulseFresh(cached)) return
        backgroundFetch.launch {
            val loaded = pulseData.loadPulse()
            if (loaded != null || cached == null) drawPulse(pulseWidgetIds(), loaded)
        }
    }

    /**
     * Schlagzeile mit ↗/↘ in der Kursfarbe, Leitsatz (2 Zeilen, bei wenig Höhe 1), Coin-Chips
     * (ab 220 dp Breite BTC/ETH/SOL, sonst BTC) und Stand. Ohne (brauchbare) Daten ein ruhiger
     * Satz. Screenreader: das ganze Widget als ein Satz wie die Karte.
     */
    private suspend fun drawPulse(appWidgetIds: IntArray, value: CachedValue<PulseInput>?, busy: Boolean = false) {
        if (appWidgetIds.isEmpty()) return
        val manager = AppWidgetManager.getInstance(context) ?: return
        val settings = settingsRepository.current()
        val accent = settings.accentColor
        val highContrast = HighContrast.isEffective(context, settings.highContrast)
        widgetPrefs.lastHighContrast = highContrast
        val now = System.currentTimeMillis()
        val online = connectivity.online()
        val fresh = value?.takeIf { PulseWidgetMath.isShowable(it.savedAt, now) }
        val report = fresh?.let { CryptoPulse.evaluate(it.value) }
        val impulseTitle = context.getString(R.string.pulse_now_title)
        // Fear & Greed nur aus dem Zwischenspeicher (Markt-Tab oder Pulse, ≤ 24 h) — kein eigener Abruf
        val cachedFng = if (fresh == null || report == null) {
            null
        } else {
            cycleCacheStore.read(CycleSource.FEAR_GREED.key, CycleCacheCodecs.fearGreed)
        }
        val fearGreedValue = if (fresh == null || report == null) {
            null
        } else {
            PulseWidgetMath.fearGreed(
                cached = cachedFng?.value?.value,
                cachedAt = cachedFng?.savedAt,
                pulse = fresh.value.fearGreed,
                pulseAt = fresh.savedAt,
                now = now,
            )
        }
        val fearGreedText = fearGreedValue?.let { PulseWidgetTexts.fearGreed(context, it) }
        // «Kurz gesagt» aus denselben Eingaben und derselben Frische-Regel wie im Markt-Tab
        val plainInput = if (fresh == null || report == null) {
            null
        } else {
            PulseWidgetPlain.input(
                now = now,
                pulseMarketChange24h = report.marketCap24h,
                pulseSavedAt = fresh.savedAt,
                fearGreed = cachedFng?.value?.value,
                fearGreedSavedAt = cachedFng?.savedAt,
            )
        }
        val plainSummary = plainInput?.let { MarketPlainSummary.evaluate(it) }
        val plainSentences = plainSummary?.let { PulseWidgetTexts.plainSentences(context, it) }.orEmpty()
        val plainTitle = context.getString(R.string.market_plain_title)
        val plainFootnote = context.getString(R.string.market_plain_footnote)

        for (appWidgetId in appWidgetIds) {
            val dark = widgetPrefs.isDark(appWidgetId, settings)
            val colors = WidgetColors.of(accent, dark, settings.priceColorScheme, highContrast, settings.priceColorsInverted)
            val views = RemoteViews(context.packageName, R.layout.widget_pulse)
            val view = widgetPrefs.getPulseView(appWidgetId)
            val title = when (view) {
                PulseWidgetView.IMPULSE -> impulseTitle
                PulseWidgetView.MOVERS -> context.getString(R.string.movers_title)
                PulseWidgetView.HALVING -> context.getString(R.string.insights_halving_title)
            }

            toolkit.background(views, R.id.pulse_bg, colors, widgetPrefs.getOpacity(appWidgetId))
            views.setImageViewResource(R.id.pulse_logo, accent.logoRes(dark))
            views.setTextViewText(R.id.pulse_title, title)
            views.setTextColor(R.id.pulse_title, colors.textColor)
            views.setOnClickPendingIntent(R.id.pulse_root, openMarket())
            // Rechts in der Kopfzeile der Stand (Halving: nach Kalender, ohne) und «Aktualisieren», wie im Listen-Widget
            val (widgetWidthDp, heightDp) = toolkit.sizeDp(manager, appWidgetId)
            val status = WidgetRefreshHeader.bind(
                toolkit = toolkit,
                views = views,
                ids = WidgetRefreshHeader.PULSE,
                candidates = if (view == PulseWidgetView.HALVING || fresh == null) {
                    emptyList()
                } else {
                    WidgetRefreshHeader.candidates(context, fresh.savedAt, online, outdated = false)
                },
                widthDp = widgetWidthDp,
                titleDp = toolkit.textWidthDp(title, WidgetType.LABEL),
                colors = colors,
                refresh = WidgetRefreshHeader.intent(
                    context,
                    PulseWidgetProvider::class.java,
                    PulseWidgetProvider.ACTION_REFRESH,
                    appWidgetId,
                ),
                busy = busy,
            )
            // Titel ganz oder gar nicht (dann nur das Logo), nie «Was gerade a…»; Stand und Knopf daneben abgezogen
            val titleWidthDp = if (widgetWidthDp > 0) widgetWidthDp - WidgetRefreshHeader.usedDp(toolkit, status).toInt() else 0
            val titleSp = PulseWidgetMath.titleSizeSp(titleWidthDp) { toolkit.textWidthDp(title, it) }
            views.setViewVisibility(R.id.pulse_title, if (titleSp != null) View.VISIBLE else View.INVISIBLE)
            views.setTextViewTextSize(R.id.pulse_title, TypedValue.COMPLEX_UNIT_SP, titleSp ?: WidgetType.BODY)

            val movers = report?.movers?.takeIf { !it.isEmpty }
            if (view == PulseWidgetView.HALVING) {
                // Nach Kalender, ohne Netz: auch ohne Marktdaten
                views.setContentDescription(R.id.pulse_root, drawPulseHalving(context, views, colors))
            } else if (view == PulseWidgetView.MOVERS && report != null && movers != null) {
                val stand = PulseWidgetTexts.stand(context, report.time, online)
                val spoken = drawPulseMovers(context, views, colors, movers, PulseWidgetMovers.rows(heightDp), stand)
                views.setContentDescription(R.id.pulse_root, spoken)
            } else if (report == null || view == PulseWidgetView.MOVERS) {
                val message = context.getString(R.string.pulse_unavailable)
                showPulseSection(views, R.id.pulse_message)
                views.setTextViewText(R.id.pulse_message, message)
                views.setTextColor(R.id.pulse_message, colors.secondaryTextColor)
                views.setContentDescription(R.id.pulse_root, "$title. $message")
            } else {
                drawPulseReport(
                    context = context,
                    toolkit = toolkit,
                    views = views,
                    colors = colors,
                    report = report,
                    title = title,
                    online = online,
                    widgetWidthDp = widgetWidthDp,
                    heightDp = heightDp,
                    fearGreedValue = fearGreedValue,
                    fearGreedText = fearGreedText,
                    plainSentences = plainSentences,
                    plainTitle = plainTitle,
                    // «Beschreibt die letzten 24 Stunden – keine Prognose, keine Anlageberatung» (Quellen nur in der App)
                    plainNote = { plainFootnote },
                )
            }

            runCatchingCancellable { WidgetPublish.update(manager, appWidgetId, views) }
                .onFailure { Timber.w(it, "Pulse-Widget %d konnte nicht gezeichnet werden", appWidgetId) }
        }
        schedulePlainExpiry(
            if (plainInput != null && plainSummary != null && plainSentences.isNotEmpty()) {
                PulseWidgetPlain.expiresAt(plainInput, plainSummary)
            } else {
                null
            }
        )
    }

    /**
     * Widgets zeichnen nur nach einer Aktualisierung neu: Wecker auf den Moment, an dem ein Satz von
     * «Kurz gesagt» auf veralteten Daten stünde (null = löschen). RTC ohne Wecken wie [WidgetOutdated] —
     * fällig, sobald das Gerät ohnehin wach ist; dann ohne Netz neu zeichnen (der Satz entfällt).
     */
    private fun schedulePlainExpiry(at: Long?) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, PulseWidgetProvider::class.java).setAction(PulseWidgetProvider.ACTION_PLAIN_EXPIRED)
        val pending = PendingIntent.getBroadcast(
            context,
            PLAIN_EXPIRY_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        runCatching {
            if (at == null) alarmManager.cancel(pending) else alarmManager.set(AlarmManager.RTC, at, pending)
        }.onFailure { Timber.w(it, "Wecker für «Kurz gesagt» im Pulse-Widget nicht gestellt") }
    }

    /** Öffnet die App im Markt-Tab (Ziel «cycle» wie die App-Verknüpfung). */
    private fun openMarket(): PendingIntent {
        val intent = AppIntents.main(context).apply {
            // Eigene Action: Extras zählen nicht zur Identität eines PendingIntent
            action = ACTION_OPEN_MARKET
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(AppIntents.EXTRA_OPEN, "cycle")
        }
        return PendingIntent.getActivity(
            context,
            3,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private companion object {
        const val ACTION_OPEN_MARKET = "com.cryptochecker.app.action.OPEN_MARKET"

        /** Eigener Request-Code (neben [WidgetOutdated] und [WidgetRefreshUnlock]). */
        const val PLAIN_EXPIRY_REQUEST_CODE = -7_015
    }
}
