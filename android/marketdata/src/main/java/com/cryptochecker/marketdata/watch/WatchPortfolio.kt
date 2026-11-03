package com.cryptochecker.marketdata.watch

import org.json.JSONObject

/**
 * Portfolio-Wert auf der Uhr (nur mit dem Schalter «Portfolio-Wert auf der Uhr», Standard aus): Gesamtwert
 * [total], Veränderung heute in Prozent [changePercent], Währung [currency] und Stand [time] (Epoch-ms) — keine
 * Bestände, keine Transaktionen. [hidden]: «Beträge verbergen» oder Portfolio-Sperre auf dem Handy; dann gehen keine
 * Zahlen an die Uhr, sie zeigt «•••». Wie iOS `WatchPortfolio` (Paritäts-Fixture `watch_link.json`).
 */
data class WatchPortfolio(
    val total: Double?,
    val changePercent: Double?,
    val currency: String,
    val time: Long,
    val hidden: Boolean,
) {
    /** Sendung an die Uhr: Wert oder [Clear] (Schalter aus bzw. Portfolio aus — die Uhr entfernt den Wert). */
    sealed interface Message {
        object Clear : Message

        data class Value(val portfolio: WatchPortfolio) : Message
    }

    companion object {
        private val CURRENCY = Regex("[A-Z]{3,5}")

        /**
         * Was das Handy schickt: [Message.Clear] ohne Schalter ([onWatch]) oder ohne Portfolio ([portfolioEnabled]);
         * null = noch keine Aufnahme ([time] ≤ 0, nichts senden). Verborgen ([hidden] oder [locked]) ohne Zahlen.
         */
        fun message(
            onWatch: Boolean,
            portfolioEnabled: Boolean,
            hidden: Boolean,
            locked: Boolean,
            total: Double?,
            changePercent: Double?,
            currency: String?,
            time: Long,
        ): Message? {
            if (!onWatch || !portfolioEnabled) return Message.Clear
            val code = currency?.trim()?.uppercase()?.takeIf { CURRENCY.matches(it) } ?: return null
            if (time <= 0L) return null
            val masked = hidden || locked
            return Message.Value(
                WatchPortfolio(
                    total = if (masked) null else total?.takeIf { it.isFinite() },
                    changePercent = if (masked) null else changePercent?.takeIf { it.isFinite() },
                    currency = code,
                    time = time,
                    hidden = masked,
                )
            )
        }

        fun toJson(message: Message): String = when (message) {
            Message.Clear -> JSONObject().put("v", 1).put("on", false).toString()
            is Message.Value -> {
                val p = message.portfolio
                val o = JSONObject().put("v", 1).put("on", true).put("h", p.hidden).put("cur", p.currency).put("t", p.time)
                if (!p.hidden) {
                    p.total?.let { o.put("tot", it) }
                    p.changePercent?.let { o.put("pct", it) }
                }
                o.toString()
            }
        }

        /** Liest [text]; Unlesbares, ohne Währung oder ohne Zeit → null. Verborgen: Zahlen fallen weg, auch wenn da. */
        fun fromJson(text: String?): Message? {
            val o = text?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
            if (!o.has("on")) return null
            if (!o.optBoolean("on")) return Message.Clear
            val currency = o.optString("cur").takeIf { CURRENCY.matches(it) } ?: return null
            val time = o.optLong("t").takeIf { it > 0L } ?: return null
            val hidden = o.optBoolean("h")
            fun number(key: String): Double? = if (hidden || !o.has(key)) null else o.optDouble(key).takeIf { it.isFinite() }
            return Message.Value(WatchPortfolio(number("tot"), number("pct"), currency, time, hidden))
        }
    }
}
