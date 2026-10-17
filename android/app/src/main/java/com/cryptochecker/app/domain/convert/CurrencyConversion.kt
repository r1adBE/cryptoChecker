package com.cryptochecker.app.domain.convert

/**
 * Reine Rechenregeln der «≈ Umrechnung» (ohne Netz, ohne Android), damit sie
 * als Unit-Test prüfbar bleiben. Die Kurse selbst liefert
 * [com.cryptochecker.app.data.portfolio.CurrencyConverter].
 */
object CurrencyConversion {

    /** Art der Quote-Währung eines Paars. */
    enum class QuoteKind {
        /** USD oder ein an den USD gebundener Stablecoin (Wert nach [stableToUsd]). */
        USD,

        /** Fiat-Währung mit Devisenkurs (z. B. EUR, JPY). */
        FIAT,

        /** Alles andere gilt als Krypto (z. B. BTC, ETH) und wird über USDT bewertet. */
        CRYPTO,

        /** Leer oder kein gültiges Kürzel. */
        INVALID,
    }

    /**
     * Die eine Stablecoin-Liste der App. «≈ Umrechnung» der Merkliste und Portfolio bewerten sie
     * mit Marktkurs ([stableToUsd] bzw. [com.cryptochecker.app.domain.portfolio.PortfolioStables]):
     * USD/USDT 1, andere ihr Marktkurs in USDT. Ohne Kurs: kein ≈-Wert bzw. Portfolio kein Wert
     * («ohne Kurs»).
     */
    val USD_STABLES: Set<String> = setOf(
        "USD", "USDT", "USDC", "FDUSD", "BUSD", "DAI", "TUSD", "USDE", "USD1",
        "RLUSD", "PYUSD", "USDP", "GUSD",
    )

    fun normalize(code: String?): String = code?.trim()?.uppercase().orEmpty()

    /** Gleiche Währung (Gross-/Kleinschreibung egal) — dann gibt es nichts umzurechnen. */
    fun sameCurrency(a: String?, b: String?): Boolean {
        val x = normalize(a)
        return x.isNotEmpty() && x == normalize(b)
    }

    /** Einordnung von [quote]; [fiat] = Währungen mit Devisenkurs (FxRateSource.CURRENCIES). */
    fun classify(quote: String?, fiat: Collection<String>): QuoteKind {
        val code = normalize(quote)
        if (code.isEmpty() || code.length > 15 || !code.all { it.isLetterOrDigit() }) return QuoteKind.INVALID
        if (code in USD_STABLES) return QuoteKind.USD
        if (fiat.any { it.equals(code, ignoreCase = true) }) return QuoteKind.FIAT
        return QuoteKind.CRYPTO
    }

    /**
     * Stablecoin-Regel der «≈ Umrechnung» wie das Portfolio
     * ([com.cryptochecker.app.domain.portfolio.PortfolioStables]): USD und USDT (Bewertungswährung)
     * genau 1, jeder andere Stablecoin sein Marktkurs in USDT [marketUsdt]; ohne gültigen Kurs null
     * (kein ≈-Wert statt eines stillen 1). Ohne Kürzel gilt USD.
     */
    fun stableToUsd(code: String?, marketUsdt: Double?): Double? {
        if (!stableNeedsMarket(code)) return 1.0
        return marketUsdt?.takeIf { it.isValidRate() }
    }

    /** Braucht die Quote [code] (Stablecoin) einen Marktkurs? Alle ausser USD und USDT (leer = USD). */
    fun stableNeedsMarket(code: String?): Boolean = normalize(code).let { it.isNotEmpty() && it != "USD" && it != "USDT" }

    /** Ab diesem Alter (Abrufzeit) zeigt die ≈ Umrechnung die Uhrzeit des Devisenkurses (3 h). */
    const val FX_STALE_MILLIS = 3 * 60 * 60_000L

    /**
     * Abrufzeit des ältesten Devisenkurses im Faktor Quote → Ziel ([quoteFxAt]: Kurs der Fiat-Quote,
     * [targetFxAt]: Kurs der Zielwährung; je null = nicht beteiligt), wenn älter als [FX_STALE_MILLIS];
     * sonst null (kein Hinweis).
     */
    fun staleFxTime(quoteFxAt: Long?, targetFxAt: Long?, now: Long): Long? {
        val oldest = listOfNotNull(quoteFxAt, targetFxAt).minOrNull() ?: return null
        return oldest.takeIf { now - it > FX_STALE_MILLIS }
    }

    /**
     * Wert einer Einheit der Quote-Währung in USD.
     * @param usdToQuote Einheiten der Quote-Währung je USD (nur bei FIAT nötig)
     * @param cryptoUsdt Kurs der Quote-Währung in USDT (bei CRYPTO nötig; bei USD = Marktkurs des
     *   Stablecoins, ohne gültigen Kurs null — siehe [stableToUsd])
     * @param code Kürzel der Quote-Währung (nur bei USD: USD/USDT sind immer genau 1)
     * @return null, wenn der nötige Kurs fehlt oder ungültig ist
     */
    fun quoteToUsd(kind: QuoteKind, usdToQuote: Double?, cryptoUsdt: Double?, code: String? = null): Double? = when (kind) {
        QuoteKind.USD -> stableToUsd(code, cryptoUsdt)
        QuoteKind.FIAT -> usdToQuote?.takeIf { it.isValidRate() }?.let { 1.0 / it }
        QuoteKind.CRYPTO -> cryptoUsdt?.takeIf { it.isValidRate() }
        QuoteKind.INVALID -> null
    }

    /**
     * Umrechnungsfaktor Quote → Ziel: Kurs in Quote × Faktor = Kurs in Ziel.
     * @param quoteUsd Wert einer Quote-Einheit in USD
     * @param usdToTarget Einheiten der Zielwährung je USD
     */
    fun rate(quoteUsd: Double?, usdToTarget: Double?): Double? {
        val q = quoteUsd?.takeIf { it.isValidRate() } ?: return null
        val t = usdToTarget?.takeIf { it.isValidRate() } ?: return null
        return (q * t).takeIf { it.isValidRate() }
    }

    /** Fester Umrechnungskurs Lew → Euro (Bulgarien führt den Euro am 1.1.2026 ein). */
    const val BGN_PER_EUR = 1.95583

    /** Ab diesem Tag darf ein fehlender BGN-Kurs aus dem EUR-Kurs abgeleitet werden. */
    val BGN_EURO_SINCE: java.time.LocalDate = java.time.LocalDate.of(2026, 1, 1)

    /**
     * Fehlt der BGN-Kurs (live oder historisch) ab dem 1.1.2026, gilt EUR × 1.95583.
     * @return true, wenn für [currency] am Tag [date] aus EUR abgeleitet werden darf
     */
    fun deriveBgnFromEur(currency: String?, date: java.time.LocalDate): Boolean =
        normalize(currency) == "BGN" && !date.isBefore(BGN_EURO_SINCE)

    /** BGN je USD aus EUR je USD; null ohne gültigen EUR-Kurs. */
    fun bgnFromEur(usdToEur: Double?): Double? =
        usdToEur?.takeIf { it.isValidRate() }?.let { it * BGN_PER_EUR }

    /** Wandelt einen Betrag um; null ohne gültigen Faktor. */
    fun convert(value: Double, rate: Double?): Double? =
        rate?.takeIf { it.isValidRate() }?.let { value * it }

    private fun Double.isValidRate() = !isNaN() && !isInfinite() && this > 0.0
}
