package com.cryptochecker.app.widget

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.cryptochecker.app.domain.market.PulseBreadth
import com.cryptochecker.app.domain.market.PulseReport
import com.cryptochecker.app.domain.util.LocaleNumbers
import com.cryptochecker.app.domain.widget.WidgetTextFit
import com.cryptochecker.app.util.A11yText
import com.cryptochecker.app.util.PriceFormat
import com.cryptochecker.resources.R

// Inhalt des Pulse-Widgets mit Bericht ([PulseWidgetRenderer.drawPulse]): Schlagzeile, Nebenzeilen, «Kurz gesagt»,
// Coins, Stand und der Satz für den Screenreader. Eigene Datei, weil der Renderer sonst zu lang würde.

/** Zeichnet ein Pulse-Widget mit Bericht ([report]); ohne Bericht zeigt der Renderer selbst den ruhigen Satz. */
internal fun drawPulseReport(
    context: Context,
    toolkit: WidgetToolkit,
    views: RemoteViews,
    colors: WidgetColors,
    report: PulseReport,
    title: String,
    online: Boolean,
    widgetWidthDp: Int,
    heightDp: Int,
    fearGreedValue: Int?,
    fearGreedText: String?,
    plainSentences: List<String>,
    plainTitle: String,
    plainNote: (Int) -> String,
) {
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

    // «Stand» bzw. «Keine Verbindung · Stand» in jeder Grösse (wie iOS); der Leitsatz weicht ihm notfalls
    val stand = PulseWidgetTexts.stand(context, report.time, online)
    val leadLines = PulseWidgetMath.leadLines(heightDp, headlineStyle.lines, showsTime = stand != null)

    val breadth = report.breadth
    val period24h = context.getString(R.string.widget_range_short_24h)
    val sideLayout = drawPulseSideLines(
        context = context,
        toolkit = toolkit,
        views = views,
        colors = colors,
        breadth = breadth,
        period24h = period24h,
        fearGreedValue = fearGreedValue,
        fearGreedText = fearGreedText,
        widgetWidthDp = widgetWidthDp,
        heightDp = heightDp,
        headlineLines = headlineStyle.lines,
    )
    val showFng = sideLayout?.showsFearGreed == true
    val showBreadth = sideLayout?.showsBreadth == true

    // «Kurz gesagt»: gross so viele ganze Sätze, wie unter Nebenzeilen, Coins und Stand passen
    // (die behalten ihren Platz); mittel der erste Satz statt des Leitsatzes; klein unverändert
    val sideLines = when (sideLayout) {
        PulseSideLayout.TWO_LINES -> 2
        null -> 0
        else -> 1
    }
    val textWidthDp = widgetWidthDp - PulseWidgetMath.PADDING_DP - WidgetTextFit.SAFETY_DP
    val plainCount = if (widgetWidthDp <= 0 || plainSentences.isEmpty()) {
        0
    } else {
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
    views.setViewVisibility(R.id.pulse_lead, if (leadLines > 0) View.VISIBLE else View.GONE)
    views.setInt(R.id.pulse_lead, "setMaxLines", leadLines.coerceAtLeast(1))

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
    PulseWidgetChips.draw(views, coins, colors)
    views.setViewVisibility(R.id.pulse_time, if (stand != null) View.VISIBLE else View.GONE)
    views.setTextViewText(R.id.pulse_time, stand.orEmpty())
    views.setTextColor(R.id.pulse_time, colors.secondaryTextColor)
    // «Was gerade auffällt. Breite Stärke. Bitcoin führt den Markt an. Bitcoin gestiegen um 2.8%, …»
    val spokenCoins = coins.joinToString(", ") { coin ->
        coin.name + " " + A11yText.change(context, PulseWidgetMath.spokenChange(coin.change), decimals = 1)
    }
    views.setContentDescription(
        R.id.pulse_root,
        listOfNotNull(
            if (leadLines > 0) "$title. $headline. $lead" else "$title. $headline.", // nur Gezeigtes
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

/** Nebenzeilen Fear & Greed und Marktbreite; gibt die gewählte Aufteilung zurück (null = keine Nebenzeile). */
private fun drawPulseSideLines(
    context: Context,
    toolkit: WidgetToolkit,
    views: RemoteViews,
    colors: WidgetColors,
    breadth: PulseBreadth?,
    period24h: String,
    fearGreedValue: Int?,
    fearGreedText: String?,
    widgetWidthDp: Int,
    heightDp: Int,
    headlineLines: Int,
): PulseSideLayout? {
    // Nebenzeile «Fear & Greed 72 · Gier · Top 30 ↗ 22 ↘ 8»: nur mittel/gross; passt nicht
    // alles in eine Zeile, die Marktbreite in einer zweiten Zeile darunter (wenn die Höhe
    // reicht), sonst zuerst Fear & Greed, dann die Marktbreite — nie abgeschnitten
    // «Top 30 · 24h  ↗ 21  ↘ 9» — Marktbreite über 24 Stunden
    val breadthText = breadth?.let {
        context.getString(R.string.pulse_breadth_label, it.total) + " · " + period24h + "  " + PriceFormat.ARROW_UP + " " +
            LocaleNumbers.integer(it.up) +
            "  " +
            PriceFormat.ARROW_DOWN +
            " " +
            LocaleNumbers.integer(it.down)
    }
    // Stufenpunkt «●» vor Fear & Greed (feste Skala, AA); gehört zur gemessenen Breite
    val fearGreedShown = fearGreedText?.let { "${PulseSideLine.FNG_DOT} $it" }
    val bothText = if (fearGreedShown != null && breadthText != null) "$fearGreedShown · $breadthText" else null
    fun sideWidth(text: String?) = text?.let { toolkit.textWidthDp(it, 12f, bold = false, tabular = true) }
    val sideLayout = PulseWidgetMath.sideLayout(
        widgetWidthDp,
        heightDp,
        headlineLines,
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
    return sideLayout
}

/** Die bis zu drei Sätze von «Kurz gesagt» im grossen Widget (widget_pulse.xml). */
private val PULSE_PLAIN_LINES = listOf(R.id.pulse_plain_1, R.id.pulse_plain_2, R.id.pulse_plain_3)
