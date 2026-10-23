import Foundation

/// Einmalige Umstellung der alten Speicher-Notlösungen auf eigene Felder — wie Android `LegacyStorageMigration`
/// (Begründung und alte Formen dort; gemeinsame Fälle `legacy_storage_migration.json`). Läuft beim Lesen von
/// `portfolio.json` (`PortfolioTx.init(from:)`) und beim Wiederherstellen alter Sicherungen (`BackupManager.jsonToTx`); das
/// nächste Speichern schreibt nur noch die neuen Felder. Nur hier stehen die alten Formen noch.
///  - Merker `spouseMarker` im Feld «nach» einer Schenkung → `giftToSpouse`.
///  - Beide Herkunftsschlüssel im `importKey` («Auszahlung `link` Einzahlung») → `importKey` + `linkedImportKey`.
///  - Zustand vor der Verknüpfung hinter `restore` (Fassung «1» oder «2» der alten Form) → `importRestoreState`;
///    unlesbar → keiner (die Schlüssel werden trotzdem getrennt).
/// Werte ohne alte Form kommen unverändert zurück — die Umstellung ist beliebig oft anwendbar.
enum LegacyStorageMigration {
    /// Gebühr-Zeile nach der früheren Regel: ein Verkauf zum Preis 0 MIT Import-Herkunft. Heute steht das im
    /// eigenen Feld `feeRow`; diese Regel leitet es nur noch einmal ab (Datei bzw. Sicherung ohne das Feld). Wie Android
    /// `legacyFeeRow`.
    static func legacyFeeRow(_ type: PortfolioTxType?, importKey: String?, price: Decimal?) -> Bool {
        guard type == .SELL, let key = importKey, !key.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              let price else { return false }
        return price == 0
    }

    /// Zins (Lending) nach der früheren Regel: Belohnung, deren Notiz «interest», «lending», «Zins» … nennt
    /// (`interestRewardLegacy`). Heute eigenes Feld `interest`. Wie Android `legacyInterest`.
    static func legacyInterest(_ type: PortfolioTxType?, note: String?) -> Bool {
        type == .REWARD && interestRewardLegacy(note)
    }

    /// Zins-Erkennung, wie sie früher galt (Teilstring, auch «Verzinsung»): eingefroren für Altbestand — alte Dateien
    /// und Sicherungen ohne Feld `interest` werden so abgeleitet, wie die App sie damals rechnete. Die Wort-Regel
    /// (`TaxEngines.interestReward`) gilt nur für neue Eingaben und Importe. Wie Android.
    static func interestRewardLegacy(_ text: String?) -> Bool {
        guard let t = text?.lowercased() else { return false }
        return ["interest", "lending", "zins", "interet", "intérêt"].contains { t.contains($0) }
    }


    /// Merker im Feld «nach» einer Schenkung (alte Form).
    static let spouseMarker = "@spouse"

    /// Trenner der beiden Herkunftsschlüssel im alten `importKey`.
    static let link = " ⇄ "

    /// Trenner vor dem alten Zustand im `importKey`.
    static let restore = " ⟲ "

    private static let versionWithAbsorbed = "2"
    private static let versionStoredOnly = "1"

    /// Die betroffenen Felder einer Transaktion (vorher bzw. nachher).
    struct Fields: Equatable {
        var transferTo: String?
        var giftToSpouse: Bool
        var importKey: String?
        var linkedImportKey: String?
        var importRestoreState: CsvRestoreState?
    }

    /// Alte Formen in `fields` einer Transaktion der Art `type` (nil = unbekannte Art) in die Felder überführen.
    static func convert(_ type: PortfolioTxType?, _ fields: Fields) -> Fields {
        var out = fields
        if type == .GIFT, fields.transferTo?.trimmingCharacters(in: .whitespacesAndNewlines) == spouseMarker {
            out.transferTo = nil
            out.giftToSpouse = true
        }
        if let key = fields.importKey, key.contains(link), fields.linkedImportKey == nil {
            let segments = key.components(separatedBy: restore)
            let parts = segments[0].components(separatedBy: link)
            if parts.count == 2 {
                out.importKey = parts[0]
                out.linkedImportKey = parts[1]
                out.importRestoreState = fields.importRestoreState ?? restoreState(segments, parts)
            }
        }
        return out
    }

    /// Kurzform eines langen Teilschlüssels («~» + FNV-1a wie `PortfolioImportBatch.hash`): früher gekürzte Teile
    /// verknüpfter Umbuchungen bleiben so stehen (`CsvImport.splitDuplicates` erkennt die Zeile darüber). Wie Android.
    static func shortKey(_ key: String) -> String { "~" + PortfolioImportBatch.hash([key]) }

    /// Zustand aus den Abschnitten nach `restore`; nil = keiner, unbekannte Fassung oder unlesbar.
    private static func restoreState(_ segments: [String], _ parts: [String]) -> CsvRestoreState? {
        guard (2...3).contains(segments.count) else { return nil }
        let f = segments[1].components(separatedBy: "|").map(unescape)
        let withAbsorbed = segments.count == 3
        guard f.count == 15, f[0] == (withAbsorbed ? versionWithAbsorbed : versionStoredOnly),
              let type = PortfolioTxType(rawValue: f[2]) else { return nil }
        // Eigene Seite der Verknüpfung: Einzahlung rechts, sonst links; die aufgegangene neue Zeile ist die andere
        let ownKey = type == .DEPOSIT ? parts[1] : parts[0]
        let otherKey = type == .DEPOSIT ? parts[0] : parts[1]
        var absorbed: CsvAbsorbedRow?
        if withAbsorbed {
            guard let row = absorbedRow(segments[2], importKey: otherKey) else { return nil }
            absorbed = row
        }
        func text(_ i: Int) -> String? { f[i].isEmpty ? nil : f[i] }
        func dec(_ i: Int) -> Decimal? { text(i).flatMap { Decimal(string: $0, locale: Locale(identifier: "en_US_POSIX")) } }
        guard let amount = dec(3), let time = Int64(f[5]) else { return nil }
        return CsvRestoreState(
            type: type, amount: amount, priceUsdt: dec(4), time: time, fee: dec(6),
            feeUnit: text(7).flatMap(PortfolioFeeUnit.init(rawValue:)), feeCoin: text(8), feeCoinAmount: dec(9),
            feeCoinPrice: dec(10), transferFrom: text(11), transferTo: text(12), acquiredTime: text(13).flatMap { Int64($0) },
            exchange: text(14), importKey: ownKey, importBatch: text(1), absorbed: absorbed)
    }

    /// Aufgegangene neue Zeile aus dem dritten Abschnitt (Fassung «2»); nil = unlesbar.
    private static func absorbedRow(_ segment: String, importKey: String) -> CsvAbsorbedRow? {
        let f = segment.components(separatedBy: "|").map(unescape)
        guard f.count == 8, let type = PortfolioTxType(rawValue: f[0]) else { return nil }
        func text(_ i: Int) -> String? { f[i].isEmpty ? nil : f[i] }
        func dec(_ i: Int) -> Decimal? { text(i).flatMap { Decimal(string: $0, locale: Locale(identifier: "en_US_POSIX")) } }
        guard let amount = dec(1), let time = Int64(f[3]) else { return nil }
        return CsvAbsorbedRow(type: type, amount: amount, priceUsdt: dec(2), time: time, fee: dec(4),
                              feeUnit: text(5).flatMap(PortfolioFeeUnit.init(rawValue:)), feeCoin: text(6),
                              feeCoinAmount: dec(7), importKey: importKey)
    }

    /// %-Ersatz der alten Felder rückgängig («%7C» = «|», «%21C4» = «⇄», «%27F2» = «⟲», «%25» = «%»).
    private static func unescape(_ t: String) -> String {
        t.replacingOccurrences(of: "%27F2", with: "⟲").replacingOccurrences(of: "%21C4", with: "⇄")
            .replacingOccurrences(of: "%7C", with: "|").replacingOccurrences(of: "%25", with: "%")
    }
}
