package com.cryptochecker.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.cryptochecker.app.R
import com.cryptochecker.app.data.CachedValue
import com.cryptochecker.app.data.CycleCacheCodecs
import com.cryptochecker.app.data.CycleCacheStore
import com.cryptochecker.app.data.remote.PulseDataSource
import com.cryptochecker.app.domain.market.CryptoPulse
import com.cryptochecker.app.domain.market.CycleCachePolicy
import com.cryptochecker.app.domain.market.CycleSource
import com.cryptochecker.app.domain.market.PulseInput
import com.cryptochecker.app.settings.HighContrast
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.ui.MainActivity
import com.cryptochecker.app.util.A11yText
import com.cryptochecker.app.util.LocaleNumbers
import com.cryptochecker.app.util.PriceFormat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Widget «Was gerade auffällt» (Crypto Pulse): Schlagzeile, Leitsatz, Coin-Chips und Stand —
 * aus dem Zwischenspeicher des Markt-Tabs, bei Bedarf frisch geladen.
 */
@Singleton
class PulseWidgetRenderer @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val widgetPrefs: WidgetPrefs,
    private val settingsRepository: SettingsRepository,
    private val pulseDataSource: PulseDataSource,
    private val cycleCacheStore: CycleCacheStore,
    private val toolkit: WidgetToolkit,
) {
    /** Höchstens ein Abruf der Pulse-Daten gleichzeitig (mehrere Auslöser kurz nacheinander). */
    private val pulseMutex = Mutex()

    /** Abruf im Hintergrund, damit [WidgetUpdater.updateAll] (Kurs-Aktualisierung) nicht auf das Netz wartet. */
    private val pulseScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun pulseWidgetIds(): IntArray = toolkit.ids(PulseWidgetProvider::class.java)

    /**
     * Zeichnet die Widgets «Was gerade auffällt»: sofort aus dem Zwischenspeicher des
     * Markt-Tabs ([CycleCacheStore], Eintrag «pulse»). Ist er älter als dessen Gültigkeit
     * (5 Min., [CycleSource.PULSE]) und [fetch], frisch über [PulseDataSource] — dieselbe
     * Quelle wie die Karte —, speichern (die Karte zeigt ihn dann auch) und neu zeichnen.
     */
    suspend fun update(appWidgetIds: IntArray, fetch: Boolean = true) {
        if (appWidgetIds.isEmpty()) return
        val cached = readPulse()
        // Ohne gespeicherte Daten erst nach dem Abruf zeichnen (sonst kurz «nicht verfügbar»)
        if (cached != null || !fetch) drawPulse(appWidgetIds, cached)
        if (!fetch || isPulseFresh(cached)) return
        val loaded = loadPulse()
        if (loaded != null) drawPulse(appWidgetIds, loaded) else if (cached == null) drawPulse(appWidgetIds, null)
    }

    /**
     * Mit den anderen Widgets ([WidgetUpdater.updateAll], gleicher Zeitplan): sofort aus dem Zwischenspeicher
     * zeichnen; ist er alt, im Hintergrund nachladen und dann neu zeichnen.
     */
    suspend fun updateWithOthers() {
        val ids = pulseWidgetIds()
        if (ids.isEmpty()) return
        val cached = readPulse()
        if (cached != null) drawPulse(ids, cached)
        if (isPulseFresh(cached)) return
        pulseScope.launch {
            val loaded = loadPulse()
            if (loaded != null || cached == null) drawPulse(pulseWidgetIds(), loaded)
        }
    }

    private fun isPulseFresh(value: CachedValue<PulseInput>?): Boolean =
        value != null && CycleCachePolicy.isFresh(value.savedAt, System.currentTimeMillis(), CycleSource.PULSE.ttlMillis)

    /** Gespeicherte Pulse-Eingaben, nur wenn sie sich auswerten lassen (wie im Markt-Tab). */
    private suspend fun readPulse(): CachedValue<PulseInput>? =
        cycleCacheStore.read(CycleSource.PULSE.key, CycleCacheCodecs.pulseInput)
            ?.takeIf { CryptoPulse.evaluate(it.value) != null }

    /**
     * Frisch laden (harte Zeitgrenze des Bereichs) und speichern; null bei Fehler. Lief
     * inzwischen schon ein Abruf (anderer Auslöser), gilt dessen frisches Ergebnis.
     */
    private suspend fun loadPulse(): CachedValue<PulseInput>? = pulseMutex.withLock {
        readPulse()?.takeIf { isPulseFresh(it) }?.let { return@withLock it }
        val fetched = try {
            withTimeoutOrNull(CycleSource.PULSE.timeoutMillis) { pulseDataSource.fetchInput() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Timber.d(e, "Pulse für Widget: Zeitüberschreitung")
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Pulse für Widget nicht verfügbar")
            null
        }
        val fresh = fetched?.takeIf { CryptoPulse.evaluate(it) != null } ?: return@withLock null
        val at = fresh.time.takeIf { it > 0L } ?: System.currentTimeMillis()
        cycleCacheStore.write(CycleSource.PULSE.key, fresh, at, CycleCacheCodecs.pulseInput)
        CachedValue(fresh, at)
    }

    /**
     * Schlagzeile mit ▲/▼ in der Kursfarbe, Leitsatz (2 Zeilen, bei wenig Höhe 1), Coin-Chips
     * (ab 220 dp Breite BTC/ETH/SOL, sonst BTC) und Stand. Ohne (brauchbare) Daten ein ruhiger
     * Satz. Screenreader: das ganze Widget als ein Satz wie die Karte.
     */
    private suspend fun drawPulse(appWidgetIds: IntArray, value: CachedValue<PulseInput>?) {
        if (appWidgetIds.isEmpty()) return
        val manager = AppWidgetManager.getInstance(context) ?: return
        val settings = settingsRepository.current()
        val accent = settings.accentColor
        val highContrast = HighContrast.isEffective(context, settings.highContrast)
        widgetPrefs.lastHighContrast = highContrast
        val now = System.currentTimeMillis()
        val report = value?.takeIf { PulseWidgetMath.isShowable(it.savedAt, now) }?.let { CryptoPulse.evaluate(it.value) }
        val title = context.getString(R.string.pulse_now_title)
        // Fear & Greed nur aus dem Zwischenspeicher (Markt-Tab oder Pulse, ≤ 24 h) — kein eigener Abruf
        val fearGreedValue = if (report == null) null else {
            val cachedFng = cycleCacheStore.read(CycleSource.FEAR_GREED.key, CycleCacheCodecs.fearGreed)
            PulseWidgetMath.fearGreed(
                cached = cachedFng?.value?.value,
                cachedAt = cachedFng?.savedAt,
                pulse = value.value.fearGreed,
                pulseAt = value.savedAt,
                now = now,
            )
        }
        val fearGreedText = fearGreedValue?.let { PulseWidgetTexts.fearGreed(context, it) }

        for (appWidgetId in appWidgetIds) {
            val dark = widgetPrefs.isDark(appWidgetId)
            val colors = WidgetColors.of(accent, dark, settings.priceColorScheme, highContrast, settings.priceColorsInverted)
            val views = RemoteViews(context.packageName, R.layout.widget_pulse)

            toolkit.background(views, R.id.pulse_bg, colors, widgetPrefs.getOpacity(appWidgetId))
            views.setImageViewResource(R.id.pulse_logo, accent.logoRes(dark))
            views.setTextViewText(R.id.pulse_title, title)
            views.setTextColor(R.id.pulse_title, colors.textColor)
            views.setOnClickPendingIntent(R.id.pulse_root, openMarket())
            // Titel ganz oder gar nicht (dann nur das Logo), nie «Was gerade a…»
            val (widgetWidthDp, heightDp) = toolkit.sizeDp(manager, appWidgetId)
            val titleSp = PulseWidgetMath.titleSizeSp(widgetWidthDp) { toolkit.textWidthDp(title, it) }
            views.setViewVisibility(R.id.pulse_title, if (titleSp != null) View.VISIBLE else View.INVISIBLE)
            views.setTextViewTextSize(R.id.pulse_title, TypedValue.COMPLEX_UNIT_SP, titleSp ?: 13f)

            if (report == null) {
                val message = context.getString(R.string.pulse_unavailable)
                views.setViewVisibility(R.id.pulse_content, View.GONE)
                views.setViewVisibility(R.id.pulse_message, View.VISIBLE)
                views.setTextViewText(R.id.pulse_message, message)
                views.setTextColor(R.id.pulse_message, colors.secondaryTextColor)
                views.setContentDescription(R.id.pulse_root, "$title. $message")
            } else {
                views.setViewVisibility(R.id.pulse_content, View.VISIBLE)
                views.setViewVisibility(R.id.pulse_message, View.GONE)

                // Zeichen folgt der Richtung, nie dem Farbtausch; gemischt/ruhig ohne Zeichen
                val headline = PulseWidgetTexts.headline(context, report.summary)
                val direction = PulseWidgetMath.direction(report.summary)
                val glyph = PulseWidgetMath.glyph(report.summary)
                views.setViewVisibility(R.id.pulse_glyph, if (glyph != null) View.VISIBLE else View.GONE)
                views.setTextViewText(R.id.pulse_glyph, glyph.orEmpty())
                views.setTextColor(R.id.pulse_glyph, if (direction > 0) colors.upColor else colors.downColor)
                views.setTextViewText(R.id.pulse_headline, headline)
                views.setTextColor(R.id.pulse_headline, colors.textColor)
                // Einzeilig 18 → 16 → 14 sp, sonst zweizeilig in 14 sp — nie «Breite Stä…»
                val headlineStyle = PulseWidgetMath.headlineStyle(widgetWidthDp, glyph != null) { toolkit.textWidthDp(headline, it) }
                views.setTextViewTextSize(R.id.pulse_headline, TypedValue.COMPLEX_UNIT_SP, headlineStyle.sizeSp)
                views.setInt(R.id.pulse_headline, "setMaxLines", headlineStyle.lines)

                val lead = PulseWidgetTexts.lead(context, report)
                views.setTextViewText(R.id.pulse_lead, lead)
                views.setTextColor(R.id.pulse_lead, colors.textColor)
                views.setInt(R.id.pulse_lead, "setMaxLines", PulseWidgetMath.leadLines(heightDp, headlineStyle.lines))

                // Nebenzeile «Fear & Greed 72 · Gier · Top 30 ▲ 22 ▼ 8»: nur mittel/gross; passt
                // nicht alles, zuerst Fear & Greed, sonst die Marktbreite — nie abgeschnitten
                val breadth = report.breadth
                val breadthText = breadth?.let {
                    context.getString(R.string.pulse_breadth_label, it.total) + "  ▲ " + LocaleNumbers.integer(it.up) + "  ▼ " + LocaleNumbers.integer(it.down)
                }
                val sideText = listOfNotNull(
                    listOfNotNull(fearGreedText, breadthText).takeIf { it.size == 2 }?.joinToString(" · "),
                    fearGreedText,
                    breadthText,
                ).firstOrNull { text ->
                    PulseWidgetMath.showsFearGreed(
                        widgetWidthDp, heightDp, headlineStyle.lines, toolkit.textWidthDp(text, 11f, bold = false, tabular = true)
                    )
                }
                val showFng = sideText != null && fearGreedText != null && sideText.contains(fearGreedText)
                val showBreadth = sideText != null && breadthText != null && sideText.contains(breadthText)
                views.setViewVisibility(R.id.pulse_fng, if (sideText != null) View.VISIBLE else View.GONE)
                views.setTextViewText(R.id.pulse_fng, sideText.orEmpty())
                views.setTextColor(R.id.pulse_fng, colors.secondaryTextColor)

                // Nur so viele Chips, wie ganz in die Breite passen (nie ein abgeschnittener)
                val allCoins = PulseWidgetMath.coins(report, 3)
                val chipWidths = allCoins.map { toolkit.textWidthDp(PulseWidgetMath.chipText(it), 11f, tabular = true) }
                val coins = allCoins.take(PulseWidgetMath.coinCount(widgetWidthDp, chipWidths))
                PULSE_CHIPS.forEachIndexed { index, (box, bg, text) ->
                    val coin = coins.getOrNull(index)
                    if (coin == null) {
                        views.setViewVisibility(box, View.GONE)
                        return@forEachIndexed
                    }
                    val color = when {
                        PulseWidgetMath.isFlat(coin.change) -> colors.secondaryTextColor
                        coin.change > 0 -> colors.upColor
                        else -> colors.downColor
                    }
                    views.setViewVisibility(box, View.VISIBLE)
                    views.setTextViewText(text, PulseWidgetMath.chipText(coin))
                    views.setTextColor(text, color)
                    toolkit.pill(views, bg, color, dark, highContrast)
                }

                val time = PriceFormat.time(report.time).takeIf { it != "—" }
                val stand = time?.let { context.getString(R.string.pulse_updated, it) }
                val showTime = stand != null && PulseWidgetMath.showsTime(heightDp)
                views.setViewVisibility(R.id.pulse_time, if (showTime) View.VISIBLE else View.GONE)
                views.setTextViewText(R.id.pulse_time, stand.orEmpty())
                views.setTextColor(R.id.pulse_time, colors.secondaryTextColor)

                // «Was gerade auffällt. Breite Stärke. Bitcoin führt den Markt an. Bitcoin gestiegen um 2.8%, …»
                val spokenCoins = coins.joinToString(", ") { coin ->
                    coin.name + " " + A11yText.change(context, PulseWidgetMath.spokenChange(coin.change), decimals = 1)
                }
                views.setContentDescription(
                    R.id.pulse_root,
                    listOfNotNull(
                        "$title. $headline. $lead",
                        "$spokenCoins.",
                        fearGreedText?.takeIf { showFng }?.let { "$it." },
                        breadth?.takeIf { showBreadth }?.let {
                            context.getString(R.string.pulse_breadth_spoken, it.total, it.up, it.down) + "."
                        },
                        stand,
                    ).joinToString(" ")
                )
            }

            runCatching { manager.updateAppWidget(appWidgetId, views) }
                .onFailure { Timber.w(it, "Pulse-Widget %d konnte nicht gezeichnet werden", appWidgetId) }
        }
    }

    /** Öffnet die App im Markt-Tab (Ziel «cycle» wie die App-Verknüpfung). */
    private fun openMarket(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            // Eigene Action: Extras zählen nicht zur Identität eines PendingIntent
            action = ACTION_OPEN_MARKET
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN, "cycle")
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
    }
}

/** Die drei Coin-Chips des Widgets «Was gerade auffällt»: Rahmen, Hintergrund, Text (widget_pulse.xml). */
private val PULSE_CHIPS = listOf(
    Triple(R.id.pulse_chip_1, R.id.pulse_chip_1_bg, R.id.pulse_chip_1_text),
    Triple(R.id.pulse_chip_2, R.id.pulse_chip_2_bg, R.id.pulse_chip_2_text),
    Triple(R.id.pulse_chip_3, R.id.pulse_chip_3_bg, R.id.pulse_chip_3_text),
)
