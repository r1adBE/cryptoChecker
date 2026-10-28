package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

/**
 * Steuer-Vorlage (Einstellung «tax_template», Standard [NEUTRAL]) — ORIENTIERUNG, keine Steuerberatung. Die Zeilen der
 * Arbeitsunterlage nennen nur Tatsachen (Daten, Haltedauer, Beträge); Schlüsse wie «steuerfrei» stehen nur in der
 * Jahresübersicht als «Orientierung» mit Hinweis. Je Vorlage: erlaubte Methoden (erste = Standard), Steuerjahr,
 * Haltedauer-Gruppen, Tausch Krypto↔Krypto, Grenzen. Quellen und Stand (14./15.10.2026, alle abgerufen). Alle Beträge in
 * USDT; Umrechnung mit USD-Kursen × USDT/USD-Tagesschluss ([TaxExport.rateOn] `usdtUsd`);
 * ohne Schluss des Tags USDT = 1 USD als Näherung, je Zeile vermerkt ([TaxCsvTexts.usdtAssumed]):
 *  - DE: § 23 Abs. 1 Nr. 2, Abs. 3 Satz 5 EStG (Freigrenze 1000 € ab 2024, 600 € 2009–2023), § 22 Nr. 3 Satz 2 EStG (256 €);
 *    BMF-Schreiben Kryptowerte vom 06.03.2025 (Rn. 61 f.: FIFO; Rn. 62: Verwendungsreihenfolge je Wallet bzw. Konto —
 *    darum nur FIFO je Wallet, FIFO über alle Wallets nicht zulässig). Kalenderjahr.
 *  - AT: BMF «Steuerliche Behandlung von Kryptowährungen» (Stand 01.01.2026): Neuvermögen = angeschafft nach 28.02.2021,
 *    27,5 %, gleitender Durchschnittspreis (in EUR), Tausch Krypto↔Krypto keine Veräusserung (Anschaffungskosten
 *    fortgeführt; Stablecoins wie Tether sind Kryptowährungen — Verkauf gegen USDT = Tausch) ab 01.03.2022; davor und
 *    für Altvermögen Realisation nach § 31 EStG a. F. (EStR Rz 6103k), erhaltener Coin angeschafft am Tag des Tauschs; Staking mit vorhandenen Coins, Airdrops, Bounties, Hardforks: kein Zufluss, Anschaffungskosten null
 *    (§ 27b Abs. 2 EStG — der erfasste Wert beim Erhalt zählt dafür nicht, Hinweis im Export). Zinsen aus Lending/
 *    Liquidity-Pools: laufende Einkünfte beim Zufluss, Anschaffungskosten = Wert beim Zufluss —
 *    unterschieden, wo der CSV-Import die Art kennt oder die Notiz sie nennt ([PortfolioTrade.interestIncome],
 *    [TaxEngines.interestReward]); jede andere Belohnung im Neubestand rechnet mit 0. KryptowährungsVO BGBl. II 2022/455 § 2 Abs. 1
 *    (Durchschnitt für alle Einheiten derselben Kryptowährung auf EINER Adresse bzw. EINEM Wallet — hier je Coin und
 *    Wallet wie FIFO je Wallet, nicht über alle Wallets), § 3 (im Zweifel früher erworbene Einheit zuerst). Altvermögen:
 *    Spekulationsfrist 1 Jahr (§ 31 EStG a. F.). Neuvermögen, verkauft vor dem 1.3.2022: ebenso alte Rechtslage
 *    (Spekulationsgeschäft, Freigrenze 440 €, Gebühren abziehbar); Option auf 27,5 % nur für Realisationen 1.1.–28.2.2022
 *    (§ 124b Z 384 lit. c EStG), die Übersicht rechnet ohne Option. Belohnungen vor dem 1.3.2022: Anschaffungskosten 0
 *    wie nach neuer Rechtslage (keine Verwaltungsquelle zum Wert beim Zufluss vor dem Inkrafttreten — Vermerk). Kalenderjahr.
 *  - CH: ESTV «Kryptowährungen» (Stand 22.09.2026) und Arbeitspapier vom 03.08.2022 — die App ordnet NICHT ein:
 *    Kapitalgewinne im Privatvermögen «nicht gerechnet – Einordnung siehe ESTV», Belohnungen aufgeführt «Behandlung siehe
 *    ESTV-Arbeitspapier», Vermögenssteuer «nicht gerechnet» (Verkehrswert Ende Steuerperiode, ESTV-Kursliste);
 *    der Vermögenswert per 31.12. als Orientierung (Bestand × Börsen-Tagesschluss × Devisenkurs, [TaxExport.chWealth]) —
 *    massgebend bleibt die ESTV-Kursliste, die Steuer selbst rechnet die App nicht (kantonale Tarife). Kalenderjahr.
 *  - UK: HMRC Cryptoassets Manual CRYPTO22200 (Same Day → 30 Tage → Section-104-Pool), Beispiele CRYPTO22251–22253;
 *    CRYPTO22280 (Stand 28.11.2025): Gebühr in Tokens = eigene Veräusserung zum Marktwert, am selben Tag mit den übrigen
 *    Abgängen eine Veräusserung (Netzwerkgebühr einer Umbuchung hier ebenso);
 *    gov.uk «Capital Gains Tax rates and allowances» (Stand 13.04.2026): Annual Exempt Amount £3,000 ab 2024/25, £6,000
 *    2023/24, £12,300 2020/21–2022/23. Steuerjahr 6. April bis 5. April. Tausch steuerbar.
 *  - US: IRS «FAQ on digital asset transactions» (Stand 29.06.2026): Haltedauer beginnt am Tag nach dem Erwerb, langfristig
 *    bei mehr als 1 Jahr (FAQ 50); ohne spezifische Identifikation älteste Einheiten derselben Wallet zuerst (FAQ 86);
 *    Rev. Proc. 2024-28 (Wallet für Wallet ab 01.01.2025: ab Steuerjahr 2025 nur FIFO je Wallet, [TaxTemplate.methodsIn];
 *    Safe Harbor: Restposten des universellen FIFO am 31.12.2024 den Wallets zugeordnet, [TaxTemplate.walletFrom]);
 *    Tausch steuerbar (FAQ 64). Kalenderjahr.
 *  - CA: CRA «Capital losses and deductions» (Stand 05.02.2026): Superficial Loss (identisches Gut 30 Kalendertage vor
 *    bis 30 nach dem Verkauf erworben und am 30. Tag danach noch gehalten → Verlust nicht abziehbar, zum ACB addiert);
 *    T4037 Capital Gains 2025 (Stand 11.02.2026): Einschlussrate 1/2, ACB = Durchschnitt identischer Güter. Kalenderjahr.
 *  - AU: ATO «CGT discount» (Stand 29.06.2026): 50 % für Gewinne aus mindestens 12 Monate gehaltenen Gütern (Tag des
 *    Erwerbs und des CGT-Ereignisses zählen nicht = Verkauf nach Kaufdatum + 1 Jahr). Steuerjahr 1. Juli bis 30. Juni.
 *  - FR: art. 150 VH bis CGI, BOFiP BOI-RPPM-PVBMC-30-20 (02.09.2019): plus-value = prix de cession − prix total
 *    d'acquisition × prix de cession / valeur globale du portefeuille (Gebühren nur im ersten Glied), Tausch Krypto↔Krypto
 *    aufgeschoben (art. 150 VH bis II A: Tausch ohne Ausgleichszahlung unter actifs numériques nach L. 54-10-1 CMF, also
 *    auch in Stablecoins — steuerbar nur gegen Euro oder Waren/Dienstleistungen); § 50 und BOI-RPPM-PVBMC-30-10 § 70 Remarque 1 (02.09.2019): Gebühr in Krypto = Gegenleistung für eine
 *    Dienstleistung (Abtretung), beim Verkauf mit diesem eine Operation; Formular 2086 (2025): Gesamtbetrag der
 *    Abtretungen ≤ 305 € befreit; 12,8 % + prélèvements sociaux.
 *  - JP: 国税庁 A1-20 (Bewertungsmethode, 所得税法施行令119条の3): 総平均法 oder 移動平均法, ohne Wahl 総平均法;
 *    国税庁 «仮想通貨に関する所得の計算方法等について» (2017): 雑所得, Tausch steuerbar. Kalenderjahr.
 */
enum class TaxTemplate {
    NEUTRAL, DE, AT, CH, UK, US, CA, AU, FR, JP;

    /** Währung der Vorlage (Steuerwährung); null = Anzeigewährung ([NEUTRAL]). */
    val currency: String?
        get() = when (this) {
            NEUTRAL -> null
            DE, AT, FR -> "EUR"
            CH -> "CHF"
            UK -> "GBP"
            US -> "USD"
            CA -> "CAD"
            AU -> "AUD"
            JP -> "JPY"
        }

    /**
     * Zeitzone der Daten (Steuerjahr, Kauf-/Verkaufsdatum, Haltedauer): massgebend ist das rechtliche Datum im Land der
     * Vorlage, nicht die Zeitzone des Geräts — ein in Zürich eingestelltes Gerät, das die UK-Vorlage rechnet, ordnet einen
     * Verkauf um 23:30 Londoner Zeit dem Londoner Tag zu. Steht in jeder CSV («Daten in Ortszeit (Zone)»). Länder mit
     * mehreren Zonen: die der Hauptstadt bzw. des Finanzplatzes (US New York, CA Toronto, AU Sydney). null = Gerät ([NEUTRAL]).
     */
    val zoneId: String?
        get() = when (this) {
            NEUTRAL -> null
            DE -> "Europe/Berlin"
            AT -> "Europe/Vienna"
            CH -> "Europe/Zurich"
            UK -> "Europe/London"
            US -> "America/New_York"
            CA -> "America/Toronto"
            AU -> "Australia/Sydney"
            FR -> "Europe/Paris"
            JP -> "Asia/Tokyo"
        }

    /** Zeitzone der Arbeitsunterlage ([zoneId]); Neutral die des Geräts ([device]). */
    fun zone(device: ZoneId = ZoneId.systemDefault()): ZoneId = zoneId?.let { ZoneId.of(it) } ?: device

    /** Erlaubte Methoden nach den aktuellen Regeln, erste = Standard ([methodsIn] ohne Steuerjahr). */
    val methods: List<TaxMethod>
        get() = methodsIn(null)

    /**
     * Erlaubte Methoden für das Steuerjahr ab [startYear] (null = aktuelle Regeln), erste = Standard. US: ab Steuerjahr
     * 2025 nur Wallet für Wallet (Rev. Proc. 2024-28: Einstand je Wallet/Konto, keine «universal method» mehr) — davor
     * auch FIFO über alle Wallets. Übergang 1.1.2025 siehe [walletFrom].
     */
    fun methodsIn(startYear: Int?): List<TaxMethod> {
        if (this != US) return baseMethods
        return if (startYear != null && startYear < US_WALLET_FROM_YEAR) {
            listOf(TaxMethod.FIFO, TaxMethod.FIFO_WALLET)
        } else {
            listOf(TaxMethod.FIFO_WALLET)
        }
    }

    private val baseMethods: List<TaxMethod>
        get() = when (this) {
            NEUTRAL, CH -> listOf(TaxMethod.FIFO, TaxMethod.FIFO_WALLET, TaxMethod.AVERAGE)
            // DE: BMF 06.03.2025 Rn. 62 verlangt die walletbezogene Betrachtung — FIFO über alle Wallets wäre falsch
            DE -> listOf(TaxMethod.FIFO_WALLET)
            US -> listOf(TaxMethod.FIFO, TaxMethod.FIFO_WALLET)
            AU -> listOf(TaxMethod.FIFO)
            UK -> listOf(TaxMethod.UK_POOL)
            CA -> listOf(TaxMethod.CA_ACB)
            FR -> listOf(TaxMethod.FR_PTA)
            JP -> listOf(TaxMethod.JP_TOTAL_AVERAGE, TaxMethod.AVERAGE)
            AT -> listOf(TaxMethod.AT_MOVING_AVERAGE)
        }

    /**
     * Methode des Exports für das Steuerjahr ab [startYear] (null = aktuelle Regeln): die Einstandsmethode des
     * Portfolios, wenn die Vorlage sie erlaubt, sonst ihr Standard.
     */
    fun methodFor(app: CostMethod, startYear: Int? = null): TaxMethod {
        val m = methodsIn(startYear)
        return TaxMethod.of(app).takeIf { it in m } ?: m.first()
    }

    /**
     * US-Übergang (Rev. Proc. 2024-28, Safe Harbor): Steuerjahr ab [startYear] ≥ 2025 rechnet FIFO je Wallet, die
     * Jahre davor aber FIFO über alle Wallets ([methodFor] 2024 = FIFO) → Stichtag 1.1.2025: bis dahin universelles FIFO,
     * dessen Restposten den Wallets zugeordnet werden ([TaxEngines.usTransition]); null = kein Übergang (andere Vorlage,
     * Jahr vor 2025 oder schon davor FIFO je Wallet — dann ist FIFO je Wallet über die ganze Geschichte stimmig).
     */
    fun walletFrom(app: CostMethod, startYear: Int): LocalDate? =
        if (this == US && startYear >= US_WALLET_FROM_YEAR && methodFor(app, startYear) == TaxMethod.FIFO_WALLET &&
            methodFor(app, US_WALLET_FROM_YEAR - 1) == TaxMethod.FIFO
        ) LocalDate.of(US_WALLET_FROM_YEAR, 1, 1) else null

    /**
     * Die Vorlage erlaubt die Einstandsmethode [app] nicht — der Export rechnet still mit [methodFor]. Darum Hinweis
     * «Vorlage verlangt FIFO – App-Einstellung Ø, Export rechnet FIFO» in der Kopfzeile, auf der Seite
     * «Steuer-Arbeitsunterlage» und bei der Vorlage in den Einstellungen.
     */
    fun methodOverridden(app: CostMethod, startYear: Int? = null): Boolean = TaxMethod.of(app) !in methodsIn(startYear)

    /**
     * Verschenkt ([PortfolioTxType.GIFT]) ist beim Schenkenden eine Veräusserung zum Marktwert: UK (TCGA 1992 s. 17/18,
     * ausser an Ehegatten), CA (ITA 69(1)(b), deemed disposition), AU (CGT event A1, Marktwert-Ersatzregel s. 116-30).
     * Sonst (DE, AT, US, JP, FR, CH, Neutral) keine Veräusserung — wie eine Auszahlung (Bestand und Einstand gehen ab).
     */
    val giftIsDisposal: Boolean get() = this == UK || this == CA || this == AU

    /**
     * Verschenkt an Ehe-/Lebenspartner ([PortfolioTrade.isGiftToSpouse]) ohne Gewinn und Verlust zum Einstand: UK (TCGA 1992
     * s. 58: «neither a gain nor a loss», der Partner übernimmt den Einstand), CA (ITA 73(1): Spousal Rollover zum ACB).
     * AU kennt keinen solchen Aufschub für Schenkungen (nur bei Trennung, s. 126-5) — dort bleibt sie eine
     * Veräusserung zum Marktwert; die übrigen Vorlagen behandeln jede Schenkung wie eine Auszahlung.
     */
    val spouseGiftNeutral: Boolean get() = this == UK || this == CA

    /**
     * Netzwerkgebühr in Krypto einer Umbuchung auf eine eigene Wallet bzw. einer Auszahlung = eigene Veräusserung der
     * Gebühr-Coins zum Marktwert (Gewinn/Verlust nur auf diesen Coins, kein Verlust des ganzen Gebührenwerts) —
     * nach den allgemeinen Veräusserungsregeln: UK HMRC CRYPTO22280 (Gebühr in Tokens = eigene Veräusserung zum
     * Marktwert) und CRYPTO22150 (Kosten nur bei Erwerb/Veräusserung; Umbuchung und Auszahlung sind keine);
     * US IRS Notice 2014-21 Q-6 und «FAQ on digital asset transactions» (Bezahlen einer Leistung mit Krypto =
     * Veräusserung zum Marktwert, Umbuchung zwischen eigenen Wallets keine); CA CRA «Guide for cryptocurrency users
     * and tax professionals» (Bezahlen mit Krypto = Tausch, disposition); AU ATO «Crypto asset transactions» (Krypto
     * für eine Gebühr hingeben = CGT event A1); JP 国税庁 «暗号資産等に関する税務上の取扱いについて（FAQ）»
     * (Bezahlen mit Krypto = Veräusserung/使用, Differenz zum Erwerbspreis ist Einkommen). FR rechnet Gebühren in Krypto
     * schon als eigene Abtretung (BOI-RPPM-PVBMC-30-10 § 70); DE, AT, CH, Neutral unverändert.
     */
    val networkFeeIsDisposal: Boolean get() = this == UK || this == US || this == CA || this == AU || this == JP

    /**
     * Auszahlungsgebühr in USDT bzw. Fiat ([PortfolioTrade.quoteFee] einer Auszahlung/Schenkung, [PortfolioFeeExpense.WITHDRAWAL])
     * ist kein Verlust, nur Information (nicht «überall realisierter Aufwand»).
     * Das Feld hält USDT (Preiswährung; der CSV-Import rechnet Fiat-Gebühren darin um), darum beide Lesarten:
     * Fiat-Gebühr = keine Veräusserung und keine abziehbaren Kosten; USDT-Gebühr = Veräusserung von USDT zum Nennwert
     * (Einstand 1 wie überall angenommen, Kopfzeile «USDT = 1 USD») ohne Gewinn — in beiden Fällen kein Verlust.
     *  - UK: HMRC CRYPTO22150 — Ein-/Auszahlungen von Fiat haben keine «allowable cost» (keine Anschaffung/Veräusserung).
     *  - US: IRS «FAQ on digital asset transactions»: Umbuchung zwischen eigenen Wallets ist kein steuerbarer Vorgang; eine
     *    Regel, die ihre Gebühr als Verlust zulässt, gibt es nicht (Werbungskosten der Privatperson nach § 67(g) IRC
     *    ausgesetzt) — unklar, darum «nicht abziehbar, Information».
     *  - CA (CRA), AU (ATO), JP (国税庁 FAQ): keine ausdrückliche Regel für Auszahlungsgebühren in Fiat — unklar, darum wie
     *    vom Inhaber festgelegt «nicht abziehbar, Information».
     * Gebühren in Krypto (auch in einem Stablecoin als drittem Coin) sind dagegen Veräusserungen ([networkFeeIsDisposal]).
     * DE/AT führen die Gebühr als eigenen Posten (dort unverändert), CH, FR und Neutral rechnen sie weiter als Aufwand.
     *
     * Ebenso die Gebühr USDT/Fiat einer Umbuchung zwischen eigenen Wallets —
     * sie erhöht den Einstand nicht (UK CRYPTO22150: Kosten nur bei Anschaffung/Veräusserung, eine Umbuchung ist
     * keins von beiden; US/CA/AU/JP: Umbuchung zwischen eigenen Wallets kein steuerbarer Vorgang, keine Regel für einen
     * Zuschlag zum Einstand — unklar, darum «nicht abziehbar, Information»), eigene Zeile [PortfolioFeeExpense.TRANSFER].
     * Die übrigen Vorlagen bleiben bewusst beim Zuschlag zum Einstand (wie das Portfolio): DE — weder BMF-Schreiben
     * Kryptowerte (06.03.2025) noch Rechtsprechung regeln Transfergebühren zwischen eigenen Wallets ausdrücklich (keine
     * Verwaltungsauffassung); AT — rechnet ohnehin ohne Gebühren einer Umbuchung (§ 27a Abs. 4 Z 2 EStG, [TaxEngines] AT);
     * CH — ESTV ordnet nicht ein, die App rechnet CH-Gewinne nur zur Information; FR — die Gebühr USDT einer Umbuchung
     * zählt schon nicht zur Erwerbspreis-Summe (BOI-RPPM-PVBMC-30-20: nur Erwerbspreis samt Erwerbskosten).
     */
    val quoteFeeIsInfo: Boolean get() = this == UK || this == US || this == CA || this == AU || this == JP

    /**
     * DE/AT: Auszahlungsgebühren sind keine Veräusserung — eigener Posten neben der Summe der Gewinne (Jahresübersicht
     * [TaxLine.WITHDRAWAL_FEES], Gewinne-CSV eigene Summenzeile [TaxCsvTexts.withdrawalFeesTotal]), nicht darin verrechnet.
     */
    val withdrawalFeeApart: Boolean get() = this == DE || this == AT

    /** Regeln der Vorlage für Vorgänge ohne Verkauf ([TaxEngines.applyRules], [TaxExport.gainRows]). */
    val eventRules: TaxEventRules
        get() = TaxEventRules(giftIsDisposal, spouseGiftNeutral, networkFeeIsDisposal, quoteFeeIsInfo)

    /** Haltedauer-Gruppen ≤ 1 Jahr / > 1 Jahr in der Jahresübersicht (DE, US, AU, Neutral). */
    val holdingBuckets: Boolean get() = this == NEUTRAL || this == DE || this == US || this == AU

    /** Tausch Krypto↔Krypto ist ein steuerbarer Vorgang (FR: aufgeschoben, AT: steuerneutral). */
    val cryptoToCryptoTaxable: Boolean get() = this != FR && this != AT

    /** Monat und Tag des Beginns des Steuerjahrs. */
    private val startMonthDay: Pair<Int, Int>
        get() = when (this) {
            UK -> 4 to 6
            AU -> 7 to 1
            else -> 1 to 1
        }

    /** Steuerjahr, das im Kalenderjahr [startYear] beginnt. */
    fun period(startYear: Int): TaxPeriod {
        val (m, d) = startMonthDay
        val start = LocalDate.of(startYear, m, d)
        return TaxPeriod(startYear, start, start.plusYears(1).minusDays(1))
    }

    /** Beginnjahr des Steuerjahrs, in das [date] fällt. */
    fun periodOf(date: LocalDate): Int {
        val (m, d) = startMonthDay
        return if (date.isBefore(LocalDate.of(date.year, m, d))) date.year - 1 else date.year
    }

    /** Anzeige des Steuerjahrs: «2025», UK «2025/26», AU «2025–26». */
    fun label(startYear: Int): String {
        val next = ((startYear + 1) % 100).toString().padStart(2, '0')
        return when (this) {
            UK -> "$startYear/$next"
            AU -> "$startYear–$next"
            else -> startYear.toString()
        }
    }

    companion object {
        val DEFAULT = NEUTRAL

        /** Gespeicherter Name; unbekannt oder leer = [DEFAULT]. */
        fun fromName(name: String?): TaxTemplate = entries.firstOrNull { it.name == name } ?: DEFAULT

        /**
         * Vorschlag aus der Region des Geräts (ISO-Ländercode) — wird NIE still übernommen, nur angeboten. Liechtenstein
         * wie die Schweiz (Steuerrecht ähnlich, CHF); unbekannt = [NEUTRAL].
         */
        fun suggest(region: String): TaxTemplate = when (region.trim().uppercase()) {
            "DE" -> DE
            "AT" -> AT
            "CH", "LI" -> CH
            "GB", "UK" -> UK
            "US" -> US
            "CA" -> CA
            "AU" -> AU
            "FR" -> FR
            "JP" -> JP
            else -> NEUTRAL
        }

        /** AT: Stichtag Neuvermögen — angeschafft ab dem 1.3.2021 (Ortszeit). */
        val AT_NEW_FROM: LocalDate = LocalDate.of(2021, 3, 1)

        /**
         * AT: Tausch Krypto↔Krypto steuerneutral ab dem 1.3.2022 (§ 27b Abs. 3 Z 2 EStG, in Kraft mit § 124b Z 384 EStG);
         * davor ist jeder Tausch eine Veräusserung nach alter Rechtslage (Ortszeit).
         */
        val AT_SWAP_NEUTRAL_FROM: LocalDate = LocalDate.of(2022, 3, 1)

        /**
         * AT: Realisationen von Neuvermögen ab diesem Tag bis vor [AT_SWAP_NEUTRAL_FROM] können auf Antrag schon nach
         * neuer Rechtslage (27,5 %) besteuert werden (§ 124b Z 384 lit. c EStG); die vom 1.3. bis 31.12.2021 nicht.
         */
        val AT_OPTION_FROM: LocalDate = LocalDate.of(2022, 1, 1)

        /**
         * Stand der hinterlegten Regeln («JJJJ-MM»; Dialog und Kopfzeile der Länder-Vorlagen zeigen ihn als Monat und Jahr,
         * Hinweis bei jüngerem Steuerjahr: [TaxRulesDate]).
         */
        const val RULES_DATE = "2026-10"

        /** US: erstes Steuerjahr mit Einstand Wallet für Wallet (Rev. Proc. 2024-28, ab 01.01.2025). */
        const val US_WALLET_FROM_YEAR = 2025

        /** FR: Abtretungen eines Jahres bis und mit diesem Gesamtbetrag (Abtretungspreise) sind befreit. */
        val FR_EXEMPT_EUR: BigDecimal = BigDecimal(305)

        /** CA: Einschlussrate (taxable capital gain = 1/2). */
        val CA_INCLUSION: BigDecimal = BigDecimal("0.5")

        /** AU: CGT-Rabatt für Privatpersonen (Gewinne > 12 Monate). */
        val AU_DISCOUNT: BigDecimal = BigDecimal("0.5")

        /** CA: Fenster der Superficial-Loss-Regel in Kalendertagen vor und nach dem Verkauf. */
        const val CA_WINDOW_DAYS = 30L

        /** UK: 30-Tage-Regel (Zugänge an den 30 Tagen nach dem Verkauf). */
        const val UK_BB_DAYS = 30L

        /**
         * UK: Meldepflicht an HMRC (wer für Self Assessment registriert ist), wenn die Veräusserungserlöse vor Gebühren des
         * Steuerjahrs ab 6. April [startYear] diesen Betrag (GBP) übersteigen, auch ohne steuerbaren Gewinn — £50,000 ab
         * 2023/24 (gov.uk «Report and pay your Capital Gains Tax», Stand 2026); 2020/21 bis 2022/23 das Vierfache des
         * Annual Exempt Amount (4 × £12,300 = £49,200); früher nicht hinterlegt (null).
         */
        fun ukReportProceedsGbp(startYear: Int): BigDecimal? = when {
            startYear >= 2023 -> BigDecimal(50000)
            startYear in 2020..2022 -> ukAnnualExemptGbp(startYear)?.multiply(BigDecimal(4))
            else -> null
        }

        /** UK: Annual Exempt Amount (Privatperson) in GBP für das Steuerjahr ab 6. April [startYear]; null = nicht hinterlegt. */
        fun ukAnnualExemptGbp(startYear: Int): BigDecimal? = when {
            startYear >= 2024 -> BigDecimal(3000)
            startYear == 2023 -> BigDecimal(6000)
            startYear in 2020..2022 -> BigDecimal(12300)
            startYear == 2019 -> BigDecimal(12000)
            startYear == 2018 -> BigDecimal(11700)
            else -> null
        }
    }
}
