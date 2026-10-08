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

    /// Kurs-Mitteilung für dieses Paar.
    var notificationEnabled: Bool = true

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

    /// Veränderung über 24 Stunden in Prozent (Pille, Puls-Zeile, Widgets, Live Activity),
    /// bei jeder Aktualisierung neu berechnet (siehe `DayChange`). nil = kein 24-h-Bezug,
    /// Pille «—». Abgeleiteter Marktwert, nicht in der Sicherung.
    var change24h: Double? = nil

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

    var pairInfo: CurrencyPairInfo { CurrencyPairInfo(baseAsset, quoteAsset, pairId, contractType) }

    /// Gleiches Paar an derselben Börse (eindeutiger Schlüssel wie in Android).
    func samePair(as other: Watch) -> Bool {
        marketKey == other.marketKey && baseAsset == other.baseAsset &&
            quoteAsset == other.quoteAsset && contractType == other.contractType
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
        contractType = try c.decodeIfPresent(FuturesContractType.self, forKey: .contractType) ?? .none
        pairId = try c.decodeIfPresent(String.self, forKey: .pairId)
        sortOrder = try c.decodeIfPresent(Int.self, forKey: .sortOrder) ?? 0
        notificationEnabled = try c.decodeIfPresent(Bool.self, forKey: .notificationEnabled) ?? true
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
    }

    /// Höchstlänge einer Notiz (wie `NOTE_MAX` in Android).
    static let noteMax = 120

    /// Notiz getrimmt und gekürzt; leer = keine Notiz.
    static func validNote(_ text: String?) -> String? {
        guard let trimmed = text?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty else { return nil }
        return String(trimmed.prefix(noteMax))
    }

    /// Bestand nur, wenn endlich und grösser als 0 — sonst nil (wie `setHoldings` in Android).
    static func validHoldings(_ value: Double?) -> Double? {
        guard let value, value.isFinite, value > 0 else { return nil }
        return value
    }

    /// Gruppenname getrimmt; leer = keine Gruppe (wie `setGroup` in Android).
    static func validGroupName(_ name: String?) -> String? {
        guard let trimmed = name?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty else { return nil }
        return trimmed
    }
}

/// Gruppe für ein neues Paar, wenn beim Hinzufügen keine gewählt ist: Futures auf Aktien, Rohstoffe,
/// Devisen und Pre-IPO kommen nach «TradFi», Laufzeit-Futures (Quartal u. a.) nach «QTLY» — so stehen
/// sie nicht zwischen den Coins. Eine selbst gewählte Gruppe geht immer vor; bestehende Einträge
/// bleiben, wie sie sind. Namen in allen Sprachen gleich. Wie `AutoGroup.kt`.
enum AutoGroup {
    static let tradFi = "TradFi"
    static let dated = "QTLY"

    /// nil: keine eigene Gruppe (Krypto-Spot und -Perpetuals).
    static func forPair(_ pair: CurrencyPairInfo) -> String? {
        if pair.isTradFi { return tradFi }
        if pair.contractType.isRolling { return dated }
        return nil
    }

    /// Gewählte Gruppe, sonst die passende von `forPair`.
    static func resolve(_ chosen: String?, pair: CurrencyPairInfo) -> String? {
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
        windowHours = try c.decodeIfPresent(Int.self, forKey: .windowHours) ?? 1
        referenceAt = try c.decodeIfPresent(Int64.self, forKey: .referenceAt) ?? 0
        // Seit der «≈ Umrechnung»; fehlt in älteren Dateien (= Quote-Währung)
        let code = try? c.decodeIfPresent(String.self, forKey: .currency)
        currency = Alarm.validCurrency(code)
    }
}
