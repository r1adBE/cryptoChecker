package com.cryptochecker.app.domain.portfolio.csvimport

import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId

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
 *    Schlüssel bis Build 46 (noch Kauf/Verkauf) stehen in [CsvImportRow.legacyKeys].
 *  - Kauf/Verkauf gegen einen Krypto-Coin ([CsvImport.isCryptoQuote], Paar «ETH/BTC») ebenso → Tausch (Kauf ETH = BTC → ETH,
 *    Verkauf ETH = ETH → BTC; BTC-Menge = Quote-Betrag, sonst Preis × Menge), Wert unbekannt (Tagesschluss beim Rechnen),
 *    ohne Stablecoin-Hinweis; frühere Schlüssel (Kauf/Verkauf ohne Preis in USDT, bis Build 47) in [CsvImportRow.legacyKeys].
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

    private val SATS_PER_BTC = BigDecimal(100_000_000)

    /** Spaltenrollen. */
    internal enum class Role { DATE, KIND, COIN, AMOUNT, AMOUNT_SATS, PRICE, FIAT_AMOUNT, QUOTE, NET_CURRENCY, PAIR, FEE, FEE_UNIT, ID, ISIN, TO_COIN, TO_AMOUNT }

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

    internal data class Record(val line: Int, val cells: List<String>)

    internal class Header(val cells: List<String>) {
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
            val head = tokenize(clean, d, maxRecords = HEADER_SEARCH)
            for (h in head.indices) {
                val header = Header(head[h].cells)
                val format = detect(header)
                if (format != CsvImportFormat.UNKNOWN) {
                    val records = tokenize(clean, d)
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

    // ---------- Zeichensatz ----------

    /** Windows-1252 0x80–0x9F (nicht belegte Bytes bleiben ihr Codepunkt, wie WHATWG); Rest = Latin-1. */
    private val CP1252_HIGH = intArrayOf(
        0x20AC, 0x0081, 0x201A, 0x0192, 0x201E, 0x2026, 0x2020, 0x2021, 0x02C6, 0x2030, 0x0160, 0x2039, 0x0152, 0x008D, 0x017D, 0x008F,
        0x0090, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014, 0x02DC, 0x2122, 0x0161, 0x203A, 0x0153, 0x009D, 0x017E, 0x0178,
    )

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

    internal fun decodeCp1252(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            sb.append((if (v in 0x80..0x9F) CP1252_HIGH[v - 0x80] else v).toChar())
        }
        return sb.toString()
    }

    // ---------- Zerlegen ----------

    /** RFC-4180-Zerlegung («""» = «"», Umbruch in Anführungszeichen erlaubt); Zellen getrimmt, leere Zeilen weg. */
    internal fun tokenize(text: String, delimiter: Char, maxRecords: Int = Int.MAX_VALUE): List<Record> {
        val out = ArrayList<Record>()
        var cells = ArrayList<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var line = 1
        var startLine = 1
        fun endCell() {
            cells.add(cell.toString().trim())
            cell.setLength(0)
        }
        fun endRecord() {
            endCell()
            if (cells.any { it.isNotEmpty() }) out.add(Record(startLine, cells))
            cells = ArrayList()
        }
        var i = 0
        val n = text.length
        // Höchstens [maxRecords] Zeilen (Erkennung der Kopfzeile): danach nicht weiterlesen
        while (i < n && out.size < maxRecords) {
            val c = text[i]
            when {
                inQuotes && c == '"' && i + 1 < n && text[i + 1] == '"' -> { cell.append('"'); i++ }
                inQuotes && c == '"' -> inQuotes = false
                inQuotes -> { if (c == '\n') line++; cell.append(c) }
                c == '"' && cell.isBlank() -> { cell.setLength(0); inQuotes = true }
                c == delimiter -> endCell()
                c == '\n' -> { endRecord(); line++; startLine = line }
                else -> cell.append(c)
            }
            i++
        }
        if (out.size < maxRecords && (cell.isNotEmpty() || cells.isNotEmpty())) endRecord()
        return out
    }

    /** Spaltenname/Art vergleichbar machen: klein, Umlaute ausgeschrieben, nur a–z und 0–9. */
    internal fun normalize(text: String): String {
        val sb = StringBuilder()
        for (ch in text.lowercase()) {
            when (ch) {
                'ä' -> sb.append("ae")
                'ö' -> sb.append("oe")
                'ü' -> sb.append("ue")
                'ß' -> sb.append("ss")
                'é', 'è', 'ê', 'ë' -> sb.append('e')
                'à', 'â' -> sb.append('a')
                'ô' -> sb.append('o')
                'î', 'ï' -> sb.append('i')
                'ç' -> sb.append('c')
                else -> if (ch in 'a'..'z' || ch in '0'..'9') sb.append(ch)
            }
        }
        return sb.toString()
    }

    // ---------- Format ----------

    internal fun detect(h: Header): CsvImportFormat {
        val names = h.names.toSet()
        // Kraken «Ledgers»-Export: txid, refid, time, type, subtype, aclass, asset, (wallet,) amount, fee, balance
        if ("refid" in names && "txid" in names && "asset" in names && "amount" in names && ("aclass" in names || "balance" in names)) {
            return CsvImportFormat.KRAKEN
        }
        // Börsen-Exporte mit eigenem Aufbau vor der allgemeinen Erkennung (Coinbase hätte auch Datum/Art/Coin)
        if (CsvImportBinance.matches(names)) return CsvImportFormat.BINANCE
        if (CsvImportCoinbase.matches(names)) return CsvImportFormat.COINBASE
        if (CsvImportBitpanda.matches(names)) return CsvImportFormat.BITPANDA
        val hasAmount = h.has(Role.AMOUNT) || h.has(Role.AMOUNT_SATS)
        if (!h.has(Role.DATE) || !h.has(Role.KIND) || !hasAmount) return CsvImportFormat.UNKNOWN
        val relai = names.any { it in setOf("btcamount", "btcprice", "satsamount", "amountsats") } ||
            ("operationid" in names && "counterparty" in names)
        if (relai) return CsvImportFormat.RELAI
        val order = names.any { it in setOf("order", "auftrag", "ordre") }
        if (h.has(Role.ISIN) && order && h.has(Role.PRICE)) return CsvImportFormat.SWISSQUOTE
        if (h.has(Role.COIN)) return CsvImportFormat.GENERIC
        return CsvImportFormat.UNKNOWN
    }

    /** Wörter einer Art: wie [normalize], aber an allem ausser a–z/0–9 getrennt («Staking-Belohnung» → staking, belohnung). */
    internal fun words(text: String): List<String> {
        val sb = StringBuilder()
        for (ch in text) {
            val n = normalize(ch.toString())
            sb.append(n.ifEmpty { " " })
        }
        return sb.split(' ').filter { it.isNotEmpty() }
    }

    /**
     * Muster gegen [words]: «wort» genau, «wort*» Anfang, «*wort» Ende, «*wort*» enthalten (zusammengesetzte Wörter wie
     * «Depotgebühren»), «zwei wörter» nacheinander oder zusammengeschrieben («Transfer out», «TransferOut»).
     */
    private fun matches(words: List<String>, pattern: String): Boolean {
        if (' ' in pattern) {
            val parts = pattern.split(' ')
            if (words.any { it == parts.joinToString("") }) return true
            return (0..words.size - parts.size).any { i -> parts.indices.all { k -> words[i + k] == parts[k] } }
        }
        val core = pattern.trim('*')
        val head = pattern.startsWith('*')
        val tail = pattern.endsWith('*')
        return words.any { w ->
            when {
                head && tail -> w.contains(core)
                tail -> w.startsWith(core)
                head -> w.endsWith(core)
                else -> w == core
            }
        }
    }

    private val UNSTAKE = listOf("unstak*", "redeem*", "redemption*", "entstak*", "stake", "staked")
    private val STAKING = listOf("staking*", "stake", "staked")
    private val MOVE = listOf(
        "withdraw*", "*auszahlung*", "retrait*", "deposit*", "*einzahlung*", "depot", "transfer*", "send*", "receive*",
        "unlock*", "lock*",
    )
    private val SWAP = listOf("*tausch*", "swap*", "convert*", "conversion*", "konvert*", "echange*")
    private val SELL = listOf("sell*", "sold", "*verkauf*", "vente*", "vendu", "vendita")
    private val BUY = listOf(
        "buy*", "bought", "*kauf*", "achat*", "purchase*", "savingsplan*", "savings plan", "sparplan*", "dca", "recurring*",
        "acquisto",
    )
    private val REWARD = listOf(
        "reward*", "*belohnung*", "praemie*", "bonus*", "referral*", "cashback*", "airdrop*", "interest*", "interet*",
        "recompense*",
    )
    private val FEE = listOf("fee", "fees", "*gebuehr*", "frais")
    private val WITHDRAWAL = listOf(
        "withdraw*", "send*", "sent", "*auszahlung*", "transfer out", "retrait*", "outgoing", "*ausgang*", "envoi*",
    )
    private val DEPOSIT = listOf(
        "deposit*", "receive*", "*einzahlung*", "transfer in", "depot", "incoming", "*eingang*", "reception*",
    )

    /**
     * Art aus dem Text der Datei (Wörter statt Teiltext, feste Reihenfolge); null = nicht unterstützt (Dividende, Forex …):
     *  1. Eigene Mittel zurück aus Staking/Earn («Unstaking», «Redeem», «Staking withdrawal», «Auszahlung Staking») →
     *     [CsvImportKind.TRANSFER] (kein Gewinn, keine Belohnung); mit Belohnungswort bleibt es eine Belohnung.
     *  2. Tausch, Verkauf, Kauf wie bisher.
     *  3. Belohnung («Staking reward», «Staking-Belohnung», «Interest», «Airdrop», «Staking» allein).
     *  4. Gebühr, Auszahlung («Withdraw», «Send», «Retrait»), Einzahlung («Deposit», «Receive»).
     */
    fun classify(text: String): CsvImportKind? {
        val w = words(text)
        if (w.isEmpty()) return null
        fun any(patterns: List<String>) = patterns.any { matches(w, it) }
        val reward = any(REWARD)
        return when {
            !reward && (any(UNSTAKE) || (any(STAKING) && any(MOVE))) -> CsvImportKind.TRANSFER
            any(SWAP) -> CsvImportKind.SWAP
            any(SELL) -> CsvImportKind.SELL
            any(BUY) -> CsvImportKind.BUY
            reward || any(STAKING) -> CsvImportKind.REWARD
            any(FEE) -> CsvImportKind.FEE
            any(WITHDRAWAL) -> CsvImportKind.WITHDRAWAL
            any(DEPOSIT) -> CsvImportKind.DEPOSIT
            else -> null
        }
    }

    // ---------- Zeilen ----------

    private class Skip(val reason: CsvImportSkipReason) : Exception()

    private fun build(
        format: CsvImportFormat,
        h: Header,
        data: List<Record>,
        zone: String,
        deviceZone: String,
        chosenOrder: CsvDateOrder?,
        chosenMark: Char?,
    ): CsvImportResult {
        when (format) {
            CsvImportFormat.KRAKEN -> return CsvImportKraken.build(h.names, data, zone)
            CsvImportFormat.BINANCE -> return CsvImportBinance.build(h.names, data, zone)
            CsvImportFormat.COINBASE -> return CsvImportCoinbase.build(h.names, data, zone)
            CsvImportFormat.BITPANDA -> return CsvImportBitpanda.build(h.names, data, zone)
            else -> Unit
        }
        val dateIndex = h.index[Role.DATE]
        // Tag/Monat je Datei: Swissquote immer Tag zuerst; sonst aus den Daten, nur mehrdeutige → Auswahl der Vorschau
        val detected = if (format == CsvImportFormat.SWISSQUOTE || dateIndex == null) CsvDateOrder.DAY_FIRST
        else detectDateOrder(data.map { it.cells.getOrNull(dateIndex).orEmpty() })
        val ambiguous = detected == null
        val order = detected ?: chosenOrder ?: CsvDateOrder.DAY_FIRST
        val monthFirst = order == CsvDateOrder.MONTH_FIRST
        val numericRoles = listOf(Role.AMOUNT, Role.AMOUNT_SATS, Role.PRICE, Role.FIAT_AMOUNT, Role.FEE)
        val numericCells = data.flatMap { r -> numericRoles.mapNotNull { role -> h.index[role]?.let { r.cells.getOrNull(it) } } }
        val detectedMark = decimalHint(numericCells)
        // Datei verrät kein Dezimalzeichen und hat «42,000»: Vorschau fragt; ohne Wahl wie bisher das Zeichen der ersten
        // mehrdeutigen Zahl = Dezimalzeichen (gilt dann für die ganze Datei, wie angezeigt)
        val decimalSample = if (detectedMark == null) ambiguousNumber(numericCells) else null
        val hint = detectedMark ?: decimalSample?.let { sample ->
            chosenMark?.takeIf { it == '.' || it == ',' } ?: defaultMark(sample)
        }
        val parsed = ArrayList<Pair<CsvImportRow, Int>>()
        val skipped = ArrayList<CsvImportSkip>()
        // Zeile → ursprünglicher Kauf/Verkauf (vor dem Tausch gegen Stablecoin), für die Schlüssel bis Build 46
        val origins = HashMap<Int, CsvImportRow>()
        for (rec in data) {
            try {
                parsed.add(row(format, h, rec, hint, zone, monthFirst, origins))
            } catch (s: Skip) {
                skipped.add(CsvImportSkip(rec.line, s.reason))
            }
        }
        // Gebühr ≥ erhaltene Menge: übersprungen mit Grund (wie das Blatt nicht speicherbar)
        val drafts = pairSwapLegs(format, parsed, skipped).filter { r ->
            val consumes = CsvImport.feeConsumesAmount(r)
            if (consumes) skipped += CsvImportSkip(r.line, CsvImportSkipReason.FEE_EXCEEDS_AMOUNT)
            !consumes
        }
        skipped.sortBy { it.line }
        val keys = CsvImport.withOccurrence(drafts.map { it.key })
        // Frühere Schlüssel: Zeit in UTC je Zeitzone der Auswahl (bis Build 45) und Kauf/Verkauf gegen Stablecoin noch
        // als Kauf/Verkauf (bis Build 46) — ältere Importe weiter als Duplikat erkennen
        val old = drafts.map { r -> origins[r.line]?.takeIf { r.kind == CsvImportKind.SWAP } ?: r }
        val oldKeys = CsvImport.withOccurrence(old.map { it.key })
        val legacyZones = listOf(zone, deviceZone, SWISS_ZONE, "UTC").distinct()
        val seen = HashMap<String, Int>()
        val rows = drafts.mapIndexed { i, r ->
            val o = old[i]
            val n = (seen[o.key] ?: 0) + 1
            seen[o.key] = n
            val local = r.localTime
            val zoned = if (local == null) emptyList() else legacyZones.map { z ->
                val k = CsvImport.key(format, o.kind, o.coin, o.amount, o.price, toUtc(local, z), o.externalId)
                if (n == 1) k else "$k#$n"
            }
            val legacy = (listOf(oldKeys[i]) + zoned).distinct().filter { it != keys[i] }
            r.copy(key = keys[i], legacyKeys = legacy)
        }
        // Zählt die Zeitzone? Nur wenn eine lesbare Zeit ohne Versatz vorkommt
        val localTimes = dateIndex != null && data.any { r ->
            val t = r.cells.getOrNull(dateIndex).orEmpty()
            !hasOffset(t) && parseTime(t, zone, monthFirst) != null
        }
        return CsvImportResult(
            format, rows, skipped, zone, localTimes, order, ambiguous,
            decimalSample = decimalSample,
            decimalMark = hint.takeIf { decimalSample != null },
        )
    }

    /** Ohne Wahl gilt das einzige Zeichen einer Zahl als Dezimalzeichen ([parseNumber] ohne Hinweis). */
    private fun defaultMark(sample: String): Char = if (',' in sample) ',' else '.'

    /**
     * Wert von [sample] mit Dezimalzeichen [mark] ('.' oder ','), für die Auswahl der Vorschau («42,000 = 42000» /
     * «42,000 = 42»); null = nicht lesbar.
     */
    fun sampleValue(sample: String, mark: Char): BigDecimal? = parseNumber(sample, mark)

    /**
     * Kauf/Verkauf gegen einen Stablecoin → Tausch (siehe Kopf); null = bleibt wie [r] (kein Stablecoin, ohne Preis,
     * Coin = Stablecoin der Preiswährung). [stableAmount] = Fiat-/Quote-Betrag der Datei (null = Preis × Menge).
     */
    internal fun stableSwap(format: CsvImportFormat, r: CsvImportRow, stableAmount: BigDecimal?): CsvImportRow? {
        if (r.kind != CsvImportKind.BUY && r.kind != CsvImportKind.SELL) return null
        val price = r.price ?: return null
        // Stablecoin oder Krypto-Coin als Preiswährung (Paar «ETH/BTC»): Tausch, Wert nur mit USDT-Seite
        val stable = r.quote?.takeIf { (CsvImport.isStable(it) || CsvImport.isCryptoQuote(it)) && it != r.coin } ?: return null
        val sAmount = stableAmount ?: price.multiply(r.amount)
        val sell = r.kind == CsvImportKind.SELL
        val coin = if (sell) r.coin else stable
        val amount = if (sell) r.amount else sAmount
        val toCoin = if (sell) stable else r.coin
        val toAmount = if (sell) sAmount else r.amount
        val (value, valueQuote) = CsvImport.stableSwapValue(coin, amount, toCoin, toAmount)
        return r.copy(
            kind = CsvImportKind.SWAP,
            coin = coin,
            amount = amount,
            price = null,
            quote = valueQuote,
            key = CsvImport.key(format, CsvImportKind.SWAP, coin, amount, null, r.timeMillis, r.externalId, r.localTime),
            toCoin = toCoin,
            toAmount = toAmount,
            swapValue = value,
            stableSwap = CsvImport.isStable(stable),
        )
    }

    /**
     * Tausch-Seiten (Tausch-Zeile ohne erhaltene Seite) paarweise zu einer Zeile: zwei aufeinanderfolgende Seiten mit
     * gleicher Kennung (fehlt eine: gleicher Zeit) und verschiedenen Coins. Abgegeben = negative Menge, sonst die
     * Seite mit Verkaufs-/Ausgangswort in der Art; nicht eindeutig oder ohne Gegenstück → übersprungen.
     * [parsed] = Zeile mit Vorzeichen der Menge (−1/1).
     */
    private fun pairSwapLegs(
        format: CsvImportFormat,
        parsed: List<Pair<CsvImportRow, Int>>,
        skipped: MutableList<CsvImportSkip>,
    ): List<CsvImportRow> {
        fun isLeg(r: CsvImportRow) = r.kind == CsvImportKind.SWAP && r.toCoin == null
        fun outWord(r: CsvImportRow) = normalize(r.rawKind).let { t ->
            listOf("sell", "verkauf", "vente", "out", "from", "ausgang", "debit", "belastung", "abgang").any { t.contains(it) }
        }
        val out = ArrayList<CsvImportRow>()
        var i = 0
        while (i < parsed.size) {
            val (a, signA) = parsed[i]
            if (!isLeg(a)) { out += a; i++; continue }
            val next = parsed.getOrNull(i + 1)
            val b = next?.first
            val matches = b != null && isLeg(b) && a.coin != b.coin &&
                (if (a.externalId != null && b.externalId != null) a.externalId == b.externalId else a.timeMillis == b.timeMillis)
            if (!matches) {
                skipped += CsvImportSkip(a.line, CsvImportSkipReason.MISSING_FIELD)
                i++
                continue
            }
            val signB = next.second
            val aGiven = when {
                signA < 0 && signB > 0 -> true
                signB < 0 && signA > 0 -> false
                outWord(a) && !outWord(b) -> true
                outWord(b) && !outWord(a) -> false
                else -> null
            }
            if (aGiven == null) {
                skipped += CsvImportSkip(a.line, CsvImportSkipReason.MISSING_FIELD)
                skipped += CsvImportSkip(b.line, CsvImportSkipReason.MISSING_FIELD)
                i += 2
                continue
            }
            val given = if (aGiven) a else b
            val received = if (aGiven) b else a
            // Wert: abgegebene Seite zuerst; Gebühr: abgegebene Seite zuerst
            val valueRow = if (given.swapValue != null) given else received
            // Beide Seiten mit Gebühr: gleiche Einheit → zusammenzählen; verschiedene → nicht still eine verwerfen,
            // beide Zeilen überspringen (Tausch von Hand erfassen)
            val fees = CsvImport.swapFee(given.fee, given.feeUnit, received.fee, received.feeUnit)
            if (fees == null) {
                skipped += CsvImportSkip(a.line, CsvImportSkipReason.TWO_FEES)
                skipped += CsvImportSkip(b.line, CsvImportSkipReason.TWO_FEES)
                i += 2
                continue
            }
            val externalId = given.externalId ?: received.externalId
            out += CsvImportRow(
                line = minOf(a.line, b.line),
                kind = CsvImportKind.SWAP,
                rawKind = given.rawKind,
                coin = given.coin,
                amount = given.amount,
                price = null,
                quote = valueRow.quote.takeIf { valueRow.swapValue != null },
                fee = fees.first,
                feeUnit = fees.second,
                timeMillis = given.timeMillis,
                externalId = externalId,
                key = CsvImport.key(
                    format, CsvImportKind.SWAP, given.coin, given.amount, null, given.timeMillis, externalId, given.localTime,
                ),
                toCoin = received.coin,
                toAmount = received.amount,
                swapValue = valueRow.swapValue,
                localTime = given.localTime,
            )
            i += 2
        }
        return out
    }

    /** Eine Zeile samt Vorzeichen der Menge in der Datei (−1 negativ, sonst 1; für Tausch-Seiten). */
    private fun row(
        format: CsvImportFormat,
        h: Header,
        rec: Record,
        hint: Char?,
        zone: String,
        monthFirst: Boolean,
        origins: MutableMap<Int, CsvImportRow>,
    ): Pair<CsvImportRow, Int> {
        fun cell(role: Role): String = h.index[role]?.let { rec.cells.getOrNull(it) }.orEmpty()
        fun number(role: Role): BigDecimal? {
            val t = cell(role)
            // «-» / «–» = leer (manche Exporte für fehlende Gebühr)
            if (t.isEmpty() || t == "-" || t == "–") return null
            return parseNumber(t, hint) ?: throw Skip(CsvImportSkipReason.BAD_NUMBER)
        }

        val rawKind = cell(Role.KIND)
        val kind = classify(rawKind) ?: throw Skip(CsvImportSkipReason.UNSUPPORTED_KIND)

        // Coin: Relai nur Bitcoin; Swissquote nur Zeilen ohne ISIN (Aktien/ETF haben eine)
        val coin = when (format) {
            CsvImportFormat.RELAI -> cell(Role.COIN).ifEmpty { "BTC" }
            CsvImportFormat.SWISSQUOTE -> {
                if (cell(Role.ISIN).isNotEmpty() || cell(Role.COIN).isEmpty()) throw Skip(CsvImportSkipReason.NOT_CRYPTO)
                cell(Role.COIN)
            }
            else -> cell(Role.COIN)
        }.uppercase()
        if (coin.isEmpty()) throw Skip(CsvImportSkipReason.MISSING_FIELD)
        // Nur Geld («EUR»-Einzahlung u. ä.): kein Krypto
        if (CsvImport.isFiat(coin)) throw Skip(CsvImportSkipReason.NOT_CRYPTO)

        val time = parseTime(cell(Role.DATE), zone, monthFirst) ?: throw Skip(CsvImportSkipReason.BAD_DATE)
        // Ohne Versatz: Kalenderzeit für den Schlüssel (unabhängig von der Zeitzone)
        val local = localTime(cell(Role.DATE), monthFirst)

        val signed = number(Role.AMOUNT) ?: number(Role.AMOUNT_SATS)?.let { it.divide(SATS_PER_BTC) }
            ?: throw Skip(CsvImportSkipReason.MISSING_FIELD)
        val amount = signed.abs()
        if (amount.signum() == 0) throw Skip(CsvImportSkipReason.BAD_NUMBER)

        val pair = cell(Role.PAIR)
        val pairQuote = pair.split('/', '-').takeIf { it.size == 2 }?.get(1)?.trim().orEmpty()
        val quote = cell(Role.NET_CURRENCY).ifEmpty { cell(Role.QUOTE) }.ifEmpty { pairQuote }.uppercase().ifEmpty { null }

        var price = number(Role.PRICE)?.abs()?.takeIf { it.signum() > 0 }
        if (price == null && (kind == CsvImportKind.BUY || kind == CsvImportKind.SELL)) {
            price = number(Role.FIAT_AMOUNT)?.abs()?.takeIf { it.signum() > 0 }
                ?.let { CsvImport.derive(it, amount) }
        }
        // Tausch: kein Preis, sondern Wert (Fiat-Betrag, sonst Preis × Menge) — nur in Geld, sonst unbekannt
        var swapValue: BigDecimal? = null
        var toCoin: String? = null
        var toAmount: BigDecimal? = null
        if (kind == CsvImportKind.SWAP) {
            swapValue = (number(Role.FIAT_AMOUNT)?.abs()?.takeIf { it.signum() > 0 } ?: price?.multiply(amount))
                ?.takeIf { quote != null && quote in CsvImport.MONEY }
            price = null
            toCoin = cell(Role.TO_COIN).uppercase().ifEmpty { null }
            toAmount = number(Role.TO_AMOUNT)?.abs()?.takeIf { it.signum() > 0 }
            if (toCoin == null || toAmount == null || toCoin == coin) { toCoin = null; toAmount = null }
        }
        if (price != null && quote == null) throw Skip(CsvImportSkipReason.MISSING_FIELD)

        var fee = number(Role.FEE)?.abs()?.takeIf { it.signum() > 0 }
        var feeUnit = cell(Role.FEE_UNIT).uppercase().ifEmpty { null }
        if (fee != null) {
            if (feeUnit in setOf("SATS", "SAT", "SATOSHI", "SATOSHIS")) {
                fee = fee.divide(SATS_PER_BTC)
                feeUnit = "BTC"
            }
            if (feeUnit == null) feeUnit = quote ?: throw Skip(CsvImportSkipReason.MISSING_FIELD)
        } else {
            feeUnit = null
        }

        // Swissquote: «00000000» = ohne Auftrag (Gebühren, Zahlungen)
        val externalId = cell(Role.ID).takeIf { id -> id.isNotEmpty() && id.any { it != '0' } }

        val draft = CsvImportRow(
            line = rec.line,
            kind = kind,
            rawKind = rawKind,
            coin = coin,
            amount = amount,
            price = price,
            quote = quote,
            fee = fee,
            feeUnit = feeUnit,
            timeMillis = time,
            externalId = externalId,
            key = CsvImport.key(format, kind, coin, amount, price, time, externalId, local),
            toCoin = toCoin,
            toAmount = toAmount,
            swapValue = swapValue,
            localTime = local,
        )
        // Gegen Stablecoin bzw. Krypto-Coin: Tausch; der ursprüngliche Kauf/Verkauf bleibt für die früheren Schlüssel
        val stableAmount = if ((kind == CsvImportKind.BUY || kind == CsvImportKind.SELL) &&
            (CsvImport.isStable(quote) || CsvImport.isCryptoQuote(quote))
        ) {
            number(Role.FIAT_AMOUNT)?.abs()?.takeIf { it.signum() > 0 }
        } else null
        val swapped = stableSwap(format, draft, stableAmount)
        if (swapped != null) origins[rec.line] = draft
        return (swapped ?: draft) to (if (signed.signum() < 0) -1 else 1)
    }

    // ---------- Zahlen ----------

    /**
     * Dezimalzeichen der Datei aus eindeutigen Zahlen: nur «.»-Zahlen (genau ein Punkt, nicht 3 Stellen
     * dahinter, oder «0»/«-0» davor — «0.125» ist nie Tausender) → '.'; nur solche mit «,» → ','; beides oder keine → null.
     */
    internal fun decimalHint(cells: List<String>): Char? {
        var dot = false
        var comma = false
        for (raw in cells) {
            val t = stripGrouping(raw)
            val dots = t.count { it == '.' }
            val commas = t.count { it == ',' }
            if (dots == 1 && commas == 0 && (t.length - t.indexOf('.') - 1 != 3 || zeroWhole(t, '.'))) dot = true
            if (commas == 1 && dots == 0 && (t.length - t.indexOf(',') - 1 != 3 || zeroWhole(t, ','))) comma = true
            if (dots >= 1 && commas >= 1) { if (t.lastIndexOf('.') > t.lastIndexOf(',')) dot = true else comma = true }
        }
        return when {
            dot && !comma -> '.'
            comma && !dot -> ','
            else -> null
        }
    }

    /** Ganzzahlteil vor dem einzigen [sep] ist «0» oder «-0» (nie eine Tausendergruppe). */
    private fun zeroWhole(t: String, sep: Char): Boolean = t.substringBefore(sep).removePrefix("-") == "0"

    /**
     * Erste mehrdeutige Zahl ([cells] ohne Gruppierung), wenn die Datei kein Dezimalzeichen verrät ([decimalHint] null):
     * genau ein «.» oder «,», genau 3 Ziffern dahinter, gültig als Tausender («42,000» = 42000 oder 42?). null = keine.
     */
    internal fun ambiguousNumber(cells: List<String>): String? = cells.firstOrNull { raw ->
        val t = stripGrouping(raw)
        val dots = t.count { it == '.' }
        val commas = t.count { it == ',' }
        val sep = when {
            dots == 1 && commas == 0 -> '.'
            commas == 1 && dots == 0 -> ','
            else -> return@firstOrNull false
        }
        grouped(t, sep) && PLAIN.matches(t.replace(sep.toString(), ""))
    }?.trim()

    private fun stripGrouping(text: String): String = buildString {
        for (c in text.trim()) {
            when (c) {
                ' ', ' ', ' ', '\'', '’', 'ʼ', '+' -> {}
                '−' -> append('-')
                else -> append(c)
            }
        }
    }

    /**
     * Zahl aus der Datei → exakte Dezimalzahl; ungültig = null.
     * Beide Zeichen vorhanden: das letzte ist das Dezimalzeichen. Nur eines, mehrfach: Tausender.
     * Nur eines, einmal: Dezimalzeichen — ausser [hint] sagt das andere und die Gruppen passen (Tausender).
     * Tausender nur mit gültigen Gruppen ([grouped]): «0.001» bleibt 0.001, auch in einer Datei mit Dezimalkomma.
     */
    fun parseNumber(text: String, hint: Char? = null): BigDecimal? {
        val t = stripGrouping(text)
        if (t.isEmpty()) return null
        val dots = t.count { it == '.' }
        val commas = t.count { it == ',' }
        val plain = when {
            dots > 0 && commas > 0 -> {
                val dotLast = t.lastIndexOf('.') > t.lastIndexOf(',')
                val cut = if (dotLast) t.lastIndexOf('.') else t.lastIndexOf(',')
                val whole = t.substring(0, cut)
                val sep = if (dotLast) ',' else '.'
                if (!grouped(whole, sep)) return null
                whole.replace(sep.toString(), "") + "." + t.substring(cut + 1)
            }
            commas > 1 -> if (grouped(t, ',')) t.replace(",", "") else return null
            dots > 1 -> if (grouped(t, '.')) t.replace(".", "") else return null
            commas == 1 -> if (hint == '.' && grouped(t, ',')) t.replace(",", "") else t.replace(',', '.')
            dots == 1 -> if (hint == ',' && grouped(t, '.')) t.replace(".", "") else t
            else -> t
        }
        if (!PLAIN.matches(plain) || plain.length > 60) return null
        return runCatching { BigDecimal(plain) }.getOrNull()
    }

    /**
     * [sep] als Tausender gültig: erste Gruppe 1–3 Ziffern ohne führende 0 (eine einzelne «0» nie), alle weiteren
     * genau 3 Ziffern, mindestens eine Trennung. Spiegel: `grouped` in `CsvImportParser.swift`.
     */
    private fun grouped(text: String, sep: Char): Boolean {
        val groups = text.removePrefix("-").split(sep)
        val first = groups.first()
        if (groups.size < 2 || first.length !in 1..3 || first[0] == '0' || !first.all { it in '0'..'9' }) return false
        return groups.drop(1).all { g -> g.length == 3 && g.all { it in '0'..'9' } }
    }

    private val PLAIN = Regex("^-?[0-9]+(\\.[0-9]+)?$")

    // ---------- Zeit ----------

    private val ISO = Regex("^([0-9]{4})-([0-9]{2})-([0-9]{2})(?:[T ]([0-9]{2}):([0-9]{2})(?::([0-9]{2})(?:[.,]([0-9]{1,9}))?)?)?\\s*(Z|UTC|[+-][0-9]{2}:?[0-9]{2})?$")
    private val DAY_FIRST = Regex("^([0-9]{1,2})[./-]([0-9]{1,2})[./-]([0-9]{4})(?:[ T,]+([0-9]{1,2}):([0-9]{2})(?::([0-9]{2}))?)?$")

    /**
     * Zeit aus der Datei → UTC-Millisekunden; null = unlesbar.
     * «2024-09-15T14:23:17Z», «…+02:00», «2024-09-15 14:23:17» (Ortszeit), «24-08-2022 07:00:15»
     * (Swissquote, Tag zuerst), «15.09.2024 14:23». Ohne Versatz gilt [zone]. [monthFirst]: «03/04/2024» bzw.
     * «03-04-2024» = 4. März ([CsvDateOrder.MONTH_FIRST]); mit «.» immer Tag zuerst.
     */
    fun parseTime(text: String, zone: String = DEFAULT_ZONE, monthFirst: Boolean = false): Long? {
        val (local, offsetMinutes) = parseClock(text, monthFirst) ?: return null
        return if (offsetMinutes == null) toUtc(local, zone) else local - offsetMinutes * 60_000L
    }

    /** Kalenderzeit (als wäre sie UTC) einer Zeit OHNE Versatz; null = mit Versatz/«Z» oder unlesbar. */
    internal fun localTime(text: String, monthFirst: Boolean = false): Long? =
        parseClock(text, monthFirst)?.takeIf { it.second == null }?.first

    /** Kalenderzeit (als wäre sie UTC) und Versatz in Minuten (null = ohne Versatz); null = unlesbar. */
    private fun parseClock(text: String, monthFirst: Boolean): Pair<Long, Int?>? {
        val t = text.trim()
        ISO.matchEntire(t)?.let { m ->
            val g = m.groupValues
            val frac = g[7].padEnd(3, '0').take(3)
            val local = localMillis(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toIntOr0(), g[5].toIntOr0(), g[6].toIntOr0(), frac.toIntOr0())
                ?: return null
            val off = g[8]
            if (off.isEmpty()) return local to null
            if (off == "Z" || off == "UTC") return local to 0
            val digits = off.substring(1).replace(":", "")
            val minutes = digits.substring(0, 2).toInt() * 60 + digits.substring(2, 4).toInt()
            return local to (if (off[0] == '-') -minutes else minutes)
        }
        DAY_FIRST.matchEntire(t)?.let { m ->
            val g = m.groupValues
            // «.» ist immer Tag zuerst; «/» und «-» je nach Reihenfolge der Datei
            val swap = monthFirst && t[g[1].length] != '.'
            val day = if (swap) g[2].toInt() else g[1].toInt()
            val month = if (swap) g[1].toInt() else g[2].toInt()
            val local = localMillis(g[3].toInt(), month, day, g[4].toIntOr0(), g[5].toIntOr0(), g[6].toIntOr0(), 0)
                ?: return null
            return local to null
        }
        return null
    }

    /**
     * Reihenfolge Tag/Monat der Datumsspalte ([texts]): eine Zahl > 12 vorne → [CsvDateOrder.DAY_FIRST], an zweiter
     * Stelle → [CsvDateOrder.MONTH_FIRST] (vorne gewinnt bei Widerspruch); nur mehrdeutige Daten («03/04/2024», Tag ≠
     * Monat) → null (Vorschau fragt). Nur Trenner «/» und «-»; ISO und «03.04.2024» sind eindeutig → DAY_FIRST.
     */
    fun detectDateOrder(texts: List<String>): CsvDateOrder? {
        var dayFirst = false
        var monthFirst = false
        var ambiguous = false
        for (raw in texts) {
            val t = raw.trim()
            val g = DAY_FIRST.matchEntire(t)?.groupValues ?: continue
            if (t[g[1].length] == '.') continue
            val a = g[1].toInt()
            val b = g[2].toInt()
            when {
                a > 12 -> dayFirst = true
                b > 12 -> monthFirst = true
                a != b -> ambiguous = true
            }
        }
        return when {
            dayFirst -> CsvDateOrder.DAY_FIRST
            monthFirst -> CsvDateOrder.MONTH_FIRST
            ambiguous -> null
            else -> CsvDateOrder.DAY_FIRST
        }
    }

    /** Zeit mit Versatz oder «Z»/«UTC» (dann zählt keine Zeitzone). */
    internal fun hasOffset(text: String): Boolean = ISO.matchEntire(text.trim())?.groupValues?.get(8)?.isNotEmpty() == true

    private fun String.toIntOr0(): Int = if (isEmpty()) 0 else toInt()

    /** Kalenderzeit als wäre sie UTC → ms; ungültiges Datum = null. */
    internal fun localMillis(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int, ms: Int): Long? {
        if (mo !in 1..12 || d < 1 || d > daysInMonth(y, mo) || h > 23 || mi > 59 || s > 59) return null
        return ((daysFromCivil(y, mo, d) * 24 + h) * 60 + mi) * 60_000L + s * 1000L + ms
    }

    private fun daysInMonth(y: Int, m: Int): Int = when (m) {
        2 -> if ((y % 4 == 0 && y % 100 != 0) || y % 400 == 0) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    /** Tage seit 1970-01-01 (proleptisch gregorianisch, H. Hinnant). */
    internal fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
        val y = (if (m <= 2) y0 - 1 else y0).toLong()
        val era = Math.floorDiv(y, 400L)
        val yoe = y - era * 400
        val mp = ((m + 9) % 12).toLong()
        val doy = (153 * mp + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    /**
     * Ortszeit in [zone] → UTC. Zwei Schritte über den Versatz (gleich wie iOS): in der Herbst-Doppelstunde
     * gilt die spätere (Winterzeit), in der Frühlingslücke wird um eine Stunde vorgerückt.
     */
    internal fun toUtc(local: Long, zone: String): Long {
        val rules = ZoneId.of(zone).rules
        fun offset(utc: Long): Long = rules.getOffset(Instant.ofEpochMilli(utc)).totalSeconds * 1000L
        val guess = local - offset(local)
        return local - offset(guess)
    }
}
