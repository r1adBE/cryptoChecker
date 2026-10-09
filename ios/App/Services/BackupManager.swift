import Foundation
import SwiftUI
import UniformTypeIdentifiers

/// Sichern und Wiederherstellen: Merkliste, Alarme, Favoriten, Portfolio und
/// Einstellungen als eine JSON-Datei, die der Nutzer selbst ablegt — wie `BackupManager.kt`.
///
/// Das Format ist genau das der Android-Fassung, damit Sicherungen zwischen
/// Android und iOS austauschbar bleiben (mit Passwort zusätzlich verschlüsselt, `BackupCrypto`):
/// `{"format":"cryptochecker-backup","version":1,"createdAt":…,"watches":[…],
///   "alarms":[…],"favorites":{"MARKET":[…],"COIN":[…],"QUOTE":[…]},"settings":{…},
///   "portfolio":[{"id","coin","type","amount","priceUsdt","time","note"}…],
///   "portfolioAlarms":[{"id","kind","threshold","currency","enabled","repeating"}…]}`
///
/// Bewusst `JSONSerialization` statt `Codable`: fehlende Werte werden als
/// `null` geschrieben (z. B. `pairId`, `darkMode`), wie `JSONObject.NULL`.
enum BackupManager {
    static let format = "cryptochecker-backup"
    static let version = 1

    enum Failure: Error {
        /// Keine Crypto-Checker-Sicherung.
        case notABackup
        /// Pflichtfeld fehlt oder hat den falschen Typ.
        case invalid(String)
    }

    /// Vorschlag für den Dateinamen (ohne Endung), z. B. `cryptochecker-backup-2026-10-03`.
    static func defaultFilename(now: Date = Date()) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.calendar = Calendar(identifier: .gregorian)
        f.dateFormat = "yyyy-MM-dd"
        return "cryptochecker-backup-" + f.string(from: now)
    }

    // MARK: Sichern

    /// Sicherung als Datei; mit `password` verschlüsselt (`BackupCrypto`, gleiches Format wie
    /// unter Android), sonst wie bisher als lesbares JSON.
    @MainActor
    static func exportData(_ data: AppData, password: String?) throws -> Data {
        let plain = try plainExportData(data)
        guard let password else { return plain }
        let envelope = try BackupCrypto.encrypt(plain, password: password)
        return BackupCrypto.json(envelope)
    }

    @MainActor
    private static func plainExportData(_ data: AppData) throws -> Data {
        let snapshot = data.snapshot
        let watches = snapshot.watches.sorted {
            $0.sortOrder != $1.sortOrder ? $0.sortOrder < $1.sortOrder : $0.id < $1.id
        }
        let alarms = snapshot.alarms.sorted { $0.id < $1.id }

        var favorites: [String: Any] = [:]
        for kind in FavoriteKind.allCases {
            favorites[kind.rawValue] = Array(data.favorites[kind] ?? []).sorted()
        }

        let root: [String: Any] = [
            "format": format,
            "version": version,
            "createdAt": NSNumber(value: TimeUtils.nowMillis),
            "watches": watches.map(watchToJson),
            "alarms": alarms.map(alarmToJson),
            "favorites": favorites,
            "settings": settingsToJson(data.settings),
            // Neueste zuerst — wie `portfolioDao.getAll()`
            "portfolio": data.portfolio.map(txToJson),
            // Alarme «Portfolio-Wert» (Runde 28), nach Id — wie Android
            "portfolioAlarms": data.portfolioAlarms.map(portfolioAlarmToJson),
        ]
        return try JSONSerialization.data(withJSONObject: root, options: [.prettyPrinted, .sortedKeys])
    }

    // MARK: Wiederherstellen

    /// Verschlüsselte Sicherung? Dann vor dem Wiederherstellen nach dem Passwort fragen.
    /// - Throws: `Failure.notABackup`, wenn die Datei keine Sicherung ist.
    static func needsPassword(_ file: Data) throws -> Bool {
        guard let root = try JSONSerialization.jsonObject(with: file) as? [String: Any] else { throw Failure.notABackup }
        // Verschlüsselt: eigenes «format» (cryptochecker-backup-enc), ältere Versionen lehnen sie ab
        if BackupCrypto.isEnvelope(root) { return true }
        guard root["format"] as? String == format else { throw Failure.notABackup }
        return false
    }

    /// Klartext-JSON der Sicherung; verschlüsselt ohne Passwort gilt als falsches Passwort.
    /// - Throws: `BackupCrypto.Failure.wrongPassword` bei falschem Passwort oder veränderter Datei.
    static func plainJSON(_ file: Data, password: String?) throws -> Data {
        guard let root = try JSONSerialization.jsonObject(with: file) as? [String: Any] else { throw Failure.notABackup }
        guard BackupCrypto.isEnvelope(root) else {
            guard root["format"] as? String == format else { throw Failure.notABackup }
            return file
        }
        guard let password else { throw BackupCrypto.Failure.wrongPassword }
        let envelope = try BackupCrypto.envelope(from: root)
        return try BackupCrypto.decrypt(envelope, password: password)
    }

    /// Ersetzt Merkliste, Alarme, Favoriten und Einstellungen durch die Sicherung.
    /// Alarme, deren Paar fehlt, fallen weg (wie der Fremdschlüssel unter Android).
    /// `json` ist der Klartext (`plainJSON`); eine verschlüsselte Hülle hat ein anderes «format» und wird abgelehnt.
    @MainActor
    static func restore(_ json: Data, into data: AppData) throws -> (watches: Int, alarms: Int) {
        guard let root = try JSONSerialization.jsonObject(with: json) as? [String: Any],
              root["format"] as? String == format
        else { throw Failure.notABackup }

        // Merkliste — gleiche id ersetzt (wie REPLACE beim Einfügen)
        var watches: [Watch] = []
        for item in root["watches"] as? [Any] ?? [] {
            guard let o = item as? [String: Any] else { throw Failure.invalid("watches") }
            let w = try jsonToWatch(o)
            if let i = watches.firstIndex(where: { $0.id == w.id }) { watches[i] = w } else { watches.append(w) }
        }
        let watchIds = Set(watches.map(\.id))

        var alarms: [Alarm] = []
        for item in root["alarms"] as? [Any] ?? [] {
            guard let o = item as? [String: Any] else { throw Failure.invalid("alarms") }
            let a = try jsonToAlarm(o)
            guard watchIds.contains(a.watchId) else { continue }
            if let i = alarms.firstIndex(where: { $0.id == a.id }) { alarms[i] = a } else { alarms.append(a) }
        }

        var favorites: [FavoriteKind: Set<String>] = [:]
        if let fav = root["favorites"] as? [String: Any] {
            for kind in FavoriteKind.allCases {
                if let arr = fav[kind.rawValue] as? [Any] {
                    favorites[kind] = Set(arr.compactMap { $0 as? String })
                }
            }
        }

        let newSettings = (root["settings"] as? [String: Any]).map { applySettings($0, onto: data.settings) }

        // Ältere Sicherungen haben noch kein Portfolio: dann bleibt das bestehende stehen.
        // Unvollständige oder ungültige Einträge werden übersprungen.
        let portfolio: [PortfolioTx]? = (root["portfolio"] as? [Any]).map { items in
            items.compactMap { ($0 as? [String: Any]).flatMap(jsonToTx) }
        }

        // Seit Runde 28; ältere Sicherungen ohne Portfolio-Alarme lassen die bestehenden stehen
        let portfolioAlarms: [PortfolioAlarm]? = (root["portfolioAlarms"] as? [Any]).map { items in
            items.compactMap { ($0 as? [String: Any]).flatMap(jsonToPortfolioAlarm) }
        }

        let old = data.snapshot
        var snapshot = SharedStorage.Snapshot()
        snapshot.watches = watches
        snapshot.alarms = alarms
        // Wiederhergestellte Portfolio-Alarme sind scharf und ohne letzte Meldung
        snapshot.portfolioAlarms = portfolioAlarms.map { list in
            list.map { a in
                var copy = a
                copy.referenceAt = 0
                copy.lastTriggeredAt = 0
                copy.lastTriggeredValue = nil
                return copy
            }
        } ?? old.portfolioAlarms
        let maxPortfolioId = (snapshot.portfolioAlarms ?? []).map(\.id).max() ?? 0
        snapshot.nextPortfolioAlarmId = max(old.nextPortfolioAlarmId ?? 1, maxPortfolioId + 1)
        // Ids nie wiederverwenden: über dem bisherigen Zähler und über allen neuen ids
        snapshot.nextWatchId = max(old.nextWatchId, (watches.map(\.id).max() ?? 0) + 1)
        snapshot.nextAlarmId = max(old.nextAlarmId, (alarms.map(\.id).max() ?? 0) + 1)

        // Kurs-Mitteilungen der bisherigen Paare entfernen
        old.watches.forEach { Notifier.cancelPrice($0.id) }

        data.replaceAll(snapshot: snapshot, settings: newSettings, favorites: favorites)
        // Portfolio ersetzen und Bestand alter Sicherungen übernehmen (nur Coins ohne Transaktion)
        data.restorePortfolio(portfolio)
        return (watches.count, alarms.count)
    }

    // MARK: Portfolio-Alarme

    /// Gleiches Format wie Android; Zustand (gemeldet, zuletzt) wird nicht gesichert.
    private static func portfolioAlarmToJson(_ a: PortfolioAlarm) -> [String: Any] {
        [
            "id": NSNumber(value: a.id),
            "kind": a.kind.rawValue,
            "threshold": a.threshold,
            "currency": a.currency.map { $0 as Any } ?? NSNull(),
            "enabled": a.enabled,
            "repeating": a.repeating,
        ]
    }

    /// Unbekannte Art (neuere Version), ungültiger Schwellwert oder fehlende Währung: überspringen.
    private static func jsonToPortfolioAlarm(_ o: [String: Any]) -> PortfolioAlarm? {
        guard let name = string(o, "kind"), let kind = PortfolioAlarmKind(rawValue: name),
              let threshold = double(o, "threshold"), PortfolioAlarmLogic.isValidThreshold(kind, threshold)
        else { return nil }
        let currency = isNull(o, "currency") ? nil : Alarm.validCurrency(string(o, "currency"))
        if kind.isValue && currency == nil { return nil }
        return PortfolioAlarm(id: max(0, int64(o, "id") ?? 0), kind: kind, threshold: threshold,
                              currency: kind.isValue ? currency : nil,
                              enabled: bool(o, "enabled") ?? true, repeating: bool(o, "repeating") ?? false)
    }

    // MARK: Merkliste & Alarme

    private static func watchToJson(_ w: Watch) -> [String: Any] {
        [
            "id": NSNumber(value: w.id),
            "marketKey": w.marketKey,
            "marketName": w.marketName,
            "baseAsset": w.baseAsset,
            "quoteAsset": w.quoteAsset,
            "contractType": w.contractType.backupName,
            "pairId": w.pairId.map { $0 as Any } ?? NSNull(),
            "sortOrder": w.sortOrder,
            "notificationEnabled": w.notificationEnabled,
            "ttsEnabled": w.ttsEnabled,
            "favorite": w.favorite,
            "holdings": w.holdings.map { $0 as Any } ?? NSNull(),
            "group": w.groupName.map { $0 as Any } ?? NSNull(),
            "note": w.note.map { $0 as Any } ?? NSNull(),
        ]
    }

    private static func jsonToWatch(_ o: [String: Any]) throws -> Watch {
        guard let id = int64(o, "id") else { throw Failure.invalid("watch.id") }
        guard let marketKey = string(o, "marketKey"),
              let marketName = string(o, "marketName"),
              let baseAsset = string(o, "baseAsset"),
              let quoteAsset = string(o, "quoteAsset")
        else { throw Failure.invalid("watch") }
        return Watch(
            id: id,
            marketKey: marketKey,
            marketName: marketName,
            baseAsset: baseAsset,
            quoteAsset: quoteAsset,
            contractType: FuturesContractType(backupName: string(o, "contractType") ?? ""),
            pairId: isNull(o, "pairId") ? nil : string(o, "pairId"),
            sortOrder: int(o, "sortOrder") ?? 0,
            notificationEnabled: bool(o, "notificationEnabled") ?? true,
            ttsEnabled: bool(o, "ttsEnabled") ?? false,
            favorite: bool(o, "favorite") ?? false,
            // Ältere Sicherungen kennen Bestand und Gruppe noch nicht.
            holdings: isNull(o, "holdings") ? nil : Watch.validHoldings(double(o, "holdings")),
            groupName: isNull(o, "group") ? nil : Watch.validGroupName(string(o, "group")),
            // Ältere Sicherungen haben keine Notiz
            note: isNull(o, "note") ? nil : Watch.validNote(string(o, "note"))
        )
    }

    // MARK: Portfolio

    private static func txToJson(_ t: PortfolioTx) -> [String: Any] {
        [
            "id": NSNumber(value: t.id),
            "coin": t.coin,
            "type": t.type.rawValue,
            "amount": t.amount,
            "priceUsdt": t.priceUsdt.map { $0 as Any } ?? NSNull(),
            "time": NSNumber(value: t.time),
            "note": t.note.map { $0 as Any } ?? NSNull(),
        ]
    }

    /// Wie `jsonToTx` (Android): ohne Coin, Art oder gültige Menge → nil.
    private static func jsonToTx(_ o: [String: Any]) -> PortfolioTx? {
        let coin = PortfolioCalculator.normalizeCoin(string(o, "coin") ?? "")
        guard let type = PortfolioTxType(rawValue: string(o, "type") ?? "") else { return nil }
        guard let amount = double(o, "amount"), !amount.isNaN, !amount.isInfinite, amount > 0 else { return nil }
        guard !coin.isEmpty else { return nil }
        let price: Double? = isNull(o, "priceUsdt") ? nil
            : double(o, "priceUsdt").flatMap { !$0.isNaN && !$0.isInfinite && $0 >= 0 ? $0 : nil }
        let note: String? = isNull(o, "note") ? nil
            : string(o, "note").map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.flatMap { $0.isEmpty ? nil : $0 }
        return PortfolioTx(
            id: max(int64(o, "id") ?? 0, 0),
            coin: coin,
            type: type,
            amount: amount,
            priceUsdt: price,
            time: int64(o, "time") ?? TimeUtils.nowMillis,
            note: note
        )
    }

    private static func alarmToJson(_ a: Alarm) -> [String: Any] {
        [
            "id": NSNumber(value: a.id),
            "watchId": NSNumber(value: a.watchId),
            "condition": a.condition.rawValue,
            "threshold": a.threshold,
            "enabled": a.enabled,
            "repeating": a.repeating,
            "sound": a.sound,
            "vibrate": a.vibrate,
            "speak": a.speak,
            "referencePrice": a.referencePrice.map { $0 as Any } ?? NSNull(),
            "windowHours": a.windowHours,
            "currency": a.currency.map { $0 as Any } ?? NSNull(),
        ]
    }

    private static func jsonToAlarm(_ o: [String: Any]) throws -> Alarm {
        guard let id = int64(o, "id"), let watchId = int64(o, "watchId"), let threshold = double(o, "threshold")
        else { throw Failure.invalid("alarm") }
        let referencePrice: Double?
        if isNull(o, "referencePrice") {
            referencePrice = nil
        } else if let p = double(o, "referencePrice"), !p.isNaN {
            referencePrice = p
        } else {
            referencePrice = nil
        }
        return Alarm(
            id: id,
            watchId: watchId,
            condition: AlarmCondition(rawValue: string(o, "condition") ?? "") ?? .PRICE_ABOVE,
            threshold: threshold,
            enabled: bool(o, "enabled") ?? true,
            repeating: bool(o, "repeating") ?? false,
            sound: bool(o, "sound") ?? true,
            vibrate: bool(o, "vibrate") ?? true,
            speak: bool(o, "speak") ?? false,
            referencePrice: referencePrice,
            windowHours: int(o, "windowHours") ?? 1,
            // Ältere Sicherungen kennen die Alarmwährung nicht (= Quote-Währung)
            currency: isNull(o, "currency") ? nil : Alarm.validCurrency(string(o, "currency"))
        )
    }

    // MARK: Einstellungen

    private static func settingsToJson(_ s: AppSettings) -> [String: Any] {
        [
            "backgroundUpdates": s.backgroundUpdates,
            "backgroundIntervalMinutes": s.backgroundIntervalMinutes,
            "liveService": s.liveService,
            "liveIntervalSeconds": s.liveIntervalSeconds,
            "liveWebSocket": s.liveWebSocket,
            "priceNotifications": s.priceNotifications,
            "ongoingNotifications": s.ongoingNotifications,
            "notificationChangePercent": s.notificationChangePercent,
            "ttsEnabled": s.ttsEnabled,
            "ttsAlarmsOnly": s.ttsAlarmsOnly,
            "ttsSpeechRate": s.ttsSpeechRate,
            "alarmCooldownMinutes": s.alarmCooldownMinutes,
            "includeRollingFutures": s.includeRollingFutures,
            "includeTradFiFutures": s.includeTradFiFutures,
            "accentColor": s.accentColor.rawValue,
            "darkMode": s.darkMode.map { $0 as Any } ?? NSNull(),
            "zoneAlerts": s.zoneAlerts,
            "fearGreedBelow": s.fearGreedBelow,
            "fearGreedAbove": s.fearGreedAbove,
            "gasAlertEthTenths": s.gasAlertEthTenths,
            "gasAlertBtc": s.gasAlertBtc,
            "activityAlerts": s.activityAlerts,
            "activitySensitivity": s.activitySensitivity.rawValue,
            "macroNotifications": s.macroNotifications,
            "portfolioEnabled": s.portfolioEnabled,
            "portfolioCurrency": s.portfolioCurrency,
            "showConverted": s.showConverted,
            "priceColorScheme": s.priceColorScheme.rawValue,
            "watchlistSparkline": s.watchlistSparkline,
            "watchlistNames": s.watchlistNames,
            "showChangePeriod": s.showChangePeriod,
            "appIconBadge": s.appIconBadge,
            "watchlistActivityCard": s.watchlistActivityCard,
            "coinLogos": s.coinLogos,
            "widgetCoinLogos": s.widgetCoinLogos,
            "portfolioCoinLogos": s.portfolioCoinLogos,
            "changeBasis": s.changeBasis.rawValue,
            "highContrast": s.highContrast,
            "priceColorsInverted": s.priceColorsInverted,
            "alarmSignal": s.alarmSignal.rawValue,
            "quietHoursEnabled": s.quietHoursEnabled,
            "quietHoursStart": s.quietHoursStart,
            "quietHoursEnd": s.quietHoursEnd,
            "appLock": s.appLock,
            "hidePortfolioAmounts": s.hidePortfolioAmounts,
        ]
    }

    /// Übernimmt nur vorhandene Schlüssel, mit denselben Grenzen wie die Android-Setter.
    private static func applySettings(_ o: [String: Any], onto current: AppSettings) -> AppSettings {
        var s = current
        if let v = bool(o, "backgroundUpdates") { s.backgroundUpdates = v }
        if let v = int(o, "backgroundIntervalMinutes") { s.backgroundIntervalMinutes = max(v, AppSettings.minBackgroundIntervalMinutes) }
        if let v = bool(o, "liveService") { s.liveService = v }
        if let v = int(o, "liveIntervalSeconds") { s.liveIntervalSeconds = max(v, AppSettings.minLiveIntervalSeconds) }
        if let v = bool(o, "liveWebSocket") { s.liveWebSocket = v }
        if let v = bool(o, "priceNotifications") { s.priceNotifications = v }
        if let v = bool(o, "ongoingNotifications") { s.ongoingNotifications = v }
        if let v = double(o, "notificationChangePercent") { s.notificationChangePercent = min(max(v, 0), 100) }
        if let v = bool(o, "ttsEnabled") { s.ttsEnabled = v }
        if let v = bool(o, "ttsAlarmsOnly") { s.ttsAlarmsOnly = v }
        if let v = double(o, "ttsSpeechRate") { s.ttsSpeechRate = min(max(v, 0.5), 2.0) }
        if let v = int(o, "alarmCooldownMinutes") { s.alarmCooldownMinutes = max(v, 0) }
        if let v = bool(o, "includeRollingFutures") { s.includeRollingFutures = v }
        if let v = bool(o, "includeTradFiFutures") { s.includeTradFiFutures = v }
        if o["accentColor"] != nil { s.accentColor = AccentColor(rawValue: string(o, "accentColor") ?? "") ?? .default }
        if o["darkMode"] != nil { s.darkMode = isNull(o, "darkMode") ? nil : bool(o, "darkMode") }
        if let v = bool(o, "zoneAlerts") { s.zoneAlerts = v }
        if let v = int(o, "fearGreedBelow") { s.fearGreedBelow = min(max(v, 0), 100) }
        if let v = int(o, "fearGreedAbove") { s.fearGreedAbove = min(max(v, 0), 100) }
        if let v = int(o, "gasAlertEthTenths") { s.gasAlertEthTenths = min(max(v, 0), 10_000) }
        if let v = int(o, "gasAlertBtc") { s.gasAlertBtc = min(max(v, 0), 10_000) }
        if let v = bool(o, "activityAlerts") { s.activityAlerts = v }
        // Fehlt der Schlüssel (ältere Sicherung) oder ist er unbekannt: «Normal» (wie Android)
        s.activitySensitivity = ActivitySensitivity.from(name: isNull(o, "activitySensitivity") ? nil : string(o, "activitySensitivity"))
        if let v = bool(o, "macroNotifications") { s.macroNotifications = v }
        if let v = bool(o, "portfolioEnabled") { s.portfolioEnabled = v }
        // Dreistelliger Währungscode, sonst bleibt die bisherige Währung (wie `setPortfolioCurrency`)
        if !isNull(o, "portfolioCurrency"), let v = string(o, "portfolioCurrency") {
            let code = v.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
            if FxRateSource.isCurrencyCode(code) { s.portfolioCurrency = code }
        }
        if let v = bool(o, "showConverted") { s.showConverted = v }
        if !isNull(o, "priceColorScheme") {
            s.priceColorScheme = PriceColorScheme(rawValue: string(o, "priceColorScheme") ?? "") ?? .default
        }
        if let v = bool(o, "watchlistSparkline") { s.watchlistSparkline = v }
        if let v = bool(o, "watchlistNames") { s.watchlistNames = v }
        if let v = bool(o, "showChangePeriod") { s.showChangePeriod = v }
        if let v = bool(o, "appIconBadge") { s.appIconBadge = v }
        if let v = bool(o, "watchlistActivityCard") { s.watchlistActivityCard = v }
        if let v = bool(o, "coinLogos") { s.coinLogos = v }
        if let v = bool(o, "widgetCoinLogos") { s.widgetCoinLogos = v }
        if let v = bool(o, "portfolioCoinLogos") { s.portfolioCoinLogos = v }
        // %-Basis (wie Android): fehlt der Schlüssel, bleibt der Wert; unbekannt → «Letzte 24 Std.»
        if o["changeBasis"] != nil {
            s.changeBasis = ChangeBasis.from(name: isNull(o, "changeBasis") ? nil : string(o, "changeBasis"))
        }
        if let v = bool(o, "highContrast") { s.highContrast = v }
        // Fehlt der Schlüssel (ältere Sicherung), bleibt der bisherige Wert
        if let v = bool(o, "priceColorsInverted") { s.priceColorsInverted = v }
        // Alarm-Signal (wie Android): fehlt der Schlüssel, bleibt der Wert; unbekannt → «System»;
        // Android-Werte («Nur Vibration» …) → nächster iOS-Wert
        if o["alarmSignal"] != nil {
            s.alarmSignal = AlarmSignal.from(name: isNull(o, "alarmSignal") ? nil : string(o, "alarmSignal")).ios
        }
        // Nachtruhe: nur vorhandene Schlüssel, Zeiten nur 0…1439
        if let v = bool(o, "quietHoursEnabled") { s.quietHoursEnabled = v }
        if let v = minute(o, "quietHoursStart") { s.quietHoursStart = v }
        if let v = minute(o, "quietHoursEnd") { s.quietHoursEnd = v }
        if let v = bool(o, "hidePortfolioAmounts") { s.hidePortfolioAmounts = v }
        // Portfolio-Sperre nur, wenn das Gerät entsperren kann (sonst bliebe das Portfolio gesperrt)
        if let v = bool(o, "appLock"), !v || AppLock.canAuthenticate() { s.appLock = v }
        return s
    }

    // MARK: Lesen mit Typ-Toleranz (wie optInt/optBoolean von org.json)

    /// Minute des Tages (0…1439) oder nil, wenn fehlend, gebrochen oder ausserhalb — wie `optMinute`.
    private static func minute(_ o: [String: Any], _ key: String) -> Int? {
        guard !isNull(o, key), let value = double(o, key), value.isFinite,
              value.truncatingRemainder(dividingBy: 1) == 0 else { return nil }
        let m = Int(value)
        return QuietHours.isValidMinute(m) ? m : nil
    }

    private static func isNull(_ o: [String: Any], _ key: String) -> Bool {
        o[key] == nil || o[key] is NSNull
    }

    private static func string(_ o: [String: Any], _ key: String) -> String? {
        if let s = o[key] as? String { return s }
        if let n = o[key] as? NSNumber { return n.stringValue }
        return nil
    }

    private static func int64(_ o: [String: Any], _ key: String) -> Int64? {
        if let n = o[key] as? NSNumber { return n.int64Value }
        if let s = o[key] as? String { return Int64(s) ?? Double(s).map { Int64($0) } }
        return nil
    }

    private static func int(_ o: [String: Any], _ key: String) -> Int? {
        int64(o, key).map { Int($0) }
    }

    private static func double(_ o: [String: Any], _ key: String) -> Double? {
        if let n = o[key] as? NSNumber { return n.doubleValue }
        if let s = o[key] as? String { return Double(s) }
        return nil
    }

    private static func bool(_ o: [String: Any], _ key: String) -> Bool? {
        if let n = o[key] as? NSNumber { return n.boolValue }
        if let s = o[key] as? String {
            switch s.lowercased() {
            case "true": return true
            case "false": return false
            default: return nil
            }
        }
        return nil
    }
}

/// JSON-Datei für `.fileExporter`.
struct BackupDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.json] }
    static var writableContentTypes: [UTType] { [.json] }

    var data: Data

    init(data: Data) {
        self.data = data
    }

    init(configuration: ReadConfiguration) throws {
        guard let contents = configuration.file.regularFileContents else {
            throw CocoaError(.fileReadCorruptFile)
        }
        data = contents
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}
