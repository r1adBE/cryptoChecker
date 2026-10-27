package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal
import java.math.MathContext

/**
 * Exakte Dezimalzahlen für die Buchhaltung des Portfolios (Mengen, Kaufpreise, Gebühren,
 * Einstand, realisierter Gewinn). Ein Bitcoin hat 8 Nachkommastellen (1 Satoshi =
 * 0.00000001 BTC), Ethereum-Tokens bis 18 (1 Wei) — mit Gleitkomma (Double) summieren sich
 * Rundungsfehler («0.1 + 0.2 = 0.30000000000000004»), mit [BigDecimal] nicht.
 *
 * Exakt sind Addition, Subtraktion und Multiplikation; Teilungen (Ø-Kaufpreis, anteiliger
 * Verkauf bei Bestand ohne Kaufpreis) rechnen mit 34 gültigen Stellen ([MC]) — iOS rundet seine
 * `Decimal`-Teilungen auf dieselben 34 Stellen (`PortfolioDecimal.divide`). Börsenkurse kommen als
 * Double an; Wert und unrealisierter Gewinn rechnen exakt mit ihrer kürzesten Darstellung ([of]).
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
     * Gespeicherter Text («0.00000001», auch «1.0E-8» aus älteren Ständen) → Dezimal; ungültig = null.
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

    /** Betrag so klein, dass er nur Rest aus einer Teilung sein kann. */
    fun isDust(value: BigDecimal): Boolean = value.abs() <= DUST
}
