import Foundation

/// USDT-Depeg-Alarm: meldet einmal je Abweichung, wenn USDT um mindestens die Schwelle
/// von 1 USD abweicht. Dieselbe Messung ist der Live-Kurs USDT/USD der Bewertung (`liveUsdtUsd`): USDT gilt in Fiat seinen
/// Marktkurs (`PortfolioStables.usdtUsd`); ohne frische Messung 1 als Näherung.
///
/// Quellen (bestehende Kurs-Abrufe, kein neuer Anbieter): Kraken USDT/USD (direkt, zuerst), Coinbase USDT-USD (direkt,
/// zweite Quelle) und erst als dritte Quelle bzw. Ersatz Binance USDC/USDT (USDT = 1 / Kurs, USDC als 1 USD angenommen).
/// Regeln siehe `measure`: Im Normalbereich zählt der Wert näher bei 1 (eine Börse allein löst keinen Fehlalarm aus);
/// ein Kurs im Einbruch-Bereich (z. B. 0.30) zählt nur, wenn mindestens zwei Quellen innerhalb von 5 % übereinstimmen.
/// Ein Wert allein aus USDC/USDT löst nie einen Alarm aus — er unterscheidet nicht, ob USDT oder USDC abweicht.
///
/// Hysterese: Nach einer Meldung erst wieder scharf, wenn die Abweichung auf die halbe Schwelle
/// zurückgeht. Kann die Mitteilung gerade nicht ankommen (Nachtruhe, keine Erlaubnis), bleibt der Alarm
/// scharf (`QuietHours.mayFire`) — wie die Kursalarme. Reine Logik — wie `DepegAlarm.kt`.
enum DepegAlarm {
    /// Messen? Mit eingeschaltetem Alarm immer; sonst, sobald ein Portfolio da ist (eingeschaltet und mit Transaktionen
    /// oder beobachteten Adressen): Die Messung ist der Live-Kurs USDT/USD der Bewertung (`liveUsdtUsd`).
    /// Gemeldet wird nur mit eingeschaltetem Alarm. Wie `DepegAlarm.shouldMeasure` (Android).
    /// Ebenso mit `fiatConversion`: Fiat-Alarm oder ≈ Umrechnung auf einem Paar, dessen Faktor an USDT hängt
    /// (`FiatAlarmGuard.needsUsdtMeasurement`) — sonst rechnete sie still USDT = 1. Wie Android.
    static func shouldMeasure(depegAlarm: Bool, portfolioEnabled: Bool, hasPortfolioData: Bool,
                              fiatConversion: Bool = false) -> Bool {
        depegAlarm || (portfolioEnabled && hasPortfolioData) || fiatConversion
    }

    /// Schwelle in Zehntel-Prozent: 0.3 / 0.5 / 1 / 2 %.
    static let thresholdChoices = [3, 5, 10, 20]
    static let defaultThresholdTenths = 5

    // Quellen: Börsen-Schlüssel und Paar-Kennung (wie in der Paarliste der Börse)
    static let krakenKey = "Kraken"
    static let krakenPairId = "USDTZUSD"
    static let coinbaseKey = "Coinbase"
    static let coinbasePairId = "USDT-USD"
    static let binanceKey = "Binance"
    static let binancePairId = "USDCUSDT"

    /// Normalbereich: eine Quelle allein genügt; ausserhalb braucht es eine Bestätigung (`measure`).
    private static let minPlausible = PortfolioStables.usdtUsdMin
    private static let maxPlausible = PortfolioStables.usdtUsdMax

    /// Einbruch-Bereich: nur bestätigt gültig; ausserhalb davon immer Börsenfehler.
    private static let minBreak = PortfolioStables.usdtUsdBreakMin
    private static let maxBreak = PortfolioStables.usdtUsdBreakMax

    /// Zwei Kurse stimmen überein, wenn sie höchstens 5 % (vom grösseren) auseinanderliegen.
    static let breakAgreement = 0.05

    /// Gespeicherte Schwelle; unbekannte Werte → Standard.
    static func threshold(_ tenths: Int) -> Int {
        thresholdChoices.contains(tenths) ? tenths : defaultThresholdTenths
    }

    static func thresholdPercent(_ tenths: Int) -> Double { Double(threshold(tenths)) / 10 }

    private static func normal(_ price: Double) -> Bool {
        price.isFinite && price >= minPlausible && price <= maxPlausible
    }

    private static func inBreakRange(_ price: Double) -> Bool {
        price.isFinite && price >= minBreak && price <= maxBreak
    }

    private static func agree(_ a: Double, _ b: Double) -> Bool { abs(a - b) <= breakAgreement * max(a, b) }

    /// Ergebnis einer Messung: USDT in USD `price`; `viaUsdc` = nur aus Binance USDC/USDT abgeleitet (USDC = 1
    /// angenommen) — dann nie ein Alarm (`mayAlarm`) und die Karte nennt die Herkunft. Wie Android `Measurement`.
    struct Measurement: Equatable {
        let price: Double
        let viaUsdc: Bool
        /// Bestätigter Einbruch (ausserhalb des Normalbereichs).
        var broken: Bool { PortfolioStables.isUsdtBreak(price) }
    }

    /// USDT in USD aus den drei Quellen (`kraken`, `coinbase` = letzter Kurs USDT/USD; `binanceUsdc` = letzter Kurs
    /// USDC/USDT; nil = fehlt). Wie Android `measure`:
    ///  1. Jeder Kurs ausserhalb 0.05…3 ist ein Börsenfehler.
    ///  2. Im Normalbereich (0.5…1.5) genügt eine Quelle; im Einbruch-Bereich (0.05…0.5, 1.5…3) zählt ein Kurs nur,
    ///     wenn eine andere Quelle innerhalb von `breakAgreement` übereinstimmt — ein einzelner Ausreisser bleibt
    ///     unplausibel, ein echter Einbruch (mehrere Börsen bei 0.30) wird nicht weggefiltert.
    ///  3. Beide direkten Quellen gültig: der Wert näher bei 1 (USDC/USDT zählt nicht). Eine direkte: der Wert näher bei
    ///     1 aus ihr und USDC/USDT (gemeldet wird nur, wenn beide abweichen). Keine direkte: USDC/USDT
    ///     allein (`Measurement.viaUsdc`).
    static func measure(kraken: Double?, coinbase: Double?, binanceUsdc: Double?) -> Measurement? {
        var usdc: Double?
        if let b = binanceUsdc, b.isFinite, b > 0 { usdc = 1 / b }
        let raw: [Double?] = [kraken, coinbase, usdc].map { v in
            guard let v, inBreakRange(v) else { return nil }
            return v
        }
        func accepted(_ i: Int) -> Double? {
            guard let v = raw[i] else { return nil }
            if normal(v) { return v }
            let confirmed = raw.indices.contains { j in
                guard j != i, let o = raw[j] else { return false }
                return agree(v, o)
            }
            return confirmed ? v : nil
        }
        let direct = [accepted(0), accepted(1)].compactMap { $0 }
        let fromUsdc = accepted(2)
        // Zwei Direktquellen widersprechen sich, USDC/USDT bestätigt eine davon: die Mehrheit (2 von 3) gilt
        if direct.count == 2, !agree(direct[0], direct[1]), let u = fromUsdc {
            let backed = direct.filter { agree($0, u) }
            if backed.count == 1 { return Measurement(price: backed[0], viaUsdc: false) }
        }
        if direct.count == 2 { return Measurement(price: closestToOne(direct), viaUsdc: false) }
        if direct.count == 1 {
            return Measurement(price: closestToOne(direct + [fromUsdc].compactMap { $0 }), viaUsdc: false)
        }
        if let fromUsdc { return Measurement(price: fromUsdc, viaUsdc: true) }
        return nil
    }

    private static func closestToOne(_ values: [Double]) -> Double {
        // Gleichstand: der erste (wie Android minBy)
        var best = values[0]
        for v in values.dropFirst() where abs(v - 1) < abs(best - 1) { best = v }
        return best
    }

    /// Taugt der letzte Kurs `last` einer Quelle für die Messung? Dieselbe Regel wie bei Kursalarmen
    /// (`AlarmFreshness.exchangeTimeTooOld`): Meldet die Börse eine Zeit des Kurses (`exchangeTime`) älter als 2 Std.,
    /// steht der Wert (Handel ruht) — er zählt wie keine Antwort (nil), damit weder eine alte Zahl als frische Messung
    /// gespeichert wird noch der Alarm auf ihr auslöst. Ohne Zeit der Börse zählt der Kurs. Wie Android.
    static func usablePrice(last: Double?, exchangeTime: Int64?, now: Int64) -> Double? {
        guard let last, !AlarmFreshness.exchangeTimeTooOld(exchangeTime, now: now) else { return nil }
        return last
    }

    /// Darf diese Messung den Alarm auslösen? Nicht, wenn sie allein aus USDC/USDT stammt.
    static func mayAlarm(_ measurement: Measurement) -> Bool { !measurement.viaUsdc }

    /// Abweichung von 1 USD in Prozent (negativ = unter 1).
    static func deviationPercent(_ price: Double) -> Double { (price - 1) * 100 }

    /// Betrag der Abweichung, auf 4 Stellen gerundet (keine Fliesskomma-Ränder an der Schwelle).
    private static func magnitude(_ deviationPercent: Double) -> Double {
        (abs(deviationPercent) * 10_000).rounded() / 10_000
    }

    /// - Parameter mayFire: Mitteilung jetzt erlaubt und zustellbar (`QuietHours.mayFire`).
    /// - Returns: melden? und «scharf danach» (gilt für eine angenommene Mitteilung, siehe `armedAfter`).
    static func evaluate(deviationPercent: Double, thresholdPercent: Double, armed: Bool,
                         mayFire: Bool) -> (fire: Bool, armed: Bool) {
        if thresholdPercent <= 0 { return (false, true) }
        let m = magnitude(deviationPercent)
        // Fällig, aber nicht zustellbar: nicht verbrauchen
        if armed && m >= thresholdPercent { return mayFire ? (true, false) : (false, true) }
        // Zurück innerhalb der halben Schwelle: wieder scharf
        if !armed && m <= thresholdPercent / 2 { return (false, true) }
        return (false, armed)
    }

    /// Zu speichernder Zustand: Nahm das System die fällige Mitteilung nicht an, bleibt der alte Zustand.
    static func armedAfter(fire: Bool, posted: Bool, armedBefore: Bool, next: Bool) -> Bool {
        (fire && !posted) ? armedBefore : next
    }

    /// Gespeicherter Zustand für die aktuelle Schwelle (Zehntelprozent): Wurde er bei einer anderen Schwelle gemerkt
    /// (geändert oder zwischendurch ausgeschaltet = 0, `MarketAlertReset.depegOff`), ist der Alarm wieder scharf.
    /// `storedTenths` nil = keine Schwelle gemerkt: scharf. Wie Android und der Gas-Alarm (`GasAlertCheck.armedFor`).
    static func armedFor(storedArmed: Bool, storedTenths: Int?, tenths: Int) -> Bool {
        storedTenths != tenths ? true : storedArmed
    }

    /// Letzte Messung der Prüfung (USDT in USD, Zeitpunkt) — gespeichert, damit Karte und Alarm übereinstimmen.
    /// `viaUsdc`: nur aus Binance USDC/USDT abgeleitet (`Measurement.viaUsdc`).
    struct Reading: Equatable {
        let price: Double
        let atMillis: Int64
        var viaUsdc: Bool = false
    }

    /// So lange gilt eine Messung auf der Portfolio-Karte (Prüfung höchstens alle 5 Minuten).
    static let cardMaxAgeMillis: Int64 = 30 * 60_000

    /// So lange gilt eine Messung als Live-Kurs USDT/USD der Bewertung (`liveUsdtUsd`): 6 Std. — mit langen
    /// Hintergrund-Intervallen fiele die Bewertung sonst oft auf Ersatz 1 («USDT = 1 angenommen») zurück; USDT bewegt
    /// sich in dieser Zeit kaum. Die Warnzeile der Karte (`cardWarning`) bleibt bei `cardMaxAgeMillis`. Wie Android
    /// `LIVE_MAX_AGE_MILLIS`.
    static let liveMaxAgeMillis: Int64 = 6 * 60 * 60_000

    /// Uhr leicht vorgestellt/zurückgestellt: kleine Abweichung in die Zukunft noch gültig.
    private static let cardFutureToleranceMillis: Int64 = 60_000

    /// Warnzeile «USDT = 1 angenommen – Markt bei …» auf der Portfolio-Karte: nur mit einer Messung,
    /// die höchstens `cardMaxAgeMillis` alt ist und um mindestens die Schwelle abweicht (gleiche Rundung
    /// wie `evaluate`). Ohne Prüfung (Alarm aus) gibt es keine Messung und damit keine Zeile. Wie `DepegAlarm.cardWarning` (Android).
    static func cardWarning(_ reading: Reading?, thresholdTenths: Int, now: Int64) -> Reading? {
        guard let reading else { return nil }
        let age = now - reading.atMillis
        if age > cardMaxAgeMillis || age < -cardFutureToleranceMillis { return nil }
        guard reading.price.isFinite else { return nil }
        return magnitude(deviationPercent(reading.price)) >= thresholdPercent(thresholdTenths) ? reading : nil
    }

    /// Live-Kurs USDT/USD für die Bewertung (`UsdtUsdRates.live`): die letzte Messung, wenn höchstens `liveMaxAgeMillis`
    /// alt und im Einbruch-Bereich 0.05…3 (gespeichert wird nur, was `measure` geprüft hat — ausserhalb des
    /// Normalbereichs also nur ein bestätigter Einbruch); sonst nil (Bewertung mit Ersatz 1, Näherung). Wie Android.
    static func liveUsdtUsd(_ reading: Reading?, now: Int64) -> Double? {
        guard let reading else { return nil }
        let age = now - reading.atMillis
        if age > liveMaxAgeMillis || age < -cardFutureToleranceMillis { return nil }
        return inBreakRange(reading.price) ? reading.price : nil
    }

    /// Art der Zeile zu «USDT = 1» auf der Portfolio-Karte (`cardNote`) — wie `CardNoteKind` (Android).
    enum CardNoteKind: String, Equatable {
        /// Frische Messung weicht um mindestens die Schwelle ab: «USDT = 1 angenommen – Markt bei …» (Warnfarbe).
        case DEVIATION
        /// Werte hängen an USDT = USD (Anzeigewährung, USD, gemischte Börsen): «Kurse: Binance · USDT = 1».
        case PRICE_SOURCE
        /// Der Bestand enthält USDT (zählt 1) bzw. mit USDT zum Marktkurs: Ersatz 1 ohne Kurs — «USDT = 1 angenommen».
        case USDT_ASSUMED
        /// Bestätigter USDT-Einbruch: die Werte rechnen mit dem Marktkurs — «USDT-Einbruch – Markt bei …» (Warnfarbe).
        case USDT_BREAK
        /// Live-Kurs nur aus Binance USDC/USDT (USDC = 1 angenommen): «USDT/USD über USDC/USDT …».
        case VIA_USDC
    }

    /// Zeile der Karte; `reading` bei `.DEVIATION`, `.USDT_BREAK` und `.VIA_USDC`.
    struct CardNote: Equatable {
        let kind: CardNoteKind
        var reading: Reading? = nil
    }

    /// Zeile zu «USDT = 1» auf der Portfolio-Karte — UNABHÄNGIG vom Depeg-Alarm: Die Annahme gilt immer, also
    /// steht sie da, sobald sie die Zahlen trägt; der Alarm liefert nur die Abweichung dazu. Wie `cardNote` (Android):
    ///  1. frische Messung ≥ Schwelle (`cardWarning`, immer auf der Karte) → `.DEVIATION`;
    ///  2. sonst `valuesViaUsdt` (`PortfolioExchangeRoute.usdtParityShown`) → `.PRICE_SOURCE`;
    ///  3. sonst enthält der Bestand `holdingCoins` USDT (`PortfolioStables.isValuation`) → `.USDT_ASSUMED`;
    ///  4. sonst keine Zeile (nil).
    /// Mit USDT zum Marktkurs (`usdtMarket`): frischer Kurs (`liveUsdtUsd`) → keine Zeile
    /// (nichts angenommen, die Werte rechnen mit dem Markt); ohne ihn trägt Ersatz 1 die Zahlen → wie 2./3., aber immer
    /// `.USDT_ASSUMED` («USDT = 1 angenommen» = Markierung der Näherung). Mit frischem Kurs und Werten, die an USDT
    /// hängen: bestätigter Einbruch → `.USDT_BREAK`; Kurs nur aus USDC/USDT → `.VIA_USDC`.
    static func cardNote(holdingCoins: [String], valuesViaUsdt: Bool, reading: Reading?, thresholdTenths: Int,
                         now: Int64, usdtMarket: Bool = false) -> CardNote? {
        if usdtMarket {
            let dependsOnUsdt = valuesViaUsdt || holdingCoins.contains(where: { PortfolioStables.isValuation($0) })
            if let live = liveUsdtUsd(reading, now: now), let reading {
                if !dependsOnUsdt { return nil }
                if PortfolioStables.isUsdtBreak(live) { return CardNote(kind: .USDT_BREAK, reading: reading) }
                if reading.viaUsdc { return CardNote(kind: .VIA_USDC, reading: reading) }
                return nil
            }
            return dependsOnUsdt ? CardNote(kind: .USDT_ASSUMED) : nil
        }
        if let warning = cardWarning(reading, thresholdTenths: thresholdTenths, now: now) {
            return CardNote(kind: .DEVIATION, reading: warning)
        }
        if valuesViaUsdt { return CardNote(kind: .PRICE_SOURCE) }
        if holdingCoins.contains(where: { PortfolioStables.isValuation($0) }) { return CardNote(kind: .USDT_ASSUMED) }
        return nil
    }

    /// Seite USDC/USDT je Börse (Web-Adresse; ist die App der Börse installiert, öffnet iOS sie dort, wo
    /// sie die Adresse übernimmt). Bewusst kurz gehalten: nur grosse Börsen mit dem Paar. Reihenfolge =
    /// Vorrang bei Gleichstand. Kein Handel in der App — nur ein Link. Wie `DepegAlarm.EXCHANGE_URLS`.
    static let exchangeUrls: [(key: String, url: String)] = [
        ("Binance", "https://www.binance.com/en/trade/USDC_USDT?type=spot"),
        ("Kraken", "https://pro.kraken.com/app/trade/usdc-usdt"),
        ("Coinbase", "https://www.coinbase.com/advanced-trade/spot/USDT-USDC"),
        ("Bybit", "https://www.bybit.com/en/trade/spot/USDC/USDT"),
        ("Okex", "https://www.okx.com/trade-spot/usdc-usdt"),
        ("Bitget", "https://www.bitget.com/spot/USDCUSDT"),
        ("Kucoin", "https://www.kucoin.com/trade/USDC-USDT"),
        ("GateIo", "https://www.gate.io/trade/USDC_USDT"),
        ("Mexc", "https://www.mexc.com/exchange/USDC_USDT"),
    ]

    /// Futures-Börsen zählen für ihre Spot-Börse.
    private static let aliases = ["BinanceFutures": "Binance", "OkexFutures": "Okex"]

    /// Ohne passende Börse in der Merkliste.
    static let fallbackExchange = "Kraken"

    /// Hauptbörse: die in der Merkliste am häufigsten genutzte Börse mit Link; Gleichstand → Reihenfolge von `exchangeUrls`.
    static func mainExchange(_ exchangeKeys: [String]) -> String {
        var counts: [String: Int] = [:]
        for key in exchangeKeys { counts[aliases[key] ?? key, default: 0] += 1 }
        var best: String?
        var bestCount = 0
        for entry in exchangeUrls {
            let count = counts[entry.key] ?? 0
            if count > bestCount {
                best = entry.key
                bestCount = count
            }
        }
        return best ?? fallbackExchange
    }

    /// Link «Börse öffnen» für die Hauptbörse.
    static func exchangeUrl(_ exchangeKeys: [String]) -> String {
        let key = mainExchange(exchangeKeys)
        return exchangeUrls.first { $0.key == key }?.url
            ?? exchangeUrls.first { $0.key == fallbackExchange }?.url ?? ""
    }
}
