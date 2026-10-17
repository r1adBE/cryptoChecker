package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Art eines Schritts der Steuer-Rechnung ([TaxEngines]). */
internal enum class TaxStepKind {
    /** Zugang (Kauf, Belohnung, Einzahlung, Zugang aus Tausch). */
    ACQUIRE,
    /** Veräusserung (Verkauf, Tausch, Gebühr im dritten Coin). */
    DISPOSE,
    /** Auszahlung: Menge und Einstand verlassen den Topf, keine Veräusserung. */
    REMOVE,
    /** Netzwerkgebühr im Coin (Umbuchung): Menge sinkt, Einstand bleibt. */
    NETWORK_FEE,
    /** Gebühr USDT + Wert der Gebühr im dritten Coin einer Umbuchung: erhöht den Einstand. */
    ADD_FEE,
}

/**
 * Ein Schritt eines Coins, abgeleitet aus [PortfolioLedgerEvent] mit denselben Regeln wie der Ø in [PortfolioCalculator]:
 * Zugang = erhaltene Menge mit Einstand (Menge × Preis + Gebühr USDT + Wert der Gebühr im dritten Coin; Tausch: Wert des
 * Tauschs), Abgang = Menge + Gebühr im Coin mit Erlös (Menge × Preis bzw. Wert des Tauschs − Gebühr USDT − Wert der Gebühr
 * im dritten Coin), Gebühr-Abgang zum Gebühr-Preis.
 */
internal class TaxStep(
    val kind: TaxStepKind,
    val trade: PortfolioTrade,
    val qty: BigDecimal,
    /** Zugang: Einstand (null = ohne Kaufpreis); [TaxStepKind.ADD_FEE]: Betrag. */
    val cost: BigDecimal? = null,
    /** Zugang: Erwerb ([PortfolioTrade.acquisitionTime]). */
    val acquired: Long = trade.time,
    /** Abgang: Erlös netto; null = unbekannt. */
    val proceeds: BigDecimal? = null,
    /** Abgang: Erlös vor Gebühren. */
    val gross: BigDecimal? = null,
    val feeLeg: Boolean = false,
    val swap: Boolean = false,
    val feeMissing: Boolean = false,
    /** Schenkung als Veräusserung ([PortfolioTrade.fromGift]) bzw. an Ehe-/Lebenspartner ([spouseGift]). */
    val gift: Boolean = false,
    /** UK/CA: Schenkung an Ehe-/Lebenspartner — Erlös je Teil = sein Einstand, kein Gewinn/Verlust ([emit]). */
    val spouseGift: Boolean = false,
    /** Zahlung mit Krypto ([PortfolioTxType.PAYMENT]). */
    val payment: Boolean = false,
    /** Erlös aus geschätztem Wert ([PortfolioTrade.valueEstimated]). */
    val estimated: Boolean = false,
) {
    val time: Long get() = trade.time
    val id: Long get() = trade.id
}

/** Ergebnis von [TaxPool.take]: abgegebene Menge, Teil mit Kaufpreis, abgegebener Einstand samt Herkunft. */
internal class TaxTaken(
    val removed: BigDecimal,
    val removedPriced: BigDecimal,
    val cost: BigDecimal,
    val parts: List<PortfolioCostPart>,
) {
    val removedUnpriced: BigDecimal get() = removed - removedPriced
}

/**
 * Durchschnitts-Topf (Menge mit/ohne Kaufpreis, Einstand mit Herkunft je Zeitpunkt) — dieselbe Rechnung wie der Ø in
 * [PortfolioCalculator]: ein Abgang nimmt beide Teile anteilig und den Einstand zum Durchschnitt mit; die Herkunftsteile
 * ([PortfolioCostPart]) erlauben die Umrechnung jedes Zugangs zum Devisenkurs seines Datums.
 */
internal class TaxPool(
    /**
     * Zeitzone der Kalendertage: Herkunftsteile desselben Tags werden zusammengefasst (gleicher Devisenkurs; die Liste
     * wächst mit den Tagen statt mit den Zugängen — kein O(Zugänge × Abgänge)). null = jeder Teil einzeln.
     */
    private val zone: ZoneId? = null,
) {
    var priced: BigDecimal = BigDecimal.ZERO
        private set
    var unpriced: BigDecimal = BigDecimal.ZERO
        private set
    var cost: BigDecimal = BigDecimal.ZERO
        private set
    val parts = ArrayList<PortfolioCostPart>()
    /** Mit [zone]: Tag → Index in [parts] (erster Zeitpunkt des Tags bleibt). */
    private val dayIndex = HashMap<LocalDate, Int>()
    val held: BigDecimal get() = priced + unpriced

    /** Zugang [qty] mit Einstand [cost] (null = ohne Kaufpreis), Herkunft [time]. */
    fun add(qty: BigDecimal, cost: BigDecimal?, time: Long) {
        if (qty.signum() <= 0) return
        if (cost == null) {
            unpriced += qty
            return
        }
        priced += qty
        addCost(time, cost)
    }

    /** Zugang mit fortgeführtem Einstand (Herkunftsteile [from]). */
    fun addCarried(qty: BigDecimal, from: List<PortfolioCostPart>) {
        if (qty.signum() <= 0) return
        priced += qty
        from.forEach { addCost(it.time, it.usdt) }
    }

    /** Einstand direkt zum Teil mit Kaufpreis (z. B. nicht abziehbarer Verlust). */
    fun addCost(time: Long, usdt: BigDecimal) {
        cost += usdt
        if (usdt.signum() == 0) return
        val z = zone
        if (z == null) {
            parts += PortfolioCostPart(time, usdt)
            return
        }
        val day = Instant.ofEpochMilli(time).atZone(z).toLocalDate()
        val i = dayIndex[day]
        if (i != null) {
            parts[i] = PortfolioCostPart(parts[i].time, parts[i].usdt + usdt)
        } else {
            dayIndex[day] = parts.size
            parts += PortfolioCostPart(time, usdt)
        }
    }

    /** Alle Herkunftsteile entfernen (samt Tag-Index). */
    private fun clearParts() {
        parts.clear()
        dayIndex.clear()
    }

    /**
     * Gebühr einer Umbuchung: anteilig (Menge mit Kaufpreis / Bestand) zum Einstand, wie der Ø. Rückgabe = Anteil des
     * Teils ohne Kaufpreis (ohne Teil mit Kaufpreis: die ganze Gebühr) — der Aufrufer vermerkt ihn als
     * [PortfolioUnassignedFee]; 0 = alles zugeordnet bzw. kein Bestand.
     */
    fun addFee(time: Long, extra: BigDecimal): BigDecimal {
        val h = held
        if (extra.signum() <= 0 || h.signum() <= 0) return BigDecimal.ZERO
        if (priced.signum() <= 0) return extra
        val assigned = if (unpriced.signum() == 0) extra else extra.multiply(priced).divide(h, PortfolioDecimal.MC)
        addCost(time, assigned)
        return extra - assigned
    }

    /** Übernimmt den ganzen Inhalt von [other] (danach leer). */
    fun absorb(other: TaxPool) {
        priced += other.priced
        unpriced += other.unpriced
        // Einstand unverändert, Herkunft je Teil (mit [zone] nach Tag zusammengefasst)
        val c = cost
        other.parts.forEach { addCost(it.time, it.usdt) }
        cost = c + other.cost
        other.priced = BigDecimal.ZERO
        other.unpriced = BigDecimal.ZERO
        other.cost = BigDecimal.ZERO
        other.clearParts()
    }

    /** Gibt [requested] ab (gekappt auf den Bestand), beide Teile anteilig samt Einstand. */
    fun take(requested: BigDecimal): TaxTaken {
        val zero = BigDecimal.ZERO
        val mc = PortfolioDecimal.MC
        val h = held
        val removed = requested.min(h).max(zero)
        if (removed.signum() <= 0) return TaxTaken(zero, zero, zero, emptyList())
        val allPriced = unpriced.signum() == 0
        val removedPriced = if (allPriced) removed else PortfolioDecimal.amount(removed.multiply(priced).divide(h, mc)).min(priced)
        val costRemoved = when {
            priced.signum() == 0 -> zero
            removedPriced >= priced -> cost
            else -> cost.multiply(removedPriced).divide(priced, mc)
        }
        val partsRemoved = takeParts(costRemoved)
        val rest = h - removed
        priced -= removedPriced
        cost -= costRemoved
        if (PortfolioDecimal.isDust(priced) || priced.signum() < 0) { priced = zero; cost = zero; clearParts() }
        unpriced = (rest - priced).max(zero)
        if (PortfolioDecimal.isDust(unpriced)) unpriced = zero
        return TaxTaken(removed, removedPriced, costRemoved, partsRemoved)
    }

    /**
     * Netzwerkgebühr: Menge sinkt anteilig, der Einstand bleibt im Rest. Bleibt kein Teil mit Kaufpreis, geht der
     * Einstand nicht still verloren: Rückgabe = dieser Einstand (Aufrufer vermerkt [PortfolioLostFeeCost]), sonst 0.
     */
    fun dropQty(qty: BigDecimal): BigDecimal {
        val h = held
        if (qty.signum() <= 0 || h.signum() <= 0) return BigDecimal.ZERO
        val removed = qty.min(h)
        val removedPriced = if (unpriced.signum() == 0) removed
            else PortfolioDecimal.amount(removed.multiply(priced).divide(h, PortfolioDecimal.MC)).min(priced)
        priced -= removedPriced
        var lost = BigDecimal.ZERO
        if (PortfolioDecimal.isDust(priced) || priced.signum() < 0) {
            lost = cost
            priced = BigDecimal.ZERO; cost = BigDecimal.ZERO; clearParts()
        }
        unpriced = (h - removed - priced).max(BigDecimal.ZERO)
        if (PortfolioDecimal.isDust(unpriced)) unpriced = BigDecimal.ZERO
        return lost
    }

    /** Nimmt [removed] Einstand anteilig aus allen Herkunftsteilen; Summe exakt [removed]. */
    private fun takeParts(removed: BigDecimal): List<PortfolioCostPart> {
        if (removed.signum() == 0 || parts.isEmpty()) return emptyList()
        if (removed >= cost) {
            val all = parts.toList()
            clearParts()
            return all
        }
        val out = ArrayList<PortfolioCostPart>(parts.size)
        var left = removed
        for (i in parts.indices) {
            val p = parts[i]
            val r = if (i == parts.size - 1) left else p.usdt.multiply(removed).divide(cost, PortfolioDecimal.MC)
            left -= r
            out += PortfolioCostPart(p.time, r)
            parts[i] = PortfolioCostPart(p.time, p.usdt - r)
        }
        return out
    }
}

/**
 * Rechnung der Steuer-Vorlagen (reines Kotlin, Spiegel: `TaxEngines.swift`) — Regeln und Quellen in [TaxTemplate]:
 *  - [TaxMethod.UK_POOL]: je Coin zuerst Zugänge DESSELBEN Tags (alle Abgänge eines Tags = eine Veräusserung, alle Zugänge
 *    eines Tags = ein Zugang zum Durchschnitt), dann Zugänge der 30 Tage danach (früheste Veräusserung zuerst, früheste
 *    Zugänge zuerst), der Rest aus dem Section-104-Pool (Durchschnitt). Was nicht zugeordnet wird, kommt am Tag des
 *    Zugangs in den Pool. Einzahlung mit früherem ursprünglichem Kaufdatum ([PortfolioTrade.acquiredTime]): Umbuchung
 *    zwischen eigenen Wallets ist kein Erwerb — «gleicher Tag»/«30 Tage» gelten am ursprünglichen Kaufdatum (Teil des
 *    Tages-Zugangs dieses Tags, auch für eine Veräusserung vor der Buchung); was davon übrig bleibt, kommt erst am
 *    BUCHUNGSTAG in den Pool (die App kennt die Coins erst ab da — eine Veräusserung davor nutzt sie im Pool nicht);
 *    Einstand-Herkunft (Devisenkurs) bleibt das ursprüngliche Datum. Einzahlung OHNE Kaufdatum: am Buchungstag direkt
 *    in den Pool, nicht «gleicher Tag»/«30 Tage» (eigene Coins, kein Erwerb im Fenster — nur Käufe, Belohnungen,
 *    Zugänge aus Tausch und Einzahlungen mit Kaufdatum zählen). UK kennt keine Haltedauer. Tausch und Gebühr in Krypto sind Veräusserungen — auch die Netzwerkgebühr einer
 *    Umbuchung oder Auszahlung ([applyRules], HMRC CRYPTO22280 «Fees satisfied in tokens»: eigene Veräusserung zum
 *    Marktwert; am selben Tag mit den übrigen Abgängen EINE Veräusserung nach der Same-Day-Regel). Schenkung an Ehe-/
 *    Lebenspartner (TCGA 1992 s. 58): Veräusserung ohne Gewinn und Verlust — Erlös = zugeordneter Einstand (gleiche
 *    Zuordnung wie jeder Abgang), der Partner übernimmt ihn.
 *  - [TaxMethod.CA_ACB]: Durchschnitt je Coin; ein Verlust (entschieden in CAD: Erlös zum Kurs des Verkaufs, Einstand je
 *    Herkunftsteil zum Kurs seines Datums; ohne Kurse in USDT) wird anteilig nicht abziehbar (Superficial Loss), wenn im
 *    Fenster 30 Tage vor bis 30 Tage nach dem Verkauf Zugänge liegen (Erwerbsdatum; Einzahlung ohne Kaufdatum = eigene
 *    Coins, kein Ersatz) und am Ende des Fensters Bestand da ist:
 *    nicht abziehbar = Verlust × min(verkauft, erworben im Fenster, Bestand am Fensterende) / verkauft. Jeder Ersatz-Zugang
 *    deckt nur EINEN Verlust: die zugeordnete Menge wird verbraucht (früheste Zugänge im Fenster zuerst) und steht für
 *    spätere Verkäufe nicht mehr bereit. Der Betrag kommt zum ACB des Ersatzes: Ersatz vor dem Verkauf → zum Bestand
 *    (ohne Bestand mit Kaufpreis: zum nächsten Zugang mit Kaufpreis), Ersatz danach → zum ACB bei diesem Zugang.
 *    Schenkung an Ehe-/Lebenspartner (ITA 73(1), Spousal Rollover): Abgang zum ACB, Erlös = ACB, kein Verlust zu prüfen.
 *    Netzwerkgebühr in Krypto: eigene Veräusserung zum Marktwert ([applyRules]; CRA: Bezahlen mit Krypto = disposition).
 *  - [TaxMethod.FR_PTA]: über das ganze Portfolio — Verkäufe (gegen Fiat) und Gebühren in Krypto sind Abtretungen;
 *    Tausch Krypto↔Krypto ist aufgeschoben — auch Krypto→Stablecoin (ein «Verkauf gegen USDT», den der Nutzer behält,
 *    ist als Tausch in den Stablecoin zu erfassen; [PortfolioTxType.SELL] = Verkauf gegen Euro, Hinweis im Dialog).
 *    Erwerbspreis-Summe (PTA) = Σ Zugänge mit Preis
 *    (Gebühren inklusive, Belohnungen zum Wert beim Erhalt); Abtretung: Einstand = PTA × Preis vor Gebühren / Gesamtwert
 *    des Portfolios unmittelbar davor, PTA sinkt um diesen Teil. Gesamtwert = Bestände × Kurs (verkaufter Coin zum
 *    Verkaufspreis, übrige über `priceAt`, Stablecoins nach [PortfolioStables]) — samt Guthaben beobachteter Adressen
 *    (Adress-Einträge, Build 47: sie gehören zum Portfolio); fehlt ein Kurs, bleibt der Einstand leer.
 *    Verkauf über den erfassten Bestand hinaus: nur der gedeckte Anteil (Bestand / (Menge + Gebühr im Coin)) ist eine
 *    Abtretung mit Einstand; der Rest steht als eigene Zeile ohne Einstand (Gewinn unbekannt, «mehr verkauft als
 *    erfasst») und kürzt die PTA nicht.
 *    Gebühr in Krypto (BOI-RPPM-PVBMC-30-10 § 70 Remarque 1, BOI-RPPM-PVBMC-30-20 § 50: Gegenleistung für eine
 *    Dienstleistung = Abtretung): bei einem Verkauf EINE Operation — Preis vor Gebühren = (Menge + Gebühr im Coin) ×
 *    Preis + Wert der Gebühr im dritten Coin, Erlös = Menge × Preis − Gebühr USDT; sonst (Umbuchung, Auszahlung, Tausch,
 *    dritter Coin eines Kaufs) eigene Abtretung der Gebühr-Coins zu ihrem Wert (Preis/Wert des Tauschs, Stablecoin 1,
 *    sonst `priceAt`), vor dem Rest der Transaktion. Gebühr im erhaltenen Coin (Kauf, Tausch) = weniger erhalten.
 *    Jede Art ausdrücklich: Umbuchung (eigene Wallets) und Tausch (aufgeschoben) ändern die PTA sonst nicht; Auszahlung
 *    (Abgang ohne Verkauf: Schenkung, Verlust, unbekannt) kürzt die PTA um PTA × Wert der Menge / Gesamtwert davor
 *    (BOFiP BOI-RPPM-PVBMC-30-20 § 100: Kürzung um die «fractions de capital initial» auch bei Abtretungen «à titre
 *    gratuit»; für Verlust/Diebstahl nennt das BOFiP keine Regel) — als [TaxOutflow] vermerkt, die Jahresübersicht
 *    markiert das Jahr «Abgang ohne Verkauf»; ohne Kurs bleibt die PTA danach unvollständig.
 *  - [TaxMethod.JP_TOTAL_AVERAGE]: je Coin und Kalenderjahr (Zeitzone) ein Stückpreis = (Einstand Jahresanfang + Zugänge
 *    des Jahres) / (Menge Jahresanfang + Zugänge des Jahres); jede Veräusserung des Jahres zu diesem Stückpreis.
 *    Netzwerkgebühr in Krypto: eigene Veräusserung zum Marktwert ([applyRules]; 国税庁 FAQ: Bezahlen mit Krypto = 譲渡).
 *  - [TaxMethod.AT_MOVING_AVERAGE]: je Coin UND Wallet (KryptowährungsVO § 2; Wallet-Schlüssel wie
 *    [CostMethod.FIFO_WALLET]: Börse, Umbuchung von [PortfolioTrade.transferFrom] nach [PortfolioTrade.transferTo] nimmt
 *    Posten bzw. Einstand mit) Altbestand (Erwerb vor dem 1.3.2021, Ortszeit) als FIFO-Posten, Neubestand als
 *    gleitender Durchschnitt; Abgänge nehmen zuerst den Altbestand (früher erworben). Tausch ab dem 1.3.2022 (Inkrafttreten
 *    § 27b Abs. 3 Z 2 EStG): Altbestand-Teil ist eine Veräusserung (§ 31 EStG a. F., EStR Rz 6103k: Altvermögen auch
 *    durch Tausch nur § 31; der erhaltene Coin = Neubestand zum Wert), der Neubestand-Teil keine — sein Einstand geht auf
 *    den erhaltenen Coin über, auch in einen Stablecoin (Krypto→Stablecoin ist Krypto→Krypto; steuerbar erst der Verkauf
 *    gegen Euro mit diesem Einstand). Tausch VOR dem 1.3.2022: ganz eine Veräusserung nach alter Rechtslage (EStR Rz
 *    6103k: Realisation stets nach § 31 EStG a. F.), der erhaltene Coin ist zum Wert des Tauschs angeschafft — vor dem
 *    1.3.2021 als Altbestand (Spekulationsfrist ab dem Tausch), danach als Neubestand.
 *    Gebühren einer Umbuchung erhöhen den Einstand nicht. Gebühren im Neubestand sind nicht abzugsfähig (§ 27a Abs. 4 Z 2
 *    EStG: Anschaffungskosten ohne Anschaffungsnebenkosten; § 20 Abs. 2 EStG: keine Aufwendungen beim Sondersteuersatz,
 *    also auch keine Veräusserungskosten; BMF «Steuerliche Behandlung von Kryptowährungen»): Zugang = Menge × Preis
 *    (Gebühr im Coin = weniger erhalten, Gebühr USDT bzw. im dritten Coin nicht dazu), Verkauf mit Erlös VOR Gebühren,
 *    die Gebühr im Coin eines Verkaufs ist keine Veräusserung (Menge sinkt, Einstand bleibt im Rest wie bei der
 *    Netzwerkgebühr einer Umbuchung). Altbestand (§ 31 EStG a. F.) unverändert mit Gebühren. Ebenso keine Zeile für eine
 *    Gebühr, die einen Zugang im Neubestand ganz verbraucht ([PortfolioFeeExpense.CONSUMED]: nur Nebenkosten).
 *    Belohnung im Neubestand: Anschaffungskosten 0 im Durchschnitt
 *    (BMF «Steuerliche Behandlung von Kryptowährungen», Stand 01.01.2026: Staking mit vorhandenen Coins, Airdrops,
 *    Bounties, Hardforks — kein Zufluss, Anschaffungskosten null; § 27b Abs. 2 EStG). Zinsen aus Lending/Liquidity-Pools
 *    (Entgelt für die Überlassung, § 27b Abs. 2 Z 1 EStG: laufende Einkünfte beim Zufluss zum Sondersteuersatz 27,5 %,
 *    Posten [TaxLine.AT_INTEREST] der Jahresübersicht; Anschaffungskosten = Wert beim Zufluss): nur erkannt, wo der
 *    CSV-Import die Art kennt bzw. die Notiz sie nennt ([PortfolioTrade.interestIncome], [interestReward]: «Interest»,
 *    «Lending», «Zinsen»); sonst bleibt eine Belohnung bei 0. Altbestand: Wert beim Erhalt.
 * Gebühr-Zeile (CSV «Fee», [PortfolioLedger.isFeeDisposal]): Veräusserung zum Tageskurs `priceAt` (alle Vorlagen;
 * [PortfolioLedger.valueFeeDisposals]); ohne Kurs Erlös unbekannt.
 * Gebühr als Aufwand (alle Vorlagen, [PortfolioFeeExpenses]): Gebühr verbraucht den ganzen Zugang bzw. Gebühr USDT/
 * dritter Coin einer Auszahlung — je eine Veräusserung mit Erlös 0 wie bei den Einstandsmethoden.
 * Netzwerkgebühr im Coin ohne Regel [TaxEventRules.networkFeeIsDisposal] (AT; CA/JP nur ohne Vorlage-Regeln): Einstand
 * bleibt im Rest; ohne Rest mit Kaufpreis wird er als [PortfolioLostFeeCost] vermerkt ([lostFeeCosts]) wie beim FIFO
 * des Portfolios. Gebühr USDT/dritter Coin einer Umbuchung im Pool (UK/CA nur ohne Vorlage-Regeln, [TaxPool.addFee]):
 * der Anteil des Bestands ohne Kaufpreis wird als [PortfolioUnassignedFee] vermerkt ([unassignedFees]).
 * Ergebnis wie [PortfolioCalculator.disposals]: [PortfolioDisposal] zeitlich (Verkauf), dann Id, Coin.
 */
object TaxEngines {

    /**
     * Veräusserungen nach [method]; [zone] = Zeitzone der Kalendertage (UK, CA, JP, AT), [priceAt] = USDT-Kurs eines Coins
     * zu einem Zeitpunkt ([TaxMethod.FR_PTA]: Gesamtwert und Gebühren in Krypto; mit [TaxEventRules.networkFeeIsDisposal]:
     * Netzwerkgebühren; alle: Gebühr-Zeilen zum Tageskurs; null = keine Kurse → Einstand bzw. Erlös leer); [fx] = USDT →
     * Steuerwährung zu einem Zeitpunkt ([TaxMethod.CA_ACB]: Superficial Loss in CAD entschieden; null bzw. ein Kurs
     * fehlt = in USDT). [rules] = Schenkung und Netzwerkgebühr nach der Vorlage ([TaxTemplate.eventRules], [applyRules]).
     */
    fun disposals(
        trades: List<PortfolioTrade>,
        method: TaxMethod,
        zone: ZoneId,
        priceAt: ((coin: String, time: Long) -> BigDecimal?)? = null,
        fx: ((time: Long) -> BigDecimal?)? = null,
        rules: TaxEventRules = TaxEventRules.of(method),
        /** US-Übergang ([TaxTemplate.walletFrom]): mit [TaxMethod.FIFO_WALLET] universell bis vor diesen Tag; sonst null. */
        walletFrom: LocalDate? = null,
    ): List<PortfolioDisposal> {
        // Belohnungen ohne Wert zum Tagesschluss (Einstand statt unbekannt, [PortfolioLedger.valueRewardsAtDayClose])
        val input = applyRules(PortfolioLedger.valueRewardsAtDayClose(trades, priceAt), rules, priceAt)
        val rows = method.costMethod?.let {
            val valued = PortfolioLedger.valueAtDayClose(input, priceAt)
            if (walletFrom != null && it == CostMethod.FIFO_WALLET) usTransition(valued, walletFrom, zone)
            else PortfolioCalculator.disposals(valued, it)
        } ?: run(input, method, zone, priceAt, fx).disposals
        if (!rules.quoteFeeIsInfo) return rows
        // Gebühr USDT einer Umbuchung: nicht im Einstand ([applyRules]), aber als Zeile zur Information
        val fees = transferFeeRows(trades)
        if (fees.isEmpty()) return rows
        return (rows + fees).sortedWith(compareBy<PortfolioDisposal> { it.sellTime }.thenBy { it.sellTxId }.thenBy { it.coin })
    }

    /**
     * [TaxEventRules.quoteFeeIsInfo]: je Umbuchung mit Gebühr USDT eine Zeile [PortfolioFeeExpense.TRANSFER] (Menge 0,
     * «Einstand» = Gebühr, Erlös 0) — der Export führt sie nur zur Information, nicht in den Summen.
     */
    private fun transferFeeRows(trades: List<PortfolioTrade>): List<PortfolioDisposal> =
        AddressBalance.split(trades).first
            .filter { it.type == PortfolioTxType.TRANSFER && it.amount.signum() > 0 && it.quoteFee.signum() > 0 }
            .map { t ->
                PortfolioFeeExpenses.disposal(
                    PortfolioCalculator.normalizeCoin(t.coin), PortfolioLedgerEvent(t, false), PortfolioFeeExpense.TRANSFER,
                    PortfolioFeeExpenses.Expense(t.quoteFee, false), CostMethod.FIFO,
                )
            }

    /**
     * US-Übergang 1.1.2025 (Rev. Proc. 2024-28, Safe Harbor): alles vor [walletFrom] (Ortszeit) FIFO über alle Wallets;
     * die Restposten dieses universellen FIFO am Stichtag werden den Wallets zugeordnet ([allocate]), ab [walletFrom]
     * FIFO je Wallet mit diesen Posten (statt FIFO je Wallet über die ganze Geschichte). Veräusserungen vor dem Stichtag
     * universell ([CostMethod.FIFO]), danach je Wallet. Spiegel: `TaxEngines.usTransition` (iOS).
     */
    internal fun usTransition(trades: List<PortfolioTrade>, walletFrom: LocalDate, zone: ZoneId): List<PortfolioDisposal> {
        val real = AddressBalance.split(trades).first
        return PortfolioLedger.eventsByCoin(real).flatMap { (coin, events) ->
            val (before, after) = events.partition { day(it.trade.time, zone).isBefore(walletFrom) }
            val universal = PortfolioFifo.run(coin, before)
            val held = PortfolioFifo.run(coin, before, perWallet = true).lots
            universal.disposals + PortfolioFifo.run(coin, after, perWallet = true, seed = allocate(coin, universal.lots, held)).disposals
        }.sortedWith(compareBy<PortfolioDisposal> { it.sellTime }.thenBy { it.sellTxId }.thenBy { it.coin })
    }

    /**
     * Zuordnung der universellen Restposten [universal] zu den Wallets (Regel der App, dokumentiert): Menge je Wallet =
     * dort am Stichtag gehaltene Einheiten ([held], FIFO je Wallet über dieselbe Geschichte). Die tatsächlich gehaltenen
     * Einheiten nach Erwerb (dann Id, Wallet) sortiert; die Restposten, älteste zuerst, füllen sie der Reihe nach — die
     * Wallet mit den ältesten Coins erhält den ältesten Einstand (Posten werden bei Bedarf geteilt, Einstand anteilig).
     * Universell mehr als in den Wallets (Wallet-Fehlbestand): Rest in die Wallet der letzten Einheit (ohne: «ohne
     * Angabe»); weniger: die übrigen Einheiten haben keinen Posten (später ungedeckt).
     */
    internal fun allocate(coin: String, universal: List<PortfolioLot>, held: List<PortfolioLot>): List<PortfolioLot> {
        val mc = PortfolioDecimal.MC
        val slots = held.filter { it.amount.signum() > 0 }
            .sortedWith(compareBy<PortfolioLot> { it.acquiredTime }.thenBy { it.txId }.thenBy { PortfolioWallets.key(it.wallet) })
        val fallback = slots.lastOrNull()?.wallet ?: PortfolioWallets.UNSPECIFIED
        val out = ArrayList<PortfolioLot>()
        var si = 0
        var slotLeft = slots.firstOrNull()?.amount ?: BigDecimal.ZERO
        for (lot in universal.sortedWith(compareBy<PortfolioLot> { it.acquiredTime }.thenBy { it.txId })) {
            var left = lot.amount
            var costLeft = lot.costUsdt
            while (left.signum() > 0) {
                val slot = slots.getOrNull(si)
                val qty = if (slot == null) left else left.min(slotLeft)
                val c = costLeft?.let { if (qty.compareTo(left) == 0) it else it.multiply(qty).divide(left, mc) }
                out += PortfolioLot(coin, lot.txId, lot.acquiredTime, lot.kind, qty, c, slot?.wallet ?: fallback)
                left -= qty
                if (c != null) costLeft = costLeft - c
                if (slot != null) {
                    slotLeft -= qty
                    if (slotLeft.signum() <= 0) {
                        si++
                        slotLeft = slots.getOrNull(si)?.amount ?: BigDecimal.ZERO
                    }
                }
            }
        }
        return out
    }

    /**
     * Art einer Belohnung ist ein Zins (Lending, Zinsen) — aus der Notiz bzw. der Art im CSV: AT (BMF) rechnet ihn mit
     * Anschaffungskosten = Wert beim Zufluss statt 0. Staking, Earn-Reward, Airdrop: nein.
     */
    fun interestReward(text: String?): Boolean {
        val t = text?.lowercase() ?: return false
        return INTEREST_WORDS.any { it in t }
    }

    private val INTEREST_WORDS = listOf("interest", "lending", "zins", "interet", "intérêt")

    /**
     * Vorgänge ohne Verkauf nach den Regeln der Vorlage umschreiben (vor jeder Rechnung; ohne Treffer dieselben Werte):
     *  - Verschenkt an Ehe-/Lebenspartner mit [TaxEventRules.spouseGiftNeutral] (UK, CA): bleibt eine Schenkung mit Merker
     *    ([PortfolioTrade.isGiftToSpouse]) — die eigenen Rechnungen machen daraus eine Veräusserung ohne Gewinn/Verlust;
     *  - sonst Verschenkt mit [TaxEventRules.giftIsDisposal] (UK, CA, AU): wie [PortfolioTxType.PAYMENT] mit
     *    [PortfolioTrade.fromGift] (Veräusserung zum Marktwert, Vermerk «Schenkung»); ohne: wie eine Auszahlung. Der
     *    Partner-Merker fällt dabei weg — Vorlagen ohne die Regel ignorieren ihn;
     *  - mit [TaxEventRules.networkFeeIsDisposal]: Netzwerkgebühr in Krypto einer Umbuchung, Auszahlung oder Schenkung
     *    (Gebühr im Coin und im dritten Coin) wird eine eigene Veräusserung der Gebühr-Coins zum Marktwert ([networkFeeSales]);
     *  - mit [TaxEventRules.quoteFeeIsInfo] (Build 57): die Gebühr USDT einer Umbuchung erhöht den Einstand nicht (sie
     *    fällt weg; [disposals] führt sie als Zeile zur Information).
     * Danach Gebühr-Zeilen und Werte ohne Marktwert wie bisher ([PortfolioLedger.valueAtDayClose]).
     */
    internal fun applyRules(
        trades: List<PortfolioTrade>,
        rules: TaxEventRules,
        priceAt: ((String, Long) -> BigDecimal?)?,
    ): List<PortfolioTrade> {
        if (trades.none { it.type == PortfolioTxType.GIFT } && !rules.networkFeeIsDisposal && !rules.quoteFeeIsInfo) return trades
        val out = ArrayList<PortfolioTrade>(trades.size)
        for (t in trades) {
            val g = when {
                rules.quoteFeeIsInfo && t.type == PortfolioTxType.TRANSFER && t.quoteFee.signum() > 0 -> t.copy(fee = null, feeUnit = null)
                t.type != PortfolioTxType.GIFT -> t
                rules.spouseGiftNeutral && t.isGiftToSpouse -> t
                rules.giftIsDisposal -> t.copy(type = PortfolioTxType.PAYMENT, fromGift = true, giftToSpouse = false)
                t.giftToSpouse -> t.copy(giftToSpouse = false)
                else -> t
            }
            if (rules.networkFeeIsDisposal) networkFeeSales(g, priceAt, out) else out += g
        }
        return out
    }

    /**
     * Netzwerkgebühr in Krypto einer Umbuchung (eigene Wallets), Auszahlung oder Schenkung als eigene Veräusserung: je
     * Gebühr-Coin ein Verkauf der Gebühr-Menge zum Marktwert (erfasster Preis bzw. Gebühr-Preis, Stablecoin 1, sonst
     * [priceAt] = Tagesschluss des Vortags; ohne Kurs Erlös unbekannt) mit Id und Zeit der Transaktion und ihrer
     * Quell-Wallet (Umbuchung: «von», sonst die Börse) — VOR ihr in die Liste, damit die Gebühr-Coins (stabil sortiert,
     * [PortfolioLedger.ORDER]) die Quelle zuerst verlassen. Die Transaktion selbst verliert diese Gebühr: eine Umbuchung
     * ändert danach weder Menge noch Einstand, die Gebühr im dritten Coin erhöht keinen Einstand und ist bei einer
     * Auszahlung kein Aufwand mehr — sonst zählte ihr Wert ein zweites Mal als Verlust. Die Gebühr USDT bleibt wie sie ist.
     * Der Export erkennt die Zeilen an der Id einer Transaktion ohne Verkauf (Vermerk «Gebühr», [TaxExport.gainRows]).
     */
    private fun networkFeeSales(t: PortfolioTrade, priceAt: ((String, Long) -> BigDecimal?)?, out: MutableList<PortfolioTrade>) {
        if ((t.type != PortfolioTxType.TRANSFER && !t.type.isWithdrawalLike) || t.amount.signum() <= 0) {
            out += t
            return
        }
        val wallet = if (t.type == PortfolioTxType.TRANSFER) t.transferFrom else t.exchange
        fun sale(coin: String, qty: BigDecimal, recorded: BigDecimal?) = PortfolioTrade(
            id = t.id, coin = coin, type = PortfolioTxType.SELL, amount = qty,
            priceUsdt = feePrice(coin, recorded, t.time, priceAt), time = t.time, exchange = wallet,
        )
        var rest = t
        if (t.coinFee.signum() > 0) {
            out += sale(PortfolioCalculator.normalizeCoin(t.coin), t.coinFee, t.validPrice)
            rest = rest.copy(fee = null, feeUnit = null)
        }
        t.thirdFeeCoin?.let { c ->
            out += sale(c, t.thirdFeeAmount, t.thirdFeePrice)
            rest = rest.copy(feeCoin = null, feeCoinAmount = null, feeCoinPrice = null)
        }
        out += rest
    }

    /**
     * Einstand von Netzwerkgebühren ohne Rest mit Kaufpreis ([PortfolioLostFeeCost]) der eigenen Rechnungen, zeitlich;
     * Einstandsmethoden des Portfolios: leer (dort [PortfolioCalculator.ledger]). Mit [TaxEventRules.networkFeeIsDisposal]
     * ist die Gebühr eine Veräusserung — dann bleibt kein Einstand liegen.
     */
    fun lostFeeCosts(
        trades: List<PortfolioTrade>,
        method: TaxMethod,
        zone: ZoneId,
        rules: TaxEventRules = TaxEventRules.of(method),
    ): List<PortfolioLostFeeCost> =
        if (method.costMethod != null) emptyList() else run(applyRules(trades, rules, null), method, zone, null).lostFeeCosts

    /**
     * Umbuchungsgebühren auf Bestand ohne Kaufpreis ([PortfolioUnassignedFee]) der eigenen Rechnungen, zeitlich;
     * Einstandsmethoden des Portfolios: leer (dort [PortfolioCalculator.ledger]). Nur Pool-Rechnungen mit Gebühr im
     * Einstand (UK/CA ohne [TaxEventRules.quoteFeeIsInfo]); JP, AT und FR rechnen Umbuchungsgebühren nicht zum Einstand.
     */
    fun unassignedFees(
        trades: List<PortfolioTrade>,
        method: TaxMethod,
        zone: ZoneId,
        rules: TaxEventRules = TaxEventRules.of(method),
    ): List<PortfolioUnassignedFee> =
        if (method.costMethod != null) emptyList() else run(applyRules(trades, rules, null), method, zone, null).unassignedFees

    /** [TaxMethod.FR_PTA]: Abgänge ohne Verkauf mit Kürzung der Erwerbspreis-Summe, zeitlich; sonst leer. */
    fun outflows(trades: List<PortfolioTrade>, method: TaxMethod, priceAt: ((String, Long) -> BigDecimal?)? = null): List<TaxOutflow> =
        if (method != TaxMethod.FR_PTA) emptyList()
        else run(applyRules(PortfolioLedger.valueRewardsAtDayClose(trades, priceAt), TaxEventRules.of(method), priceAt), method, ZoneId.of("UTC"), priceAt).outflows

    /** Ergebnis einer Rechnung: Veräusserungen, vermerkter Gebühr-Einstand, nicht zugeordnete Gebühren, FR-Abgänge ohne Verkauf. */
    private class TaxRun(
        val disposals: List<PortfolioDisposal>,
        val lostFeeCosts: List<PortfolioLostFeeCost>,
        val unassignedFees: List<PortfolioUnassignedFee>,
        val outflows: List<TaxOutflow>,
    )

    private fun run(
        trades: List<PortfolioTrade>,
        method: TaxMethod,
        zone: ZoneId,
        priceAt: ((String, Long) -> BigDecimal?)?,
        fx: ((Long) -> BigDecimal?)? = null,
    ): TaxRun {
        // Gebühr-Zeilen, Verschenkt/bezahlt ohne Marktwert und Tausch ohne Wert zum Tageskurs (ohne Kurs: unbekannt)
        val real = PortfolioLedger.valueAtDayClose(AddressBalance.split(trades).first, priceAt)
        val lost = ArrayList<PortfolioLostFeeCost>()
        val unassigned = ArrayList<PortfolioUnassignedFee>()
        val outflows = ArrayList<TaxOutflow>()
        val out = when (method) {
            TaxMethod.UK_POOL -> PortfolioLedger.eventsByCoin(real).flatMap { (coin, ev) -> ukPool(coin, steps(ev), zone, lost, unassigned) }
            TaxMethod.CA_ACB -> PortfolioLedger.eventsByCoin(real).flatMap { (coin, ev) -> caAcb(coin, steps(ev), zone, lost, unassigned, fx) }
            TaxMethod.JP_TOTAL_AVERAGE -> PortfolioLedger.eventsByCoin(real).flatMap { (coin, ev) -> jpTotal(coin, steps(ev), zone, lost) }
            // FR: Gesamtwert samt beobachteter Adressen (Adress-Einträge, [AddressBalance.isAddressTrade])
            TaxMethod.FR_PTA -> frPta(real, priceAt, outflows, trades.filter { AddressBalance.isAddressTrade(it.id) })
            TaxMethod.AT_MOVING_AVERAGE -> atSplit(real, zone, lost)
            TaxMethod.FIFO, TaxMethod.FIFO_WALLET, TaxMethod.AVERAGE -> emptyList()
        }
        // Gebühr als Aufwand wie bei den Einstandsmethoden ([PortfolioFeeExpenses]); FR: Abtretungspreis 0. FR und AT
        // (Neubestand) behandeln einen Tausch steuerneutral — dort bucht der abgegebene Coin keinen Erlös, also auch
        // kein Aufwand für den verbrauchten Zugang eines Tauschs
        val swaps = method != TaxMethod.FR_PTA && method != TaxMethod.AT_MOVING_AVERAGE
        val byId = real.associateBy { it.id }
        val atNewFrom = TaxTemplate.AT_NEW_FROM.atStartOfDay(zone).toInstant().toEpochMilli()
        val expenses = PortfolioLedger.eventsByCoin(real).flatMap { (coin, ev) -> PortfolioFeeExpenses.of(coin, ev, swaps) }
            .mapNotNull { d ->
                // AT Neubestand: eine Gebühr, die den ganzen Zugang verbraucht, ist nur Anschaffungsnebenkosten — nicht
                // anzusetzen (§ 27a Abs. 4 Z 2 EStG), also kein abziehbarer Verlust
                if (method == TaxMethod.AT_MOVING_AVERAGE && d.feeExpense == PortfolioFeeExpense.CONSUMED &&
                    (byId[d.sellTxId]?.acquisitionTime ?: d.sellTime) >= atNewFrom
                ) return@mapNotNull null
                if (method != TaxMethod.FR_PTA) return@mapNotNull d
                // FR: Gebühr im dritten Coin einer Auszahlung ist schon eine eigene Abtretung (frPta) — als Aufwand bleibt
                // nur die Gebühr USDT
                val t = byId[d.sellTxId]
                if (d.feeExpense == PortfolioFeeExpense.WITHDRAWAL && t != null && t.thirdFeeCoin != null) {
                    val q = t.quoteFee
                    if (q.signum() <= 0) null
                    else d.copy(costUsdt = q, gainUsdt = q.negate(), feeValueMissing = false, grossProceedsUsdt = BigDecimal.ZERO)
                } else d.copy(grossProceedsUsdt = BigDecimal.ZERO)
            }
        return TaxRun(
            (out + expenses).sortedWith(compareBy<PortfolioDisposal> { it.sellTime }.thenBy { it.sellTxId }.thenBy { it.coin }),
            lost.sortedWith(compareBy<PortfolioLostFeeCost> { it.time }.thenBy { it.txId }.thenBy { it.coin }),
            unassigned.sortedWith(compareBy<PortfolioUnassignedFee> { it.time }.thenBy { it.txId }.thenBy { it.coin }),
            outflows,
        )
    }

    /** Vermerkt Einstand [cost] einer Netzwerkgebühr ([s]) ohne Rest mit Kaufpreis (0 = nichts). */
    private fun MutableList<PortfolioLostFeeCost>.note(coin: String, s: TaxStep, cost: BigDecimal) {
        if (cost.signum() != 0) add(PortfolioLostFeeCost(coin, s.id, s.time, cost))
    }

    /** Vermerkt den Anteil [usdt] einer Umbuchungsgebühr ([s]) auf Bestand ohne Kaufpreis ([TaxPool.addFee]; 0 = nichts). */
    private fun MutableList<PortfolioUnassignedFee>.noteUnassigned(coin: String, s: TaxStep, usdt: BigDecimal) {
        if (usdt.signum() > 0) add(PortfolioUnassignedFee(coin, s.id, s.time, usdt))
    }

    private fun day(millis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    /** Schritte eines Coins aus seinen Ereignissen ([PortfolioLedger.events]). */
    internal fun steps(events: List<PortfolioLedgerEvent>): List<TaxStep> {
        val zero = BigDecimal.ZERO
        val out = ArrayList<TaxStep>()
        for (e in events) {
            val t = e.trade
            if (t.amount.signum() <= 0) continue
            if (e.feeLeg) {
                val fee = t.thirdFeeAmount
                val p = t.thirdFeePrice?.let { fee * it }
                out += TaxStep(TaxStepKind.DISPOSE, t, fee, proceeds = p, gross = p, feeLeg = true)
                continue
            }
            if (e.swapIn) {
                val r = t.swapReceived
                if (r.signum() > 0) out += TaxStep(TaxStepKind.ACQUIRE, t, r, cost = t.swapValue, swap = true)
                continue
            }
            val price = t.validPrice
            val feeValue = t.thirdFeeValue
            when (t.type) {
                PortfolioTxType.BUY, PortfolioTxType.REWARD, PortfolioTxType.DEPOSIT -> {
                    val r = t.received
                    if (r.signum() <= 0) continue
                    val cost = price?.let { t.amount * it + t.quoteFee + (feeValue ?: zero) }
                    out += TaxStep(TaxStepKind.ACQUIRE, t, r, cost = cost, acquired = t.acquisitionTime)
                }
                // Damit bezahlt: Veräusserung zum Marktwert wie ein Verkauf (alle Vorlagen; Verschenkt nur nach
                // [applyRules] mit [TaxEventRules.giftIsDisposal], sonst wie eine Auszahlung)
                PortfolioTxType.SELL, PortfolioTxType.PAYMENT, PortfolioTxType.SWAP -> {
                    val swap = t.type == PortfolioTxType.SWAP
                    // Tausch ohne gültigen Zugang (erhaltener Coin fehlt): Erlös unbekannt → «realisiert unvollständig»
                    val gross = if (swap) t.swapValue?.takeIf { t.swapToCoin != null } else price?.let { t.amount * it }
                    out += TaxStep(
                        TaxStepKind.DISPOSE, t, t.amount + t.coinFee,
                        proceeds = gross?.let { it - t.quoteFee - (feeValue ?: zero) }, gross = gross,
                        swap = swap, feeMissing = gross != null && feeValue == null,
                        gift = t.fromGift, payment = t.type == PortfolioTxType.PAYMENT && !t.fromGift,
                        estimated = t.valueEstimated,
                    )
                }
                // Verschenkt (beim Schenkenden keine Veräusserung): wie eine Auszahlung; an Ehe-/Lebenspartner (Merker nur
                // nach [applyRules] mit UK/CA-Regeln): Veräusserung ohne Gewinn/Verlust zum zugeordneten Einstand
                PortfolioTxType.WITHDRAWAL, PortfolioTxType.GIFT -> out += if (t.isGiftToSpouse) {
                    TaxStep(TaxStepKind.DISPOSE, t, t.amount + t.coinFee, gift = true, spouseGift = true)
                } else {
                    TaxStep(TaxStepKind.REMOVE, t, t.amount + t.coinFee)
                }
                PortfolioTxType.TRANSFER -> {
                    val extra = t.quoteFee + (feeValue ?: zero)
                    if (extra.signum() > 0) out += TaxStep(TaxStepKind.ADD_FEE, t, zero, cost = extra)
                    if (t.coinFee.signum() > 0) out += TaxStep(TaxStepKind.NETWORK_FEE, t, t.coinFee)
                }
            }
        }
        return out
    }

    /** Ein Teil einer Veräusserung vor der Erlös-Verteilung. */
    private class Piece(
        val amount: BigDecimal,
        val cost: BigDecimal?,
        val parts: List<PortfolioCostPart> = emptyList(),
        val acquiredTime: Long? = null,
        val lotTxId: Long? = null,
        val lotKind: PortfolioTxType? = null,
        val match: TaxMatch? = null,
        val matchedTime: Long? = null,
        val uncovered: Boolean = false,
        val oldHolding: Boolean? = null,
    )

    /** Teile eines Abgangs aus [taken]: Teil mit Kaufpreis (Einstand, Herkunft) und Teil ohne. */
    private fun piecesOf(taken: TaxTaken, match: TaxMatch? = null, matchedTime: Long? = null, oldHolding: Boolean? = null): List<Piece> =
        listOfNotNull(
            Piece(taken.removedPriced, taken.cost, taken.parts, match = match, matchedTime = matchedTime, oldHolding = oldHolding)
                .takeIf { taken.removedPriced.signum() > 0 },
            Piece(taken.removedUnpriced, null, match = match, matchedTime = matchedTime, oldHolding = oldHolding)
                .takeIf { taken.removedUnpriced.signum() > 0 },
        )

    /**
     * Veräusserungen aus [pieces]: Erlös [proceeds] nach Menge verteilt (letzter Teil bekommt den Rest, Summe exakt);
     * [method] wie in [PortfolioDisposal.method] (Pool/Durchschnitt = [CostMethod.AVERAGE], Posten = [CostMethod.FIFO]).
     * Schenkung an Ehe-/Lebenspartner ([TaxStep.spouseGift]): Erlös je Teil = sein Einstand (ohne Kaufpreis unbekannt).
     */
    private fun emit(
        coin: String,
        s: TaxStep,
        pieces: List<Piece>,
        proceeds: BigDecimal?,
        extra: (Piece, PortfolioDisposal) -> PortfolioDisposal = { _, d -> d },
    ): List<PortfolioDisposal> {
        val total = pieces.fold(BigDecimal.ZERO) { a, p -> a + p.amount }
        if (total.signum() <= 0) return emptyList()
        var left = proceeds
        return pieces.mapIndexed { i, p ->
            val share = if (s.spouseGift) p.cost else proceeds?.let {
                if (i == pieces.size - 1) left!! else it.multiply(p.amount).divide(total, PortfolioDecimal.MC)
            }
            if (share != null && !s.spouseGift) left = left!! - share
            val d = PortfolioDisposal(
                coin = coin, sellTxId = s.id, sellTime = s.time,
                lotTxId = p.lotTxId, acquiredTime = p.acquiredTime, lotKind = p.lotKind,
                amount = p.amount, costUsdt = p.cost, proceedsUsdt = share,
                gainUsdt = if (share != null && p.cost != null) share - p.cost else null,
                holdingDays = p.acquiredTime?.let { PortfolioFifo.holdingDays(it, s.time) },
                feeLeg = s.feeLeg, feeValueMissing = s.feeMissing,
                method = if (p.acquiredTime != null) CostMethod.FIFO else CostMethod.AVERAGE,
                costParts = p.parts, uncovered = p.uncovered, swap = s.swap, gift = s.gift, payment = s.payment, valueEstimated = s.estimated,
                match = p.match, matchedTime = p.matchedTime, oldHolding = p.oldHolding, spouseGift = s.spouseGift,
            )
            extra(p, d)
        }
    }

    // ------------------------------------------------------------------ UK

    /** Kurs eines Gebühr-Coins: erfasster Preis [recorded], Stablecoin 1, sonst [priceAt]; null = unbekannt. */
    private fun feePrice(coin: String, recorded: BigDecimal?, time: Long, priceAt: ((String, Long) -> BigDecimal?)?): BigDecimal? =
        recorded?.takeIf { it.signum() >= 0 } ?: BigDecimal.ONE.takeIf { !PortfolioStables.needsQuote(coin) }
            ?: priceAt?.invoke(coin, time)?.takeIf { it.signum() >= 0 }

    private fun ukPool(
        coin: String,
        steps: List<TaxStep>,
        zone: ZoneId,
        lost: MutableList<PortfolioLostFeeCost>,
        unassigned: MutableList<PortfolioUnassignedFee>,
    ): List<PortfolioDisposal> {
        // Netzwerkgebühren sind hier schon eigene Verkäufe ([applyRules], HMRC CRYPTO22280) — sie zählen so in der
        // Same-Day-Gruppe ihres Tags und in der 30-Tage-Regel wie jeder andere Abgang
        // Einzahlung ohne Kaufdatum: eigene Coins von anderswo sind kein Erwerb an diesem Tag (CRYPTO22200 ordnet nur
        // Erwerbe zu) — direkt in den Pool, nicht «gleicher Tag»/«30 Tage»; sie dürfen keinen früheren Verkauf umleiten
        fun late(s: TaxStep): Boolean = s.trade.type == PortfolioTxType.DEPOSIT && s.trade.acquiredTime == null
        // Einzahlung mit früherem ursprünglichem Kaufdatum: Umbuchung zwischen eigenen Wallets ist kein Erwerb — erworben
        // wurde am Kaufdatum, also «gleicher Tag»/«30 Tage» nach dem Kaufdatum (Teil des Tages-Zugangs dieses Tags); den
        // Rest übernimmt der Pool erst am BUCHUNGSTAG (die App kennt die Coins erst ab da, siehe Schritt 3)
        fun moved(s: TaxStep): Boolean = day(s.acquired, zone) != day(s.time, zone)
        // Zugänge je Tag (Erwerbsdatum) als ein Zugang zum Durchschnitt
        val dayPools = sortedMapOf<LocalDate, TaxPool>()
        val dayFirst = HashMap<LocalDate, Long>()
        // Menge je Tag noch nicht im Pool (alle Zugänge) bzw. am Tag selbst gebucht (ohne nachgebuchte Einzahlungen)
        val dayLeft = HashMap<LocalDate, BigDecimal>()
        val dayOwn = HashMap<LocalDate, BigDecimal>()
        for (s in steps) if (s.kind == TaxStepKind.ACQUIRE && !late(s)) {
            val d = day(s.acquired, zone)
            dayPools.getOrPut(d) { TaxPool() }.add(s.qty, s.cost, s.acquired)
            dayFirst[d] = minOf(dayFirst[d] ?: Long.MAX_VALUE, s.acquired)
            dayLeft[d] = (dayLeft[d] ?: BigDecimal.ZERO) + s.qty
            if (!moved(s)) dayOwn[d] = (dayOwn[d] ?: BigDecimal.ZERO) + s.qty
        }
        val disposes = steps.filter { it.kind == TaxStepKind.DISPOSE }
        val remaining = HashMap<TaxStep, BigDecimal>()
        val pieces = HashMap<TaxStep, ArrayList<Piece>>()
        disposes.forEach { remaining[it] = it.qty; pieces[it] = ArrayList() }
        // 1. Gleicher Tag: alle Abgänge des Tags gemeinsam gegen die Zugänge des Tags, anteilig nach Menge
        for ((d, group) in disposes.groupBy { day(it.time, zone) }) {
            val pool = dayPools[d] ?: continue
            val total = group.fold(BigDecimal.ZERO) { a, s -> a + s.qty }
            val matched = total.min(pool.held)
            if (matched.signum() <= 0) continue
            var left = matched
            group.forEachIndexed { i, s ->
                val want = if (i == group.size - 1) left else matched.multiply(s.qty).divide(total, PortfolioDecimal.MC)
                left -= want
                val taken = pool.take(want)
                remaining[s] = remaining.getValue(s) - taken.removed
                pieces.getValue(s) += piecesOf(taken, TaxMatch.SAME_DAY, dayFirst[d])
            }
        }
        // 2. 30 Tage: früheste Veräusserung zuerst, Zugänge der 30 Tage danach früheste zuerst
        for (s in disposes) {
            val d = day(s.time, zone)
            for ((ad, pool) in dayPools.subMap(d.plusDays(1), d.plusDays(TaxTemplate.UK_BB_DAYS + 1))) {
                val r = remaining.getValue(s)
                if (r.signum() <= 0) break
                if (pool.held.signum() <= 0) continue
                val taken = pool.take(r)
                remaining[s] = r - taken.removed
                pieces.getValue(s) += piecesOf(taken, TaxMatch.THIRTY_DAY, dayFirst[ad])
            }
        }
        // 3. Section-104-Pool: Rest der Tages-Zugänge am Tag des Zugangs (Anteil nachgebuchter Einzahlungen erst an ihrem
        // Buchungstag), Rest der Abgänge aus dem Pool
        val pool = TaxPool(zone)
        val added = HashSet<LocalDate>()
        val out = ArrayList<PortfolioDisposal>()
        // Anteil [q] der noch nicht übernommenen Menge des Tages [d] vom Rest seines Tages-Zugangs in den Pool (zuletzt: alles)
        fun enter(d: LocalDate, q: BigDecimal) {
            val dp = dayPools[d] ?: return
            val left = dayLeft.getValue(d)
            dayLeft[d] = left - q
            if (q >= left) {
                pool.absorb(dp)
                return
            }
            val taken = dp.take(dp.held.multiply(q).divide(left, PortfolioDecimal.MC))
            pool.addCarried(taken.removedPriced, taken.parts)
            pool.add(taken.removedUnpriced, null, dayFirst.getValue(d))
        }
        for (s in steps) {
            when (s.kind) {
                TaxStepKind.ACQUIRE -> {
                    val d = day(s.acquired, zone)
                    when {
                        late(s) -> pool.add(s.qty, s.cost, s.acquired)
                        moved(s) -> enter(d, s.qty)
                        added.add(d) -> enter(d, dayOwn.getValue(d))
                    }
                }
                TaxStepKind.DISPOSE -> {
                    val list = pieces.getValue(s)
                    val r = remaining.getValue(s)
                    if (r.signum() > 0) {
                        val taken = pool.take(r)
                        list += piecesOf(taken, TaxMatch.POOL)
                        val rest = r - taken.removed
                        if (rest.signum() > 0) list += Piece(rest, null, uncovered = true)
                    }
                    out += emit(coin, s, list, s.proceeds)
                }
                TaxStepKind.REMOVE -> pool.take(s.qty)
                TaxStepKind.NETWORK_FEE -> lost.note(coin, s, pool.dropQty(s.qty))
                TaxStepKind.ADD_FEE -> unassigned.noteUnassigned(coin, s, pool.addFee(s.time, s.cost ?: BigDecimal.ZERO))
            }
        }
        return out
    }

    // ------------------------------------------------------------------ CA

    /**
     * CA: Verlust einer Zeile [row] in der Steuerwährung (CAD) — Erlös zum Kurs des Verkaufs, Einstand je Herkunftsteil
     * zum Kurs seines Datums (wie der Export); Rückgabe = Verlust als USDT-Betrag zum Kurs des Verkaufs (der Export
     * rechnet ihn so exakt zurück), 0 = kein Verlust in CAD; null = ein Kurs fehlt.
     */
    private fun caLossAtSaleRate(row: PortfolioDisposal, fx: (Long) -> BigDecimal?): BigDecimal? {
        val mc = PortfolioDecimal.MC
        val proceeds = row.proceedsUsdt ?: return null
        val cost = row.costUsdt ?: return null
        val rate = fx(row.sellTime)?.takeIf { it.signum() > 0 } ?: return null
        var costT = BigDecimal.ZERO
        if (row.costParts.isNotEmpty()) {
            for (p in row.costParts) costT += p.usdt.multiply(fx(p.time) ?: return null, mc)
        } else if (cost.signum() != 0) {
            costT = cost.multiply(rate, mc)
        }
        val gainT = proceeds.multiply(rate, mc) - costT
        return if (gainT.signum() >= 0) BigDecimal.ZERO else gainT.negate().divide(rate, mc)
    }

    private fun caAcb(
        coin: String,
        steps: List<TaxStep>,
        zone: ZoneId,
        lost: MutableList<PortfolioLostFeeCost>,
        unassigned: MutableList<PortfolioUnassignedFee>,
        fx: ((Long) -> BigDecimal?)? = null,
    ): List<PortfolioDisposal> {
        val zero = BigDecimal.ZERO
        val days = steps.map { day(it.time, zone) }
        // Bestand nach jedem Schritt (nur Mengen) für «Bestand am Fensterende»
        val heldAfter = ArrayList<BigDecimal>(steps.size)
        var h = zero
        for (s in steps) {
            h = when (s.kind) {
                TaxStepKind.ACQUIRE -> h + s.qty
                TaxStepKind.DISPOSE, TaxStepKind.REMOVE, TaxStepKind.NETWORK_FEE -> (h - s.qty).max(zero)
                TaxStepKind.ADD_FEE -> h
            }
            heldAfter += h
        }
        val pool = TaxPool(zone)
        val pending = ArrayList<PortfolioCostPart>()
        // Ersatz-Menge je Zugang: deckt nur einen nicht abziehbaren Verlust (wird verbraucht)
        val replaceLeft = steps.map { if (it.kind == TaxStepKind.ACQUIRE) it.qty else zero }.toMutableList()
        // Nicht abziehbarer Verlust (Herkunft: Verkauf) für einen Ersatz NACH dem Verkauf: kommt bei diesem Zugang zum ACB
        val pendingAt = HashMap<Int, ArrayList<PortfolioCostPart>>()
        val out = ArrayList<PortfolioDisposal>()
        // Zugänge (Indizes, zeitlich): das Fenster per Binärsuche statt über alle Schritte je Verkauf
        val acquires = steps.indices.filter { steps[it].kind == TaxStepKind.ACQUIRE }
        /** Erster Index in [days] mit Tag ≥ [d] (die Schritte sind zeitlich, also die Tage aufsteigend). */
        fun firstDayAtLeast(d: LocalDate): Int {
            var lo = 0
            var hi = days.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (days[mid].isBefore(d)) lo = mid + 1 else hi = mid
            }
            return lo
        }

        /**
         * Ersatzkauf (superficial loss) nur ein Erwerb im Fenster: Einzahlung ohne Kaufdatum = eigene Coins von anderswo, nie
         * (wie UK `late`); sonst zählt das Erwerbsdatum (Einzahlung mit früherem Kaufdatum ausserhalb des Fensters: keiner).
         */
        fun replaces(s: TaxStep, from: LocalDate, to: LocalDate): Boolean {
            if (s.trade.type == PortfolioTxType.DEPOSIT && s.trade.acquiredTime == null) return false
            val a = day(s.acquired, zone)
            return !a.isBefore(from) && !a.isAfter(to)
        }

        /** Nicht abziehbarer Betrag zum ACB des Bestands (ohne Teil mit Kaufpreis: zum nächsten Zugang mit Kaufpreis). */
        fun toAcb(time: Long, amount: BigDecimal) {
            if (amount.signum() <= 0) return
            if (pool.priced.signum() > 0) pool.addCost(time, amount) else pending += PortfolioCostPart(time, amount)
        }
        steps.forEachIndexed { i, s ->
            when (s.kind) {
                TaxStepKind.ACQUIRE -> {
                    pool.add(s.qty, s.cost, s.acquired)
                    // Ersatz dieses Zugangs für einen früheren Verkauf: dessen nicht abziehbarer Verlust zum ACB
                    pendingAt.remove(i)?.forEach { toAcb(it.time, it.usdt) }
                    // Nicht abziehbarer Verlust ohne Bestand: zum nächsten Zugang mit Kaufpreis
                    if (s.cost != null && pending.isNotEmpty()) {
                        pending.forEach { pool.addCost(it.time, it.usdt) }
                        pending.clear()
                    }
                }
                TaxStepKind.DISPOSE -> {
                    val taken = pool.take(s.qty)
                    val list = ArrayList(piecesOf(taken))
                    val rest = s.qty - taken.removed
                    if (rest.signum() > 0) list += Piece(rest, null, uncovered = true)
                    val d = days[i]
                    val from = d.minusDays(TaxTemplate.CA_WINDOW_DAYS)
                    val to = d.plusDays(TaxTemplate.CA_WINDOW_DAYS)
                    // Noch nicht verbrauchte Ersatz-Zugänge im Fenster und Bestand am Ende des Fensters (Binärsuche über die
                    // aufsteigenden Tage: gleiche Indizes wie der frühere Durchlauf über alle Schritte)
                    val lo = firstDayAtLeast(from)
                    val afterTo = firstDayAtLeast(to.plusDays(1))
                    val endIndex = maxOf(i, afterTo - 1)
                    val window = ArrayList<Int>()
                    var k = acquires.binarySearch(lo).let { if (it < 0) -it - 1 else it }
                    while (k < acquires.size && acquires[k] < afterTo) {
                        if (replaces(steps[acquires[k]], from, to)) window += acquires[k]
                        k++
                    }
                    val bought = window.fold(zero) { a, j -> a + replaceLeft[j] }
                    val heldEnd = heldAfter[endIndex]
                    val n = s.qty.min(bought).min(heldEnd).max(zero)
                    var denied = zero
                    val rows = emit(coin, s, list, s.proceeds) { _, row ->
                        val g = row.gainUsdt.takeUnless { s.spouseGift }
                        // Verlust in der Steuerwährung entscheiden (Schenkung an Partner: kein Verlust, ITA 73(1)) (CAD: gemeldet wird in CAD — ein USDT-Verlust kann in CAD
                        // ein Gewinn sein); ohne Kurse in USDT
                        val loss = if (g == null) null else fx?.let { caLossAtSaleRate(row, it) } ?: g.negate().max(zero)
                        if (loss != null && loss.signum() > 0 && row.costUsdt != null && s.qty.signum() > 0) {
                            val part = if (n.compareTo(s.qty) == 0) loss else loss.multiply(n).divide(s.qty, PortfolioDecimal.MC)
                            if (part.signum() > 0) {
                                denied += part
                                return@emit row.copy(adjustmentUsdt = part)
                            }
                        }
                        row
                    }
                    out += rows
                    if (denied.signum() > 0 && n.signum() > 0) {
                        // Ersatz verbrauchen (früheste zuerst); Betrag anteilig zum ACB dieses Ersatzes
                        var need = n
                        val matched = ArrayList<Pair<Int, BigDecimal>>()
                        for (j in window) {
                            if (need.signum() <= 0) break
                            val q = replaceLeft[j].min(need)
                            if (q.signum() <= 0) continue
                            replaceLeft[j] = replaceLeft[j] - q
                            need -= q
                            matched += j to q
                        }
                        var left = denied
                        matched.forEachIndexed { k, (j, q) ->
                            val part = if (k == matched.size - 1) left else denied.multiply(q).divide(n, PortfolioDecimal.MC)
                            left -= part
                            if (j > i) pendingAt.getOrPut(j) { ArrayList() } += PortfolioCostPart(s.time, part) else toAcb(s.time, part)
                        }
                    }
                }
                TaxStepKind.REMOVE -> pool.take(s.qty)
                TaxStepKind.NETWORK_FEE -> lost.note(coin, s, pool.dropQty(s.qty))
                TaxStepKind.ADD_FEE -> unassigned.noteUnassigned(coin, s, pool.addFee(s.time, s.cost ?: zero))
            }
        }
        return out
    }

    // ------------------------------------------------------------------ JP

    /** Herkunftsteile [parts] (Summe [total]) auf den Anteil [target] skaliert; Summe exakt [target]. */
    private fun scaleParts(parts: List<PortfolioCostPart>, total: BigDecimal, target: BigDecimal): List<PortfolioCostPart> {
        if (target.signum() == 0 || total.signum() == 0 || parts.isEmpty()) return emptyList()
        if (target.compareTo(total) == 0) return parts.toList()
        var left = target
        return parts.mapIndexed { i, p ->
            val v = if (i == parts.size - 1) left else p.usdt.multiply(target).divide(total, PortfolioDecimal.MC)
            left -= v
            PortfolioCostPart(p.time, v)
        }
    }

    private fun jpTotal(coin: String, steps: List<TaxStep>, zone: ZoneId, lost: MutableList<PortfolioLostFeeCost>): List<PortfolioDisposal> {
        val zero = BigDecimal.ZERO
        val mc = PortfolioDecimal.MC
        var carryPriced = zero
        var carryUnpriced = zero
        var carryCost = zero
        var carryParts: List<PortfolioCostPart> = emptyList()
        val out = ArrayList<PortfolioDisposal>()
        for ((_, ys) in steps.groupBy { day(it.time, zone).year }.toSortedMap()) {
            // Jahressumme: Anfangsbestand + Zugänge des Jahres (Gebühren von Umbuchungen gehören nicht zum Erwerbspreis)
            var totPriced = carryPriced
            var totUnpriced = carryUnpriced
            var totCost = carryCost
            val parts = ArrayList(carryParts)
            for (s in ys) if (s.kind == TaxStepKind.ACQUIRE) {
                if (s.cost != null) {
                    totPriced += s.qty
                    totCost += s.cost
                    if (s.cost.signum() != 0) parts += PortfolioCostPart(s.acquired, s.cost)
                } else totUnpriced += s.qty
            }
            val totQty = totPriced + totUnpriced
            val share = if (totQty.signum() > 0) totPriced.divide(totQty, mc) else BigDecimal.ONE
            val unit = if (totPriced.signum() > 0) totCost.divide(totPriced, mc) else zero
            var held = carryPriced + carryUnpriced
            var costOut = zero
            fun remove(qty: BigDecimal): TaxTaken {
                val removed = qty.min(held).max(zero)
                held -= removed
                val removedPriced = if (share.compareTo(BigDecimal.ONE) == 0) removed else removed.multiply(share, mc)
                val c = if (totPriced.signum() > 0) removedPriced.multiply(unit, mc) else zero
                costOut += c
                return TaxTaken(removed, removedPriced, c, scaleParts(parts, totCost, c))
            }
            var lastFee: TaxStep? = null
            for (s in ys) {
                when (s.kind) {
                    TaxStepKind.ACQUIRE -> held += s.qty
                    TaxStepKind.DISPOSE -> {
                        val taken = remove(s.qty)
                        val list = ArrayList(piecesOf(taken))
                        val rest = s.qty - taken.removed
                        if (rest.signum() > 0) list += Piece(rest, null, uncovered = true)
                        out += emit(coin, s, list, s.proceeds)
                    }
                    TaxStepKind.REMOVE -> remove(s.qty)
                    TaxStepKind.NETWORK_FEE -> {
                        held = (held - s.qty).max(zero)
                        lastFee = s
                    }
                    TaxStepKind.ADD_FEE -> Unit
                }
            }
            // Übertrag: Restmenge (Anteil mit Kaufpreis wie im Jahr), Rest-Einstand samt Herkunft
            carryPriced = if (share.compareTo(BigDecimal.ONE) == 0) held else held.multiply(share, mc)
            carryUnpriced = held - carryPriced
            val restRaw = (totCost - costOut).max(zero)
            // Ohne Rest mit Kaufpreis: Einstand der Netzwerkgebühren nicht still verlieren, sondern vermerken
            val fee = lastFee
            if (fee != null && (carryPriced.signum() <= 0 || PortfolioDecimal.isDust(carryPriced)) && !PortfolioDecimal.isDust(restRaw)) {
                lost.note(coin, fee, restRaw)
            }
            val restCost = if (carryPriced.signum() > 0) restRaw else zero
            carryParts = scaleParts(parts, totCost, restCost)
            carryCost = restCost
        }
        return out
    }

    // ------------------------------------------------------------------ AT

    /** Altbestand-Posten (FIFO). */
    private class AtLot(val txId: Long, val acquired: Long, val kind: PortfolioTxType, var qty: BigDecimal, var cost: BigDecimal?)

    /** Topf eines Coins in EINER Wallet ([wallet] = Anzeigename, leer = ohne Angabe). */
    private class AtCoin(val wallet: String, zone: ZoneId) {
        val old = ArrayList<AtLot>()
        val neu = TaxPool(zone)
        val oldHeld: BigDecimal get() = old.fold(BigDecimal.ZERO) { a, l -> a + l.qty }

        /** Altbestand-Posten nach Erwerb (dann Id) einsortieren (Umbuchung). */
        fun insertOld(lot: AtLot) {
            val i = old.indexOfFirst { it.acquired > lot.acquired || (it.acquired == lot.acquired && it.txId > lot.txId) }
            if (i < 0) old += lot else old.add(i, lot)
        }
    }

    /** Ereignisse aller Coins in Reihenfolge der Transaktionen: Haupt-Coin, Gebühr-Coin, erhaltener Coin. */
    private fun mergedEvents(real: List<PortfolioTrade>): List<Pair<String, PortfolioLedgerEvent>> {
        val out = ArrayList<Pair<String, PortfolioLedgerEvent>>()
        // Gleiche Reihenfolge wie das Buch ([PortfolioLedger.ORDER]): Zeit, bei gleicher Zeit Zugänge vor Abgängen, dann Id
        for (t in real.sortedWith(compareBy(PortfolioLedger.ORDER) { PortfolioLedgerEvent(it, false) })) {
            out += PortfolioCalculator.normalizeCoin(t.coin) to PortfolioLedgerEvent(t, false)
            t.thirdFeeCoin?.let { out += it to PortfolioLedgerEvent(t, true) }
            t.swapToCoin?.let { out += it to PortfolioLedgerEvent(t, false, swapIn = true) }
        }
        return out
    }

    private fun atSplit(real: List<PortfolioTrade>, zone: ZoneId, lost: MutableList<PortfolioLostFeeCost>): List<PortfolioDisposal> {
        val zero = BigDecimal.ZERO
        val mc = PortfolioDecimal.MC
        val newFrom = TaxTemplate.AT_NEW_FROM.atStartOfDay(zone).toInstant().toEpochMilli()
        val neutralFrom = TaxTemplate.AT_SWAP_NEUTRAL_FROM.atStartOfDay(zone).toInstant().toEpochMilli()
        // Topf je (Coin, Wallet-Schlüssel wie FIFO je Wallet); Anzeigename = erste Schreibweise
        val coins = HashMap<Pair<String, String>, AtCoin>()
        val names = HashMap<String, String>()
        val out = ArrayList<PortfolioDisposal>()
        // Neubestand-Teil des letzten Tauschs (Trade-Id → was auf den erhaltenen Coin übergeht); [realized] = Tausch vor dem
        // 1.3.2022: ganz eine Veräusserung, der erhaltene Coin angeschafft zum Wert am Tag des Tauschs
        class Carry(val total: BigDecimal, val oldQty: BigDecimal, val taken: TaxTaken, val value: BigDecimal?, val realized: Boolean)
        val carries = HashMap<Long, Carry>()

        fun pot(coin: String, raw: String?): AtCoin {
            val key = PortfolioWallets.key(raw)
            val name = names.getOrPut(key) { raw?.trim().orEmpty() }
            return coins.getOrPut(coin to key) { AtCoin(name, zone) }
        }

        /** Nimmt höchstens [qty] aus dem Altbestand (FIFO, früher erworben zuerst). */
        fun takeOld(c: AtCoin, qty: BigDecimal): List<Piece> {
            val pieces = ArrayList<Piece>()
            var left = qty
            while (left.signum() > 0 && c.old.isNotEmpty()) {
                val lot = c.old[0]
                val q = left.min(lot.qty)
                val cost = lot.cost?.let { if (q.compareTo(lot.qty) == 0) it else it.multiply(q).divide(lot.qty, mc) }
                lot.qty -= q
                if (cost != null) lot.cost = lot.cost!! - cost
                if (lot.qty.signum() <= 0) c.old.removeAt(0)
                left -= q
                pieces += Piece(q, cost, acquiredTime = lot.acquired, lotTxId = lot.txId, lotKind = lot.kind, oldHolding = true)
            }
            return pieces
        }

        /** Nimmt [qty] zuerst aus dem Altbestand (FIFO), dann aus dem Neubestand. */
        fun takeBoth(c: AtCoin, qty: BigDecimal): Pair<List<Piece>, TaxTaken> {
            val pieces = takeOld(c, qty)
            val taken = c.neu.take(qty - pieces.fold(zero) { a, p -> a + p.amount })
            return pieces to taken
        }

        /**
         * Anschaffungskosten eines Zugangs im Neubestand: Menge × Preis ohne Gebühren (§ 27a Abs. 4 Z 2 EStG: ohne
         * Anschaffungsnebenkosten; [s] hat schon die erhaltene Menge, Gebühr USDT bzw. im dritten Coin kommt nicht dazu).
         * Belohnung: 0 (BMF: Staking, Airdrop — § 27b Abs. 2 EStG); Zins ([PortfolioTrade.interestIncome]) zum Wert beim
         * Zufluss, denn er ist dort schon Einkunft (§ 27b Abs. 2 Z 1 EStG).
         */
        fun newCost(s: TaxStep): BigDecimal? =
            if (s.trade.type == PortfolioTxType.REWARD && !s.trade.interestIncome) zero else s.trade.validPrice?.let { s.qty * it }

        /**
         * Veräusserung ohne Tausch (Verkauf, Zahlung, Gebühr im dritten Coin). Altbestand (§ 31 EStG a. F.) mit Gebühren:
         * Erlös nach Gebühren, die Gebühr im Coin geht mit ihrem Einstand ab. Neubestand ohne Gebühren (§ 20 Abs. 2 EStG,
         * BMF): Erlös vor Gebühren; die Gebühr im Coin (anteilig nach Menge) ist keine Veräusserung — Menge sinkt, der
         * Einstand bleibt im Rest wie bei der Netzwerkgebühr einer Umbuchung.
         */
        fun sale(coin: String, c: AtCoin, s: TaxStep): List<PortfolioDisposal> {
            val oldPieces = takeOld(c, s.qty)
            val oldQty = oldPieces.fold(zero) { a, p -> a + p.amount }
            val newQty = s.qty - oldQty
            // Gebühr im dritten Coin: der ganze Schritt ist die Gebühr, darin steckt keine Gebühr im Coin
            val fee = if (s.feeLeg) zero else s.trade.coinFee
            val newFee = if (fee.signum() == 0 || newQty.signum() <= 0) zero else newQty.multiply(fee).divide(s.qty, mc)
            val taken = c.neu.take(newQty - newFee)
            lost.note(coin, s, c.neu.dropQty(newFee))
            fun oldShare(v: BigDecimal?): BigDecimal? = v?.let { if (oldQty.compareTo(s.qty) == 0) it else it.multiply(oldQty).divide(s.qty, mc) }
            val newPieces = ArrayList(piecesOf(taken, oldHolding = false))
            val rest = newQty - newFee - taken.removed
            if (rest.signum() > 0) newPieces += Piece(rest, null, uncovered = true)
            val inWallet: (Piece, PortfolioDisposal) -> PortfolioDisposal = { _, d -> d.copy(wallet = c.wallet) }
            return emit(coin, s, oldPieces, oldShare(s.proceeds), inWallet) +
                emit(coin, s, newPieces, s.gross?.let { it - oldShare(it)!! }, inWallet)
        }

        /** Netzwerkgebühr: zuerst Altbestand (Einstand auf den nächsten Altbestand-Posten mit Kaufpreis), dann Neubestand. */
        fun networkFee(coin: String, c: AtCoin, s: TaxStep) {
            val fromOld = s.qty.min(c.oldHeld)
            if (fromOld.signum() > 0) {
                val carried = takeBoth(c, fromOld).first.fold(zero) { a, p -> a + (p.cost ?: zero) }
                val target = c.old.firstOrNull { it.cost != null }
                if (target != null) target.cost = target.cost!! + carried else lost.note(coin, s, carried)
            }
            lost.note(coin, s, c.neu.dropQty(s.qty - fromOld))
        }

        for ((coin, e) in mergedEvents(real)) {
            val t = e.trade
            // Umbuchung: Gebühr in der Quelle, dann wandern Altbestand-Posten (FIFO) und Neubestand-Anteil zum Ziel
            if (!e.feeLeg && !e.swapIn && t.type == PortfolioTxType.TRANSFER) {
                val src = pot(coin, t.transferFrom)
                val dst = pot(coin, t.transferTo)
                for (s in steps(listOf(e))) when (s.kind) {
                    TaxStepKind.NETWORK_FEE -> networkFee(coin, src, s)
                    // Gebühren einer Umbuchung erhöhen den Einstand nicht
                    TaxStepKind.ADD_FEE, TaxStepKind.ACQUIRE, TaxStepKind.DISPOSE, TaxStepKind.REMOVE -> Unit
                }
                if (src !== dst && t.amount.signum() > 0) {
                    val (oldPieces, taken) = takeBoth(src, t.amount)
                    for (p in oldPieces) dst.insertOld(AtLot(p.lotTxId!!, p.acquiredTime!!, p.lotKind!!, p.amount, p.cost))
                    dst.neu.addCarried(taken.removedPriced, taken.parts)
                    dst.neu.add(taken.removedUnpriced, null, t.time)
                }
                continue
            }
            val c = pot(coin, PortfolioWallets.of(e))
            for (s in steps(listOf(e))) when (s.kind) {
                TaxStepKind.ACQUIRE -> {
                    val carry = if (s.swap) carries.remove(s.id) else null
                    if (carry != null && carry.realized) {
                        // Tausch vor dem 1.3.2022 = Anschaffung zum Wert des Tauschs: vor dem 1.3.2021 Altbestand (Frist 1 Jahr ab
                        // dem Tausch), danach Neubestand
                        if (s.time < newFrom) {
                            c.insertOld(AtLot(s.id, s.time, s.trade.type, s.qty, carry.value))
                        } else {
                            c.neu.add(s.qty, carry.value, s.time)
                        }
                    } else if (carry == null) {
                        if (s.acquired < newFrom) c.old += AtLot(s.id, s.acquired, s.trade.type, s.qty, s.cost)
                        else c.neu.add(s.qty, newCost(s), s.acquired)
                    } else if (carry.total.signum() > 0) {
                        // Erhaltene Menge anteilig: aus Altbestand (Veräusserung, Einstand = Wert), aus Neubestand mit
                        // fortgeführtem Einstand, ohne Kaufpreis bzw. ungedeckt ohne Kaufpreis
                        fun part(q: BigDecimal): BigDecimal = if (q.compareTo(carry.total) == 0) s.qty else s.qty.multiply(q).divide(carry.total, mc)
                        val fromOld = part(carry.oldQty)
                        if (fromOld.signum() > 0) {
                            c.neu.add(fromOld, carry.value?.let { v -> if (carry.oldQty.compareTo(carry.total) == 0) v else v.multiply(carry.oldQty).divide(carry.total, mc) }, s.time)
                        }
                        val fromPriced = part(carry.taken.removedPriced)
                        if (fromPriced.signum() > 0) c.neu.addCarried(fromPriced, carry.taken.parts)
                        val restQty = s.qty - fromOld - fromPriced
                        if (restQty.signum() > 0) c.neu.add(restQty, null, s.time)
                    } else {
                        c.neu.add(s.qty, null, s.time)
                    }
                }
                TaxStepKind.DISPOSE -> if (!s.swap) out += sale(coin, c, s) else {
                    val (oldPieces, taken) = takeBoth(c, s.qty)
                    val oldQty = oldPieces.fold(zero) { a, p -> a + p.amount }
                    val inWallet: (Piece, PortfolioDisposal) -> PortfolioDisposal = { _, d -> d.copy(wallet = c.wallet) }
                    if (s.time < neutralFrom) {
                        // Tausch vor dem 1.3.2022: alte Rechtslage (§ 31 EStG a. F.) — Tausch = Veräusserung des ganzen
                        // abgegebenen Coins (Alt- und Neubestand, EStR Rz 6103k), der erhaltene Coin ist neu angeschafft
                        val list = ArrayList<Piece>(oldPieces)
                        list += piecesOf(taken, oldHolding = false)
                        val rest = s.qty - oldQty - taken.removed
                        if (rest.signum() > 0) list += Piece(rest, null, uncovered = true)
                        out += emit(coin, s, list, s.proceeds, inWallet)
                        carries[s.id] = Carry(s.qty, oldQty, taken, s.gross, true)
                    } else {
                        // Tausch ab 1.3.2022: nur der Altbestand-Teil ist eine Veräusserung (§ 31 EStG a. F., Rz 6103k); Neubestand
                        // geht steuerneutral über (§ 27b Abs. 3 Z 2 EStG) — auch in einen Stablecoin (USDT, USDC …): der
                        // Einstand geht mit, steuerbar erst der Verkauf gegen Euro
                        val proceedsOld = s.proceeds?.let { p -> if (oldQty.compareTo(s.qty) == 0) p else p.multiply(oldQty).divide(s.qty, mc) }
                        if (oldQty.signum() > 0) out += emit(coin, s, oldPieces, proceedsOld, inWallet)
                        carries[s.id] = Carry(s.qty, oldQty, taken, s.gross, false)
                    }
                }
                TaxStepKind.REMOVE -> takeBoth(c, s.qty)
                TaxStepKind.NETWORK_FEE -> networkFee(coin, c, s)
                TaxStepKind.ADD_FEE -> Unit
            }
        }
        return out
    }

    // ------------------------------------------------------------------ FR

    private fun frPta(
        real: List<PortfolioTrade>,
        priceAt: ((String, Long) -> BigDecimal?)?,
        outflows: MutableList<TaxOutflow>,
        /** Adress-Einträge (beobachtete Adressen, «im Portfolio zählen»): Teil des Gesamtwerts, keine Abtretungen. */
        addressTrades: List<PortfolioTrade> = emptyList(),
    ): List<PortfolioDisposal> {
        val zero = BigDecimal.ZERO
        val mc = PortfolioDecimal.MC
        val books = LinkedHashMap<String, PortfolioHoldingsBook>()
        // Guthaben beobachteter Adressen je Coin bis zum laufenden Zeitpunkt (Einzahlung +, Auszahlung −, nie unter 0)
        val addrSorted = addressTrades.filter { it.amount.signum() > 0 }
            .sortedWith(compareBy(PortfolioLedger.ORDER) { PortfolioLedgerEvent(it, false) })
        val addr = LinkedHashMap<String, BigDecimal>()
        var ai = 0
        fun advanceAddresses(time: Long) {
            while (ai < addrSorted.size && addrSorted[ai].time <= time) {
                val a = addrSorted[ai++]
                val c = PortfolioCalculator.normalizeCoin(a.coin)
                val d = if (a.type == PortfolioTxType.WITHDRAWAL) a.amount.negate() else a.amount
                addr[c] = ((addr[c] ?: zero) + d).max(zero)
            }
        }
        var pta = zero
        var ptaParts: List<PortfolioCostPart> = emptyList()
        var incomplete = false
        val out = ArrayList<PortfolioDisposal>()
        val events = mergedEvents(real)

        /**
         * Gesamtwert aller Bestände unmittelbar vor [time] — eigene Bestände und Guthaben beobachteter Adressen (zum selben
         * Kurs: erfasst, Stablecoin, sonst priceAt); [coin] zu [price]; null = ein Kurs fehlt (Einstand leer, unvollständig).
         */
        fun portfolioValue(coin: String, price: BigDecimal?, time: Long): BigDecimal? {
            var value: BigDecimal? = zero
            val coins = LinkedHashSet<String>(books.keys)
            coins += addr.keys
            for (c in coins) {
                val q = (books[c]?.total ?: zero) + (addr[c] ?: zero)
                if (q.signum() <= 0 || PortfolioDecimal.isDust(q)) continue
                val p = when {
                    c == coin -> price
                    !PortfolioStables.needsQuote(c) -> BigDecimal.ONE
                    else -> priceAt?.invoke(c, time)?.takeIf { it.signum() >= 0 }
                }
                value = if (p == null || value == null) null else value + q.multiply(p, mc)
            }
            return value
        }

        /** Kürzt die PTA um den Anteil [part] / [value] (gekappt auf 1); Rückgabe = Kürzung samt Herkunft. */
        fun reduce(part: BigDecimal, value: BigDecimal): Pair<BigDecimal, List<PortfolioCostPart>> {
            val f = part.divide(value, mc).min(BigDecimal.ONE)
            val c = if (f.compareTo(BigDecimal.ONE) == 0) pta else pta.multiply(f, mc)
            val parts = scaleParts(ptaParts, pta, c)
            ptaParts = scaleParts(ptaParts, pta, pta - c)
            pta -= c
            return c to parts
        }

        var i = 0
        while (i < events.size) {
            val t = events[i].second.trade
            var j = i
            while (j < events.size && events[j].second.trade.id == t.id) j++
            val coin = PortfolioCalculator.normalizeCoin(t.coin)
            advanceAddresses(t.time)
            if (t.amount.signum() > 0) {
                val price = t.validPrice
                val feeValue = t.thirdFeeValue
                // Kurs des Haupt-Coins für Gebühr und Abgang: Preis, Wert des Tauschs je Coin, Stablecoin 1, sonst priceAt
                val unit = price ?: t.swapValue?.divide(t.amount, mc)
                    ?: BigDecimal.ONE.takeIf { !PortfolioStables.needsQuote(coin) }
                    ?: priceAt?.invoke(coin, t.time)?.takeIf { it.signum() >= 0 }
                // Wert der Gebühr-Abtretungen dieser Transaktion: der Gesamtwert danach ist um so viel kleiner
                var spent = zero

                /**
                 * Gebühr in Krypto ausserhalb eines Verkaufs: eigene Abtretung gegen eine Dienstleistung
                 * (BOI-RPPM-PVBMC-30-10 § 70 Remarque 1) — Abtretungspreis = Wert der Gebühr, keine weiteren Gebühren.
                 */
                fun feeCession(feeCoin: String, qty: BigDecimal, p: BigDecimal?, feeLeg: Boolean) {
                    if (qty.signum() <= 0) return
                    val held = books[feeCoin]?.total ?: zero
                    val gross = p?.let { qty.multiply(it, mc) }
                    val value = portfolioValue(feeCoin, p, t.time)?.let { it - spent }
                    val wasIncomplete = incomplete
                    var cost: BigDecimal? = null
                    var parts: List<PortfolioCostPart> = emptyList()
                    if (gross != null && value != null && value.signum() > 0) {
                        val (c, pp) = reduce(gross, value)
                        cost = c
                        parts = pp
                    } else {
                        incomplete = true
                    }
                    if (gross != null) spent += gross
                    out += PortfolioDisposal(
                        coin = feeCoin, sellTxId = t.id, sellTime = t.time,
                        lotTxId = null, acquiredTime = null, lotKind = null,
                        amount = qty, costUsdt = cost, proceedsUsdt = gross,
                        gainUsdt = if (gross != null && cost != null) gross - cost else null,
                        holdingDays = null, feeLeg = feeLeg, feeValueMissing = false,
                        method = CostMethod.AVERAGE, costParts = parts, uncovered = qty > held,
                        costIncomplete = wasIncomplete || cost == null, portfolioValueUsdt = value, grossProceedsUsdt = gross,
                    )
                }

                /** Gebühr im dritten Coin (ausser beim Verkauf): eigene Abtretung zum erfassten Preis bzw. priceAt. */
                fun thirdFeeCession() {
                    val tc = t.thirdFeeCoin ?: return
                    feeCession(tc, t.thirdFeeAmount, t.thirdFeePrice ?: priceAt?.invoke(tc, t.time)?.takeIf { it.signum() >= 0 }, true)
                }
                when (t.type) {
                    PortfolioTxType.BUY, PortfolioTxType.REWARD, PortfolioTxType.DEPOSIT -> {
                        // Gebühr im dritten Coin: Abtretung vor dem Zugang (Gesamtwert ohne den neuen Coin)
                        thirdFeeCession()
                        if (price != null) {
                            val c = t.amount * price + t.quoteFee + (feeValue ?: zero)
                            pta += c
                            if (c.signum() != 0) ptaParts = ptaParts + PortfolioCostPart(t.acquisitionTime, c)
                        } else {
                            // Ohne Preis (z. B. Einzahlung ohne Kaufpreis): zum Wert 0 (BOFiP § 90), aber unvollständig
                            incomplete = true
                        }
                    }
                    // Damit bezahlt: Abtretung zum Marktwert wie ein Verkauf (Zahlung mit Krypto = cession à titre onéreux)
                    PortfolioTxType.SELL, PortfolioTxType.PAYMENT -> {
                        val held = books[coin]?.total ?: zero
                        // Eine Operation (BOFiP § 50): Preis vor Gebühren = (Menge + Gebühr im Coin) × Preis + Wert der Gebühr
                        // im dritten Coin; Gebühren = Gebühr USDT + dieselben Gebühr-Werte → Erlös = Menge × Preis − Gebühr USDT
                        val gross = price?.let { (t.amount + t.coinFee) * it + (feeValue ?: zero) }
                        val net = price?.let { t.amount * it - t.quoteFee }
                        // Über den erfassten Bestand hinaus: nur der gedeckte Anteil ist eine Abtretung mit Einstand
                        val requested = t.amount + t.coinFee
                        val removed = requested.min(held.max(zero))
                        val share = when {
                            removed.signum() <= 0 -> zero
                            removed.compareTo(requested) == 0 -> BigDecimal.ONE
                            else -> removed.divide(requested, mc)
                        }
                        fun part(v: BigDecimal?): BigDecimal? = v?.let { if (share.compareTo(BigDecimal.ONE) == 0) it else it.multiply(share, mc) }
                        val grossCovered = part(gross)
                        val netCovered = part(net)
                        val feeMissing = gross != null && feeValue == null
                        if (removed.signum() > 0) {
                            // Gesamtwert unmittelbar vor der Abtretung (verkaufter Coin zum Verkaufspreis)
                            val value = portfolioValue(coin, price, t.time)
                            val wasIncomplete = incomplete
                            var cost: BigDecimal? = null
                            var parts: List<PortfolioCostPart> = emptyList()
                            if (grossCovered != null && value != null && value.signum() > 0) {
                                val (c, p) = reduce(grossCovered, value)
                                cost = c
                                parts = p
                            } else {
                                // Formel nicht anwendbar: die Erwerbspreis-Summe danach ist nicht mehr gesichert
                                incomplete = true
                            }
                            out += PortfolioDisposal(
                                coin = coin, sellTxId = t.id, sellTime = t.time,
                                lotTxId = null, acquiredTime = null, lotKind = null,
                                amount = removed, costUsdt = cost, proceedsUsdt = netCovered,
                                gainUsdt = if (netCovered != null && cost != null) netCovered - cost else null,
                                holdingDays = null, feeLeg = false, feeValueMissing = feeMissing,
                                method = CostMethod.AVERAGE, costParts = parts,
                                // Ohne Gesamtwert (Kurs fehlt) ist schon diese Abtretung unvollständig
                                costIncomplete = wasIncomplete || cost == null, portfolioValueUsdt = value, grossProceedsUsdt = grossCovered,
                            )
                        }
                        if (removed < requested) {
                            // Rest ohne erfassten Bestand: Erlös bekannt, Einstand unbekannt — zählt nicht in der Summe
                            out += PortfolioDisposal(
                                coin = coin, sellTxId = t.id, sellTime = t.time,
                                lotTxId = null, acquiredTime = null, lotKind = null,
                                amount = requested - removed, costUsdt = null,
                                proceedsUsdt = net?.let { it - (netCovered ?: zero) }, gainUsdt = null,
                                holdingDays = null, feeLeg = false, feeValueMissing = feeMissing,
                                method = CostMethod.AVERAGE, uncovered = true,
                                costIncomplete = true, grossProceedsUsdt = gross?.let { it - (grossCovered ?: zero) },
                            )
                        }
                    }
                    // Verschenkt = Abtretung «à titre gratuit»: keine Veräusserung, PTA anteilig gekürzt wie die Auszahlung
                    PortfolioTxType.WITHDRAWAL, PortfolioTxType.GIFT -> {
                        // Gebühren in Krypto zuerst als eigene Abtretungen
                        feeCession(coin, t.coinFee, unit, false)
                        thirdFeeCession()
                        // Abgang ohne Verkauf (Schenkung, Verlust, unbekannt): PTA anteilig kürzen (BOFiP § 100,
                        // «à titre gratuit»), Wert = Menge × Kurs (erfasst, Stablecoin oder priceAt)
                        val qty = t.amount
                        val v = unit?.let { qty.multiply(it, mc) }
                        val value = portfolioValue(coin, unit, t.time)?.let { it - spent }
                        var reduction: BigDecimal? = null
                        if (v != null && value != null && value.signum() > 0) {
                            reduction = reduce(v, value).first
                        } else {
                            // Kurs fehlt: Kürzung unbekannt, die PTA danach nicht mehr gesichert
                            incomplete = true
                        }
                        outflows += TaxOutflow(coin, t.id, t.time, qty, v, reduction)
                    }
                    // Tausch Krypto↔Krypto: aufgeschoben (sursis d'imposition), PTA unverändert — auch in einen Stablecoin
                    // (USDT, USDC …): er bleibt im Gesamtwert, steuerbar erst sein Verkauf gegen Euro. Gebühren in Krypto
                    // (abgegebener bzw. dritter Coin) sind eigene Abtretungen
                    // Umbuchung zwischen eigenen Wallets: bleibt im Portfolio — Netzwerkgebühr (Coin, dritter Coin) dito
                    PortfolioTxType.SWAP, PortfolioTxType.TRANSFER -> {
                        feeCession(coin, t.coinFee, unit, false)
                        thirdFeeCession()
                    }
                }
            }
            for (k in i until j) {
                val (c, e) = events[k]
                books.getOrPut(c) { PortfolioHoldingsBook(CostMethod.FIFO) }.apply(e)
            }
            i = j
        }
        return out
    }
}

/**
 * [TaxMethod.FR_PTA]: Abgang ohne Verkauf (Auszahlung) — abgegebene Menge (ohne Gebühr: die ist eine Abtretung), Wert beim Abgang und
 * Kürzung der Erwerbspreis-Summe (USDT); null = Kurs fehlt (Kürzung unbekannt).
 */
data class TaxOutflow(
    val coin: String,
    val txId: Long,
    val time: Long,
    val amount: BigDecimal,
    val valueUsdt: BigDecimal?,
    val ptaReductionUsdt: BigDecimal?,
)
