package com.cryptochecker.app.util

import android.content.Context
import com.cryptochecker.app.R
import java.util.Locale

/**
 * Sätze für Screenreader (TalkBack): Zeilen der Merkliste, Widgets und Charts.
 * Änderungen mit Richtungswort statt Vorzeichen, siehe [ChartSummary.unsignedPercent].
 */
object A11yText {

    /** «up 2.35%» / «down 1.20%» / «unchanged». */
    fun change(context: Context, percent: Double?, decimals: Int = 2): String =
        when (ChartSummary.direction(percent)) {
            ChangeDirection.UP -> context.getString(
                R.string.a11y_change_up, ChartSummary.unsignedPercent(percent ?: 0.0, decimals)
            )
            ChangeDirection.DOWN -> context.getString(
                R.string.a11y_change_down, ChartSummary.unsignedPercent(percent ?: 0.0, decimals)
            )
            ChangeDirection.FLAT -> context.getString(R.string.a11y_change_flat)
        }

    /**
     * Veränderung über 24 Stunden: «up 2.35% in 24 hours»; ohne Wert
     * «24-hour change not available» (Anzeige «—»).
     */
    fun change24h(context: Context, percent: Double?): String =
        if (percent == null || !percent.isFinite()) context.getString(R.string.a11y_change_24h_none)
        else context.getString(R.string.a11y_change_24h, change(context, percent))

    /**
     * «Chart 24h: from 100 to 110, up 10.00%. High 112, low 98.» — oder
     * «Chart: no data» bei weniger als zwei Werten.
     */
    fun chart(
        context: Context,
        period: String,
        values: List<Double>,
        format: (Double) -> String = PriceFormat::price,
    ): String = chart(context, period, ChartSummary.of(values), format)

    /** Wie oben, mit fertigen Kennzahlen (z. B. aus Kerzen: erste Eröffnung, letzter Schluss). */
    fun chart(
        context: Context,
        period: String,
        summary: ChartSummary?,
        format: (Double) -> String = PriceFormat::price,
    ): String {
        val s = summary ?: return context.getString(R.string.a11y_chart_empty)
        return context.getString(
            R.string.a11y_chart,
            period,
            format(s.start),
            format(s.end),
            change(context, s.changePercent),
            format(s.high),
            format(s.low),
        )
    }

    /**
     * Eine Kurszeile (Merkliste, Widget) als ein Satz, Teile mit «, » verbunden
     * ([A11ySpeech.row]): Paar und Börse, Kurs (Fiat-Währung als Wort, wenn [quote] gesetzt),
     * Veränderung mit Zeitraum ([ChangeBasisText.spoken]), Frische ([state]), Details
     * ([extras]), Alarme ([alarms]), Rest ([rest]).
     */
    fun row(
        context: Context,
        pair: String,
        exchange: String,
        price: String,
        change24h: Double?,
        extras: List<String?> = emptyList(),
        /** %-Basis der Veränderung (Zeitraum im Satz). */
        basis: com.cryptochecker.app.domain.watch.ChangeBasis = com.cryptochecker.app.domain.watch.ChangeBasis.ROLLING_24H,
        /** Gegenwährung des Kurses: «USD» wird als «US-Dollar» gesprochen; null = Kurs wie gegeben. */
        quote: String? = null,
        /** Frische direkt nach der Veränderung, z. B. [freshness] oder «Deaktiviert». */
        state: List<String?> = emptyList(),
        /** Alarme («Alarm ausgelöst», «Alarme: 2»). */
        alarms: List<String?> = emptyList(),
        /** Alles danach (z. B. Benachrichtigung, Fehler). */
        rest: List<String?> = emptyList(),
        /** Art des Paars («Token», «TradFi», «Alpha-Token»), vor [rest] — wie das Symbol vor der Glocke. */
        kind: String? = null,
    ): String {
        val spokenPrice = if (quote == null) price else A11ySpeech.spokenPrice(price, quote, locale(context))
        return A11ySpeech.row(
            pair = context.getString(R.string.a11y_row_pair, pair, exchange),
            price = context.getString(R.string.a11y_price, spokenPrice),
            // Ohne Bezug ein kurzer Hinweis statt einer Zahl (Anzeige «—»)
            change = ChangeBasisText.spoken(context, basis, change24h),
            state = state,
            details = extras,
            alarms = alarms,
            rest = listOf(kind) + rest,
        )
    }

    /** Sprache der Oberfläche (für Währungsnamen). */
    fun locale(context: Context): Locale = context.resources.configuration.locales.get(0) ?: Locale.getDefault()

    /** Übersetzte Satzteile für [A11ySpeech.freshnessText]. */
    fun texts(context: Context): A11ySpeech.Texts = object : A11ySpeech.Texts {
        override val disabled: String = context.getString(R.string.watch_disabled)
        override val notTraded: String = context.getString(R.string.watch_not_traded)
        override val outdated: String = context.getString(R.string.a11y_price_outdated)
        override fun updated(age: A11ySpeech.Age): String = when (age) {
            A11ySpeech.Age.JustNow -> context.getString(R.string.a11y_updated_just_now)
            is A11ySpeech.Age.Minutes ->
                context.resources.getQuantityString(R.plurals.a11y_updated_minutes, age.count, age.count)
            is A11ySpeech.Age.Hours ->
                context.resources.getQuantityString(R.plurals.a11y_updated_hours, age.count, age.count)
            is A11ySpeech.Age.Days ->
                context.resources.getQuantityString(R.plurals.a11y_updated_days, age.count, age.count)
        }
        override fun unreachable(exchange: String): String = context.getString(R.string.a11y_exchange_unreachable, exchange)
    }

    /**
     * Frische einer Zeile als Satzteil: «Kurs veraltet, zuletzt aktualisiert vor 45 Minuten»,
     * «zuletzt aktualisiert vor 3 Minuten, Binance nicht erreichbar», «Deaktiviert»,
     * «Wird an der Börse nicht mehr gehandelt»; aktuell null.
     */
    fun freshness(
        context: Context,
        freshness: A11ySpeech.Freshness,
        lastUpdate: Long,
        now: Long,
        exchange: String,
    ): String? = A11ySpeech.freshnessText(freshness, lastUpdate, now, exchange, texts(context))
}
