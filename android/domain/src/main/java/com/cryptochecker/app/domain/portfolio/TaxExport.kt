package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal
import java.util.NavigableMap
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Steuer-Exporte eines Kalenderjahrs (reines Kotlin, Spiegel: `TaxExport.swift`) — keine Steuerberatung:
 *  - Realisierte Gewinne: je Veräusserung mit Verkaufsdatum im Jahr ([PortfolioCalculator.disposals] nach der
 *    Einstandsmethode; FIFO je Posten, Ø je Verkauf) Coin, Kauf-/Verkaufsdatum, Haltedauer, «> 1 Jahr», Menge,
 *    Einstand, Erlös, Gewinn in USDT und in der Anzeigewährung. Umrechnung mit dem EZB-Tageskurs des jeweiligen
 *    Datums (letzter Geschäftstag davor, höchstens [FX_MAX_GAP_DAYS] Tage): Einstand je Herkunftsteil
 *    ([PortfolioDisposal.costParts]) zum Kurs SEINES Datums — FIFO: Kauf zum Kurs des Kaufdatums, eine später
 *    zugeschlagene Umbuchungsgebühr zum Kurs der Umbuchung (5000 USDT 2018 zu 1.00 + 100 USDT 2024 zu 0.85 = 5085 CHF);
 *    Ø: laufender Ø in der Anzeigewährung, ein Abgang nimmt alle Teile anteilig mit; fehlt ein Kurs, bleibt der
 *    Einstand leer —, Erlös zum Kurs des Verkaufsdatums, Gewinn = Differenz. Mehr verkauft als erfasst: der Rest als
 *    Zeile ohne Kaufpreis. Die Karte zeigt «realisiert» nach der Rechnung der App (darum Hinweis «Die Steuer-CSV kann
 *    von ‹realisiert› abweichen»); «Seit Kauf» und Einstand rechnet sie mit derselben Kursregel wie hier um
 *    ([PortfolioCostFx], Tage in UTC).
 *  - Info-Zeilen nach der Summe (kein Gewinn/Verlust): Netzwerkgebühr-Einstand ohne bezahlten Posten ([lostFeeCostsIn])
 *    und Umbuchungsgebühren auf Bestand ohne Kaufpreis ([unassignedFeesIn], «Gebühr nicht zugeordnet – Einstand
 *    unbekannt»).
 *  - Summe: Einstand/Erlös/Gewinn nur über Zeilen, bei denen alle drei bekannt sind (die Summe geht auf);
 *    dazu eine Zeile «Erlös ohne Kaufpreis» für Zeilen mit unbekanntem Einstand; fehlt etwas: «unvollständig».
 *  - «> 1 Jahr» ([isLongTerm]): Verkaufsdatum NACH Kaufdatum + 1 Kalenderjahr (mehr als ein Jahr wie
 *    § 23 EStG; Kauf 29.2. → Grenze 28.2.). Daten und Jahr in der Zeitzone der Vorlage; sie steht in der CSV
 *    ([TaxCsvTexts.timeZone]).
 *  - Einkünfte: Belohnungen im Jahr mit Wert beim Erhalt (Menge × erfasster Wert je Coin), umgerechnet zum
 *    Kurs des Tags.
 *  - Bestand am Jahresende: der Stichtag-Export per 31.12. ([CutoffExport]).
 *  - Jahresübersicht je Vorlage ([yearSummary], [summaryCsv]), DE: kurz-/langfristig, Freigrenzen § 23 und § 22 Nr. 3 EStG.
 *  - Tausch: Veräusserung wie ein Verkauf (Vermerk [TaxCsvTexts.swap]); FIFO je Wallet: Vermerk [TaxCsvTexts.wallet].
 * Exakt mit [BigDecimal]; gerundet wird nur im Text (wie [CutoffExport]). CSV wie der Stichtag-Export: UTF-8 mit
 * BOM, CRLF, Trenn-/Dezimalzeichen je Region ([CutoffCsvFormat]); Kopfzeilen (Methode, Währung, keine
 * Steuerberatung, App-Version) gibt der Aufrufer übersetzt mit.
 */
object TaxExport {

    /** Devisenkurs höchstens so viele Tage vor dem Datum (Wochenende, Feiertage). */
    const val FX_MAX_GAP_DAYS = 7L

    /*
     * Steuerjahr und Daten in der übergebenen Zeitzone ([zone]; die App nimmt die der Vorlage, [TaxTemplate.zone],
     * Neutral die des Geräts): massgebend ist das rechtliche (lokale) Datum des Geschäfts — ein Verkauf am 31.12. um
     * 23:30 Zürich gehört ins alte Jahr, auch wenn es in UTC schon der 1.1. ist. Wertverlauf, «Heute/7 T/1 J» und Tageskerzen rechnen dagegen mit UTC-Tagen ([PortfolioHistory.utcDayEnd]) —
     * eine Transaktion um Mitternacht kann im Chart daher auf einen anderen Tag fallen als in der Steuer-CSV.
     */
    fun localDate(millis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    /**
     * Eigene Transaktionen samt Eröffnungsposten der Adressen, ohne Adress-Teil ohne Einstand — dieselbe Sicht wie die
     * Veräusserungen (wie [AddressBalance.split] in [TaxEngines] und [PortfolioCalculator.disposals]). Sonst fehlte der
     * Posten einer Adress-Wallet als Bestand: Eine Gebühr darauf stünde als «Gebühr auf Bestand ohne Kaufpreis», und ein
     * Jahr nur mit Posten fehlte in der Jahresauswahl. Die Posten sind schon aufs beobachtete Guthaben begrenzt
     * ([AddressCostLots]) und stecken nicht im Adress-Teil — nichts zählt doppelt.
     */
    private fun real(trades: List<PortfolioTrade>): List<PortfolioTrade> =
        trades.filter { !AddressBalance.isAddressPart(it.id) && it.amount.signum() > 0 }

    /** Jahre mit Transaktionen (samt Eröffnungsposten), neueste zuerst. */
    fun years(trades: List<PortfolioTrade>, zone: ZoneId): List<Int> = years(trades, zone, TaxTemplate.NEUTRAL)

    /** Steuerjahre ([TaxTemplate.periodOf], Beginnjahr) mit Transaktionen, neueste zuerst. */
    fun years(trades: List<PortfolioTrade>, zone: ZoneId, template: TaxTemplate): List<Int> =
        real(trades).map { template.periodOf(localDate(it.time, zone)) }.distinct().sortedDescending()

    /** Standard: letztes volles Jahr mit Transaktionen (vor [today]), sonst das neueste; null ohne Jahre. */
    fun defaultYear(years: List<Int>, today: LocalDate): Int? = defaultYear(years, today, TaxTemplate.NEUTRAL)

    /** Standard: letztes abgeschlossenes Steuerjahr mit Transaktionen, sonst das neueste; null ohne Jahre. */
    fun defaultYear(years: List<Int>, today: LocalDate, template: TaxTemplate): Int? {
        val current = template.periodOf(today)
        return years.filter { it < current }.maxOrNull() ?: years.maxOrNull()
    }

    /** Mehr als ein Jahr gehalten: [sold] nach [acquired] + 1 Kalenderjahr. */
    fun isLongTerm(acquired: LocalDate, sold: LocalDate): Boolean = sold.isAfter(acquired.plusYears(1))

    /** Stichtag des Bestands am Jahresende. */
    fun yearEnd(year: Int): LocalDate = LocalDate.of(year, 12, 31)

    /** Kalenderjahr als Steuerjahr (Neutral). */
    fun calendar(year: Int): TaxPeriod = TaxTemplate.NEUTRAL.period(year)

    fun gainsFileName(year: Int): String = "cryptochecker-gewinne-$year.csv"

    fun incomeFileName(year: Int): String = "cryptochecker-einkuenfte-$year.csv"

    /** Dateiname der Jahresübersicht einer Vorlage, z. B. «cryptochecker-jahresuebersicht-uk-2025.csv». */
    fun summaryFileName(template: TaxTemplate, year: Int): String =
        "cryptochecker-jahresuebersicht-${template.name.lowercase()}-$year.csv"

    /**
     * Kopfzeilen der Arbeitsunterlage: Bei CH steht «Vermögenssteuer nicht gerechnet – Kursliste ESTV» ([chWealthNote])
     * gleich unter dem Titel (Zeile 2) — in jeder CSV der Vorlage, nicht nur als Vermerk weiter unten. Andere Vorlagen
     * oder leerer Text: unverändert. Wie `TaxExport.withChWealthNote` (iOS).
     */
    fun withChWealthNote(template: TaxTemplate, header: List<String>, chWealthNote: String): List<String> =
        if (template != TaxTemplate.CH || chWealthNote.isEmpty() || chWealthNote in header) header
        else header.take(1) + chWealthNote + header.drop(1)

    /**
     * Freigrenze für private Veräusserungsgeschäfte (§ 23 Abs. 3 Satz 5 EStG) in EUR: steuerfrei, wenn der Gesamtgewinn
     * des Jahres WENIGER als die Grenze beträgt (Freigrenze, kein Freibetrag): 1000 € ab 2024 (Wachstumschancengesetz),
     * 600 € 2008–2023 (Unternehmensteuerreformgesetz 2008, ab Veranlagungszeitraum 2008), 512 € bis 2007.
     */
    fun privateSalesLimitEur(year: Int): BigDecimal = when {
        year >= 2024 -> BigDecimal(1000)
        year >= 2008 -> BigDecimal(600)
        else -> BigDecimal(512)
    }

    /** Freigrenze für Einkünfte aus Leistungen (§ 22 Nr. 3 Satz 2 EStG): steuerfrei, wenn weniger als 256 € im Jahr. */
    val INCOME_LIMIT_EUR: BigDecimal = BigDecimal(256)

    /**
     * AT Altbestand ≤ 1 Jahr (Spekulationsgeschäft, § 30 EStG a. F.): Freigrenze § 31 Abs. 3 EStG a. F. — steuerfrei, wenn die
     * Einkünfte daraus im Kalenderjahr HÖCHSTENS 440 € betragen (darüber der ganze Betrag; Freigrenze, kein Freibetrag).
     */
    val AT_SPECULATION_LIMIT_EUR: BigDecimal = BigDecimal(440)

    /**
     * USD → Zielwährung am [date]: USD = 1; sonst der Kurs des Tags oder des letzten Tags davor (höchstens
     * [FX_MAX_GAP_DAYS] Tage); [rates] = Tag (Tage seit 1970) → Kurs, null = nicht geladen.
     * Mit [usdtUsd] (USDT/USD-Tagesschluss je Datum): USDT → Zielwährung = Kurs × USDT/USD
     * DES Tags; ohne Schluss × 1 ([usdtApproximateOn] markiert die Zeile). null = USDT gilt 1 USD.
     */
    fun rateOn(
        rates: Map<Long, BigDecimal>?,
        date: LocalDate,
        currency: String,
        usdtUsd: Map<Long, BigDecimal>? = null,
    ): BigDecimal? {
        val fx = if (currency.trim().uppercase() == "USD") BigDecimal.ONE else {
            fxOnOrBefore(rates, date.toEpochDay()) ?: return null
        }
        if (usdtUsd == null) return fx
        val usdt = usdtUsdOn(usdtUsd, date) ?: return fx
        return fx.multiply(usdt, PortfolioDecimal.MC)
    }

    /**
     * Kurs des Tags [day] oder des letzten Tags davor mit Kurs > 0, höchstens [FX_MAX_GAP_DAYS] Tage zurück. Ein Export
     * fragt das für jede Zeile ab: geordnete Kurse ([NavigableMap], z. B. [java.util.TreeMap]) über `floorEntry`, sonst
     * höchstens acht direkte Abfragen — nie die ganze Kursreihe je Zeile durchsuchen.
     */
    fun fxOnOrBefore(rates: Map<Long, BigDecimal>?, day: Long): BigDecimal? {
        if (rates.isNullOrEmpty()) return null
        if (rates is NavigableMap<Long, BigDecimal>) {
            var entry = rates.floorEntry(day)
            while (entry != null && day - entry.key <= FX_MAX_GAP_DAYS) {
                if (entry.value.signum() > 0) return entry.value
                entry = rates.lowerEntry(entry.key)
            }
            return null
        }
        for (d in day downTo day - FX_MAX_GAP_DAYS) {
            rates[d]?.takeIf { it.signum() > 0 }?.let { return it }
        }
        return null
    }

    /**
     * USDT/USD-Tagesschluss am [date] aus [usdtUsd] (Tag → Schluss), nur plausibel ([PortfolioStables.usdtUsd]); null =
     * keiner (kein Schluss eines anderen Tags).
     */
    fun usdtUsdOn(usdtUsd: Map<Long, BigDecimal>?, date: LocalDate): BigDecimal? =
        usdtUsd?.get(date.toEpochDay())?.takeIf { !PortfolioStables.usdtUsd(it.toDouble()).approximate }

    /** Rechnet [rateOn] am [date] mit USDT = 1 als Näherung (USDT/USD-Kurse da, aber kein Schluss DES Tags)? */
    fun usdtApproximateOn(usdtUsd: Map<Long, BigDecimal>?, date: LocalDate): Boolean =
        usdtUsd != null && usdtUsdOn(usdtUsd, date) == null

    /** Veräusserungen mit Verkaufsdatum im Jahr [year], wie [PortfolioCalculator.disposals] sortiert. */
    fun disposalsIn(trades: List<PortfolioTrade>, method: CostMethod, year: Int, zone: ZoneId): List<PortfolioDisposal> =
        disposalsIn(trades, TaxMethod.of(method), calendar(year), zone)

    /**
     * Veräusserungen nach [method] mit Verkaufsdatum im Steuerjahr [period] ([TaxEngines.disposals]; [fx] = USDT →
     * Steuerwährung zu einem Zeitpunkt, CA: Superficial Loss in CAD).
     */
    fun disposalsIn(
        trades: List<PortfolioTrade>,
        method: TaxMethod,
        period: TaxPeriod,
        zone: ZoneId,
        priceAt: ((String, Long) -> BigDecimal?)? = null,
        fx: ((Long) -> BigDecimal?)? = null,
        /** Schenkung und Netzwerkgebühr nach der Vorlage ([TaxTemplate.eventRules]); Standard nach der Rechnung. */
        rules: TaxEventRules = TaxEventRules.of(method),
        /** US-Übergang ([TaxTemplate.walletFrom]); null = keiner. */
        walletFrom: LocalDate? = null,
    ): List<PortfolioDisposal> =
        // Adress-Einträge bleiben drin: sie sind keine Veräusserungen (die Rechnungen trennen sie ab), FR braucht sie für
        // den Gesamtwert des Portfolios
        TaxEngines.disposals(trades.filter { it.amount.signum() > 0 }, method, zone, priceAt, fx, rules, walletFrom)
            .filter { localDate(it.sellTime, zone) in period }

    /**
     * Veräusserungen aller Jahre nach [method] ohne Devisenkurse — wie [disposalsIn] ohne `fx` und ohne Filter aufs
     * Steuerjahr. Ein Export rechnet sie EINMAL und leitet daraus die Devisenspanne ([fxSpan]) und die Zeilen
     * ([gainRows], [yearSummary]) ab. CA ([TaxMethod.CA_ACB]) nehmen die Zeilen nicht daraus: dort entscheiden die
     * Devisenkurse den Superficial Loss, und welche Kurse geladen werden, bestimmt erst die Spanne.
     */
    fun allDisposals(
        trades: List<PortfolioTrade>,
        method: TaxMethod,
        zone: ZoneId,
        priceAt: ((String, Long) -> BigDecimal?)? = null,
        rules: TaxEventRules = TaxEventRules.of(method),
        walletFrom: LocalDate? = null,
    ): List<PortfolioDisposal> =
        TaxEngines.disposals(trades.filter { it.amount.signum() > 0 }, method, zone, priceAt, null, rules, walletFrom)

    /**
     * Einstand von Netzwerkgebühren ohne bezahlten Posten ([PortfolioLostFeeCost]) mit Datum im Jahr [year], zeitlich —
     * für den Vermerk im Gewinne-Export ([gainsCsv] `lostFeeCosts`). Kein realisierter Gewinn/Verlust.
     */
    fun lostFeeCostsIn(trades: List<PortfolioTrade>, method: CostMethod, year: Int, zone: ZoneId): List<PortfolioLostFeeCost> =
        lostFeeCostsIn(trades, TaxMethod.of(method), calendar(year), zone)

    /**
     * Wie oben für ein Steuerjahr und jede Vorlage: Einstandsmethoden des Portfolios über [PortfolioCalculator.ledgers],
     * eigene Rechnungen über [TaxEngines.lostFeeCosts]. Mit [TaxEventRules.networkFeeIsDisposal] (UK, US, CA, AU, JP) ist
     * die Netzwerkgebühr eine Veräusserung — kein Einstand bleibt liegen (leer); FR kennt keinen Gebühr-Einstand je Coin.
     */
    fun lostFeeCostsIn(
        trades: List<PortfolioTrade>,
        method: TaxMethod,
        period: TaxPeriod,
        zone: ZoneId,
        rules: TaxEventRules = TaxEventRules.of(method),
    ): List<PortfolioLostFeeCost> {
        val r = real(trades)
        val cm = method.costMethod
        val all = if (cm != null) {
            val applied = TaxEngines.applyRules(r, rules, null)
            // Ein Buch je Coin aus den einmal gesammelten Schritten; Herkunftsteile braucht der Vermerk nicht
            PortfolioCalculator.ledgers(applied, cm, withParts = false).flatMap { it.lostFeeCosts }
        } else TaxEngines.lostFeeCosts(r, method, zone, rules)
        return all
            .filter { localDate(it.time, zone) in period }
            .sortedWith(compareBy<PortfolioLostFeeCost> { it.time }.thenBy { it.txId }.thenBy { it.coin })
    }

    /**
     * Umbuchungsgebühren auf Bestand ohne Kaufpreis ([PortfolioUnassignedFee]) mit Datum im Jahr [year], zeitlich — für
     * die Info-Zeile im Gewinne-Export ([gainsCsv] `unassignedFees`). Kein realisierter Gewinn/Verlust.
     */
    fun unassignedFeesIn(trades: List<PortfolioTrade>, method: CostMethod, year: Int, zone: ZoneId): List<PortfolioUnassignedFee> =
        unassignedFeesIn(trades, TaxMethod.of(method), calendar(year), zone)

    /**
     * Wie oben für ein Steuerjahr und jede Vorlage: Einstandsmethoden des Portfolios über [PortfolioCalculator.ledgers]
     * (nach [TaxEngines.applyRules]: mit [TaxEventRules.quoteFeeIsInfo] zählt die Gebühr USDT einer Umbuchung ohnehin
     * nicht zum Einstand), eigene Rechnungen über [TaxEngines.unassignedFees].
     */
    fun unassignedFeesIn(
        trades: List<PortfolioTrade>,
        method: TaxMethod,
        period: TaxPeriod,
        zone: ZoneId,
        rules: TaxEventRules = TaxEventRules.of(method),
    ): List<PortfolioUnassignedFee> {
        val r = real(trades)
        val cm = method.costMethod
        val all = if (cm != null) {
            val applied = TaxEngines.applyRules(r, rules, null)
            PortfolioCalculator.ledgers(applied, cm, withParts = false).flatMap { it.unassignedFees }
        } else TaxEngines.unassignedFees(r, method, zone, rules)
        return all
            .filter { localDate(it.time, zone) in period }
            .sortedWith(compareBy<PortfolioUnassignedFee> { it.time }.thenBy { it.txId }.thenBy { it.coin })
    }

    /**
     * [lostFeeCostsIn] und [unassignedFeesIn] aus EINEM Lauf: beide rechnen mit derselben Eingabe (ohne Tagesschlüsse,
     * nach [TaxEngines.applyRules]) — gleiche Listen, ein Buch je Coin statt zwei. Nicht aus dem Lauf der
     * Veräusserungen ([allDisposals]): der bewertet Belohnungen, Tausch und Gebühr-Zeilen ohne Wert zum Tagesschluss
     * und kann darum einen anderen Bestand mit Kaufpreis haben.
     */
    fun feesIn(
        trades: List<PortfolioTrade>,
        method: TaxMethod,
        period: TaxPeriod,
        zone: ZoneId,
        rules: TaxEventRules = TaxEventRules.of(method),
    ): TaxFees {
        val applied = TaxEngines.applyRules(real(trades), rules, null)
        val cm = method.costMethod
        val lost: List<PortfolioLostFeeCost>
        val unassigned: List<PortfolioUnassignedFee>
        if (cm != null) {
            val ledgers = PortfolioCalculator.ledgers(applied, cm, withParts = false)
            lost = ledgers.flatMap { it.lostFeeCosts }
            unassigned = ledgers.flatMap { it.unassignedFees }
        } else {
            val run = TaxEngines.run(applied, method, zone, null)
            lost = run.lostFeeCosts
            unassigned = run.unassignedFees
        }
        return TaxFees(
            lost.filter { localDate(it.time, zone) in period }
                .sortedWith(compareBy<PortfolioLostFeeCost> { it.time }.thenBy { it.txId }.thenBy { it.coin }),
            unassigned.filter { localDate(it.time, zone) in period }
                .sortedWith(compareBy<PortfolioUnassignedFee> { it.time }.thenBy { it.txId }.thenBy { it.coin }),
        )
    }

    /** Belohnungen im Steuerjahr [period], zeitlich (dann Id). */
    fun rewardsIn(trades: List<PortfolioTrade>, period: TaxPeriod, zone: ZoneId): List<PortfolioTrade> =
        real(trades).filter { it.type == PortfolioTxType.REWARD && localDate(it.time, zone) in period }
            // Reihenfolge wie das Buch ([PortfolioLedger.ORDER])
            .sortedWith(compareBy(PortfolioLedger.ORDER) { PortfolioLedgerEvent(it, false) })

    /** Erster Tag, für den Devisenkurse gebraucht werden (frühestes Kaufdatum bzw. 1.1.), ohne Vorlauf. */
    fun fxFrom(trades: List<PortfolioTrade>, method: CostMethod, year: Int, zone: ZoneId): LocalDate =
        fxFrom(trades, TaxMethod.of(method), calendar(year), zone)

    /**
     * Wie oben für [period]: frühestes Kauf-/Herkunftsdatum der Veräusserungen bzw. Beginn des Steuerjahrs. CA: auch
     * aller früheren Veräusserungen — ein nicht abziehbarer Verlust eines Vorjahrs (im ACB dieses Jahrs) wird in CAD
     * entschieden und braucht die Kurse seiner Herkunft.
     */
    fun fxFrom(
        trades: List<PortfolioTrade>,
        method: TaxMethod,
        period: TaxPeriod,
        zone: ZoneId,
        priceAt: ((String, Long) -> BigDecimal?)? = null,
        rules: TaxEventRules = TaxEventRules.of(method),
        walletFrom: LocalDate? = null,
    ): LocalDate = fxSpan(trades, method, period, zone, priceAt, rules, walletFrom).first

    /**
     * Erster und letzter Tag mit Devisenbedarf in einem Durchgang (die Veräusserungen nur einmal gerechnet). Erster wie
     * [fxFrom]. Letzter: [until] ([priceUntil]) oder das späteste Datum eines Zugangs, den eine Veräusserung des Jahres
     * nutzt (Kaufdatum, Zuordnung, Herkunftsteil), höchstens heute ([today], nie vor dem Ende des Steuerjahrs) — UK: ein
     * Kauf am 20. April deckt einen Verkauf vom 2. April und braucht den Kurs SEINES Datums, nicht den des 5. April.
     * FR: dazu die Herkunft der Kürzungen durch Abgänge ohne Verkauf des Jahres ([frOutflowTimes]).
     */
    fun fxSpan(
        trades: List<PortfolioTrade>,
        method: TaxMethod,
        period: TaxPeriod,
        zone: ZoneId,
        priceAt: ((String, Long) -> BigDecimal?)? = null,
        rules: TaxEventRules = TaxEventRules.of(method),
        walletFrom: LocalDate? = null,
        until: LocalDate = period.end,
        today: LocalDate = LocalDate.now(zone),
        /** Veräusserungen aller Jahre aus [allDisposals] (derselbe Lauf wie die Zeilen); null = hier rechnen. */
        disposals: List<PortfolioDisposal>? = null,
    ): Pair<LocalDate, LocalDate> {
        // FIFO: Kaufdatum der Posten; Durchschnitt/Pool: Datum jedes Herkunftsteils des Einstands
        val upTo = if (method == TaxMethod.CA_ACB) TaxPeriod(period.startYear, LocalDate.MIN, period.end) else period
        val inSpan = disposals?.filter { localDate(it.sellTime, zone) in upTo }
            ?: disposalsIn(trades, method, upTo, zone, priceAt, rules = rules, walletFrom = walletFrom)
        val times = inSpan
            .flatMap { d -> listOfNotNull(d.acquiredTime, d.matchedTime) + d.costParts.timeBounds() } +
            frOutflowTimes(trades, method, period, zone, priceAt)
        val first = times.minOrNull()?.let { localDate(it, zone) }
        val from = if (first != null && first.isBefore(period.start)) first else period.start
        val last = times.maxOrNull()?.let { localDate(it, zone) }
        val wanted = if (last != null && last.isAfter(until)) last else until
        return from to minOf(wanted, maxOf(today, period.end))
    }

    /**
     * Letzter Tag, bis zu dem Tagesschlüsse (Gebühr-Zeilen, Belohnungen und Tausch ohne Wert, Netzwerkgebühren) und
     * Devisenkurse mindestens geladen werden: das Ende des Steuerjahrs; UK und CA 30 Tage danach — UK ordnet einem Verkauf
     * Zugänge der 30 Tage DANACH zu (Bed-and-Breakfast, CRYPTO22200), CA prüft den Superficial Loss bis 30 Tage danach;
     * diese Zugänge liegen beim Verkauf kurz vor dem Jahresende im nächsten Steuerjahr. Höchstens [today], nie vor dem
     * Ende des Steuerjahrs (spätere Transaktionen gibt es noch nicht).
     */
    fun priceUntil(template: TaxTemplate, period: TaxPeriod, today: LocalDate): LocalDate {
        val window = when (template) {
            TaxTemplate.UK -> TaxTemplate.UK_BB_DAYS
            TaxTemplate.CA -> TaxTemplate.CA_WINDOW_DAYS
            else -> return period.end
        }
        return minOf(period.end.plusDays(window), maxOf(today, period.end))
    }
}

/** Netzwerkgebühr-Einstände und nicht zugeordnete Gebühren eines Steuerjahrs aus einem Lauf ([TaxExport.feesIn]). */
data class TaxFees(val lostFeeCosts: List<PortfolioLostFeeCost>, val unassignedFees: List<PortfolioUnassignedFee>)
