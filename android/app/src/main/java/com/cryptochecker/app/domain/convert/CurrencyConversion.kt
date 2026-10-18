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
     * USD 1, USDT sein Marktkurs USDT/USD (ohne Kurs 1), andere ihr Marktkurs in USDT. Ohne Kurs: kein ≈-Wert bzw. Portfolio kein Wert
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
     * ([com.cryptochecker.app.domain.portfolio.PortfolioStables]): USD genau 1; USDT sein Marktkurs USDT/USD
     * [usdtUsd] (ohne Kurs 1, [com.cryptochecker.app.domain.portfolio.PortfolioStables.usdtUsd]); jeder andere
     * Stablecoin sein Marktkurs in USDT [marketUsdt] × USDT/USD; ohne gültigen Kurs null (kein ≈-Wert statt eines
     * stillen 1). Ohne Kürzel gilt USD. [usdtUsd] null = USDT gilt 1 USD (bisherige Rechnung).
     */
    fun stableToUsd(code: String?, marketUsdt: Double?, usdtUsd: Double? = null): Double? {
        val c = normalize(code)
        if (c.isEmpty() || c == "USD") return 1.0
        if (c == "USDT") return usdtFactor(usdtUsd)
        return marketUsdt?.takeIf { it.isValidRate() }?.let { it * usdtFactor(usdtUsd) }
    }

    /** USDT in USD für die Umrechnung: [usdtUsd] (plausibel) oder 1; null = 1 (bisherige Rechnung). */
    private fun usdtFactor(usdtUsd: Double?): Double =
        if (usdtUsd == null) 1.0 else com.cryptochecker.app.domain.portfolio.PortfolioStables.usdtUsdLive(usdtUsd).rate

    /** Braucht die Quote [code] (Stablecoin) einen Marktkurs? Alle ausser USD und USDT (leer = USD). */
    fun stableNeedsMarket(code: String?): Boolean = normalize(code).let { it.isNotEmpty() && it != "USD" && it != "USDT" }

    /** Ab diesem Alter (Abrufzeit) zeigt die ≈ Umrechnung die Uhrzeit des Devisenkurses (3 h). */
    const val FX_STALE_MILLIS = 3 * 60 * 60_000L

    /**
     * Höchstalter eines Krypto- bzw. Stablecoin-Kurses (Quote in USDT aus dem Zwischenspeicher der Portfolio-Kurse) für
     * die Umrechnung (15 min): ein Alarm in CHF auf ein BTC-Paar darf nicht mit einem Kurs von gestern umrechnen.
     */
    const val QUOTE_MAX_AGE_MILLIS = 15 * 60_000L

    /**
     * [price] nur, wenn seine Abrufzeit [time] höchstens [QUOTE_MAX_AGE_MILLIS] vor [now] liegt; älter oder ohne Zeit
     * null — dann gibt es keinen Faktor (≈ fehlt, Alarm in fremder Währung prüft diesmal nicht).
     */
    fun freshQuotePrice(price: Double?, time: Long?, now: Long): Double? {
        if (price == null || time == null || time <= 0L) return null
        return price.takeIf { now - time <= QUOTE_MAX_AGE_MILLIS }
    }

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
     * @param code Kürzel der Quote-Währung (nur bei USD: USD ist immer genau 1, USDT siehe [stableToUsd])
     * @param usdtUsd Marktkurs USDT/USD für USDT, andere Stablecoins und Krypto (über USDT bewertet); null = 1
     * @return null, wenn der nötige Kurs fehlt oder ungültig ist
     */
    fun quoteToUsd(
        kind: QuoteKind,
        usdToQuote: Double?,
        cryptoUsdt: Double?,
        code: String? = null,
        usdtUsd: Double? = null,
    ): Double? = when (kind) {
        QuoteKind.USD -> stableToUsd(code, cryptoUsdt, usdtUsd)
        QuoteKind.FIAT -> usdToQuote?.takeIf { it.isValidRate() }?.let { 1.0 / it }
        QuoteKind.CRYPTO -> cryptoUsdt?.takeIf { it.isValidRate() }?.let { it * usdtFactor(usdtUsd) }
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

    /**
     * Devisenkurs mit Abrufzeit (Kurs, Epoch-ms): eigener Eintrag [own], sonst — wenn [deriveBgn] (BGN ab der
     * Euro-Einführung, [deriveBgnFromEur]) — BGN aus dem EUR-Eintrag [eur] mit DESSEN Abrufzeit: ein alter EUR-Kurs ergibt
     * einen ebenso alten BGN-Kurs (Hinweis mit Uhrzeit, [staleFxTime]), nie einen scheinbar frischen. null = keiner.
     */
    fun fxWithTime(own: Pair<Double, Long>?, eur: Pair<Double, Long>?, deriveBgn: Boolean): Pair<Double, Long>? {
        if (own != null) return own
        if (!deriveBgn || eur == null) return null
        return bgnFromEur(eur.first)?.let { it to eur.second }
    }

    /** Wandelt einen Betrag um; null ohne gültigen Faktor. */
    fun convert(value: Double, rate: Double?): Double? =
        rate?.takeIf { it.isValidRate() }?.let { value * it }

    private fun Double.isValidRate() = !isNaN() && !isInfinite() && this > 0.0
}
