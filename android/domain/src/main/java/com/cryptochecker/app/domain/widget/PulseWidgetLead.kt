package com.cryptochecker.app.domain.widget

import com.cryptochecker.app.domain.market.CryptoPulse
import com.cryptochecker.app.domain.market.MoverCoin
import com.cryptochecker.app.domain.market.PulseDetail
import com.cryptochecker.app.domain.market.PulseLead
import com.cryptochecker.app.domain.market.PulseReport
import com.cryptochecker.app.domain.market.PulseSummary

/** Art des Leitsatzes im Widget: stärkster Coin in Richtung des Markts, beide Enden oder der Satz der Karte. */
enum class PulseWidgetLeadKind { UP, DOWN, MIXED, CARD }

/**
 * Leitsatz des Widgets «Was gerade auffällt»: [kind] mit [top]/[bottom] (Gewinner/Verlierer der Rangliste) oder bei
 * [PulseWidgetLeadKind.CARD] der Satz der Karte ([card]); [detail] nur, wenn er etwas sagt (sonst null).
 */
data class PulseWidgetLeadChoice(
    val kind: PulseWidgetLeadKind,
    val top: MoverCoin?,
    val bottom: MoverCoin?,
    val card: PulseLead,
    val detail: PulseDetail?,
)

/**
 * Konkreter Leitsatz für das Widget (reine Regel, wie iOS `PulseWidgetLead`): statt «Der Markt gibt breit nach» der
 * Coin, der am stärksten fällt (bzw. steigt; gemischt beide Enden) mit seiner 24-h-Veränderung — aus derselben
 * Rangliste wie «Gewinner und Verlierer 24 h». Ohne Rangliste und bei ruhigem Markt der Satz der Karte. Der Zusatz
 * (Volumen, Hebel) entfällt, wenn er nur «normal/neutral» sagt. Keine Prognose: nur Werte der letzten 24 h.
 */
object PulseWidgetLead {

    fun choose(report: PulseReport): PulseWidgetLeadChoice {
        val card = CryptoPulse.leadSentence(report)
        val top = report.movers?.gainers?.firstOrNull { (it.change24h ?: 0.0) > 0.0 }
        val bottom = report.movers?.losers?.firstOrNull { (it.change24h ?: 0.0) < 0.0 }
        val kind = when (report.summary) {
            PulseSummary.BROAD_UP, PulseSummary.BROAD_UP_VOLUME -> if (top != null) PulseWidgetLeadKind.UP else PulseWidgetLeadKind.CARD
            PulseSummary.BROAD_DOWN, PulseSummary.BROAD_DOWN_VOLUME ->
                if (bottom != null) PulseWidgetLeadKind.DOWN else PulseWidgetLeadKind.CARD
            PulseSummary.MIXED -> if (top != null && bottom != null) PulseWidgetLeadKind.MIXED else PulseWidgetLeadKind.CARD
            PulseSummary.CALM -> PulseWidgetLeadKind.CARD
        }
        return PulseWidgetLeadChoice(kind, top, bottom, card, notable(card.detail))
    }

    /** Zusatz ohne die Teile «normal» und «neutral»; nichts Auffälliges = null. */
    fun notable(detail: PulseDetail?): PulseDetail? = when (detail) {
        null, PulseDetail.VOL_NORMAL, PulseDetail.VOL_NORMAL_FUND_NEUTRAL, PulseDetail.FUND_NEUTRAL -> null
        PulseDetail.VOL_ABOVE_FUND_NEUTRAL -> PulseDetail.VOL_ABOVE
        PulseDetail.VOL_BELOW_FUND_NEUTRAL -> PulseDetail.VOL_BELOW
        PulseDetail.VOL_NORMAL_FUND_HIGH -> PulseDetail.FUND_HIGH
        PulseDetail.VOL_NORMAL_FUND_NEGATIVE -> PulseDetail.FUND_NEGATIVE
        else -> detail
    }
}
