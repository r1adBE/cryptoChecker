// Datentypen des Stichtag-Exports ([CutoffExport]): Bestand, Zeilen, Tagesschlüsse, Texte und CSV-Format.
package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal

/** Bestand eines Coins am Stichtag, exakt aus der Buchhaltung (z. B. 0.00000001 BTC). */
data class CutoffHolding(
    val coin: String,
    val amount: BigDecimal,
) {
    /** Aus einem Double über die kürzeste Darstellung (0.1 → 0.1), z. B. für Tests. */
    constructor(coin: String, amount: Double) : this(coin, PortfolioDecimal.of(amount) ?: BigDecimal.ZERO)
}

/**
 * Eine Zeile des Stichtag-Exports, alle Zahlen exakt (gerundet wird erst im Text). Leere Werte = nicht bekannt.
 */
data class CutoffRow(
    val coin: String,
    val amount: BigDecimal,
    /** Tagesschlusskurs in USDT; null = kein Kurs. */
    val priceUsdt: BigDecimal?,
    /** [amount] × [priceUsdt] (34 gültige Stellen). */
    val valueUsdt: BigDecimal?,
    /** USD → Zielwährung; null = kein Devisenkurs. */
    val rate: BigDecimal?,
    /** [valueUsdt] × [rate] (34 gültige Stellen). */
    val valueTarget: BigDecimal?,
    val noPrice: Boolean,
    val noFx: Boolean,
    /** Kurs nicht als derselbe Token bestätigt ([PricePlausibility.acceptSourceClose]) — Feld leer. */
    val unchecked: Boolean = false,
    /** Guthaben einer beobachteten Adresse ([CutoffExport.addressRows]) — eigene Zeile «aus Adresse – ohne Kaufpreis». */
    val fromAddress: Boolean = false,
    /**
     * Adresse am Stichtag (noch) nicht beobachtet: ihr Guthaben fehlt ([AddressBalancesAt.unobserved]); ohne
     * beobachteten Teil steht keine Menge da.
     */
    val addressUnobserved: Boolean = false,
)

/**
 * Tagesschlüsse des Stichtags: [prices] Coin → Kurs in USDT; [unchecked] = Coins, deren Kurs mangels
 * passendem aktuellem Kurs nicht als derselbe Token bestätigt werden konnte (Hinweis «ungeprüft»).
 */
data class CutoffCloses(
    /** Exakt aus der kürzesten Darstellung des Quellkurses ([PortfolioDecimal.of]). */
    val prices: Map<String, BigDecimal> = emptyMap(),
    val unchecked: Set<String> = emptySet(),
)

/** Übersetzte Texte für die CSV-Datei (Kopfzeile, Hinweise); kommen aus den Ressourcen. */
data class CutoffCsvTexts(
    val coin: String,
    val amount: String,
    val priceUsdt: String,
    val valueUsdt: String,
    /** Bereits mit der Zielwährung, z. B. «Kurs USD→CHF». */
    val rate: String,
    /** Bereits mit der Zielwährung, z. B. «Wert CHF». */
    val valueTarget: String,
    val note: String,
    val total: String,
    val noPrice: String,
    val noFx: String,
    /** «ungeprüft (kein passender Kurs)». */
    val unchecked: String,
    val incomplete: String,
    /** Kommentarzeile mit %1$s = Stichtag, %2$s = Währung, %3$s = Datum des Devisenkurses. */
    val commentFormat: String,
    /** Hinweis ESTV-Kursliste, %1$s = Jahr, %2$s = Link ([EstvKursliste]); null = kein Hinweis. */
    val estvNoteFormat: String? = null,
    /** Sprache des ESTV-Links (z. B. «de»). */
    val estvLanguage: String = "en",
    /** Hinweis einer Adress-Zeile, z. B. «aus Adresse – ohne Kaufpreis». */
    val fromAddress: String = "",
    /** Hinweis, wenn eine Adresse am Stichtag nicht beobachtet war (Guthaben fehlt). */
    val addressUnobserved: String = "",
    /** Zeile «USDT = 1 angenommen» (kein USDT/USD-Kurs am Stichtag, [CutoffExport.csv] `usdtApproximate`); leer = keine. */
    val usdtAssumed: String = "",
    /** Zeile «Adressen nicht lesbar – Guthaben fehlen» ([CutoffExport.csv] `addressesUnreadable`); leer = keine. */
    val addressesUnreadable: String = "",
    /** Zeile «Adressen mit Einstand – Umbuchungen evtl. doppelt/fehlend» ([CutoffExport.csv] `addressLots`); leer = keine. */
    val addressLots: String = "",
)

/**
 * Zelle einer CSV-Zeile: Text (gegen Formel-Injektion geschützt, [CutoffExport.escape]) oder Zahl ([numeric], vom
 * Zahlenformat der App — bleibt unverändert, damit «-12.50» eine Zahl bleibt).
 */
data class CsvCell(val text: String, val numeric: Boolean = false)

/**
 * Trenn- und Dezimalzeichen der CSV, so dass Excel der Region die Datei richtig aufteilt
 * und die Zahlen als Zahlen liest.
 */
data class CutoffCsvFormat(val separator: Char, val decimal: Char) {
    companion object {
        /** «;» + «.» (z. B. Excel Schweiz). */
        val SEMICOLON_POINT = CutoffCsvFormat(';', '.')
        val SEMICOLON_COMMA = CutoffCsvFormat(';', ',')
        val COMMA_POINT = CutoffCsvFormat(',', '.')

        /**
         * Excel-Konvention je Region ([region] = Ländercode des Geräts, [decimalSeparator] =
         * Dezimalzeichen der Zahleneingabe): Schweiz/Liechtenstein trennen in Excel mit «;»,
         * auch mit Dezimalpunkt (de-CH «;» + «.»); sonst Dezimalkomma → «;» + «,»,
         * Dezimalpunkt (und anderes) → «,» + «.».
         */
        fun forLocale(region: String, decimalSeparator: Char): CutoffCsvFormat {
            val comma = decimalSeparator == ','
            return when {
                region.uppercase() in SEMICOLON_REGIONS -> if (comma) SEMICOLON_COMMA else SEMICOLON_POINT
                comma -> SEMICOLON_COMMA
                else -> COMMA_POINT
            }
        }

        private val SEMICOLON_REGIONS = setOf("CH", "LI")
    }
}
