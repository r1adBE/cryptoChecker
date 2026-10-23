import Foundation

/// Ein beobachtetes Handelspaar — entspricht `WatchEntity` der Android-Fassung.
/// Aus diesen Einträgen speisen sich Merkliste, Mitteilungen, Widgets,
/// Sprachansagen und Alarme.
struct Watch: Codable, Identifiable, Hashable, Sendable {
    var id: Int64
    var marketKey: String
    var marketName: String
    var baseAsset: String
    var quoteAsset: String
    var contractType: FuturesContractType = .none
    var pairId: String? = nil

    var sortOrder: Int = 0

    /// Kurs-Mitteilung für dieses Paar. Gespeicherte Paare ohne Angabe: an (bisheriger Stand);
    /// neue Paare erhalten `Watch.newPairNotificationEnabled`.
    var notificationEnabled: Bool = true

    /// Kurs-Mitteilung neuer Paare (Start-Auswahl und später hinzugefügte): an (Entscheid 16. Oktober 2026 —
    /// wer einen Kurs-Checker installiert, will den Kurs sehen; vorher aus). Auswahl je Paar: Einstellungen ›
    /// Alarme › Kursmitteilungen › Paare. Wie `WatchRepository.DEFAULT_NOTIFICATION_ENABLED` (Android).
    static let newPairNotificationEnabled = true

    /// Gespeicherte/gesicherte Paare OHNE Angabe: an. In älteren Versionen hatte jedes Paar die Kurs-Mitteilung
    /// standardmässig an (Android ebenso); fehlt das Feld, stammt es aus so einer Version — «an» erhält
    /// das damalige Verhalten. Wie `WatchRepository.LEGACY_NOTIFICATION_ENABLED` (Android).
    static let legacyNotificationEnabled = true

    /// Kurs bei jeder Aktualisierung vorlesen.
    var ttsEnabled: Bool = false

    var lastPrice: Double? = nil
    var previousPrice: Double? = nil
    var lastUpdate: Int64 = 0

    /// Kurs, bei dem zuletzt eine Mitteilung gezeigt wurde.
    var notifiedPrice: Double? = nil
    var notifiedAt: Int64 = 0
    var lastError: String? = nil

    /// Favorit: steht in der Merkliste ganz oben.
    var favorite: Bool = false

    /// Bestand: gehaltene Menge der Basiswährung (z. B. 0.25 BTC). nil = kein Bestand.
    var holdings: Double? = nil

    /// Gruppe (Merkliste) des Paars. nil = keine Gruppe.
    var groupName: String? = nil

    /// Eigene Notiz, steht in der Merkliste unter dem Paar (z. B. «Einstieg bei 0.42»).
    var note: String? = nil

    /// Veränderung über 24 Stunden in Prozent (Änderung, Puls-Zeile, Widgets, Live Activity),
    /// bei jeder Aktualisierung neu berechnet (siehe `DayChange`). nil = kein 24-h-Bezug,
    /// Anzeige «—». Abgeleiteter Marktwert, nicht in der Sicherung.
    var change24h: Double? = nil

    /// Deaktiviert: keine Kursabfragen (REST, Live, Hintergrund, Widgets), keine Mitteilungen,
    /// keine Alarme (bleiben unverändert gespeichert). Steht grau mit dem letzten Kurs in der
    /// Merkliste. Wie `WatchEntity.disabled` (Android).
    var disabled: Bool = false

    /// Zeitpunkt, an dem sich `lastPrice` zuletzt WIRKLICH geändert hat (bleibt bei gleichem Kurs
    /// stehen, anders als `lastUpdate`). 0 = unbekannt (ältere Dateien). Grundlage für «Kurs
    /// unverändert seit …» bei Börsen ohne eigene Zeit (`PriceFreshness`). Nicht in der Sicherung.
    var priceChangedAt: Int64 = 0

    /// Zeit des letzten Kurses laut Börse (`Ticker.exchangeTime`); nil = die Börse liefert keine
    /// (dann ist `lastUpdate` der Abrufzeitpunkt). Nicht in der Sicherung. Wie Android (DB v15).
    var exchangeTime: Int64? = nil

    var displayPair: String { "\(baseAsset)/\(quoteAsset)" }

    var displayName: String {
        if let c = contractType.shortName { return "\(displayPair) \(c)" }
        return displayPair
    }

    /// Wert des Bestands in der Quote-Währung; nil ohne Bestand oder Kurs.
    var holdingsValue: Double? {
        guard let amount = holdings, amount > 0, let price = lastPrice, price > 0 else { return nil }
        return amount * price
    }

    var pairInfo: MarketPair { MarketPair(baseAsset, quoteAsset, pairId, contractType) }

    /// Eindeutiger Schlüssel des Eintrags (`WatchIdentity`): bei DexScreener mit Pool.
    var identity: WatchIdentity.Key {
        WatchIdentity.key(marketKey: marketKey, base: baseAsset, quote: quoteAsset,
                          contract: String(contractType.rawValue), pairId: pairId)
    }

    /// Gleiches Paar an derselben Börse (eindeutiger Schlüssel wie in Android, `WatchIdentity`).
    func samePair(as other: Watch) -> Bool {
        identity == other.identity
    }
}

/// Bedingung, die einen Alarm auslöst. Rohwerte = Kotlin-Enum-Namen.
enum AlarmCondition: String, Codable, CaseIterable, Sendable {
    /// Kurs erreicht oder übersteigt den Schwellwert.
    case PRICE_ABOVE
    /// Kurs erreicht oder unterschreitet den Schwellwert.
    case PRICE_BELOW
    /// Kurs steigt gegenüber dem Referenzkurs um mindestens x Prozent.
    case CHANGE_PERCENT_UP
    /// Kurs fällt gegenüber dem Referenzkurs um mindestens x Prozent.
    case CHANGE_PERCENT_DOWN
    /// Kurs bewegt sich um mindestens x Prozent innerhalb von y Stunden.
    case MOVE_PERCENT_WINDOW
    /// Volumen der letzten abgeschlossenen Stunde ist mindestens x-mal so hoch wie
    /// der Schnitt der 24 Stunden davor (Stundenkerzen, siehe `VolumeDataSource`).
    /// `threshold` = Faktor, `referenceAt` = Startzeit der zuletzt gemeldeten Kerze.
    case VOLUME_SPIKE
    /// Kurs liegt höchstens x % unter dem Hoch der letzten 30/90/365 Tage — oder macht ein
    /// neues (siehe `NearExtreme`). `threshold` = Abstand in %, `windowHours` = Zeitraum in TAGEN,
    /// `referenceAt` 0 = scharf / > 0 = gemeldet, `referencePrice` = zuletzt gemeldete Marke.
    case NEAR_HIGH
    /// Wie `NEAR_HIGH`, aber höchstens x % über dem Tief des Zeitraums (oder ein neues Tief).
    case NEAR_LOW
    /// Nur Perpetual-Futures (`DerivativesAlarm.supports`): Funding Rate je Intervall erreicht oder
    /// übersteigt x % (`threshold` in Prozent, darf negativ sein); `referenceAt` 0 = scharf / > 0 = gemeldet.
    case FUNDING_ABOVE
    /// Wie `FUNDING_ABOVE`, aber Funding erreicht oder unterschreitet x %.
    case FUNDING_BELOW
    /// Nur Perpetual-Futures: Open Interest (in Coins) mindestens x % über der gespeicherten Messung
    /// von vor `windowHours` Stunden (1, 4 oder 24); `referenceAt` 0 = scharf / > 0 = gemeldet.
    case OI_UP
    /// Wie `OI_UP`, aber Open Interest mindestens x % darunter.
    case OI_DOWN

    /// «Nahe am Hoch / Tief»: Schwellwert ist ein Abstand in Prozent, Fenster in Tagen.
    var isNearExtreme: Bool {
        self == .NEAR_HIGH || self == .NEAR_LOW
    }

    /// «Funding über/unter»: Schwellwert ist eine Funding Rate in Prozent (mit Vorzeichen).
    var isFunding: Bool {
        self == .FUNDING_ABOVE || self == .FUNDING_BELOW
    }

    /// «Open Interest steigt/fällt um x % in N Stunden».
    var isOpenInterest: Bool {
        self == .OI_UP || self == .OI_DOWN
    }

    /// Braucht Funding/Open Interest eines Perpetual-Kontrakts (nur Futures-Paare).
    var isDerivatives: Bool {
        isFunding || isOpenInterest
    }

    var isPercent: Bool {
        self == .CHANGE_PERCENT_UP || self == .CHANGE_PERCENT_DOWN || self == .MOVE_PERCENT_WINDOW
    }

    /// Schwellwert ist ein Kurs (in der Quote-Währung).
    var isPriceThreshold: Bool {
        self == .PRICE_ABOVE || self == .PRICE_BELOW
    }
}

/// Ein Kursalarm — entspricht `AlarmEntity`.
struct Alarm: Codable, Identifiable, Hashable, Sendable {
    var id: Int64
    var watchId: Int64
    var condition: AlarmCondition

    /// Kurs (PRICE_*), Prozentwert (CHANGE_* / MOVE_*) oder Faktor (VOLUME_SPIKE).
    var threshold: Double

    var enabled: Bool = true
    /// false = Alarm schaltet sich nach dem Auslösen ab.
    var repeating: Bool = false
    var sound: Bool = true
    var vibrate: Bool = true
    /// Alarm zusätzlich vorlesen.
    var speak: Bool = false

    /// Bezugskurs für prozentuale Alarme; wird beim Auslösen neu gesetzt.
    var referencePrice: Double? = nil

    var lastTriggeredAt: Int64 = 0
    var lastTriggeredPrice: Double? = nil

    /// Zeitfenster in Stunden für MOVE_PERCENT_WINDOW und OI_UP/OI_DOWN (1, 4, 24); bei
    /// NEAR_HIGH/NEAR_LOW der Zeitraum in Tagen.
    var windowHours: Int = 1
    /// Seit wann der Bezugskurs gilt (Beginn des Zeitfensters). Bei PRICE_ABOVE/PRICE_BELOW:
    /// 0 = scharf, > 0 = gemeldet, bis der Kurs auf die andere Seite der Marke zurückkehrt.
    var referenceAt: Int64 = 0
    /// Währung des Schwellwerts bei PRICE_ABOVE/PRICE_BELOW, z. B. «CHF».
    /// nil = Quote-Währung des Paars (wie bisher); sonst wird der Kurs vor dem Vergleich umgerechnet.
    var currency: String? = nil

    /// Währung, in der der Schwellwert umgerechnet verglichen wird; nil = keine Umrechnung.
    var convertCurrency: String? {
        guard condition.isPriceThreshold, let currency, !currency.isEmpty else { return nil }
        return currency
    }

    /// Gültige Alarmwährung: dreistelliger Code aus A–Z, sonst nil (wie `jsonToAlarm` in Android).
    static func validCurrency(_ code: String?) -> String? {
        guard let code = code?.trimmingCharacters(in: .whitespacesAndNewlines).uppercased(),
              FxRateSource.isCurrencyCode(code) else { return nil }
        return code
    }

    /// Grösster sinnvoller Zeitraum (365 Tage bei «Nahe am Hoch/Tief»); fremde Werte aus Sicherungen und
    /// Dateien werden auf 1 … 365 gekürzt, sonst laufen Zeitrechnungen über (wie Android).
    static let maxWindowHours = 365

    static func clampWindowHours(_ value: Int) -> Int { min(max(value, 1), maxWindowHours) }
}

/// Alarm mit seinem Paar (Alarm-Übersicht).
struct AlarmWithWatch: Identifiable, Hashable {
    var alarm: Alarm
    var watch: Watch
    var id: Int64 { alarm.id }
}

/// Welche Auswahlliste im Hinzufügen-Tab: Börsen, Coins oder Gegenwerte.
enum FavoriteKind: String, CaseIterable, Codable, Sendable {
    case MARKET, COIN, QUOTE
}

// MARK: Tolerantes Lesen
// Fehlt in einer älteren Datei ein Feld, gilt der Standardwert — sonst ginge
// beim Laden die ganze Merkliste verloren.

extension Watch {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(Int64.self, forKey: .id)
        marketKey = try c.decode(String.self, forKey: .marketKey)
        marketName = try c.decodeIfPresent(String.self, forKey: .marketName) ?? marketKey
        baseAsset = try c.decode(String.self, forKey: .baseAsset)
        quoteAsset = try c.decode(String.self, forKey: .quoteAsset)
        pairId = try c.decodeIfPresent(String.self, forKey: .pairId)
        // Früher als `.perpetual` gespeicherte inverse Perpetuals (COIN-M, OKX «-USD-SWAP», Deribit BTC/ETH)
        // wie die heutige Paarliste — sonst passte die Dublettenprüfung nicht. Wie Android `normalizeContractTypes`.
        let storedType = try c.decodeIfPresent(FuturesContractType.self, forKey: .contractType) ?? .none
        contractType = FuturesContractType.normalized(marketKey: marketKey, pairId: pairId, storedType)
        sortOrder = try c.decodeIfPresent(Int.self, forKey: .sortOrder) ?? 0
        notificationEnabled = try c.decodeIfPresent(Bool.self, forKey: .notificationEnabled) ?? Watch.legacyNotificationEnabled
        ttsEnabled = try c.decodeIfPresent(Bool.self, forKey: .ttsEnabled) ?? false
        lastPrice = try c.decodeIfPresent(Double.self, forKey: .lastPrice)
        previousPrice = try c.decodeIfPresent(Double.self, forKey: .previousPrice)
        lastUpdate = try c.decodeIfPresent(Int64.self, forKey: .lastUpdate) ?? 0
        notifiedPrice = try c.decodeIfPresent(Double.self, forKey: .notifiedPrice)
        notifiedAt = try c.decodeIfPresent(Int64.self, forKey: .notifiedAt) ?? 0
        lastError = try c.decodeIfPresent(String.self, forKey: .lastError)
        favorite = try c.decodeIfPresent(Bool.self, forKey: .favorite) ?? false
        // Seit 16.2.2; ungültige Werte gelten als «kein Bestand» bzw. «keine Gruppe».
        let amount = try? c.decodeIfPresent(Double.self, forKey: .holdings)
        holdings = Watch.validHoldings(amount)
        let group = try? c.decodeIfPresent(String.self, forKey: .groupName)
        groupName = Watch.validGroupName(group)
        let note = try? c.decodeIfPresent(String.self, forKey: .note)
        self.note = Watch.validNote(note)
        // Seit Runde 11; ältere Dateien haben keinen Wert («—» bis zur nächsten Aktualisierung)
        let day = try? c.decodeIfPresent(Double.self, forKey: .change24h)
        change24h = day.flatMap { $0.isFinite ? $0 : nil }
        // Paar deaktivieren (wie Android DB v14); ältere Dateien: aktiv
        disabled = (try? c.decodeIfPresent(Bool.self, forKey: .disabled)) ?? false
        // Frische des Kurses (wie Android DB v15); ältere Dateien: unbekannt bzw. keine Börsenzeit
        priceChangedAt = (try? c.decodeIfPresent(Int64.self, forKey: .priceChangedAt)) ?? 0
        exchangeTime = try? c.decodeIfPresent(Int64.self, forKey: .exchangeTime)
    }

    /// Höchstlänge einer Notiz (wie `NOTE_MAX` in Android).
    static let noteMax = 120

    /// Notiz getrimmt, gekürzt und nochmals getrimmt; leer = keine Notiz (wie Android `TextLimit.clean`).
    static func validNote(_ text: String?) -> String? { TextLimit.clean(text, max: noteMax) }

    /// Bestand nur, wenn endlich und grösser als 0 — sonst nil (wie `setHoldings` in Android).
    static func validHoldings(_ value: Double?) -> Double? {
        guard let value, value.isFinite, value > 0 else { return nil }
        return value
    }

    /// Höchstlänge eines Gruppennamens in sichtbaren Zeichen (wie `GROUP_NAME_MAX` in Android).
    static let groupNameMax = 24

    /// Gruppenname getrimmt und auf `groupNameMax` gekürzt; leer = keine Gruppe (wie `setGroup` in Android).
    /// Gilt auch für Sicherungen — vorher kam ein beliebig langer Name aus der Datei durch.
    static func validGroupName(_ name: String?) -> String? { TextLimit.clean(name, max: groupNameMax) }
}

/// Gruppe für ein neues Paar, wenn beim Hinzufügen keine gewählt ist: Futures auf Aktien, Rohstoffe,
/// Devisen und Pre-IPO kommen nach «TradFi», Laufzeit-Futures (Quartal u. a.) nach «QTLY» — so stehen
/// sie nicht zwischen den Coins. Eine selbst gewählte Gruppe geht immer vor; bestehende Einträge
/// bleiben, wie sie sind. Namen in allen Sprachen gleich. Wie `AutoGroup.kt`.
enum AutoGroup {
    static let tradFi = "TradFi"
    static let dated = "QTLY"

    /// nil: keine eigene Gruppe (Krypto-Spot und -Perpetuals).
    static func forPair(_ pair: MarketPair) -> String? {
        if pair.isTradFi { return tradFi }
        if pair.contractType.isRolling { return dated }
        return nil
    }

    /// Gewählte Gruppe, sonst die passende von `forPair`.
    static func resolve(_ chosen: String?, pair: MarketPair) -> String? {
        Watch.validGroupName(chosen) ?? forPair(pair)
    }
}

extension Alarm {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(Int64.self, forKey: .id)
        watchId = try c.decode(Int64.self, forKey: .watchId)
        condition = try c.decodeIfPresent(AlarmCondition.self, forKey: .condition) ?? .PRICE_ABOVE
        threshold = try c.decode(Double.self, forKey: .threshold)
        enabled = try c.decodeIfPresent(Bool.self, forKey: .enabled) ?? true
        repeating = try c.decodeIfPresent(Bool.self, forKey: .repeating) ?? false
        sound = try c.decodeIfPresent(Bool.self, forKey: .sound) ?? true
        vibrate = try c.decodeIfPresent(Bool.self, forKey: .vibrate) ?? true
        speak = try c.decodeIfPresent(Bool.self, forKey: .speak) ?? false
        referencePrice = try c.decodeIfPresent(Double.self, forKey: .referencePrice)
        lastTriggeredAt = try c.decodeIfPresent(Int64.self, forKey: .lastTriggeredAt) ?? 0
        lastTriggeredPrice = try c.decodeIfPresent(Double.self, forKey: .lastTriggeredPrice)
        windowHours = try Alarm.clampWindowHours(c.decodeIfPresent(Int.self, forKey: .windowHours) ?? 1)
        referenceAt = try c.decodeIfPresent(Int64.self, forKey: .referenceAt) ?? 0
        // Seit der «≈ Umrechnung»; fehlt in älteren Dateien (= Quote-Währung)
        let code = try? c.decodeIfPresent(String.self, forKey: .currency)
        currency = Alarm.validCurrency(code)
    }
}
