package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal
import java.math.MathContext

/**
 * Exakte Dezimalzahlen für die Buchhaltung des Portfolios (Mengen, Kaufpreise, Gebühren,
 * Einstand, realisierter Gewinn). Ein Bitcoin hat 8 Nachkommastellen (1 Satoshi =
 * 0.00000001 BTC), Ethereum-Tokens bis 18 (1 Wei) — mit Gleitkomma (Double) summieren sich
 * Rundungsfehler («0.1 + 0.2 = 0.30000000000000004»), mit [BigDecimal] nicht.
 *
 * Exakt sind Addition und Subtraktion; Teilungen (Ø-Kaufpreis, anteiliger Verkauf bei Bestand ohne Kaufpreis) und
 * Produkte (Menge × Preis, Wert × Kurs) rechnen mit 34 gültigen Stellen ([MC]), einmal gerundet — nie ein einfaches
 * `a * b`: iOS `Decimal` hielte nur 38 Stellen. iOS rechnet dafür exakt über die Mantissen
 * (`PortfolioDecimal+Exact.swift`). Börsenkurse kommen als Double an; Wert und unrealisierter Gewinn rechnen mit ihrer
 * kürzesten Darstellung ([of]).
 * Reines Kotlin, wie `PortfolioDecimal` (iOS, `Decimal`).
 */
object PortfolioDecimal {

    /** 34 gültige Stellen (IEEE 754 decimal128) für Teilungen. */
    val MC: MathContext = MathContext.DECIMAL128

    /** Darunter gilt eine Menge als null (kleiner als 1 Wei = 10⁻¹⁸; nur Rest aus Teilungen). */
    val DUST: BigDecimal = BigDecimal("1E-20")

    /** Höchstens so viele Nachkommastellen werden gespeichert (1 Wei). */
    const val MAX_SCALE = 18

    /** Double → Dezimal über die kürzeste Darstellung (0.1 → «0.1»); null für NaN/∞. */
    fun of(value: Double?): BigDecimal? =
        value?.takeIf { it.isFinite() }?.let { BigDecimal.valueOf(it) }

    /** Höchstens so viele Stellen vor dem Komma (|Wert| < 10³⁰) — sonst ungültig. */
    const val MAX_INTEGER_DIGITS = 30

    /** Höchstens so viele Nachkommastellen (ohne Nullen am Ende) beim Einlesen — sonst ungültig. */
    const val MAX_PARSE_SCALE = 40

    /**
     * Gespeicherter Text («0.00000001», auch «1.0E-8») → Dezimal; ungültig = null.
     * Absurde Grössen (z. B. «1E300000000» aus einer manipulierten Sicherung) sind ungültig: mehr als
     * [MAX_INTEGER_DIGITS] Stellen vor dem Komma oder mehr als [MAX_PARSE_SCALE] Nachkommastellen —
     * sonst sprengen toPlainString/setScale den Speicher. Gleich wie iOS (`PortfolioDecimal.parse`).
     */
    fun parse(text: String?): BigDecimal? {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return null
        val v = runCatching { BigDecimal(t) }.getOrNull() ?: return null
        return v.takeIf { withinLimits(it) }
    }

    /** Grösse im erlaubten Bereich (siehe [parse]); null ist immer erlaubt. */
    fun withinLimits(value: BigDecimal): Boolean {
        val s = value.stripTrailingZeros()
        if (s.signum() == 0) return true
        return s.precision() - s.scale() <= MAX_INTEGER_DIGITS && s.scale() <= MAX_PARSE_SCALE
    }

    /**
     * Anteilige Menge (Teil mit Kaufpreis bei einem Abgang) auf [MAX_SCALE] Nachkommastellen (halbe auf gerade).
     * So bleiben beide Teile Mengen mit höchstens 18 Stellen und «Teil ohne Preis = Rest − Teil mit Preis» ist
     * auch auf iOS exakt (`Decimal` hat nur 38 Stellen, sonst wäre der Bestand 1.000…0009 statt 1). Wie iOS.
     */
    fun amount(value: BigDecimal): BigDecimal =
        if (value.scale() > MAX_SCALE) value.setScale(MAX_SCALE, java.math.RoundingMode.HALF_EVEN) else value

    /**
     * Zum Speichern: ohne Exponent und Nullen am Ende, höchstens [MAX_SCALE] Stellen («0.00000001»). Gerundet wie
     * [amount] (halbe auf gerade): eine Rundungsregel für Rechnen, Prüfen vor dem Speichern und Speichern. Wie iOS.
     */
    fun text(value: BigDecimal): String {
        val v = amount(value)
        return if (v.signum() == 0) "0" else v.stripTrailingZeros().toPlainString()
    }

    /**
     * Preis zum Speichern (Kaufpreis, Preis einer Gebühr im dritten Coin): 34 gültige Stellen ([MC]), mindestens aber
     * [MAX_SCALE] Nachkommastellen (wie eine Menge) und höchstens [MAX_PARSE_SCALE] (Lesegrenze von [parse]), halbe auf
     * gerade. So bleibt der Preis winziger Coins (1.3717333…E-9 BABYDOGE) erhalten, statt auf 18 Nachkommastellen zu
     * schrumpfen oder unter 5E-19 zu 0 (= gratis) zu werden. Werte mit höchstens 18 Nachkommastellen bleiben unverändert.
     * Wie iOS.
     */
    fun price(value: BigDecimal): BigDecimal {
        val s = value.stripTrailingZeros()
        if (s.signum() == 0) return BigDecimal.ZERO
        // Nachkommastellen für 34 gültige Stellen: 34 − Stellen vor dem Komma (bei 0.00… negativ gezählt)
        val significant = MC.precision - (s.precision() - s.scale())
        val scale = minOf(MAX_PARSE_SCALE, maxOf(MAX_SCALE, significant))
        return if (s.scale() > scale) s.setScale(scale, java.math.RoundingMode.HALF_EVEN) else s
    }

    /** Preis als gespeicherter Text ([price]): ohne Exponent und Nullen am Ende. Wie iOS. */
    fun priceText(value: BigDecimal): String {
        val v = price(value)
        return if (v.signum() == 0) "0" else v.stripTrailingZeros().toPlainString()
    }

    /** Menge, Gebühr oder Wert bleibt nach dem Runden auf [MAX_SCALE] Nachkommastellen lesbar ([withinLimits]). */
    fun storableAmount(value: BigDecimal): Boolean = withinLimits(amount(value))

    /**
     * Preis bleibt nach [price] lesbar ([withinLimits]) und ein Preis > 0 wird nicht zu 0 (unter 5E-41: das wäre ein
     * Kauf zum Preis 0 statt eines unbekannten Preises).
     */
    fun storablePrice(value: BigDecimal): Boolean {
        val p = price(value)
        return withinLimits(p) && (value.signum() <= 0 || p.signum() > 0)
    }

    /** Betrag so klein, dass er nur Rest aus einer Teilung sein kann. */
    fun isDust(value: BigDecimal): Boolean = value.abs() <= DUST
}
