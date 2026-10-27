package com.cryptochecker.app.domain.portfolio.csvimport

import java.math.BigDecimal

/**
 * Liest CSV-Exporte von Relai und Swissquote (und Dateien mit ähnlichen Spaltennamen) in
 * [CsvImportRow]; die Börsen-Exporte von Kraken, Binance, Coinbase und Bitpanda erkennt [detect] an ihren Spalten und
 * liest je ein eigener Leser ([CsvImportKraken], [CsvImportBinance], [CsvImportCoinbase], [CsvImportBitpanda]).
 * Reines Kotlin, gespiegelt in `CsvImportParser.swift`.
 *
 * Robust gegenüber Exporten aus verschiedenen Versionen/Sprachen:
 *  - BOM, CRLF, Anführungszeichen (auch mit Zeilenumbruch), Trenner «;», «,» oder Tab
 *  - Spalten werden über ihre Namen erkannt (Reihenfolge egal, EN/DE/FR), Vorspann-Zeilen vor der
 *    Kopfzeile werden übersprungen (Kopfzeile in den ersten 10 Zeilen)
 *  - Zahlen mit «.» oder «,» als Dezimalzeichen, Tausender «'» (1'234.50), Leerzeichen, «.»/«,»
 *  - Sats-Spalten werden in BTC umgerechnet
 *  - Zeit mit Versatz/«Z» = so; ohne Versatz in der Zeitzone [zoneFor]: Swissquote exportiert Schweizer Ortszeit
 *    (Beispiel-Export: US-Aktie gekauft um 15:30:02 = Börsenöffnung New York 09:30 EDT in MESZ) → Europe/Zurich;
 *    sonst die Zeitzone des Geräts. Die Vorschau zeigt sie und lässt sie wählen (Gerät / Europe/Zurich / UTC).
 *  - Tausch («Tausch», «Swap», «Convert», «Échange» → [CsvImportKind.SWAP]): beide Seiten in EINER Zeile (Spalten
 *    «To Coin»/«To Amount» o. ä.) oder in ZWEI aufeinanderfolgenden Tausch-Zeilen mit gleicher Kennung (ohne Kennung:
 *    gleicher Zeit) — abgegeben ist die Seite mit negativer Menge (sonst die mit «Sell/Verkauf/Out/From …» in der
 *    Art). Wert = Fiat-Betrag der abgegebenen (sonst erhaltenen) Seite, ersatzweise Preis × Menge, nur in Geld
 *    ([CsvImport.MONEY]); sonst unbekannt. Eine Seite ohne Gegenstück wird übersprungen ([CsvImportSkipReason.MISSING_FIELD]).
 *  - Kauf/Verkauf gegen einen Stablecoin ([CsvImport.STABLES], Preiswährung bzw. Paar «BTC/USDT») ist Krypto gegen Krypto
 *    → Tausch ([stableSwap]): Verkauf X gegen S = Tausch X → S, Kauf X mit S = Tausch S → X; S-Menge = Fiat-/Quote-Betrag
 *    der Datei, sonst Preis × Menge. Wert nur bei USDT (= S-Menge, [CsvImport.stableSwapValue]), sonst unbekannt.
 *    Gebühr bleibt in ihrer Einheit. Ohne Preis bleibt die Zeile Kauf/Verkauf (ohne Preis, Vorschau nennt sie).
 *    Frühere Schlüssel (noch Kauf/Verkauf) stehen in [CsvImportRow.legacyKeys].
 *  - Kauf/Verkauf gegen einen Krypto-Coin ([CsvImport.isCryptoQuote], Paar «ETH/BTC») ebenso → Tausch (Kauf ETH = BTC → ETH,
 *    Verkauf ETH = ETH → BTC; BTC-Menge = Quote-Betrag, sonst Preis × Menge), Wert unbekannt (Tagesschluss beim Rechnen),
 *    ohne Stablecoin-Hinweis; frühere Schlüssel (Kauf/Verkauf ohne Preis in USDT) in [CsvImportRow.legacyKeys].
 */
object CsvImportParser {

    /** Zeitzone ohne Angabe des Geräts (Tests) und der Swissquote-Exporte. */
    const val DEFAULT_ZONE = "Europe/Zurich"

    /** Swissquote: Zeiten in Schweizer Ortszeit. */
    const val SWISS_ZONE = "Europe/Zurich"

    /** Kraken-Ledger und Binance-Exporte («UTC_Time», «Date(UTC)»): Zeiten in UTC. */
    const val KRAKEN_ZONE = "UTC"

    /**
     * Zeitzone der Zeiten ohne Versatz: gewählt ([override]) vor bekanntem Format (Swissquote = [SWISS_ZONE], Kraken und
     * Binance = UTC) vor der Zeitzone des Geräts ([deviceZone]). Coinbase und Bitpanda schreiben einen Versatz.
     */
    fun zoneFor(format: CsvImportFormat, deviceZone: String, override: String? = null): String = when {
        override != null -> override
        format == CsvImportFormat.SWISSQUOTE -> SWISS_ZONE
        format == CsvImportFormat.KRAKEN || format == CsvImportFormat.BINANCE -> KRAKEN_ZONE
        else -> deviceZone
    }

    /** Kopfzeile wird in so vielen ersten Datensätzen gesucht. */
    private const val HEADER_SEARCH = 10

    /**
     * Höchstens so viele Zellen je Zeile und Datensätze je Datei ([tokenizeOrNull]); mehr = Datei zu gross, gleich wie
     * eine Datei über 16 MB «nicht lesbar» (wie iOS). Echte Exporte haben unter 40 Spalten; ohne Grenze baute eine Zeile
     * aus lauter Trennern Millionen leere Zellen.
     */
    const val MAX_CELLS_PER_ROW = 200
    const val MAX_ROWS = 200_000

    val SATS_PER_BTC = BigDecimal(100_000_000)

    /** Spaltenrollen. */
    enum class Role { DATE, KIND, COIN, AMOUNT, AMOUNT_SATS, PRICE, FIAT_AMOUNT, QUOTE, NET_CURRENCY, PAIR, FEE, FEE_UNIT, ID, ISIN, TO_COIN, TO_AMOUNT }

    /** Normalisierter Spaltenname → Rolle (exakt). */
    private val ALIASES: Map<String, Role> = buildMap {
        fun add(role: Role, vararg names: String) = names.forEach { put(it, role) }
        add(Role.DATE, "date", "datum", "timestamp", "time", "datetime", "zeitpunkt", "dateheure")
        // «Date(UTC)», «Time (UTC)», «UTC_Time»: Zeiten in UTC ([Header.utcDate])
        add(Role.DATE, "dateutc", "timeutc", "datetimeutc", "timestamputc", "utctime", "utcdate")
        add(Role.KIND, "transactiontype", "type", "transaction", "transaktion", "transaktionen", "typ", "art", "kind", "transactiontyp", "transaktionstyp")
        add(Role.COIN, "symbol", "coin", "asset", "cryptocurrency", "crypto")
        add(Role.AMOUNT, "btcamount", "amountbtc", "quantity", "amount", "anzahl", "menge", "quantite", "btc")
        add(Role.AMOUNT_SATS, "satsamount", "amountsats", "sats", "satoshis", "satamount", "amountsat")
        add(Role.PRICE, "btcprice", "pricebtc", "unitprice", "price", "stueckpreis", "kurs", "preis", "prixunitaire", "prix")
        add(Role.FIAT_AMOUNT, "fiatamountexclfees", "fiatamount", "fiatamountexclfee")
        add(Role.QUOTE, "fiatcurrency", "quotecurrency", "currency", "waehrung", "devise")
        add(Role.NET_CURRENCY, "netamountcurrency", "currencynetamount", "waehrungnettobetrag", "devisemontantnet")
        add(Role.PAIR, "currencypair", "pair", "waehrungspaar", "market")
        add(Role.FEE, "fee", "fees", "costs", "kosten", "gebuehr", "gebuehren", "frais", "commission")
        add(Role.FEE_UNIT, "feecurrency", "feeunit", "feeasset", "gebuehrenwaehrung")
        add(Role.ID, "operationid", "orderid", "order", "ordernr", "auftrag", "auftragnr", "ordre", "transactionid", "id", "txid", "reference")
        add(Role.ISIN, "isin")
        // Tausch in einer Zeile: erhaltene Seite
        add(Role.TO_COIN, "tocoin", "toasset", "tocurrency", "receivedcoin", "receivedasset", "receivedcurrency", "zielcoin", "erhaltenercoin")
        add(Role.TO_AMOUNT, "toamount", "toquantity", "receivedamount", "receivedquantity", "zielmenge", "erhaltenemenge")
    }

    data class Record(val line: Int, val cells: List<String>)

    class Header(val cells: List<String>) {
        val names: List<String> = cells.map { normalize(it) }
        val index: Map<Role, Int> = buildMap {
            names.forEachIndexed { i, n -> ALIASES[n]?.let { role -> if (role !in this) put(role, i) } }
        }
        fun has(role: Role) = role in index

        /** Datumsspalte nennt UTC («Date(UTC)»): ohne Wahl gilt UTC statt der Zeitzone des Geräts. */
        val utcDate: Boolean get() = index[Role.DATE]?.let { "utc" in names[it] } == true

        /** Erkannte Spalten (Name wie in der Datei, Reihenfolge der Datei) — für die Vorschau. */
        fun recognized(): List<String> = index.values.sorted().map { cells[it] }
    }

    // ---------- Einstieg ----------

    /**
     * Datei lesen; unbekannte Kopfzeile = [CsvImportFormat.UNKNOWN] ohne Zeilen. [deviceZone] = Zeitzone des Geräts
     * (App: `ZoneId.systemDefault()`), [zone] = in der Vorschau gewählt (null = Regel [zoneFor]); neu zuordnen = den Text
     * im Speicher mit anderer [zone] noch einmal lesen (die Datei wird nicht neu gelesen). [dateOrder] = in der Vorschau
     * gewählte Reihenfolge Tag/Monat; gilt nur bei mehrdeutigen Daten ([CsvImportResult.dateOrderAmbiguous]).
     * [decimalMark] = in der Vorschau gewähltes Dezimalzeichen ('.' oder ','); gilt nur, wenn die Datei keines verrät
     * ([CsvImportResult.decimalAmbiguous]).
     */
    fun parse(
        text: String,
        deviceZone: String = DEFAULT_ZONE,
        zone: String? = null,
        dateOrder: CsvDateOrder? = null,
        decimalMark: Char? = null,
    ): CsvImportResult {
        val clean = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val firstLine = clean.split('\n').firstOrNull { it.isNotBlank() }
            ?: return CsvImportResult(CsvImportFormat.UNKNOWN, emptyList(), emptyList())
        // Trenner nach Häufigkeit in der ersten Zeile; bei Gleichstand «;» vor «,» vor Tab
        val candidates = listOf(';', ',', '\t')
        val delimiters = candidates.indices.sortedWith(
            compareByDescending<Int> { i -> firstLine.count { it == candidates[i] } }.thenBy { it }
        ).map { candidates[it] }
        for (d in delimiters) {
            // Für die Erkennung nur die ersten Zeilen zerlegen — eine grosse Datei mit falschem Trenner kostete sonst je
            // Kandidat einen ganzen Durchlauf; zerlegt wird sie erst mit dem passenden Trenner ganz
            // Über den Grenzen (Zellen je Zeile, Datensätze): wie eine zu grosse Datei nicht lesbar
            val head = tokenizeOrNull(clean, d, maxRecords = HEADER_SEARCH) ?: return tooLarge()
            for (h in head.indices) {
                val header = Header(head[h].cells)
                val format = detect(header)
                if (format != CsvImportFormat.UNKNOWN) {
                    val records = tokenizeOrNull(clean, d) ?: return tooLarge()
                    val override = zone ?: "UTC".takeIf { header.utcDate }
                    return build(
                        format, header, records.subList(h + 1, records.size), zoneFor(format, deviceZone, override), deviceZone,
                        dateOrder, decimalMark,
                    ).copy(columns = header.recognized(), fxZone = deviceZone)
                }
            }
        }
        return CsvImportResult(CsvImportFormat.UNKNOWN, emptyList(), emptyList())
    }

    /** Datei über [MAX_CELLS_PER_ROW]/[MAX_ROWS]: ohne Zeilen, die Vorschau meldet sie als nicht lesbar. */
    private fun tooLarge() = CsvImportResult(CsvImportFormat.UNKNOWN, emptyList(), emptyList(), tooLarge = true)

    /**
     * Bytes der Datei → Text: gültiges UTF-8, sonst Windows-1252 (Excel unter Windows speichert CSV so: «Gebühr» als
     * 0xFC). BOM am Anfang weg. Spiegel: `decode` in `CsvImportParser.swift`.
     */
    fun decode(bytes: ByteArray): String {
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            decodeCp1252(bytes)
        }
        return text.removePrefix("\uFEFF")
    }

    /**
     * Wert von [sample] mit Dezimalzeichen [mark] ('.' oder ','), für die Auswahl der Vorschau («42,000 = 42000» /
     * «42,000 = 42»); null = nicht lesbar.
     */
    fun sampleValue(sample: String, mark: Char): BigDecimal? = parseNumber(sample, mark)
}
