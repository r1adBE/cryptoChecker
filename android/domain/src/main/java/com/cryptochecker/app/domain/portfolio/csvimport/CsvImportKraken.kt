package com.cryptochecker.app.domain.portfolio.csvimport

import java.math.BigDecimal

/**
 * Kraken «Ledgers»-Export (History › Export › Ledgers) → [CsvImportRow]. Gespiegelt in `CsvImportKraken.swift`.
 *
 * Spalten: txid, refid, time (UTC), type, subtype, aclass, asset, (wallet,) amount, fee, balance. Jede Zeile ist eine
 * Buchung auf einem Konto; [amount] ist brutto, [fee] wird zusätzlich im selben Asset abgezogen.
 *  - type «trade», «spend», «receive»: zwei Zeilen mit gleicher refid (eine negativ, eine positiv) = ein Geschäft.
 *    Krypto (auch Stablecoin) gegen Fiat ([CsvImport.FIAT]) → Kauf bzw. Verkauf (Preis = Fiat-Betrag / Menge, ohne
 *    Gebühr); Krypto gegen Krypto → Tausch, auch gegen Stablecoins ([CsvImport.STABLES]: BTC → USDT, USDT → ETH,
 *    USDC → USDT; Wert nur mit USDT-Seite = USDT-Menge, [CsvImport.stableSwapValue], sonst unbekannt). Gebühren beider
 *    Seiten in derselben Einheit zusammen; in verschiedenen Einheiten bleibt eine am Geschäft, die andere wird eine eigene
 *    Gebühr-Zeile (Kennung «refid/fee», [CsvImport.splitFees]). Fiat gegen Fiat → [CsvImportSkipReason.NOT_CRYPTO].
 *  - type «deposit» / «withdrawal» (Krypto, auch Stablecoins) → Einzahlung / Auszahlung; Fiat-Ein- und -Auszahlungen →
 *    [CsvImportSkipReason.NOT_CRYPTO].
 *  - type «staking», oder «earn» mit subtype «reward» → Belohnung.
 *  - Umbuchungen zwischen Kraken-Konten (type «transfer», «earn» mit «allocation»/«deallocation»/«migration»,
 *    Staking-Gegenbuchungen) bleiben im selben Bestand → übersprungen ([CsvImportSkipReason.UNSUPPORTED_KIND]),
 *    ebenso Margin, Rollover, Anpassungen. Ebenso Spot ↔ Staking als Ein-/Auszahlung (Asset mit Staking-Variante, z. B.
 *    «withdrawal DOT» + «deposit DOT.S»: die Variante und ihr Gegenstück — Gegenrichtung, gleiches Basis-Asset, gleiche
 *    Menge, zuerst gleiche refid, sonst höchstens 1 h auseinander) und Paare spend/receive/trade mit gleichem Basis-Asset
 *    (ETH → ETH2.S) — sonst entstünde Bestand doppelt bzw. ginge ein Posten mit Kaufpreis ab. Eine Staking-Zeile ohne
 *    solches Gegenstück wird ebenfalls nicht gebucht, aber als [CsvImportSkipReason.STAKING_UNPAIRED] gemeldet
 *    («Bestand prüfen»): Fehlt die Gegenbuchung im Export, stimmt der Bestand sonst unbemerkt nicht.
 *  - Doppelte Ledger-Zeilen (gleiche txid; Kraken-Exporte wiederholen Ein-/Auszahlungen) zählen einmal, die gleiche
 *    Wiederholung fällt still weg. Gleiche txid mit ANDERER Menge verschwindet nicht still:
 *    [CsvImportSkipReason.DUPLICATE_TXID_DIFFERENT] in der Vorschau.
 * Kraken-Kürzel werden vereinheitlicht ([asset]): «XXBT»/«XBT» → BTC, «ZEUR» → EUR, «XXDG» → DOGE, Staking-
 * Varianten «ETH2.S», «DOT.S», «DOT28.S», «USDC.M», «BTC.F» → Basis, «EUR.HOLD» → EUR (Fiat). Formate nur aus der
 * Kraken-Dokumentation bekannt.
 */
object CsvImportKraken {

    /** Alte Kraken-Kürzel mit X-/Z-Vorsatz → Kürzel. */
    private val LEGACY = mapOf(
        "XXBT" to "BTC", "XBT" to "BTC", "XBT.M" to "BTC", "XETH" to "ETH", "XLTC" to "LTC", "XXRP" to "XRP",
        "XXLM" to "XLM", "XETC" to "ETC", "XZEC" to "ZEC", "XXMR" to "XMR", "XREP" to "REP", "XMLN" to "MLN",
        "XXDG" to "DOGE", "XDG" to "DOGE", "XICN" to "ICN", "XNMC" to "NMC", "XXVN" to "XVN",
        "ZEUR" to "EUR", "ZUSD" to "USD", "ZGBP" to "GBP", "ZCAD" to "CAD", "ZJPY" to "JPY", "ZAUD" to "AUD",
        "ZCHF" to "CHF", "ETH2" to "ETH",
    )

    /**
     * Staking-/Earn-Varianten: «.S» (Staking), «.M» (Opt-in Rewards), «.B», «.F» (Flex), «.P», «.T»; dazu «.HOLD»
     * (gehaltenes Guthaben, z. B. «EUR.HOLD» = EUR, also Fiat).
     */
    private val SUFFIX = Regex("\\.([A-Z]|HOLD)$")

    /** Nur die Staking-/Earn-Suffixe (ohne «.HOLD»: gehaltenes Guthaben ist keine Staking-Variante, [isStakedAsset]). */
    private val STAKING_SUFFIX = Regex("\\.[A-Z]$")

    /**
     * Basis einer Staking-Variante mit Ziffern vor dem Suffix («DOT28.S», «KAVA21.S», «SOL03.S»): mindestens zwei
     * Buchstaben, dann nur Ziffern. Nur zusammen mit einem Suffix ([SUFFIX]) — echte Kürzel mit Ziffern am Ende
     * ohne Suffix bleiben, wie sie sind.
     */
    private val STAKED_DIGITS = Regex("^([A-Z]{2,})[0-9]+$")

    /** Kraken-Kürzel → einheitliches Kürzel (gross). */
    fun asset(raw: String): String {
        val t = raw.trim().uppercase()
        LEGACY[t]?.let { return it }
        val base = t.replace(SUFFIX, "")
        LEGACY[base]?.let { return it }
        if (base != t) STAKED_DIGITS.matchEntire(base)?.let { m -> m.groupValues[1].let { return LEGACY[it] ?: it } }
        return base
    }

    private class Leg(
        val line: Int,
        val refid: String,
        val type: String,
        val subtype: String,
        val asset: String,
        val signed: BigDecimal,
        val fee: BigDecimal?,
        val time: Long,
        /** Asset ist eine Staking-/Earn-Variante («ETH2.S», «DOT.S», «ETH2»). */
        val staked: Boolean = false,
    )

    /** Staking-/Earn-Variante eines Kraken-Kürzels (vor dem Vereinheitlichen). */
    private fun isStakedAsset(raw: String): Boolean {
        val t = raw.trim().uppercase()
        return t == "ETH2" || STAKING_SUFFIX.containsMatchIn(t)
    }

    /** Höchster Abstand zwischen Spot- und Staking-Buchung derselben Umbuchung ohne gemeinsame refid. */
    private const val STAKING_PAIR_MILLIS = 3_600_000L

    /**
     * Ein-/Auszahlungen zwischen Spot und Staking (Indizes in [legs]): jede Zeile mit Staking-Variante und ihr
     * Gegenstück (Gegenrichtung, gleiches Basis-Asset, gleiche Menge). Zuerst für ALLE Staking-Zeilen das Gegenstück mit
     * derselben refid (irgendwo in der Datei), erst danach für die übrigen eine Zeile ≤ 1 h daneben — sonst nähme eine
     * frühere Staking-Zeile über das Zeitfenster die Spot-Zeile einer anderen Umbuchung weg, und deren eigenes
     * Gegenstück würde als echte Ein-/Auszahlung gebucht. [StakingMoves.unpaired]: Staking-Zeilen ohne
     * gefundenes Gegenstück.
     */
    private fun stakingMoves(legs: List<Leg>): StakingMoves {
        val out = HashSet<Int>()
        val unpaired = HashSet<Int>()
        fun move(l: Leg) = l.type == "deposit" || l.type == "withdrawal"
        val staked = legs.indices.filter { move(legs[it]) && legs[it].staked }
        out += staked
        fun counterpart(l: Leg, k: Int): Boolean {
            val c = legs[k]
            return k !in out && move(c) && !c.staked && c.type != l.type && c.asset == l.asset &&
                c.signed.abs().compareTo(l.signed.abs()) == 0
        }
        val open = ArrayList<Int>()
        for (i in staked) {
            val l = legs[i]
            val j = if (l.refid.isEmpty()) null else legs.indices.firstOrNull { k -> counterpart(l, k) && legs[k].refid == l.refid }
            if (j != null) out += j else open += i
        }
        for (i in open) {
            val l = legs[i]
            val j = legs.indices.firstOrNull { k -> counterpart(l, k) && kotlin.math.abs(legs[k].time - l.time) <= STAKING_PAIR_MILLIS }
            if (j != null) out += j else unpaired += i
        }
        return StakingMoves(out, unpaired)
    }

    private class StakingMoves(val internal: Set<Int>, val unpaired: Set<Int>)

    fun build(names: List<String>, data: List<CsvImportParser.Record>, zone: String): CsvImportResult {
        fun col(n: String) = names.indexOf(n).takeIf { it >= 0 }
        val iTx = col("txid")
        val iRef = col("refid")
        val iTime = col("time")
        val iType = col("type")
        val iSub = col("subtype")
        val iAsset = col("asset")
        val iAmount = col("amount")
        val iFee = col("fee")
        val skipped = ArrayList<CsvImportSkip>()
        val legs = ArrayList<Leg>()
        val hint: Char? = '.'
        // txid → Menge der ersten Zeile (Text, wenn unlesbar): Wiederholungen vergleichen
        val seen = HashMap<String, Any>()
        for (rec in data) {
            fun cell(i: Int?) = i?.let { rec.cells.getOrNull(it) }.orEmpty()
            val amount = CsvImportParser.parseNumber(cell(iAmount), hint)
            // Wiederholte Ledger-Zeile (gleiche txid): gleiche Menge nur einmal, andere Menge in die Vorschau
            val txid = cell(iTx).trim()
            if (txid.isNotEmpty()) {
                val first = seen[txid]
                if (first != null) {
                    if (!sameAmount(first, amount, cell(iAmount))) {
                        skipped += CsvImportSkip(rec.line, CsvImportSkipReason.DUPLICATE_TXID_DIFFERENT)
                    }
                    continue
                }
                seen[txid] = amount ?: cell(iAmount).trim()
            }
            val type = CsvImportParser.normalize(cell(iType))
            val sub = CsvImportParser.normalize(cell(iSub))
            val asset = asset(cell(iAsset))
            val feeText = cell(iFee)
            val fee = if (feeText.isEmpty()) BigDecimal.ZERO else CsvImportParser.parseNumber(feeText, hint)
            val time = CsvImportParser.parseTime(cell(iTime), zone)
            when {
                asset.isEmpty() || type.isEmpty() -> skipped += CsvImportSkip(rec.line, CsvImportSkipReason.MISSING_FIELD)
                amount == null || fee == null -> skipped += CsvImportSkip(rec.line, CsvImportSkipReason.BAD_NUMBER)
                time == null -> skipped += CsvImportSkip(rec.line, CsvImportSkipReason.BAD_DATE)
                else -> legs += Leg(
                    rec.line, cell(iRef), type, sub, asset, amount, fee.abs().takeIf { it.signum() > 0 }, time,
                    staked = isStakedAsset(cell(iAsset)),
                )
            }
        }

        val rows = ArrayList<CsvImportRow>()
        val trades = LinkedHashMap<String, MutableList<Leg>>()
        val moves = stakingMoves(legs)
        for ((i, leg) in legs.withIndex()) {
            if (i in moves.internal) {
                // Spot ↔ Staking: bleibt im selben Bestand; ohne Gegenbuchung eigener Hinweis «Bestand prüfen»
                val reason = if (i in moves.unpaired) CsvImportSkipReason.STAKING_UNPAIRED else CsvImportSkipReason.UNSUPPORTED_KIND
                skipped += CsvImportSkip(leg.line, reason)
                continue
            }
            when (leg.type) {
                "trade", "spend", "receive" -> {
                    // Ohne refid kein Paar: einzeln überspringen
                    if (leg.refid.isEmpty()) skipped += CsvImportSkip(leg.line, CsvImportSkipReason.MISSING_FIELD)
                    else trades.getOrPut(leg.refid) { ArrayList() } += leg
                }
                "deposit", "withdrawal" -> single(leg, rows, skipped)
                "staking" -> reward(leg, rows, skipped)
                "earn" -> if (leg.subtype == "reward") reward(leg, rows, skipped)
                    else skipped += CsvImportSkip(leg.line, CsvImportSkipReason.UNSUPPORTED_KIND)
                else -> skipped += CsvImportSkip(leg.line, CsvImportSkipReason.UNSUPPORTED_KIND)
            }
        }
        for ((refid, group) in trades) trade(refid, group, rows, skipped)
        // Kraken schreibt UTC ohne Versatz: die Zeitzone zählt (Vorschau zeigt UTC, wählbar)
        return CsvImport.finish(CsvImportFormat.KRAKEN, rows, skipped, zone, localTimes = true)
    }

    /** Gleiche Menge wie die erste Zeile derselben txid ([first]: Zahl oder, wenn unlesbar, der Text)? */
    private fun sameAmount(first: Any, amount: BigDecimal?, text: String): Boolean = when (first) {
        is BigDecimal -> amount != null && amount.compareTo(first) == 0
        else -> amount == null && text.trim() == first
    }

    /** Fiat (Stablecoins zählen als Krypto; früher galt USDT als Geld). */
    private fun isMoney(asset: String) = asset in CsvImport.FIAT

    private fun rawKind(leg: Leg) = if (leg.subtype.isEmpty()) leg.type else leg.type + " " + leg.subtype

    private fun single(leg: Leg, rows: MutableList<CsvImportRow>, skipped: MutableList<CsvImportSkip>) {
        if (isMoney(leg.asset)) { skipped += CsvImportSkip(leg.line, CsvImportSkipReason.NOT_CRYPTO); return }
        val amount = leg.signed.abs()
        if (amount.signum() == 0) { skipped += CsvImportSkip(leg.line, CsvImportSkipReason.BAD_NUMBER); return }
        val kind = if (leg.type == "deposit") CsvImportKind.DEPOSIT else CsvImportKind.WITHDRAWAL
        rows += row(leg.line, kind, rawKind(leg), leg.asset, amount, null, null, leg.fee, leg.fee?.let { leg.asset }, leg.time, leg.refid)
    }

    private fun reward(leg: Leg, rows: MutableList<CsvImportRow>, skipped: MutableList<CsvImportSkip>) {
        // Nur Zugänge sind Belohnungen; negative Staking-Zeilen sind Gegenbuchungen (Umbuchung ins Staking)
        if (leg.signed.signum() <= 0 || isMoney(leg.asset)) {
            skipped += CsvImportSkip(leg.line, CsvImportSkipReason.UNSUPPORTED_KIND)
            return
        }
        rows += row(leg.line, CsvImportKind.REWARD, rawKind(leg), leg.asset, leg.signed, null, null, leg.fee, leg.fee?.let { leg.asset }, leg.time, leg.refid)
    }

    private fun trade(refid: String, group: List<Leg>, rows: MutableList<CsvImportRow>, skipped: MutableList<CsvImportSkip>) {
        val given = group.filter { it.signed.signum() < 0 }
        val received = group.filter { it.signed.signum() > 0 }
        if (given.size != 1 || received.size != 1) {
            group.forEach { skipped += CsvImportSkip(it.line, CsvImportSkipReason.MISSING_FIELD) }
            return
        }
        if (given[0].asset == received[0].asset) {
            // Gleiches Basis-Asset (ETH → ETH2.S): Umbuchung ins/aus dem Staking, kein Geschäft
            group.forEach { skipped += CsvImportSkip(it.line, CsvImportSkipReason.UNSUPPORTED_KIND) }
            return
        }
        val out = given[0]
        val inn = received[0]
        // Gebühren beider Seiten: gleiche Einheit zusammen; verschiedene → eine am Geschäft, die andere eigene Gebühr-Zeile
        val feeList = when {
            out.fee != null && inn.fee != null && out.asset == inn.asset -> listOf(out.asset to out.fee.add(inn.fee))
            else -> listOfNotNull(out.fee?.let { out.asset to it }, inn.fee?.let { inn.asset to it })
        }
        val split = CsvImport.splitFees(feeList, out.asset, inn.asset)
        if (split == null) {
            group.forEach { skipped += CsvImportSkip(it.line, CsvImportSkipReason.TWO_FEES) }
            return
        }
        val fees = split.first?.second to split.first?.first
        val line = minOf(out.line, inn.line)
        val outAmount = out.signed.abs()
        val inAmount = inn.signed
        when {
            isMoney(out.asset) && isMoney(inn.asset) ->
                group.forEach { skipped += CsvImportSkip(it.line, CsvImportSkipReason.NOT_CRYPTO) }
            isMoney(out.asset) -> {
                // Kauf: Geld weg, Coin rein
                val price = CsvImport.derive(outAmount, inAmount)
                rows += row(line, CsvImportKind.BUY, rawKind(inn), inn.asset, inAmount, price, out.asset, fees.first, fees.second, inn.time, refid)
            }
            isMoney(inn.asset) -> {
                // Verkauf: Coin weg, Geld rein
                val price = CsvImport.derive(inAmount, outAmount)
                rows += row(line, CsvImportKind.SELL, rawKind(out), out.asset, outAmount, price, inn.asset, fees.first, fees.second, out.time, refid)
            }
            else -> {
                // Tausch; mit Stablecoin-Seite Hinweis in der Vorschau, mit USDT-Seite Wert in USDT
                val (value, valueQuote) = CsvImport.stableSwapValue(out.asset, outAmount, inn.asset, inAmount)
                rows += CsvImportRow(
                    line = line,
                    kind = CsvImportKind.SWAP,
                    rawKind = rawKind(out),
                    coin = out.asset,
                    amount = outAmount,
                    price = null,
                    quote = valueQuote,
                    fee = fees.first,
                    feeUnit = fees.second,
                    timeMillis = out.time,
                    externalId = refid,
                    key = CsvImport.key(CsvImportFormat.KRAKEN, CsvImportKind.SWAP, out.asset, outAmount, null, out.time, refid),
                    toCoin = inn.asset,
                    toAmount = inAmount,
                    swapValue = value,
                    stableSwap = CsvImport.isStable(out.asset) || CsvImport.isStable(inn.asset),
                )
            }
        }
        // Weitere Gebühr (andere Einheit): eigene Gebühr-Zeile nach dem Geschäft, eigene Kennung (die refid trägt das Geschäft)
        if (!(isMoney(out.asset) && isMoney(inn.asset))) {
            for ((unit, amount) in split.second) {
                val leg = if (unit == out.asset) out else inn
                rows += row(line, CsvImportKind.FEE, rawKind(leg), unit, amount, null, null, null, null, leg.time, "$refid/fee")
            }
        }
    }

    private fun row(
        line: Int,
        kind: CsvImportKind,
        rawKind: String,
        coin: String,
        amount: BigDecimal,
        price: BigDecimal?,
        quote: String?,
        fee: BigDecimal?,
        feeUnit: String?,
        time: Long,
        refid: String,
    ): CsvImportRow {
        val id = refid.ifEmpty { null }
        return CsvImportRow(
            line = line,
            kind = kind,
            rawKind = rawKind,
            coin = coin,
            amount = amount,
            price = price,
            quote = quote,
            fee = fee,
            feeUnit = feeUnit,
            timeMillis = time,
            externalId = id,
            key = CsvImport.key(CsvImportFormat.KRAKEN, kind, coin, amount, price, time, id),
        )
    }
}
