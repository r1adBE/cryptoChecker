import Foundation

/// Zuordnung Abgang ↔ Einstand im Steuer-Export — wie `TaxMethod` (Android), dort beschrieben. Wird nicht gespeichert
/// (folgt aus Vorlage + Einstandsmethode, `TaxTemplate.methodFor`).
enum TaxMethod: String, CaseIterable, Sendable {
    case FIFO
    case FIFO_WALLET
    case AVERAGE
    case UK_POOL
    case CA_ACB
    case FR_PTA
    case JP_TOTAL_AVERAGE
    case AT_MOVING_AVERAGE

    /// Einstandsmethode des Portfolios dahinter; nil = eigene Rechnung in `TaxEngines`.
    var costMethod: CostMethod? {
        switch self {
        case .FIFO: return .FIFO
        case .FIFO_WALLET: return .FIFO_WALLET
        case .AVERAGE: return .AVERAGE
        case .UK_POOL, .CA_ACB, .FR_PTA, .JP_TOTAL_AVERAGE, .AT_MOVING_AVERAGE: return nil
        }
    }

    static func of(_ method: CostMethod) -> TaxMethod {
        switch method {
        case .FIFO: return .FIFO
        case .FIFO_WALLET: return .FIFO_WALLET
        case .AVERAGE: return .AVERAGE
        }
    }
}

/// Wie die Rechnung Vorgänge ohne Verkauf behandelt (`TaxEngines.applyRules`) — aus der Vorlage (`TaxTemplate.eventRules`);
/// ohne Vorlage nach der Rechnung (`of`). Wie `TaxEventRules` (Android): die Einstandsmethoden teilen sich mehrere
/// Vorlagen, darum hängen die Regeln an der Vorlage, nicht an der Methode.
struct TaxEventRules: Sendable, Equatable {
    /// Verschenkt = Veräusserung zum Marktwert (`TaxTemplate.giftIsDisposal`).
    let giftIsDisposal: Bool
    /// Verschenkt an Ehe-/Lebenspartner = kein Gewinn, kein Verlust (`TaxTemplate.spouseGiftNeutral`).
    let spouseGiftNeutral: Bool
    /// Netzwerkgebühr in Krypto = eigene Veräusserung zum Marktwert (`TaxTemplate.networkFeeIsDisposal`).
    let networkFeeIsDisposal: Bool
    /// Gebühr USDT/Fiat einer Auszahlung oder Umbuchung = kein Verlust, kein Einstand, nur Information (`TaxTemplate.quoteFeeIsInfo`).
    let quoteFeeIsInfo: Bool

    /// Keine Umschreibung (Schenkung wie eine Auszahlung, Netzwerkgebühr senkt nur die Menge).
    static let none = TaxEventRules(giftIsDisposal: false, spouseGiftNeutral: false, networkFeeIsDisposal: false,
                                    quoteFeeIsInfo: false)

    /// Standard ohne Vorlage: die eigenen Rechnungen UK, CA und JP (総平均法) wie ihre Vorlage, sonst `none`.
    static func of(_ method: TaxMethod) -> TaxEventRules {
        switch method {
        case .UK_POOL: return TaxTemplate.UK.eventRules
        case .CA_ACB: return TaxTemplate.CA.eventRules
        case .JP_TOTAL_AVERAGE: return TaxTemplate.JP.eventRules
        case .FIFO, .FIFO_WALLET, .AVERAGE, .FR_PTA, .AT_MOVING_AVERAGE: return .none
        }
    }
}

/// Steuerjahr `start`…`end` (beide inklusive); `startYear` = Kalenderjahr des Beginns — wie `TaxPeriod` (Android).
struct TaxPeriod: Sendable, Equatable {
    let startYear: Int
    let start: LocalDay
    let end: LocalDay

    func contains(_ day: LocalDay) -> Bool { !(day < start) && !(end < day) }
}

/// Steuer-Vorlage (Einstellung «tax_template», Standard Neutral) — ORIENTIERUNG, keine Steuerberatung. Regeln, Quellen
/// und Stand (14./15.10.2026) wie `TaxTemplate` (Android): DE § 23/§ 22 Nr. 3 EStG, BMF 06.03.2025 (Rn. 62: nur FIFO
/// je Wallet); AT BMF (Stand 01.01.2026: Staking/Airdrop Anschaffungskosten null, § 27b Abs. 2 EStG; Zinsen aus Lending seit Build 55 unterschieden,
/// `TaxEngines.interestReward`: Wert beim Zufluss), KryptowährungsVO § 2 Abs. 1 (je Adresse/Wallet); CH ESTV (Stand
/// 22.09.2026, ohne Einordnung durch die App); UK HMRC CRYPTO22200 ff., CRYPTO22280 (Gebühr in Tokens = Veräusserung),
/// gov.uk (Stand 13.04.2026); US IRS FAQ digital assets (Stand 29.06.2026), Rev. Proc. 2024-28 (ab 2025 nur FIFO je
/// Wallet); AT Tausch steuerneutral ab 01.03.2022, davor bzw. Altvermögen Realisation nach § 31 EStG a. F. (EStR Rz
/// 6103k); FR/AT: Verkauf gegen Stablecoin = Tausch Krypto↔Krypto; CA CRA (Stand 05.02.2026),
/// T4037 2025; AU ATO CGT discount (Stand 29.06.2026); FR art. 150 VH bis CGI, BOI-RPPM-PVBMC-30-20 (§ 50 Gebühren),
/// BOI-RPPM-PVBMC-30-10 § 70, formulaire 2086 (2025); JP 国税庁 A1-20. Umrechnung × USDT/USD-Tagesschluss
/// (`TaxExport.rateOn` `usdtUsd`); ohne Schluss des Tags USDT = 1 USD als Näherung, je Zeile vermerkt (`TaxCsvTexts.usdtAssumed`).
enum TaxTemplate: String, Codable, CaseIterable, Sendable {
    case NEUTRAL
    case DE
    case AT
    case CH
    case UK
    case US
    case CA
    case AU
    case FR
    case JP

    static let defaultTemplate: TaxTemplate = .NEUTRAL

    /// Gespeicherter Name; unbekannt oder leer = `defaultTemplate`.
    static func fromName(_ name: String?) -> TaxTemplate {
        name.flatMap { TaxTemplate(rawValue: $0) } ?? defaultTemplate
    }

    /// Unbekannte Werte (neuere Version) beim Dekodieren: Neutral statt Fehler.
    init(from decoder: Decoder) throws {
        let raw = try decoder.singleValueContainer().decode(String.self)
        self = TaxTemplate(rawValue: raw) ?? .defaultTemplate
    }

    /// Währung der Vorlage (Steuerwährung); nil = Anzeigewährung (Neutral).
    var currency: String? {
        switch self {
        case .NEUTRAL: return nil
        case .DE, .AT, .FR: return "EUR"
        case .CH: return "CHF"
        case .UK: return "GBP"
        case .US: return "USD"
        case .CA: return "CAD"
        case .AU: return "AUD"
        case .JP: return "JPY"
        }
    }

    /// Zeitzone der Daten (Steuerjahr, Kauf-/Verkaufsdatum, Haltedauer): massgebend ist das rechtliche Datum im Land der
    /// Vorlage, nicht die Zeitzone des Geräts. Steht in jeder CSV («Daten in Ortszeit (Zone)»). Länder mit mehreren Zonen:
    /// die der Hauptstadt bzw. des Finanzplatzes (US New York, CA Toronto, AU Sydney). nil = Gerät (`NEUTRAL`). Wie Android.
    var zoneId: String? {
        switch self {
        case .NEUTRAL: return nil
        case .DE: return "Europe/Berlin"
        case .AT: return "Europe/Vienna"
        case .CH: return "Europe/Zurich"
        case .UK: return "Europe/London"
        case .US: return "America/New_York"
        case .CA: return "America/Toronto"
        case .AU: return "Australia/Sydney"
        case .FR: return "Europe/Paris"
        case .JP: return "Asia/Tokyo"
        }
    }

    /// Zeitzone der Arbeitsunterlage (`zoneId`); Neutral die des Geräts (`device`) — wie Android `zone`.
    func zone(device: TimeZone = .current) -> TimeZone {
        zoneId.flatMap(TimeZone.init(identifier:)) ?? device
    }

    /// Erlaubte Methoden nach den aktuellen Regeln, erste = Standard (`methodsIn(_:)` ohne Steuerjahr).
    var methods: [TaxMethod] { methodsIn(nil) }

    /// Erlaubte Methoden für das Steuerjahr ab `startYear` (nil = aktuelle Regeln), erste = Standard. US: ab Steuerjahr
    /// 2025 nur Wallet für Wallet (Rev. Proc. 2024-28) — davor auch FIFO über alle Wallets. Übergang: `walletFrom`.
    func methodsIn(_ startYear: Int?) -> [TaxMethod] {
        if self == .US {
            if let y = startYear, y < TaxTemplate.usWalletFromYear { return [.FIFO, .FIFO_WALLET] }
            return [.FIFO_WALLET]
        }
        return baseMethods
    }

    private var baseMethods: [TaxMethod] {
        switch self {
        case .NEUTRAL, .CH: return [.FIFO, .FIFO_WALLET, .AVERAGE]
        // DE: BMF 06.03.2025 Rn. 62 verlangt die walletbezogene Betrachtung — FIFO über alle Wallets wäre falsch
        case .DE: return [.FIFO_WALLET]
        case .US: return [.FIFO, .FIFO_WALLET]
        case .AU: return [.FIFO]
        case .UK: return [.UK_POOL]
        case .CA: return [.CA_ACB]
        case .FR: return [.FR_PTA]
        case .JP: return [.JP_TOTAL_AVERAGE, .AVERAGE]
        case .AT: return [.AT_MOVING_AVERAGE]
        }
    }

    /// Methode des Exports für das Steuerjahr ab `startYear` (nil = aktuelle Regeln): die Einstandsmethode des
    /// Portfolios, wenn erlaubt, sonst der Standard der Vorlage.
    func methodFor(_ app: CostMethod, _ startYear: Int? = nil) -> TaxMethod {
        let m = TaxMethod.of(app)
        let allowed = methodsIn(startYear)
        return allowed.contains(m) ? m : allowed[0]
    }

    /// US-Übergang (Rev. Proc. 2024-28, Safe Harbor) — wie Android `walletFrom`: Steuerjahr ≥ 2025 je Wallet, davor
    /// universelles FIFO → Stichtag 1.1.2025 (Restposten des universellen FIFO den Wallets zugeordnet,
    /// `TaxEngines.usTransition`); nil = kein Übergang.
    func walletFrom(_ app: CostMethod, _ startYear: Int) -> LocalDay? {
        guard self == .US, startYear >= TaxTemplate.usWalletFromYear,
              methodFor(app, startYear) == .FIFO_WALLET,
              methodFor(app, TaxTemplate.usWalletFromYear - 1) == .FIFO else { return nil }
        return LocalDay(TaxTemplate.usWalletFromYear, 1, 1)
    }

    /// Die Vorlage erlaubt die Einstandsmethode `app` nicht — der Export rechnet still mit `methodFor`. Darum Hinweis
    /// «Vorlage verlangt FIFO – App-Einstellung Ø, Export rechnet FIFO» in der Kopfzeile, im Export-Blatt und bei der
    /// Vorlage in den Einstellungen (wie Android).
    func methodOverridden(_ app: CostMethod, _ startYear: Int? = nil) -> Bool { !methodsIn(startYear).contains(TaxMethod.of(app)) }

    /// Haltedauer-Gruppen ≤ 1 Jahr / > 1 Jahr in der Jahresübersicht (DE, US, AU, Neutral).
    /// Verschenkt (`.GIFT`) ist beim Schenkenden eine Veräusserung zum Marktwert: UK (TCGA 1992 s. 17/18), CA (ITA 69(1)(b)),
    /// AU (CGT event A1, s. 116-30); sonst wie eine Auszahlung — wie Android `giftIsDisposal`.
    var giftIsDisposal: Bool { self == .UK || self == .CA || self == .AU }

    /// Verschenkt an Ehe-/Lebenspartner (`PortfolioTx.isGiftToSpouse`) ohne Gewinn und Verlust zum Einstand: UK (TCGA 1992 s. 58), CA
    /// (ITA 73(1), Spousal Rollover zum ACB). AU kennt keinen solchen Aufschub für Schenkungen (nur bei Trennung, s. 126-5),
    /// die übrigen Vorlagen behandeln jede Schenkung wie eine Auszahlung — wie Android `spouseGiftNeutral`.
    var spouseGiftNeutral: Bool { self == .UK || self == .CA }

    /// Netzwerkgebühr in Krypto einer Umbuchung auf eine eigene Wallet bzw. einer Auszahlung = eigene Veräusserung der
    /// Gebühr-Coins zum Marktwert (Gewinn/Verlust nur auf diesen Coins, kein Verlust des ganzen Gebührenwerts) — Quellen wie
    /// Android `networkFeeIsDisposal`: HMRC CRYPTO22280/22150, IRS Notice 2014-21 Q-6 und digital asset FAQ, CRA «Guide for
    /// cryptocurrency users and tax professionals», ATO «Crypto asset transactions» (CGT event A1), 国税庁 FAQ (使用 = 譲渡).
    /// FR rechnet Gebühren in Krypto schon als Abtretung; DE, AT, CH, Neutral unverändert.
    var networkFeeIsDisposal: Bool { self == .UK || self == .US || self == .CA || self == .AU || self == .JP }

    /// Auszahlungsgebühr in USDT bzw. Fiat (`.WITHDRAWAL`-Aufwand) ist kein Verlust, nur Information (Entscheid des Inhabers
    /// 17.10.2026) — wie Android `quoteFeeIsInfo`: das Feld hält USDT (Fiat-Gebühren umgerechnet); Fiat-Gebühr = keine
    /// abziehbaren Kosten, USDT-Gebühr = Veräusserung zum Nennwert ohne Gewinn. UK: HMRC CRYPTO22150 (keine «allowable cost»
    /// für Fiat-Ein-/Auszahlungen); US (IRS digital asset FAQ, § 67(g) IRC), CA (CRA), AU (ATO), JP (国税庁 FAQ): keine Regel,
    /// die sie abziehbar macht — unklar, darum «nicht abziehbar, Information». DE/AT eigener Posten, CH/FR/Neutral Aufwand.
    /// Build 57: ebenso die Gebühr USDT/Fiat einer Umbuchung zwischen eigenen Wallets — kein Zuschlag zum Einstand mehr, eigene
    /// Zeile `.TRANSFER` (UK CRYPTO22150; sonst unklar → «nicht abziehbar, Information»). DE (keine Verwaltungsauffassung zu
    /// Transfergebühren zwischen eigenen Wallets), CH (ESTV ordnet nicht ein) bleiben beim Zuschlag wie das Portfolio; AT
    /// (§ 27a Abs. 4 Z 2 EStG) und FR (nicht in der Erwerbspreis-Summe) rechnen sie ohnehin nicht zum Einstand. Wie Android.
    var quoteFeeIsInfo: Bool { self == .UK || self == .US || self == .CA || self == .AU || self == .JP }

    /// Regeln der Vorlage für Vorgänge ohne Verkauf (`TaxEngines.applyRules`, `TaxExport.gainRows`).
    var eventRules: TaxEventRules {
        TaxEventRules(giftIsDisposal: giftIsDisposal, spouseGiftNeutral: spouseGiftNeutral,
                      networkFeeIsDisposal: networkFeeIsDisposal, quoteFeeIsInfo: quoteFeeIsInfo)
    }

    var holdingBuckets: Bool { self == .NEUTRAL || self == .DE || self == .US || self == .AU }

    /// Tausch Krypto↔Krypto ist ein steuerbarer Vorgang (FR: aufgeschoben, AT: steuerneutral).
    var cryptoToCryptoTaxable: Bool { self != .FR && self != .AT }

    /// Monat und Tag des Beginns des Steuerjahrs.
    private var startMonthDay: (month: Int, day: Int) {
        switch self {
        case .UK: return (4, 6)
        case .AU: return (7, 1)
        default: return (1, 1)
        }
    }

    /// Steuerjahr, das im Kalenderjahr `startYear` beginnt.
    func period(_ startYear: Int) -> TaxPeriod {
        let md = startMonthDay
        let start = LocalDay(startYear, md.month, md.day)
        let end = LocalDay(startYear + 1, md.month, md.day).plusDays(-1)
        return TaxPeriod(startYear: startYear, start: start, end: end)
    }

    /// Beginnjahr des Steuerjahrs, in das `day` fällt.
    func periodOf(_ day: LocalDay) -> Int {
        let md = startMonthDay
        return day < LocalDay(day.year, md.month, md.day) ? day.year - 1 : day.year
    }

    /// Anzeige des Steuerjahrs: «2025», UK «2025/26», AU «2025–26».
    func label(_ startYear: Int) -> String {
        let next = String(format: "%02ld", (startYear + 1) % 100)
        switch self {
        case .UK: return "\(startYear)/\(next)"
        case .AU: return "\(startYear)–\(next)"
        default: return String(startYear)
        }
    }

    /// Vorschlag aus der Region des Geräts — wird NIE still übernommen. Liechtenstein wie die Schweiz.
    static func suggest(_ region: String) -> TaxTemplate {
        switch region.trimmingCharacters(in: .whitespaces).uppercased() {
        case "DE": return .DE
        case "AT": return .AT
        case "CH", "LI": return .CH
        case "GB", "UK": return .UK
        case "US": return .US
        case "CA": return .CA
        case "AU": return .AU
        case "FR": return .FR
        case "JP": return .JP
        default: return .NEUTRAL
        }
    }

    /// AT: Neuvermögen = angeschafft ab dem 1.3.2021 (Ortszeit).
    static let atNewFrom = LocalDay(2021, 3, 1)
    /// AT: Tausch Krypto↔Krypto steuerneutral ab dem 1.3.2022 (§ 27b Abs. 3 Z 2 EStG); davor Veräusserung nach alter
    /// Rechtslage (Ortszeit).
    static let atSwapNeutralFrom = LocalDay(2022, 3, 1)
    /// AT: Realisationen von Neuvermögen ab diesem Tag bis vor `atSwapNeutralFrom` können auf Antrag schon nach neuer
    /// Rechtslage (27,5 %) besteuert werden (§ 124b Z 384 lit. c EStG); die vom 1.3. bis 31.12.2021 nicht. Wie Android.
    static let atOptionFrom = LocalDay(2022, 1, 1)
    /// Stand der hinterlegten Regeln (Kopfzeile «Regelstand: 2026-10» der Länder-Vorlagen) — wie Android `RULES_DATE`.
    static let rulesDate = "2026-10"
    /// US: erstes Steuerjahr mit Einstand Wallet für Wallet (Rev. Proc. 2024-28, ab 01.01.2025).
    static let usWalletFromYear = 2025
    /// FR: Abtretungen eines Jahres bis und mit diesem Gesamtbetrag sind befreit.
    static let frExemptEur: Decimal = 305
    /// CA: Einschlussrate 1/2.
    static let caInclusion = Decimal(string: "0.5") ?? 0
    /// AU: CGT-Rabatt für Privatpersonen.
    static let auDiscount = Decimal(string: "0.5") ?? 0
    /// CA: Fenster der Superficial-Loss-Regel in Kalendertagen vor und nach dem Verkauf.
    static let caWindowDays = 30
    /// UK: 30-Tage-Regel.
    static let ukBbDays = 30

    /// UK: Meldepflicht an HMRC, wenn die Veräusserungserlöse des Steuerjahrs ab 6. April `startYear` diesen Betrag (GBP)
    /// übersteigen, auch ohne steuerbaren Gewinn — £50,000 ab 2023/24; davor nicht hinterlegt (nil). Wie Android.
    static func ukReportProceedsGbp(_ startYear: Int) -> Decimal? { startYear >= 2023 ? 50000 : nil }

    /// UK: Annual Exempt Amount (Privatperson) in GBP für das Steuerjahr ab 6. April `startYear`; nil = nicht hinterlegt.
    static func ukAnnualExemptGbp(_ startYear: Int) -> Decimal? {
        if startYear >= 2024 { return 3000 }
        if startYear == 2023 { return 6000 }
        if (2020...2022).contains(startYear) { return 12300 }
        if startYear == 2019 { return 12000 }
        if startYear == 2018 { return 11700 }
        return nil
    }
}
