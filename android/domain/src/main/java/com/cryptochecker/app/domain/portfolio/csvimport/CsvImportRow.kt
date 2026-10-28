package com.cryptochecker.app.domain.portfolio.csvimport

import java.math.BigDecimal

/**
 * Neutrale Art einer importierten Zeile — vor der Zuordnung zu den Buchungsarten des Portfolios
 * (Zuordnung erst bei der Integration: BUY/SELL → Kauf/Verkauf, WITHDRAWAL → Auszahlung/Transfer,
 * DEPOSIT → Einzahlung, REWARD → Belohnung, FEE → Gebühr, SWAP → Tausch, TRANSFER → Umbuchung: eigene Mittel zurück
 * aus Staking/Earn, «Unstaking», «Redeem» — kein Gewinn, keine Belohnung). Gespiegelt in `CsvImportRow.swift`.
 */
enum class CsvImportKind { BUY, SELL, WITHDRAWAL, DEPOSIT, REWARD, FEE, SWAP, TRANSFER }

/**
 * Reihenfolge von Tag und Monat in Daten wie «03/04/2024» (nur Trenner «/» und «-»; «03.04.2024» ist immer Tag zuerst).
 * Erkannt je Datei ([CsvImportParser.detectDateOrder]); nur mehrdeutige Daten → Vorschau fragt.
 */
enum class CsvDateOrder { DAY_FIRST, MONTH_FIRST }

/**
 * Erkanntes Dateiformat; UNKNOWN = keine bekannte Kopfzeile gefunden. Der Name steht in Schlüsseln und Importläufen
 * (`BINANCE|…`) — neue Mitglieder nur anhängen bzw. vor GENERIC einfügen, nie umbenennen.
 * [exchangeName]: Name der Börse bzw. des Anbieters (Marke, nicht übersetzt) — Vorschau, Liste «Importe» und Herkunft
 * einer Umbuchung ([CsvTransferMatch]); null = Herkunft unbekannt (eigene CSV).
 */
enum class CsvImportFormat(val exchangeName: String?) {
    RELAI("Relai"),
    SWISSQUOTE("Swissquote"),
    KRAKEN("Kraken"),
    BINANCE("Binance"),
    COINBASE("Coinbase"),
    BITPANDA("Bitpanda"),
    GENERIC(null),
    UNKNOWN(null),
}

/**
 * Warum eine Zeile nicht übernommen wurde (für die Vorschau). [FEE_EXCEEDS_AMOUNT]: Gebühr im Coin ≥ erhaltene Menge;
 * [TWO_FEES]: Tausch mit Gebühr auf beiden Seiten in verschiedenen Einheiten (nicht still eine verwerfen)
 * ([CsvImport.feeConsumesAmount]) — wie das Erfassen-Blatt nicht speicherbar. [INTERNAL_MOVE]: Verschiebung zwischen
 * Konten derselben Börse (Spot ↔ Earn/Funding, Coinbase ↔ Coinbase Pro) — der Bestand bleibt gleich, nichts zu buchen.
 * [POSSIBLE_DUPLICATE]: fast gleich wie ein schon importiertes Geschäft (anderer Binance-Export,
 * [CsvImportBinance.possibleDuplicates]) — lieber prüfen lassen als doppelt zählen. [AMBIGUOUS_GROUP]: mehrere Geschäfte
 * in derselben Sekunde, die sich nicht eindeutig trennen lassen (Binance: mehrere Coins je Seite, z. B. Kleinbeträge in
 * BNB) — von Hand erfassen. [DUPLICATE_TXID_DIFFERENT]: Kraken-Zeile mit schon gesehener txid, aber anderer Menge — nicht
 * still verwerfen, von Hand prüfen (gleiche Wiederholungen fallen weiterhin still weg). [STAKING_UNPAIRED]: Kraken-Ein-
 * oder Auszahlung einer Staking-Variante («ETH2.S», «DOT.S») ohne Gegenbuchung — Bestand prüfen. [AMOUNT_TOO_SMALL]:
 * Menge rundet auf den gespeicherten 18 Nachkommastellen auf 0; [INVALID_SWAP]: Tausch ohne gültigen erhaltenen Coin
 * bzw. ohne erhaltene Menge — beide würden beim Speichern abgelehnt ([CsvImportMapping.unsavableReason]). Nur anhängen
 * (Name steht in Fixtures).
 */
enum class CsvImportSkipReason {
    UNSUPPORTED_KIND, NOT_CRYPTO, BAD_NUMBER, BAD_DATE, MISSING_FIELD, FEE_EXCEEDS_AMOUNT, TWO_FEES, INTERNAL_MOVE, POSSIBLE_DUPLICATE,
    AMBIGUOUS_GROUP, DUPLICATE_TXID_DIFFERENT, STAKING_UNPAIRED, AMOUNT_TOO_SMALL, INVALID_SWAP,
}

/** Übersprungene Zeile; [line] = Zeilennummer in der Datei (1 = erste Zeile). */
data class CsvImportSkip(val line: Int, val reason: CsvImportSkipReason)

/**
 * Eine importierte Buchung.
 * @param line Zeilennummer in der Datei (1-basiert, Kopfzeile mitgezählt)
 * @param rawKind Art wie in der Datei («Buy», «Savings plan», «Kauf»)
 * @param coin Kürzel gross («BTC»)
 * @param amount Menge in Coin-Einheiten, exakt, immer > 0 (Sats bereits in BTC umgerechnet)
 * @param price Preis je Coin in [quote]; null = unbekannt (z. B. Auszahlung)
 * @param quote Währung des Preises («CHF», «EUR», «USD»)
 * @param fee Gebühr > 0 oder null; [feeUnit] = Währung oder Coin der Gebühr
 * @param timeMillis UTC; Ortszeit der Datei (ohne Versatz) in [CsvImportResult.zone]
 * @param externalId Kennung aus der Datei (Relai «Operation ID», Swissquote «Order #»)
 * @param key Schlüssel gegen doppelten Import (siehe [CsvImport.key])
 * @param toCoin Tausch ([CsvImportKind.SWAP]): erhaltener Coin (gross); [coin]/[amount] = abgegeben
 * @param toAmount Tausch: erhaltene Menge (> 0)
 * @param swapValue Tausch: Wert in [quote] (Fiat/USD aus der Datei); null = unbekannt
 * @param localTime Kalenderzeit der Datei (als wäre sie UTC), wenn sie ohne Versatz steht — Schlüssel unabhängig
 *   von der gewählten Zeitzone ([CsvImport.key]); null = Zeit mit Versatz/«Z»
 * @param stableSwap Kauf/Verkauf gegen einen Stablecoin, als Tausch gebucht ([CsvImport.STABLES]) — Hinweis in der Vorschau
 */
data class CsvImportRow(
    val line: Int,
    val kind: CsvImportKind,
    val rawKind: String,
    val coin: String,
    val amount: BigDecimal,
    val price: BigDecimal?,
    val quote: String?,
    val fee: BigDecimal?,
    val feeUnit: String?,
    val timeMillis: Long,
    val externalId: String?,
    val key: String,
    val toCoin: String? = null,
    val toAmount: BigDecimal? = null,
    val swapValue: BigDecimal? = null,
    val localTime: Long? = null,
    val stableSwap: Boolean = false,
)

data class CsvImportResult(
    val format: CsvImportFormat,
    val rows: List<CsvImportRow>,
    val skipped: List<CsvImportSkip>,
    /** Zeitzone der Zeiten ohne Versatz/«Z» ([CsvImportParser.zoneFor]). */
    val zone: String = CsvImportParser.DEFAULT_ZONE,
    /** Mindestens eine gelesene Zeit ohne Versatz — nur dann zählt [zone] (Vorschau zeigt sie mit Auswahl). */
    val localTimes: Boolean = false,
    /** Gelesene Reihenfolge Tag/Monat ([CsvDateOrder]). */
    val dateOrder: CsvDateOrder = CsvDateOrder.DAY_FIRST,
    /** Nur mehrdeutige Daten («03/04/2024»): Vorschau bietet «Tag/Monat» / «Monat/Tag» an ([dateOrder] = Auswahl). */
    val dateOrderAmbiguous: Boolean = false,
    /**
     * Datei verrät kein Dezimalzeichen, enthält aber eine mehrdeutige Zahl («42,000»): diese (wie in der Datei); die
     * Vorschau fragt «42000 oder 42?» ([decimalMark] = gelesenes Dezimalzeichen). null = eindeutig.
     */
    val decimalSample: String? = null,
    /** Dezimalzeichen bei [decimalSample] ('.' oder ','); sonst null. */
    val decimalMark: Char? = null,
    /** Erkannte Spalten der Kopfzeile (Name wie in der Datei) — Vorschau. */
    val columns: List<String> = emptyList(),
    /**
     * Zeitzone der Tageskurse (Devisen) = Zeitzone des Geräts, wie der Steuer-Export (`TaxExport.localDate`) — sonst
     * fiele ein Geschäft nahe Mitternacht beim Import auf einen anderen Kurstag als im Export.
     */
    val fxZone: String = CsvImportParser.DEFAULT_ZONE,
    /** Zu viele Zellen je Zeile oder Datensätze ([CsvImportParser.MAX_CELLS_PER_ROW], [CsvImportParser.MAX_ROWS]). */
    val tooLarge: Boolean = false,
) {
    /** Vorschau fragt nach dem Dezimalzeichen ([decimalSample]). */
    val decimalAmbiguous: Boolean get() = decimalSample != null
}

/** Aufteilung für die Vorschau: neu vs. bereits importiert. */
data class CsvImportSplit(val fresh: List<CsvImportRow>, val duplicates: List<CsvImportRow>)

/**
 * Zeile mit Preis in USDT (Einheit des Portfolios).
 * @param priceUsdt Preis je Coin in USDT; null ohne Preis oder ohne Tageskurs
 * @param feeUsdt Gebühr in USDT, wenn sie in der Preiswährung oder USD/USDT lautet; sonst null
 *   (Gebühr in Coin bleibt in [CsvImportRow.fee]/[CsvImportRow.feeUnit])
 * @param needsFx Tageskurs für [CsvImportRow.quote] (oder Gebührenwährung) fehlt
 * @param missingPrice Kauf/Verkauf ohne Preis in der Datei
 * @param swapValueUsdt Tausch: Wert in USDT; null = unbekannt (kein Wert in der Datei oder kein Tageskurs)
 * @param usdtApproximate USD/Fiat mit USDT = 1 USD umgerechnet, weil der USDT/USD-Tagesschluss DES Tags fehlt
 *   (Näherung wie `TaxExport.usdtApproximateOn` — die Vorschau nennt die Zeile)
 */
data class CsvImportPriced(
    val row: CsvImportRow,
    val priceUsdt: BigDecimal?,
    val feeUsdt: BigDecimal?,
    val needsFx: Boolean,
    val missingPrice: Boolean,
    val swapValueUsdt: BigDecimal? = null,
    val usdtApproximate: Boolean = false,
)
