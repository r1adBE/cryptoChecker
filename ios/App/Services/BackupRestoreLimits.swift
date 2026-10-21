import Foundation

/// Obergrenzen für Merkliste und Paar-Alarme aus einer Sicherung (`BackupManager.restore`) — gleich wie Android
/// `BackupRestoreLimits.kt` (gemeinsame Fälle `Parity/backup_limits.json`). Eine Datei kann von Hand verändert sein:
/// ohne Grenzen kämen beliebig lange Kennungen oder Hunderttausende Paare in Speicher, Abfragen und Börsen-Adressen.
/// Seit Build 99.
///
/// - Paar mit zu langer Börse, Basis, Quote, Börsenname oder Paar-Kennung fällt weg (Kürzen änderte, welches Paar
///   gemeint ist). Gezählt werden Unicode-Codepunkte, auf beiden Plattformen gleich.
/// - Paar-Kennung nur aus Buchstaben und Ziffern jeder Schrift (Binance führt Coins mit chinesischem Namen) sowie
///   «/ _ : . -»; Leerzeichen, «@», «&», «?» u. Ä. kommen in keiner Börse vor.
/// - Unbekannte Börsen bleiben (Platzhalter `UnknownMarket`), nur begrenzt.
/// - Höchstens `maxPairs` Paare (die ersten der Datei) und je Paar `maxAlarmsPerPair` Alarme.
enum BackupRestoreLimits {
    static let maxMarketKey = 64
    static let maxMarketName = 60
    static let maxAsset = 30
    static let maxPairId = 128
    /// Gleiche Grenzen wie im Rest der App (`WatchlistLimits`), hier nur weitergereicht.
    static let maxPairs = WatchlistLimits.maxPairs
    static let maxAlarmsPerPair = WatchlistLimits.maxAlarmsPerPair

    /// Darf das Paar aus der Sicherung übernommen werden?
    static func acceptsWatch(_ w: Watch) -> Bool {
        acceptsPair(marketKey: w.marketKey, marketName: w.marketName, base: w.baseAsset, quote: w.quoteAsset, pairId: w.pairId)
    }

    static func acceptsPair(marketKey: String, marketName: String, base: String, quote: String, pairId: String?) -> Bool {
        marketKey.unicodeScalars.count <= maxMarketKey
            && marketName.unicodeScalars.count <= maxMarketName
            && base.unicodeScalars.count <= maxAsset
            && quote.unicodeScalars.count <= maxAsset
            && (pairId.map(validPairId) ?? true)
    }

    /// Kennung höchstens `maxPairId` Codepunkte, nur Buchstaben/Ziffern jeder Schrift und «/ _ : . -».
    static func validPairId(_ pairId: String) -> Bool {
        guard pairId.unicodeScalars.count <= maxPairId else { return false }
        return pairId.unicodeScalars.allSatisfy { s in
            if s.isASCII, "/_:.-".unicodeScalars.contains(s) { return true }
            switch s.properties.generalCategory {
            case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter, .decimalNumber:
                return true
            default:
                return false
            }
        }
    }

    /// Höchstens `maxPairs` Paare (nach der Bereinigung, damit Doppel keine Plätze belegen) und je Paar die ersten
    /// `maxAlarmsPerPair` Alarme; Alarme weggelassener Paare fallen mit weg.
    static func limit(watches: [Watch], alarms: [Alarm]) -> (watches: [Watch], alarms: [Alarm]) {
        let kept = Array(watches.prefix(maxPairs))
        let keptIds = Set(kept.map(\.id))
        var perPair: [Int64: Int] = [:]
        let keptAlarms = alarms.filter { a in
            guard keptIds.contains(a.watchId) else { return false }
            let n = perPair[a.watchId, default: 0]
            perPair[a.watchId] = n + 1
            return n < maxAlarmsPerPair
        }
        return (kept, keptAlarms)
    }
}
