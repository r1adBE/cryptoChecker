import Foundation

/// Art einer Portfolio-Transaktion. Rohwert wird so in Datei und Sicherung geschrieben
/// (gleich wie `PortfolioTxType` der Android-Fassung).
///  - `BUY`/`SELL`: Handel gegen USDT (Preis = Kurs je Coin).
///  - `TRANSFER`: Umbuchung zwischen Börsen/Wallets (von/nach) — kein Gewinn, Einstand bleibt; eine
///    Netzwerkgebühr im Coin verringert nur die Menge (ihr Einstand bleibt im Rest, der Ø steigt).
///  - `REWARD`: Staking/Airdrop/Zinsen — Zugang zum Wert beim Erhalt (ohne Preis: Teil «ohne Kaufpreis»).
///  - `DEPOSIT`: Einzahlung von aussen — Zugang, Preis optional.
///  - `WITHDRAWAL`: Auszahlung aus dem erfassten Portfolio — Abgang zum Ø-Einstand, KEIN Verkauf.
///  - `SWAP`: Tausch Coin gegen Coin in EINER Transaktion (`toCoin`/`toAmountExact`, Wert `swapValueExact`) —
///    Veräusserung von `coin` und Zugang von `toCoin` (Regeln in `PortfolioLedger`/`PortfolioFifo`). Weder Zu- noch
///    Abgang im Sinne von `isInflow`/`isOutflow`.
///  - `GIFT`: «Verschenkt» (Build 47) — beim Schenkenden meist KEINE Veräusserung: Karte, Verlauf und Einstandsmethoden wie
///    eine Auszahlung; nur wo die Schenkung beim Schenkenden eine Veräusserung zum Marktwert ist (UK, CA, AU,
///    `TaxTemplate.giftIsDisposal`) im Steuer-Export wie `PAYMENT` (`TaxEngines.applyRules`, Vermerk «Schenkung»); an
///    Ehe-/Lebenspartner (`PortfolioTx.isGiftToSpouse`) in UK/CA ohne Gewinn und Verlust.
///  - `PAYMENT`: «Damit bezahlt» (Build 47) — Tausch gegen Waren/Dienstleistungen: überall eine Veräusserung zum MARKTWERT wie
///    ein Verkauf (`isSaleLike`), Steuer-Export «Zahlung mit Krypto»; ohne Preis zum Tagesschluss geschätzt. Wie Android.
/// Ältere Versionen kennen nur `BUY`/`SELL` bzw. kein `SWAP`/`GIFT`/`PAYMENT`: Sie verwerfen unbekannte Arten beim Lesen (gezählt,
/// kein Absturz). Rohwert als Text: ein neuer Fall liest alte Dateien unverändert.
enum PortfolioTxType: String, Codable, CaseIterable, Sendable {
    case BUY
    case SELL
    case TRANSFER
    case REWARD
    case DEPOSIT
    case WITHDRAWAL
    case SWAP
    case GIFT
    case PAYMENT

    /// Zugang wie ein Kauf (Menge + Einstand, wenn der Preis bekannt ist).
    var isInflow: Bool { self == .BUY || self == .REWARD || self == .DEPOSIT }

    /// Abgang (Verkauf, Auszahlung, Verschenkt, Damit bezahlt).
    var isOutflow: Bool { self == .SELL || self == .WITHDRAWAL || self == .GIFT || self == .PAYMENT }

    /// Veräusserung wie ein Verkauf (Verkauf oder Damit bezahlt zum Marktwert) — wie Android `isSaleLike`.
    var isSaleLike: Bool { self == .SELL || self == .PAYMENT }

    /// Abgang ohne Veräusserung wie eine Auszahlung (Auszahlung oder Verschenkt) — wie Android `isWithdrawalLike`.
    var isWithdrawalLike: Bool { self == .WITHDRAWAL || self == .GIFT }
}

/// Währung einer Gebühr — wie `PortfolioFeeUnit` (Android); Rohwert so in Datei und Sicherung.
///  - `QUOTE`: in USDT (Währung der Preise) — Kauf: erhöht den Einstand, Verkauf: verringert den Erlös.
///  - `COIN`: im gehandelten Coin — verringert die Menge (Kauf: weniger erhalten, Verkauf: mehr abgegeben).
enum PortfolioFeeUnit: String, Codable, CaseIterable, Sendable {
    case QUOTE
    case COIN

    /// Unbekannt oder fehlend: `QUOTE` (Standard des Editors).
    static func from(name: String?) -> PortfolioFeeUnit { name.flatMap(PortfolioFeeUnit.init(rawValue:)) ?? .QUOTE }
}

/// Kauf oder Verkauf im Portfolio — unabhängig von der Merkliste.
/// Preise in USDT; `priceUsdt` nil = unbekannt (z. B. übernommener Bestand).
/// Menge, Preis und Gebühr sind exakt gespeichert (`amountExact`, `priceExact`, `feeExact`, siehe
/// `PortfolioDecimal`): 1 Satoshi = 0.00000001 BTC bleibt genau 1 Satoshi. `amount`, `priceUsdt`
/// und `fee` sind Double-Sichten darauf für Bewertung und Anzeige.
/// Entspricht `PortfolioTxEntity` der Android-Fassung.
struct PortfolioTx: Codable, Identifiable, Hashable, Sendable {
    /// 0 = noch nicht gespeichert.
    var id: Int64
    /// Basis-Symbol in Grossbuchstaben, z. B. «BTC».
    var coin: String
    var type: PortfolioTxType
    /// Menge exakt, immer > 0.
    var amountExact: Decimal
    /// Preis in USDT exakt; nil = unbekannt.
    var priceExact: Decimal?
    /// Zeitpunkt des Handels (ms).
    var time: Int64
    var note: String?
    /// Gebühr (> 0) in `feeUnit` exakt; nil = keine erfasst (ältere Dateien und Sicherungen).
    var feeExact: Decimal?
    /// Einheit der Gebühr; nil mit Gebühr = USDT.
    var feeUnit: PortfolioFeeUnit?
    /// Börse des Handels; alle Transaktionen eines Coins mit derselben Börse → Kurs dieser Börse
    /// (`PortfolioExchangeRoute`). nil = keine Angabe; bei einer Umbuchung nil (dort `transferFrom`/`transferTo`).
    var exchange: String?
    /// Umbuchung: woher / wohin (Börse oder Wallet, freier Text); nil = keine Angabe.
    var transferFrom: String? = nil
    var transferTo: String? = nil
    /// Gebühr in einem dritten Coin (z. B. «BNB»), nur zusammen mit `feeCoinAmountExact` (> 0).
    var feeCoin: String? = nil
    var feeCoinAmountExact: Decimal? = nil
    /// USDT je `feeCoin` zum Zeitpunkt (≥ 0); nil = unbekannt.
    var feeCoinPriceExact: Decimal? = nil
    /// Herkunft aus einem CSV-Import (Quelle + Zeilenschlüssel); erkennt dieselbe Zeile beim nächsten Import
    /// als doppelt. nil = von Hand erfasst. Wie `PortfolioTxEntity.importKey`.
    var importKey: String? = nil
    /// Einzahlung: ursprüngliches Kaufdatum (ms) — FIFO-Reihenfolge und Haltedauer ab diesem Datum; nil = Datum der
    /// Einzahlung. Nur bei `.DEPOSIT` (sonst ohne Wirkung). Wie `PortfolioTxEntity.acquiredTime`.
    var acquiredTime: Int64? = nil
    /// Tausch (`.SWAP`): erhaltener Coin (Grossbuchstaben, ≠ `coin`), erhaltene Menge (> 0) exakt und Wert des Tauschs in
    /// USDT (gesamt, ≥ 0; nil = unbekannt) — wie `PortfolioTxEntity.toCoin`/`toAmount`/`swapValueUsdt`.
    var toCoin: String? = nil
    var toAmountExact: Decimal? = nil
    var swapValueExact: Decimal? = nil
    /// Kennung des CSV-Importlaufs (`PortfolioImportBatch`); nil = von Hand erfasst. Wie `PortfolioTxEntity.importBatch`.
    var importBatch: String? = nil
    /// Schenkung (`.GIFT`) an Ehe-/Lebenspartner — UK/CA ohne Gewinn und Verlust (`TaxTemplate.spouseGiftNeutral`); bei
    /// anderen Arten ohne Wirkung (`isGiftToSpouse`). Wie `PortfolioTxEntity.giftToSpouse` (Build 58).
    var giftToSpouse = false
    /// Verknüpfte Umbuchung (`CsvTransferMatch`): Herkunft der Einzahlung (`importKey` = die der Auszahlung) — erkennt beim
    /// nächsten Import auch diese Zeile als doppelt; sonst nil. Wie `PortfolioTxEntity.linkedImportKey`.
    var linkedImportKey: String? = nil
    /// Verknüpfte Umbuchung aus einer gespeicherten Transaktion: deren Zustand davor samt der darin aufgegangenen neuen Zeile
    /// (`CsvRestoreState`) für «Import rückgängig»; sonst nil. Wie `PortfolioTxEntity.importRestoreState`.
    var importRestoreState: CsvRestoreState? = nil
    /// Gebühr-Zeile (CSV «Fee»): eine Veräusserung zum Tageskurs (`PortfolioLedger.isFeeDisposal`), gespeichert als Verkauf.
    /// Eigenes Feld seit Build 76 — bis dahin aus «Verkauf zum Preis 0 mit Import-Herkunft» geschlossen
    /// (`LegacyStorageMigration.legacyFeeRow`). Nur bei `.SELL`. Wie `PortfolioTxEntity.feeRow`.
    var feeRow = false
    /// Belohnung ist ein Zins (Lending) — AT: Einkunft beim Zufluss, Anschaffungskosten = Wert. Eigenes Feld seit Build 76
    /// (CSV-Import setzt es, im Erfassen-Blatt umschaltbar) — bis dahin aus der Notiz geschlossen
    /// (`LegacyStorageMigration.legacyInterest`). Nur bei `.REWARD`. Wie `PortfolioTxEntity.interest`.
    var interest = false
    /// Wert nur geschätzt (Tagesschluss, `PortfolioLedger.valueAtDayClose`): Verschenkt/bezahlt ohne Marktwert bzw. Tausch
    /// ohne Wert — Steuer-Export «Wert geschätzt (Tagesschluss)». Nie gespeichert (nicht in `CodingKeys`). Wie Android.
    var valueEstimated = false
    /// Verschenkt, im Steuer-Export als Veräusserung gerechnet (`TaxEngines.applyRules`: Art `.PAYMENT` mit diesem
    /// Merker, Vermerk «Schenkung»). Nie gespeichert. Wie Android.
    var fromGift = false

    /// Aus einem CSV-Import (`importKey` gesetzt). Wie Android `PortfolioTrade.imported`.
    var imported: Bool { !(importKey?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true) }

    /// Zeitpunkt des Erwerbs (FIFO-Reihenfolge, Haltedauer, Devisenkurs des Einstands): bei einer Einzahlung
    /// `acquiredTime`, höchstens die Zeit der Einzahlung; sonst `time`. Wie `PortfolioTrade.acquisitionTime`.
    var acquisitionTime: Int64 {
        if type == .DEPOSIT, let a = acquiredTime { return min(a, time) }
        return time
    }

    /// Schenkung an Ehe-/Lebenspartner: `giftToSpouse` bei einer Schenkung; nur die Steuer-Vorlagen UK/CA rechnen damit —
    /// wie `PortfolioTrade.isGiftToSpouse`.
    var isGiftToSpouse: Bool { type == .GIFT && giftToSpouse }

    /// Ursprüngliches Kaufdatum: nur bei einer Einzahlung und > 0 — wie `PortfolioJson.cleanAcquiredTime`.
    static func cleanAcquiredTime(_ type: PortfolioTxType, _ acquiredTime: Int64?) -> Int64? {
        guard type == .DEPOSIT, let a = acquiredTime, a > 0 else { return nil }
        return a
    }

    /// Menge als Double (Bewertung, Anzeige); Setzen über die kürzeste Darstellung.
    var amount: Double {
        get { PortfolioDecimal.double(amountExact) }
        set { amountExact = PortfolioDecimal.of(newValue) ?? 0 }
    }

    /// Preis als Double; nil = unbekannt.
    var priceUsdt: Double? {
        get { priceExact.map(PortfolioDecimal.double) }
        set { priceExact = PortfolioDecimal.of(newValue) }
    }

    /// Gebühr als Double; nil = keine.
    var fee: Double? {
        get { feeExact.map(PortfolioDecimal.double) }
        set { feeExact = PortfolioDecimal.of(newValue) }
    }

    /// Mit Gleitkomma-Werten (Tests, ältere Aufrufer): über die kürzeste Darstellung (0.1 → «0.1»).
    init(id: Int64 = 0, coin: String, type: PortfolioTxType, amount: Double,
         priceUsdt: Double? = nil, time: Int64, note: String? = nil,
         fee: Double? = nil, feeUnit: PortfolioFeeUnit? = nil, exchange: String? = nil) {
        self.init(id: id, coin: coin, type: type, amountExact: PortfolioDecimal.of(amount) ?? 0,
                  priceExact: PortfolioDecimal.of(priceUsdt), time: time, note: note,
                  feeExact: PortfolioDecimal.of(fee), feeUnit: feeUnit, exchange: exchange)
    }

    /// Exakt (Eingabe, Datei, Sicherung).
    init(id: Int64 = 0, coin: String, type: PortfolioTxType, amountExact: Decimal,
         priceExact: Decimal? = nil, time: Int64, note: String? = nil,
         feeExact: Decimal? = nil, feeUnit: PortfolioFeeUnit? = nil, exchange: String? = nil) {
        self.id = id
        self.coin = coin
        self.type = type
        self.amountExact = amountExact
        self.priceExact = priceExact
        self.time = time
        self.note = note
        self.feeExact = feeExact
        self.feeUnit = feeUnit
        self.exchange = exchange
    }

    /// `amount`/`priceUsdt`/`fee` als Zahl (ältere Versionen lesen nur sie), dazu exakt als Text
    /// (`amountText`: «0.00000001») — wie die Sicherung der Android-Fassung.
    private enum CodingKeys: String, CodingKey {
        case id, coin, type, amount, amountText, priceUsdt, priceText, time, note, fee, feeText, feeUnit, exchange
        // Seit Build 46 (wie Android `PortfolioJson`); fehlen = keine Angabe
        case transferFrom, transferTo, feeCoin, feeCoinAmountText, feeCoinPriceText, importKey, acquiredTime
        case toCoin, toAmountText, swapValueText, importBatch
        // Seit Build 58 (eigene Felder statt der alten Formen in «transferTo»/«importKey», `LegacyStorageMigration`)
        case giftToSpouse, linkedImportKey, importRestoreState
        // Seit Build 76 (eigene Merker statt Schluss aus Preis/Herkunft bzw. Notiz)
        case feeRow, interest
    }

    // Abgeleitete Werte und Bereinigung (Gebühren, Tausch, Route …): PortfolioTx+Derived.swift

    /// Exakte Zahl: zuerst der Text `textKey`, sonst die Zahl `numberKey` (ältere Dateien und
    /// Sicherungen, über ihre kürzeste Darstellung). Fehlt/ungültig = nil.
    private static func decimal(_ c: KeyedDecodingContainer<CodingKeys>, text textKey: CodingKeys,
                                number numberKey: CodingKeys) -> Decimal? {
        if let text = (try? c.decodeIfPresent(String.self, forKey: textKey)) ?? nil,
           let value = PortfolioDecimal.parse(text) {
            return value
        }
        let number = (try? c.decodeIfPresent(Double.self, forKey: numberKey)) ?? nil
        // Gleiche Grenzen wie der Text (absurde Grössen = ungültig)
        guard let value = PortfolioDecimal.of(number), PortfolioDecimal.withinLimits(value) else { return nil }
        return value
    }

    // Tolerant: fehlende Nebenfelder bekommen Standardwerte; ohne Coin, Art
    // oder gültige Menge ist der Eintrag unbrauchbar (wird beim Laden übersprungen).
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = (try? c.decodeIfPresent(Int64.self, forKey: .id)) ?? 0
        let rawCoin = try c.decode(String.self, forKey: .coin)
        coin = PortfolioCalculator.normalizeCoin(rawCoin)
        type = try c.decode(PortfolioTxType.self, forKey: .type)
        amountExact = Self.decimal(c, text: .amountText, number: .amount) ?? 0
        priceExact = Self.decimal(c, text: .priceText, number: .priceUsdt).flatMap { $0 >= 0 ? $0 : nil }
        time = (try? c.decodeIfPresent(Int64.self, forKey: .time)) ?? 0
        note = (try? c.decodeIfPresent(String.self, forKey: .note)) ?? nil
        // Seit Gebühr/Börse; fehlen in älteren Dateien (= keine Angabe), ungültig = keine Gebühr
        feeExact = Self.validFee(Self.decimal(c, text: .feeText, number: .fee))
        let unitName = (try? c.decodeIfPresent(String.self, forKey: .feeUnit)) ?? nil
        feeUnit = feeExact == nil ? nil : PortfolioFeeUnit.from(name: unitName)
        exchange = Self.cleanExchange((try? c.decodeIfPresent(String.self, forKey: .exchange)) ?? nil)
        transferFrom = Self.cleanExchange((try? c.decodeIfPresent(String.self, forKey: .transferFrom)) ?? nil)
        // Gebühr im dritten Coin: nur mit Coin und Menge > 0, sonst keine
        let feeAmountText = (try? c.decodeIfPresent(String.self, forKey: .feeCoinAmountText)) ?? nil
        let third = Self.cleanFeeCoin((try? c.decodeIfPresent(String.self, forKey: .feeCoin)) ?? nil, coin: coin,
                                      amount: feeAmountText.flatMap { PortfolioDecimal.parse($0) })
        feeCoin = third?.coin
        feeCoinAmountExact = third?.amount
        let feePriceText = (try? c.decodeIfPresent(String.self, forKey: .feeCoinPriceText)) ?? nil
        feeCoinPriceExact = third == nil ? nil : feePriceText.flatMap { PortfolioDecimal.parse($0) }.flatMap { $0 >= 0 ? $0 : nil }
        // Dateien bis Build 57 tragen Partner-Merker und Verknüpfung noch in «transferTo»/«importKey» (alte Formen); das
        // nächste Speichern schreibt nur noch die Felder
        let stored = LegacyStorageMigration.convert(type, LegacyStorageMigration.Fields(
            transferTo: Self.cleanExchange((try? c.decodeIfPresent(String.self, forKey: .transferTo)) ?? nil),
            giftToSpouse: ((try? c.decodeIfPresent(Bool.self, forKey: .giftToSpouse)) ?? nil) ?? false,
            importKey: Self.cleanImportKey((try? c.decodeIfPresent(String.self, forKey: .importKey)) ?? nil),
            linkedImportKey: Self.cleanImportKey((try? c.decodeIfPresent(String.self, forKey: .linkedImportKey)) ?? nil),
            importRestoreState: (try? c.decodeIfPresent(CsvRestoreState.self, forKey: .importRestoreState)) ?? nil))
        transferTo = stored.transferTo
        giftToSpouse = stored.giftToSpouse && type == .GIFT
        importKey = stored.importKey
        let linked = stored.importKey == nil ? nil : stored.linkedImportKey
        linkedImportKey = linked
        importRestoreState = linked == nil ? nil : stored.importRestoreState
        acquiredTime = Self.cleanAcquiredTime(type, (try? c.decodeIfPresent(Int64.self, forKey: .acquiredTime)) ?? nil)
        // Tausch (Build 46): ohne gültigen erhaltenen Coin und Menge ungültig (gezählt) — wie Android `PortfolioJson`
        if type == .SWAP {
            toCoin = Self.cleanToCoin((try? c.decodeIfPresent(String.self, forKey: .toCoin)) ?? nil, coin: coin)
            let toText = (try? c.decodeIfPresent(String.self, forKey: .toAmountText)) ?? nil
            toAmountExact = toText.flatMap { PortfolioDecimal.parse($0) }.flatMap { $0 > 0 ? $0 : nil }
            let valueText = (try? c.decodeIfPresent(String.self, forKey: .swapValueText)) ?? nil
            swapValueExact = valueText.flatMap { PortfolioDecimal.parse($0) }.flatMap { $0 >= 0 ? $0 : nil }
            guard toCoin != nil, toAmountExact != nil else {
                throw DecodingError.dataCorruptedError(forKey: .toCoin, in: c, debugDescription: "Ungültiger Tausch")
            }
        }
        importBatch = PortfolioImportBatch.clean((try? c.decodeIfPresent(String.self, forKey: .importBatch)) ?? nil)
        // Fehlen die Merker (Datei bzw. Sicherung bis Build 75), einmal nach der früheren Regel ableiten — das nächste
        // Speichern schreibt sie
        if let flag = (try? c.decodeIfPresent(Bool.self, forKey: .feeRow)) ?? nil {
            feeRow = flag && type == .SELL
        } else {
            feeRow = LegacyStorageMigration.legacyFeeRow(type, importKey: importKey, price: priceExact)
        }
        if let flag = (try? c.decodeIfPresent(Bool.self, forKey: .interest)) ?? nil {
            interest = flag && type == .REWARD
        } else {
            interest = LegacyStorageMigration.legacyInterest(type, note: note)
        }
        guard !coin.isEmpty, amountExact > 0 else {
            throw DecodingError.dataCorruptedError(forKey: .amount, in: c, debugDescription: "Ungültige Transaktion")
        }
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(id, forKey: .id)
        try c.encode(coin, forKey: .coin)
        try c.encode(type, forKey: .type)
        try c.encode(amount, forKey: .amount)
        try c.encode(PortfolioDecimal.text(amountExact), forKey: .amountText)
        try c.encodeIfPresent(priceUsdt, forKey: .priceUsdt)
        try c.encodeIfPresent(priceExact.map(PortfolioDecimal.text), forKey: .priceText)
        try c.encode(time, forKey: .time)
        try c.encodeIfPresent(note, forKey: .note)
        try c.encodeIfPresent(fee, forKey: .fee)
        try c.encodeIfPresent(feeExact.map(PortfolioDecimal.text), forKey: .feeText)
        try c.encodeIfPresent(feeUnit, forKey: .feeUnit)
        try c.encodeIfPresent(exchange, forKey: .exchange)
        try c.encodeIfPresent(transferFrom, forKey: .transferFrom)
        try c.encodeIfPresent(transferTo, forKey: .transferTo)
        try c.encodeIfPresent(feeCoin, forKey: .feeCoin)
        try c.encodeIfPresent(feeCoinAmountExact.map(PortfolioDecimal.text), forKey: .feeCoinAmountText)
        try c.encodeIfPresent(feeCoinPriceExact.map(PortfolioDecimal.text), forKey: .feeCoinPriceText)
        try c.encodeIfPresent(importKey, forKey: .importKey)
        try c.encodeIfPresent(Self.cleanAcquiredTime(type, acquiredTime), forKey: .acquiredTime)
        if type == .SWAP {
            try c.encodeIfPresent(toCoin, forKey: .toCoin)
            try c.encodeIfPresent(toAmountExact.map(PortfolioDecimal.text), forKey: .toAmountText)
            try c.encodeIfPresent(swapValueExact.map(PortfolioDecimal.text), forKey: .swapValueText)
        }
        try c.encodeIfPresent(importBatch, forKey: .importBatch)
        if isGiftToSpouse { try c.encode(true, forKey: .giftToSpouse) }
        try c.encodeIfPresent(linkedImportKey, forKey: .linkedImportKey)
        try c.encodeIfPresent(importRestoreState, forKey: .importRestoreState)
        // Immer geschrieben (auch false): ohne Feld schlösse eine andere Version wieder nach der früheren Regel
        try c.encode(feeRow && type == .SELL, forKey: .feeRow)
        try c.encode(interest && type == .REWARD, forKey: .interest)
    }
}

/// Inhalt der Datei `portfolio.json` in der App Group.
struct PortfolioFile: Codable, Sendable {
    var transactions: [PortfolioTx] = []
    /// Nächste freie Id (Ids werden nie wiederverwendet).
    var nextId: Int64 = 1
    /// Alter Bestand der Merkliste ist als Käufe übernommen — steht in derselben Datei wie die Käufe
    /// und wird mit ihnen in einem Schreibvorgang gespeichert. So legt ein Abbruch vor dem Leeren der
    /// Merkliste die Käufe beim nächsten Start nicht doppelt an (wie Android `holdings_import_done`).
    var holdingsImportDone = false
    /// Beim Lesen verworfene (unlesbare) Einträge — nicht gespeichert. > 0 = die Datei ist nicht ganz lesbar,
    /// vor dem nächsten Schreiben wird sie daneben gesichert (`PortfolioStore.preserveUnreadable`).
    var dropped = 0

    init(transactions: [PortfolioTx] = [], nextId: Int64 = 1, holdingsImportDone: Bool = false) {
        self.transactions = transactions
        self.nextId = nextId
        self.holdingsImportDone = holdingsImportDone
    }

    private enum CodingKeys: String, CodingKey { case transactions, nextId, holdingsImportDone }

    /// Ein einzelner kaputter Eintrag macht nicht die ganze Datei unlesbar.
    private struct Lossy: Decodable {
        let tx: PortfolioTx?
        init(from decoder: Decoder) throws { tx = try? PortfolioTx(from: decoder) }
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let decoded = (try? c.decodeIfPresent([Lossy].self, forKey: .transactions)) ?? nil
        let raw = decoded ?? []
        transactions = raw.compactMap(\.tx)
        dropped = raw.count - transactions.count
        // «transactions» da, aber keine Liste: ganze Datei beschädigt — nicht als leeres Portfolio überschreiben
        if decoded == nil && c.contains(.transactions) { dropped = max(dropped, 1) }
        nextId = (try? c.decodeIfPresent(Int64.self, forKey: .nextId)) ?? 1
        holdingsImportDone = (try? c.decodeIfPresent(Bool.self, forKey: .holdingsImportDone)) ?? false
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(transactions, forKey: .transactions)
        try c.encode(nextId, forKey: .nextId)
        if holdingsImportDone { try c.encode(true, forKey: .holdingsImportDone) }
    }
}
