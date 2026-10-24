import Foundation

/// Obergrenzen für Merkliste und Paar-Alarme aus einer Sicherung (`BackupManager.restore`) — gleich wie Android
/// `BackupRestoreLimits.kt` (gemeinsame Fälle `Parity/backup_limits.json`). Eine Datei kann von Hand verändert sein:
/// ohne Grenzen kämen beliebig lange Kennungen oder Hunderttausende Paare in Speicher, Abfragen und Börsen-Adressen.
///
/// - Paar mit zu langer Börse, Basis, Quote, Börsenname oder Paar-Kennung fällt weg (Kürzen änderte, welches Paar
///   gemeint ist). Gezählt werden Unicode-Codepunkte, auf beiden Plattformen gleich.
/// - Paar-Kennung nur aus Buchstaben und Ziffern jeder Schrift (Binance führt Coins mit chinesischem Namen) sowie
///   «/ _ : . -»; Leerzeichen, «@», «&», «?» u. Ä. kommen in keiner Börse vor.
/// - Basis und Quote wie Symbole (`validAsset`): Buchstaben und Ziffern jeder Schrift sowie «. _ -» («1000PEPE»,
///   «USDC.e», «BTC-PERP», «币安人生»). Ohne Kennung bauen die Börsen die Adresse daraus («BTC-USD»); «/», «?», «#»,
///   «&» u. Ä. veränderten sonst Pfad oder Abfrage.
/// - DexScreener-Paar nur mit gültiger Kennung «chain/adresse» (`DexScreener.isSafePairId`); nur diese kommt in die
///   Adresse, Basis und Quote sind dort Anzeigetext der Börse («USD (ethereum)», «$WIF») und nur begrenzt.
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
            && assetAllowed(marketKey: marketKey, base)
            && assetAllowed(marketKey: marketKey, quote)
            && (pairId.map(validPairId) ?? true)
            // DexScreener nur mit «chain/adresse», die unkodiert in den Pfad darf
            && (marketKey != DexScreener.key || pairId.map(DexScreener.isSafePairId) == true)
    }

    /// Kennung höchstens `maxPairId` Codepunkte, nur Buchstaben/Ziffern jeder Schrift und «/ _ : . -».
    static func validPairId(_ pairId: String) -> Bool {
        guard pairId.unicodeScalars.count <= maxPairId else { return false }
        return pairId.unicodeScalars.allSatisfy { isAllowed($0, punctuation: "/_:.-") }
    }

    /// Basis bzw. Quote höchstens `maxAsset` Codepunkte, nur Buchstaben/Ziffern jeder Schrift und «. _ -».
    static func validAsset(_ asset: String) -> Bool {
        guard asset.unicodeScalars.count <= maxAsset else { return false }
        return asset.unicodeScalars.allSatisfy { isAllowed($0, punctuation: "._-") }
    }

    /// DexScreener: Basis/Quote nur begrenzt (Anzeigetext, nie in der Adresse); sonst wie Symbole (`validAsset`).
    private static func assetAllowed(marketKey: String, _ asset: String) -> Bool {
        marketKey == DexScreener.key ? asset.unicodeScalars.count <= maxAsset : validAsset(asset)
    }

    /// Buchstabe (alle L-Kategorien), Dezimalziffer oder eines der ASCII-Zeichen in `punctuation`.
    private static func isAllowed(_ s: Unicode.Scalar, punctuation: String) -> Bool {
        if s.isASCII, punctuation.unicodeScalars.contains(s) { return true }
        switch s.properties.generalCategory {
        case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter, .decimalNumber:
            return true
        default:
            return false
        }
    }

    /// Was nach `accept` bzw. `limit` übernommen wird; `droppedPairs`/`droppedAlarms` = wie viele wegen der Grenzen
    /// wegfallen. Gezählt, damit Rückfrage und Erfolgsmeldung sie nennen — eine eigene ältere Sicherung mit mehr Paaren
    /// oder Alarmen verlöre sonst still Daten (wie Android `Limited`).
    struct Limited {
        var watches: [Watch]
        var alarms: [Alarm]
        var droppedPairs = 0
        var droppedAlarms = 0
    }

    /// Paare, die `acceptsWatch` ablehnt, weglassen und mit ihnen ihre Alarme (vor der Bereinigung, damit ein
    /// ungültiges Paar kein gültiges verdrängt). Alarme ohne Paar in der Datei zählen nicht: die fielen schon immer weg.
    static func accept(watches: [Watch], alarms: [Alarm]) -> Limited {
        let kept = watches.filter(acceptsWatch)
        let rejectedCount = watches.count - kept.count
        guard rejectedCount > 0 else { return Limited(watches: watches, alarms: alarms) }
        let rejectedIds = Set(watches.filter { !acceptsWatch($0) }.map(\.id)).subtracting(kept.map(\.id))
        let keptAlarms = alarms.filter { !rejectedIds.contains($0.watchId) }
        return Limited(watches: kept, alarms: keptAlarms,
                       droppedPairs: rejectedCount, droppedAlarms: alarms.count - keptAlarms.count)
    }

    /// Höchstens `maxPairs` Paare (nach der Bereinigung, damit Doppel keine Plätze belegen) und je Paar die ersten
    /// `maxAlarmsPerPair` Alarme; Alarme weggelassener Paare fallen mit weg. Nach der Bereinigung hängt jeder Alarm an
    /// einem Paar: jeder weggelassene zählt.
    static func limit(watches: [Watch], alarms: [Alarm]) -> Limited {
        let kept = Array(watches.prefix(maxPairs))
        let keptIds = Set(kept.map(\.id))
        var perPair: [Int64: Int] = [:]
        let keptAlarms = alarms.filter { a in
            guard keptIds.contains(a.watchId) else { return false }
            let n = perPair[a.watchId, default: 0]
            perPair[a.watchId] = n + 1
            return n < maxAlarmsPerPair
        }
        return Limited(watches: kept, alarms: keptAlarms,
                       droppedPairs: watches.count - kept.count, droppedAlarms: alarms.count - keptAlarms.count)
    }
}
