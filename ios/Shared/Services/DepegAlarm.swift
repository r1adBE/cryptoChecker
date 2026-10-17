import Foundation

/// USDT-Depeg-Alarm (Build 46): meldet einmal je Abweichung, wenn USDT um mindestens die Schwelle
/// von 1 USD abweicht. Bewertungen in der App rechnen weiter mit USDT = 1 (nur ein Hinweis).
///
/// Quellen (bestehende Kurs-Abrufe, kein neuer Anbieter): Kraken USDT/USD (direkt) und Binance
/// USDC/USDT (USDT = 1 / Kurs, USDC als 1 USD angenommen). Beide da: der Wert näher bei 1 zählt —
/// gemeldet wird also nur, wenn beide Quellen abweichen. Nur eine da: diese. Keine: keine Prüfung.
///
/// Hysterese: Nach einer Meldung erst wieder scharf, wenn die Abweichung auf die halbe Schwelle
/// zurückgeht. Kann die Mitteilung gerade nicht ankommen (Nachtruhe, keine Erlaubnis), bleibt der Alarm
/// scharf (`QuietHours.mayFire`) — wie die Kursalarme. Reine Logik — wie `DepegAlarm.kt`.
enum DepegAlarm {
    /// Schwelle in Zehntel-Prozent: 0.3 / 0.5 / 1 / 2 %.
    static let thresholdChoices = [3, 5, 10, 20]
    static let defaultThresholdTenths = 5

    // Quellen: Börsen-Schlüssel und Paar-Kennung (wie in der Paarliste der Börse)
    static let krakenKey = "Kraken"
    static let krakenPairId = "USDTZUSD"
    static let binanceKey = "Binance"
    static let binancePairId = "USDCUSDT"

    /// Unplausible Kurse (Fehler der Börse) fallen weg.
    private static let minPlausible = 0.5
    private static let maxPlausible = 1.5

    /// Gespeicherte Schwelle; unbekannte Werte → Standard.
    static func threshold(_ tenths: Int) -> Int {
        thresholdChoices.contains(tenths) ? tenths : defaultThresholdTenths
    }

    static func thresholdPercent(_ tenths: Int) -> Double { Double(threshold(tenths)) / 10 }

    private static func plausible(_ price: Double) -> Bool {
        price.isFinite && price >= minPlausible && price <= maxPlausible
    }

    /// USDT in USD aus Kraken USDT/USD.
    static func fromKraken(_ last: Double?) -> Double? {
        guard let last, plausible(last) else { return nil }
        return last
    }

    /// USDT in USD aus Binance USDC/USDT (1 USDC = `last` USDT).
    static func fromBinanceUsdc(_ last: Double?) -> Double? {
        guard let last, last.isFinite, last > 0 else { return nil }
        let usdt = 1 / last
        return plausible(usdt) ? usdt : nil
    }

    /// Beide Quellen: der Wert näher bei 1; eine: diese; keine: nil.
    static func combine(kraken: Double?, binance: Double?) -> Double? {
        [kraken, binance].compactMap { $0 }.min { abs($0 - 1) < abs($1 - 1) }
    }

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

    /// Letzte Messung der Prüfung (USDT in USD, Zeitpunkt) — gespeichert, damit Karte und Alarm übereinstimmen.
    struct Reading: Equatable {
        let price: Double
        let atMillis: Int64
    }

    /// So lange gilt eine Messung auf der Portfolio-Karte (Prüfung höchstens alle 5 Minuten).
    static let cardMaxAgeMillis: Int64 = 30 * 60_000

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

    /// Art der Zeile zu «USDT = 1» auf der Portfolio-Karte (`cardNote`) — wie `CardNoteKind` (Android).
    enum CardNoteKind: String, Equatable {
        /// Frische Messung weicht um mindestens die Schwelle ab: «USDT = 1 angenommen – Markt bei …» (Warnfarbe).
        case DEVIATION
        /// Werte hängen an USDT = USD (Anzeigewährung, USD, gemischte Börsen): «Kurse: Binance · USDT = 1».
        case PRICE_SOURCE
        /// Der Bestand enthält USDT (zählt immer genau 1): «USDT = 1 angenommen».
        case USDT_ASSUMED
    }

    /// Zeile der Karte; `reading` nur bei `.DEVIATION` (Zahlen der Warnzeile).
    struct CardNote: Equatable {
        let kind: CardNoteKind
        var reading: Reading? = nil
    }

    /// Zeile zu «USDT = 1» auf der Portfolio-Karte (Build 58) — UNABHÄNGIG vom Depeg-Alarm: Die Annahme gilt immer, also
    /// steht sie da, sobald sie die Zahlen trägt; der Alarm liefert nur die Abweichung dazu. Wie `cardNote` (Android):
    ///  1. frische Messung ≥ Schwelle (`cardWarning`, wie bisher immer auf der Karte) → `.DEVIATION`;
    ///  2. sonst `valuesViaUsdt` (`PortfolioExchangeRoute.usdtParityShown`) → `.PRICE_SOURCE`;
    ///  3. sonst enthält der Bestand `holdingCoins` USDT (`PortfolioStables.isValuation`) → `.USDT_ASSUMED`;
    ///  4. sonst keine Zeile (nil).
    static func cardNote(holdingCoins: [String], valuesViaUsdt: Bool, reading: Reading?, thresholdTenths: Int,
                         now: Int64) -> CardNote? {
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
    static func mainExchange(_ marketKeys: [String]) -> String {
        var counts: [String: Int] = [:]
        for key in marketKeys { counts[aliases[key] ?? key, default: 0] += 1 }
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
    static func exchangeUrl(_ marketKeys: [String]) -> String {
        let key = mainExchange(marketKeys)
        return exchangeUrls.first { $0.key == key }?.url
            ?? exchangeUrls.first { $0.key == fallbackExchange }?.url ?? ""
    }
}
