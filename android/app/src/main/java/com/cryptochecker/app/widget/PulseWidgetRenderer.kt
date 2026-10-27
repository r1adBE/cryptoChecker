package com.cryptochecker.app.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.R
import com.cryptochecker.app.data.CachedValue
import com.cryptochecker.app.data.CycleCacheCodecs
import com.cryptochecker.app.data.CycleCacheStore
import com.cryptochecker.app.data.remote.PulseDataSource
import com.cryptochecker.app.domain.market.CryptoPulse
import com.cryptochecker.app.domain.market.CycleSource
import com.cryptochecker.app.domain.market.MarketPlainSummary
import com.cryptochecker.app.domain.market.PulseInput
import com.cryptochecker.app.settings.HighContrast
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.settings.logoRes
import com.cryptochecker.app.ui.MainActivity
import com.cryptochecker.app.util.A11yText
import com.cryptochecker.app.util.LocaleNumbers
import com.cryptochecker.app.util.PriceFormat
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

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
        val loaded = pulseData.loadPulse()
        if (loaded != null) drawPulse(appWidgetIds, loaded) else if (cached == null) drawPulse(appWidgetIds, null)
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
    private suspend fun drawPulse(appWidgetIds: IntArray, value: CachedValue<PulseInput>?) {
        if (appWidgetIds.isEmpty()) return
        val manager = AppWidgetManager.getInstance(context) ?: return
        val settings = settingsRepository.current()
        val accent = settings.accentColor
        val highContrast = HighContrast.isEffective(context, settings.highContrast)
        widgetPrefs.lastHighContrast = highContrast
        val now = System.currentTimeMillis()
        // Frischer Stand als eigene, nicht-null Variable (kein Smart Cast über die ?.let-Kette nötig)
        val fresh = value?.takeIf { PulseWidgetMath.isShowable(it.savedAt, now) }
        val report = fresh?.let { CryptoPulse.evaluate(it.value) }
        val title = context.getString(R.string.pulse_now_title)
        // Fear & Greed nur aus dem Zwischenspeicher (Markt-Tab oder Pulse, ≤ 24 h) — kein eigener Abruf
        val cachedFng = if (fresh == null || report == null) null else {
            cycleCacheStore.read(CycleSource.FEAR_GREED.key, CycleCacheCodecs.fearGreed)
        }
        val fearGreedValue = if (fresh == null || report == null) null else {
            PulseWidgetMath.fearGreed(
                cached = cachedFng?.value?.value,
                cachedAt = cachedFng?.savedAt,
                pulse = fresh.value.fearGreed,
                pulseAt = fresh.savedAt,
                now = now,
            )
        }
        val fearGreedText = fearGreedValue?.let { PulseWidgetTexts.fearGreed(context, it) }
        // «Kurz gesagt» aus denselben Eingaben und mit derselben Frische-Regel wie im Markt-Tab
        val plainInput = if (fresh == null || report == null) null else {
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
        // «Daten: CoinGecko, alternative.me · keine Prognose, keine Anlageberatung» — Quellen der gezeigten Sätze
        fun plainNote(shownCount: Int): String =
            plainSummary?.let { PulseWidgetPlain.sources(it, shownCount) }
                ?.let { context.getString(R.string.pulse_plain_sources, it) + " · " + plainFootnote }
                ?: plainFootnote

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
            views.setTextViewTextSize(R.id.pulse_title, TypedValue.COMPLEX_UNIT_SP, titleSp ?: WidgetType.BODY)

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

                val leadLines = PulseWidgetMath.leadLines(heightDp, headlineStyle.lines)

                // Nebenzeile «Fear & Greed 72 · Gier · Top 30 ↗ 22 ↘ 8»: nur mittel/gross; passt nicht
                // alles in eine Zeile, die Marktbreite in einer zweiten Zeile darunter (wenn die Höhe
                // reicht), sonst zuerst Fear & Greed, dann die Marktbreite — nie abgeschnitten
                val breadth = report.breadth
                val period24h = context.getString(R.string.widget_range_short_24h)
                // «Top 30 · 24h  ↗ 21  ↘ 9» — Marktbreite über 24 Stunden
                val breadthText = breadth?.let {
                    context.getString(R.string.pulse_breadth_label, it.total) + " · " + period24h + "  " + PriceFormat.ARROW_UP + " " + LocaleNumbers.integer(it.up) + "  " + PriceFormat.ARROW_DOWN + " " + LocaleNumbers.integer(it.down)
                }
                // Stufenpunkt «●» vor Fear & Greed (feste Skala, AA); gehört zur gemessenen Breite
                val fearGreedShown = fearGreedText?.let { "${PulseSideLine.FNG_DOT} $it" }
                val bothText = if (fearGreedShown != null && breadthText != null) "$fearGreedShown · $breadthText" else null
                fun sideWidth(text: String?) = text?.let { toolkit.textWidthDp(it, 12f, bold = false, tabular = true) }
                val sideLayout = PulseWidgetMath.sideLayout(
                    widgetWidthDp, heightDp, headlineStyle.lines,
                    fearGreedWidthDp = sideWidth(fearGreedShown),
                    // Allein in der ersten Zeile ohne Punkt; als zweite Zeile mit Punkt davor (eigene Breite)
                    breadthWidthDp = sideWidth(breadthText),
                    bothWidthDp = sideWidth(bothText),
                    breadthLineWidthDp = sideWidth(breadthText?.let { "${PulseSideLine.FNG_DOT} $it" }),
                )
                val showFng = sideLayout?.showsFearGreed == true
                val showBreadth = sideLayout?.showsBreadth == true
                // Erste Nebenzeile: beides, Fear & Greed allein oder die Marktbreite allein; zweite nur bei TWO_LINES
                val sideText = when (sideLayout) {
                    PulseSideLayout.ONE_LINE -> bothText
                    PulseSideLayout.TWO_LINES, PulseSideLayout.FEAR_GREED -> fearGreedShown
                    PulseSideLayout.BREADTH -> breadthText
                    null -> null
                }
                // Zweite Zeile mit eigenem Punkt vor «Top 30», damit beide Zeilen gleich aussehen (wie iOS)
                val secondText = breadthText?.takeIf { sideLayout == PulseSideLayout.TWO_LINES }?.let { "${PulseSideLine.FNG_DOT} $it" }
                views.setViewVisibility(R.id.pulse_fng, if (sideText != null) View.VISIBLE else View.GONE)
                views.setTextViewText(
                    R.id.pulse_fng,
                    sideText?.let {
                        PulseSideLine.sideLine(
                            it,
                            fearGreedValue.takeIf { showFng },
                            fearGreedShown,
                            breadth.takeIf { showBreadth && secondText == null },
                            colors,
                        )
                    } ?: ""
                )
                views.setTextColor(R.id.pulse_fng, colors.secondaryTextColor)
                views.setViewVisibility(R.id.pulse_breadth, if (secondText != null) View.VISIBLE else View.GONE)
                views.setTextViewText(
                    R.id.pulse_breadth,
                    secondText?.let { PulseSideLine.sideLine(it, null, null, breadth, colors) } ?: ""
                )
                views.setTextColor(R.id.pulse_breadth, colors.secondaryTextColor)

                // «Kurz gesagt»: gross so viele ganze Sätze, wie unter Nebenzeilen, Coins und Stand passen
                // (die behalten ihren Platz); mittel der erste Satz statt des Leitsatzes; klein unverändert
                val sideLines = when (sideLayout) {
                    PulseSideLayout.TWO_LINES -> 2
                    null -> 0
                    else -> 1
                }
                val textWidthDp = widgetWidthDp - PulseWidgetMath.PADDING_DP - WidgetTextFit.SAFETY_DP
                val plainCount = if (widgetWidthDp < PulseWidgetPlain.WIDE_MIN_WIDTH_DP || plainSentences.isEmpty()) 0 else {
                    PulseWidgetPlain.sentenceCount(
                        availableDp = PulseWidgetPlain.blockAvailableDp(heightDp, headlineStyle.lines, sideLines),
                        titleDp = toolkit.textBlock(plainTitle, PulseWidgetPlain.TITLE_SP, textWidthDp, bold = true).heightDp,
                        // Mit den Quellen aller Sätze gemessen: mit weniger Sätzen wird der Hinweis höchstens kürzer
                        footnoteDp = toolkit.textBlock(plainNote(plainSentences.size), PulseWidgetPlain.FOOTNOTE_SP, textWidthDp).heightDp,
                        sentenceHeightsDp = plainSentences.map {
                            toolkit.textBlock(it, PulseWidgetPlain.SENTENCE_SP, textWidthDp).heightDp
                        },
                    )
                }
                val size = PulseWidgetPlain.size(widgetWidthDp, plainCount > 0)
                val firstSentence = plainSentences.firstOrNull()
                val sentenceLead = size == PulseWidgetSize.MEDIUM && firstSentence != null &&
                    PulseWidgetPlain.sentenceAsLead(
                        toolkit.textBlock(firstSentence, PulseWidgetPlain.LEAD_SP, textWidthDp).lines.takeIf { it > 0 },
                        leadLines,
                    )
                val lead = firstSentence?.takeIf { sentenceLead } ?: PulseWidgetTexts.lead(context, report)
                views.setTextViewText(R.id.pulse_lead, lead)
                views.setTextColor(R.id.pulse_lead, colors.textColor)
                views.setInt(R.id.pulse_lead, "setMaxLines", leadLines)

                val shownPlain = if (size == PulseWidgetSize.LARGE) plainSentences.take(plainCount) else emptyList()
                views.setViewVisibility(R.id.pulse_plain, if (shownPlain.isNotEmpty()) View.VISIBLE else View.GONE)
                views.setTextViewText(R.id.pulse_plain_title, plainTitle)
                views.setTextColor(R.id.pulse_plain_title, colors.textColor)
                PULSE_PLAIN_LINES.forEachIndexed { index, id ->
                    val sentence = shownPlain.getOrNull(index)
                    views.setViewVisibility(id, if (sentence != null) View.VISIBLE else View.GONE)
                    views.setTextViewText(id, sentence.orEmpty())
                    views.setTextColor(id, colors.textColor)
                }
                val shownNote = plainNote(shownPlain.size)
                views.setTextViewText(R.id.pulse_plain_note, shownNote)
                views.setTextColor(R.id.pulse_plain_note, colors.secondaryTextColor)

                // Nur so viele Coins, wie ganz in die Breite passen (nie ein abgeschnittener)
                val allCoins = PulseWidgetMath.coins(report, 3)
                val chipWidths = allCoins.map { toolkit.textWidthDp(PulseWidgetMath.chipText(it), 12f, tabular = true) }
                // «24h» klein und grau vor den Coins (die Veränderungen sind 24-h-Werte); Platz dafür abziehen
                val periodLeading = PulseWidgetMath.periodLeadingDp(toolkit.textWidthDp(period24h, 12f, bold = false))
                val coins = allCoins.take(PulseWidgetMath.coinCount(widgetWidthDp, chipWidths, periodLeading))
                views.setViewVisibility(R.id.pulse_coins_period, if (coins.isEmpty()) View.GONE else View.VISIBLE)
                views.setTextViewText(R.id.pulse_coins_period, period24h)
                views.setTextColor(R.id.pulse_coins_period, colors.secondaryTextColor)
                PULSE_CHIPS.forEachIndexed { index, id ->
                    val coin = coins.getOrNull(index)
                    if (coin == null) {
                        views.setViewVisibility(id, View.GONE)
                        return@forEachIndexed
                    }
                    val color = when {
                        PulseWidgetMath.isFlat(coin.change) -> colors.secondaryTextColor
                        coin.change > 0 -> colors.upColor
                        else -> colors.downColor
                    }
                    // Farbiger Text ohne Pille (wie iOS): Kürzel in der Textfarbe, Veränderung in der Kursfarbe
                    val text = PulseWidgetMath.chipText(coin)
                    views.setViewVisibility(id, View.VISIBLE)
                    views.setTextViewText(
                        id,
                        android.text.SpannableString(text).apply {
                            setSpan(
                                android.text.style.ForegroundColorSpan(colors.textColor),
                                0,
                                coin.symbol.length,
                                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                            )
                        },
                    )
                    views.setTextColor(id, color)
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
                        // «Kurz gesagt. Satz. Satz. Hinweis» — nur die gezeigten Sätze
                        shownPlain.takeIf { it.isNotEmpty() }?.let { (listOf("$plainTitle.") + it + shownNote).joinToString(" ") },
                    ).joinToString(" ")
                )
            }

            runCatchingCancellable { manager.updateAppWidget(appWidgetId, views) }
                .onFailure { Timber.w(it, "Pulse-Widget %d konnte nicht gezeichnet werden", appWidgetId) }
        }
        schedulePlainExpiry(
            if (plainInput != null && plainSummary != null && plainSentences.isNotEmpty()) {
                PulseWidgetPlain.expiresAt(plainInput, plainSummary)
            } else null
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

        /** Eigener Request-Code (neben [WidgetOutdated] und [WidgetRefreshUnlock]). */
        const val PLAIN_EXPIRY_REQUEST_CODE = -7_015
    }
}

/** Die drei Coins des Widgets «Was gerade auffällt» als Text (widget_pulse.xml). */
private val PULSE_CHIPS = listOf(R.id.pulse_chip_1, R.id.pulse_chip_2, R.id.pulse_chip_3)

/** Die bis zu drei Sätze von «Kurz gesagt» im grossen Widget (widget_pulse.xml). */
private val PULSE_PLAIN_LINES = listOf(R.id.pulse_plain_1, R.id.pulse_plain_2, R.id.pulse_plain_3)
