import Foundation

/// Die neue Zeile der späteren Datei, die in einer Verknüpfung mit einer gespeicherten Seite aufging — «Import rückgängig»
/// des FRÜHEREN Laufs stellt sie als eigene Zeile im späteren Lauf wieder her. Wie Android `CsvAbsorbedRow`.
struct CsvAbsorbedRow: Hashable, Sendable {
    let type: PortfolioTxType
    let amount: Decimal
    let priceUsdt: Decimal?
    let time: Int64
    let fee: Decimal?
    let feeUnit: PortfolioFeeUnit?
    let feeCoin: String?
    let feeCoinAmount: Decimal?
    let importKey: String
}

/// Zustand einer gespeicherten Transaktion vor der Verknüpfung zu einer Umbuchung (`CsvTransferMatch.restoreState`) —
/// gespeichert in `PortfolioTx.importRestoreState` (Datei und Sicherung «importRestoreState»; Android: Spalte der
/// Portfolio-Datenbank). «Import rückgängig» des verknüpfenden Laufs setzt die Transaktion darauf zurück; `absorbed` = die
/// darin aufgegangene neue Zeile. Wie Android
/// `CsvRestoreState` — dort steht auch, warum EIN Feld mit eigener Fassungsnummer statt 23 einzelner Felder.
///
/// Format (Fassung 1; Zahlen exakt als Text wie `PortfolioDecimal.text`, fehlende Werte weggelassen):
/// `{"v": 1, "stored": {type, amount, priceUsdt?, time, fee?, feeUnit?, feeCoin?, feeCoinAmount?, feeCoinPriceUsdt?,
/// transferFrom?, transferTo?, acquiredTime?, exchange?, importKey, importBatch?}, "absorbed": {type, amount, priceUsdt?, time,
/// fee?, feeUnit?, feeCoin?, feeCoinAmount?, importKey}?}`. Unbekannte Fassung oder unlesbar → Fehler beim Lesen (der
/// Aufrufer liest dann keinen Zustand).
struct CsvRestoreState: Hashable, Sendable {
    let type: PortfolioTxType
    let amount: Decimal
    let priceUsdt: Decimal?
    let time: Int64
    let fee: Decimal?
    let feeUnit: PortfolioFeeUnit?
    let feeCoin: String?
    let feeCoinAmount: Decimal?
    let feeCoinPrice: Decimal?
    let transferFrom: String?
    let transferTo: String?
    let acquiredTime: Int64?
    let exchange: String?
    let importKey: String
    let importBatch: String?
    let absorbed: CsvAbsorbedRow?

    /// Fassung des Formats; eine neue Fassung bekommt eine neue Zahl, ältere bleiben lesbar.
    static let version = 1

    /// Längster gelesener Text — nur gegen unsinnige Eingaben aus einer Sicherung (wie Android `MAX_TEXT`).
    static let maxText = 4_000

    /// JSON-Form für die Sicherung (`BackupManager`); gleiches Format wie die Datei.
    func json() -> [String: Any]? {
        guard let data = try? JSONEncoder().encode(self) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }

    /// Aus der JSON-Form der Sicherung; nil = zu lang, unbekannte Fassung oder unlesbar.
    static func from(json: Any?) -> CsvRestoreState? {
        guard let object = json as? [String: Any], JSONSerialization.isValidJSONObject(object),
              let data = try? JSONSerialization.data(withJSONObject: object), data.count <= maxText else { return nil }
        return try? JSONDecoder().decode(CsvRestoreState.self, from: data)
    }
}

extension CsvRestoreState: Codable {
    private enum Keys: String, CodingKey {
        case v, stored, absorbed
        case type, amount, priceUsdt, time, fee, feeUnit, feeCoin, feeCoinAmount, feeCoinPriceUsdt, transferFrom, transferTo
        case acquiredTime, exchange, importKey, importBatch
    }

    private struct Unreadable: Error {}

    init(from decoder: Decoder) throws {
        let root = try decoder.container(keyedBy: Keys.self)
        guard try root.decode(Int.self, forKey: .v) == Self.version else { throw Unreadable() }
        let s = try root.nestedContainer(keyedBy: Keys.self, forKey: .stored)
        var absorbed: CsvAbsorbedRow?
        if root.contains(.absorbed), try !root.decodeNil(forKey: .absorbed) {
            let a = try root.nestedContainer(keyedBy: Keys.self, forKey: .absorbed)
            absorbed = CsvAbsorbedRow(
                type: try Self.txType(a), amount: try Self.required(Self.decimal(a, .amount)), priceUsdt: Self.decimal(a, .priceUsdt),
                time: try a.decode(Int64.self, forKey: .time), fee: Self.decimal(a, .fee), feeUnit: Self.feeUnit(a),
                feeCoin: Self.text(a, .feeCoin), feeCoinAmount: Self.decimal(a, .feeCoinAmount),
                importKey: try Self.required(Self.text(a, .importKey)))
        }
        self.init(
            type: try Self.txType(s), amount: try Self.required(Self.decimal(s, .amount)), priceUsdt: Self.decimal(s, .priceUsdt),
            time: try s.decode(Int64.self, forKey: .time), fee: Self.decimal(s, .fee), feeUnit: Self.feeUnit(s),
            feeCoin: Self.text(s, .feeCoin), feeCoinAmount: Self.decimal(s, .feeCoinAmount),
            feeCoinPrice: Self.decimal(s, .feeCoinPriceUsdt), transferFrom: Self.text(s, .transferFrom),
            transferTo: Self.text(s, .transferTo), acquiredTime: (try? s.decodeIfPresent(Int64.self, forKey: .acquiredTime)) ?? nil,
            exchange: Self.text(s, .exchange), importKey: try Self.required(Self.text(s, .importKey)),
            importBatch: Self.text(s, .importBatch), absorbed: absorbed)
    }

    func encode(to encoder: Encoder) throws {
        var root = encoder.container(keyedBy: Keys.self)
        try root.encode(Self.version, forKey: .v)
        var s = root.nestedContainer(keyedBy: Keys.self, forKey: .stored)
        try s.encode(type.rawValue, forKey: .type)
        try s.encode(PortfolioDecimal.text(amount), forKey: .amount)
        try s.encodeIfPresent(priceUsdt.map(PortfolioDecimal.text), forKey: .priceUsdt)
        try s.encode(time, forKey: .time)
        try s.encodeIfPresent(fee.map(PortfolioDecimal.text), forKey: .fee)
        try s.encodeIfPresent(feeUnit?.rawValue, forKey: .feeUnit)
        try s.encodeIfPresent(feeCoin, forKey: .feeCoin)
        try s.encodeIfPresent(feeCoinAmount.map(PortfolioDecimal.text), forKey: .feeCoinAmount)
        try s.encodeIfPresent(feeCoinPrice.map(PortfolioDecimal.text), forKey: .feeCoinPriceUsdt)
        try s.encodeIfPresent(transferFrom, forKey: .transferFrom)
        try s.encodeIfPresent(transferTo, forKey: .transferTo)
        try s.encodeIfPresent(acquiredTime, forKey: .acquiredTime)
        try s.encodeIfPresent(exchange, forKey: .exchange)
        try s.encode(importKey, forKey: .importKey)
        try s.encodeIfPresent(importBatch, forKey: .importBatch)
        guard let absorbed else { return }
        var a = root.nestedContainer(keyedBy: Keys.self, forKey: .absorbed)
        try a.encode(absorbed.type.rawValue, forKey: .type)
        try a.encode(PortfolioDecimal.text(absorbed.amount), forKey: .amount)
        try a.encodeIfPresent(absorbed.priceUsdt.map(PortfolioDecimal.text), forKey: .priceUsdt)
        try a.encode(absorbed.time, forKey: .time)
        try a.encodeIfPresent(absorbed.fee.map(PortfolioDecimal.text), forKey: .fee)
        try a.encodeIfPresent(absorbed.feeUnit?.rawValue, forKey: .feeUnit)
        try a.encodeIfPresent(absorbed.feeCoin, forKey: .feeCoin)
        try a.encodeIfPresent(absorbed.feeCoinAmount.map(PortfolioDecimal.text), forKey: .feeCoinAmount)
        try a.encode(absorbed.importKey, forKey: .importKey)
    }

    /// Text oder nil (fehlt, JSON null, kein Text oder leer) — wie Android `text`.
    private static func text(_ c: KeyedDecodingContainer<Keys>, _ key: Keys) -> String? {
        guard let t = (try? c.decodeIfPresent(String.self, forKey: key)) ?? nil, !t.isEmpty else { return nil }
        return t
    }

    private static func decimal(_ c: KeyedDecodingContainer<Keys>, _ key: Keys) -> Decimal? {
        text(c, key).flatMap { PortfolioDecimal.parse($0) }
    }

    /// Unbekannte Gebühr-Einheit = keine (wie Android).
    private static func feeUnit(_ c: KeyedDecodingContainer<Keys>) -> PortfolioFeeUnit? {
        text(c, .feeUnit).flatMap(PortfolioFeeUnit.init(rawValue:))
    }

    private static func txType(_ c: KeyedDecodingContainer<Keys>) throws -> PortfolioTxType {
        try required(text(c, .type).flatMap(PortfolioTxType.init(rawValue:)))
    }

    private static func required<T>(_ value: T?) throws -> T {
        guard let value else { throw Unreadable() }
        return value
    }
}
