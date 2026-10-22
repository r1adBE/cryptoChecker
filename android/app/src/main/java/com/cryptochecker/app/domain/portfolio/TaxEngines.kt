package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal
import java.text.Normalizer
import java.time.LocalDate
import java.time.ZoneId

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
 *    Netzwerkgebühr einer Umbuchung). Altbestand (§ 31 EStG a. F.) unverändert mit Gebühren, ebenso ein Verkauf von
 *    Neubestand vor dem 1.3.2022 (noch alte Rechtslage wie der Tausch in dieser Zeit). Ebenso keine Zeile für eine
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
 * dritter Coin einer Auszahlung — je eine Veräusserung mit Erlös 0 wie bei den Einstandsmethoden. FR ohne Zeile für den
 * verbrauchten Zugang: sein Preis zählt schon in der Erwerbspreis-Summe.
 * Netzwerkgebühr im Coin ohne Regel [TaxEventRules.networkFeeIsDisposal] (AT; CA/JP nur ohne Vorlage-Regeln): Einstand
 * bleibt im Rest; ohne Rest mit Kaufpreis wird er als [PortfolioLostFeeCost] vermerkt ([lostFeeCosts]) wie beim FIFO
 * des Portfolios. Gebühr USDT/dritter Coin einer Umbuchung im Pool (UK/CA nur ohne Vorlage-Regeln, [TaxPool.addFee]):
 * der Anteil des Bestands ohne Kaufpreis und der Menge über Bestand − Netzwerkgebühr wird als [PortfolioUnassignedFee] vermerkt.
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
     * Anschaffungskosten = Wert beim Zufluss statt 0. Staking, Earn-Reward, Airdrop: nein. Nur ganze Wörter (auch
     * «LendingInterest», «interest_payment»): «Verzinsung», «Zinseszins» oder «keine Verzinsung» sind kein Zins.
     */
    fun interestReward(text: String?): Boolean {
        if (text.isNullOrEmpty()) return false
        return words(Normalizer.normalize(text, Normalizer.Form.NFC)).any { it in INTEREST_WORDS }
    }

    /** Wörter in Kleinbuchstaben: Trenner ist alles ausser Buchstaben/Ziffern und der Wechsel klein → gross. */
    private fun words(text: String): List<String> {
        val out = ArrayList<String>()
        val word = StringBuilder()
        fun flush() {
            if (word.isNotEmpty()) out += word.toString().lowercase()
            word.clear()
        }
        var prevLower = false
        for (c in text) {
            if (!c.isLetterOrDigit()) {
                flush()
                prevLower = false
                continue
            }
            if (prevLower && c.isUpperCase()) flush()
            word.append(c)
            prevLower = c.isLowerCase()
        }
        flush()
        return out
    }

    private val INTEREST_WORDS = setOf(
        "interest", "interests", "lending", "zins", "zinsen", "zinsertrag", "zinserträge", "zinsgutschrift",
        "interet", "interets", "intérêt", "intérêts",
    )

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
     * [priceAt] = Tagesschluss des UTC-Tags der Transaktion, [TaxDayClose]; ohne Kurs Erlös unbekannt) mit Id und Zeit
     * der Transaktion und ihrer Quell-Wallet (Umbuchung: «von», sonst die Börse) — VOR ihr in die Liste, damit die
     * Gebühr-Coins (stabil sortiert, [PortfolioLedger.ORDER]) die Quelle zuerst verlassen. Die Transaktion selbst verliert diese Gebühr: eine Umbuchung
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
            // FR: Gesamtwert samt beobachteter Adressen ohne Einstand ([AddressBalance.isAddressPart]); Eröffnungsposten
            // stecken schon in `real` (Erwerbspreis-Summe und Bestand)
            TaxMethod.FR_PTA -> frPta(real, priceAt, outflows, trades.filter { AddressBalance.isAddressPart(it.id) })
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
                // FR: der ganze Kaufpreis samt Gebühr steckt schon in der Erwerbspreis-Summe (frPta) — eine Verlust-Zeile
                // für den verbrauchten Zugang zählte ihn ein zweites Mal
                if (d.feeExpense == PortfolioFeeExpense.CONSUMED) return@mapNotNull null
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
}
