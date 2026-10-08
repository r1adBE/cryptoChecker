import Foundation
import SwiftUI
import UserNotifications

/// Alarme der Paare, Portfolio-Alarme und der Probe-Alarm.
extension AppData {
    // MARK: Alarme

    /// Speichert einen Alarm (id 0 = neu) — Bezugskurs wie in `AlarmsViewModel.save`.
    /// - Returns: true, wenn es der allererste Alarm ist (Bestätigung zeigen);
    ///   `firstAlarmShown` ist dann schon gesetzt.
    @discardableResult
    func saveAlarm(watchId: Int64, id: Int64, condition: AlarmCondition, threshold: Double,
                   repeating: Bool, sound: Bool, vibrate: Bool, speak: Bool, windowHours: Int,
                   currency: String? = nil) -> Bool {
        let existing = snapshot.alarms.first { $0.id == id && id != 0 }
        // Erster Alarm überhaupt? Gibt es schon Alarme, gilt die Bestätigung als gezeigt.
        let firstAlarm = !settings.firstAlarmShown && snapshot.alarms.isEmpty
        if !settings.firstAlarmShown { settings.firstAlarmShown = true }
        // Eigene Währung nur bei Kursalarmen und nur, wenn sie von der Quote abweicht
        let validCode = Alarm.validCurrency(currency)
        let differsFromQuote = !CurrencyConversion.sameCurrency(validCode, watch(watchId)?.quoteAsset)
        let alarmCurrency: String? = condition.isPriceThreshold && differsFromQuote ? validCode : nil
        let keep = existing != nil && existing?.condition == condition && existing?.windowHours == windowHours
        let referencePrice: Double?
        if !condition.isPercent { referencePrice = nil }
        else if keep { referencePrice = existing?.referencePrice }
        else { referencePrice = watch(watchId)?.lastPrice }

        // Bewegungs-Alarm: Fenster beginnt jetzt (oder läuft unverändert weiter).
        // Volumen-Spike: zuletzt gemeldete Kerze behalten, damit sie nicht nochmals meldet.
        let referenceAt: Int64
        if condition == .VOLUME_SPIKE {
            referenceAt = existing?.condition == .VOLUME_SPIKE ? (existing?.referenceAt ?? 0) : 0
        } else if condition != .MOVE_PERCENT_WINDOW { referenceAt = 0 }
        else if keep { referenceAt = existing?.referenceAt ?? 0 }
        else if referencePrice != nil { referenceAt = TimeUtils.nowMillis }
        else { referenceAt = 0 }

        mutate({ s in
            var alarmId = id
            if alarmId == 0 {
                alarmId = max(s.nextAlarmId, (s.alarms.map(\.id).max() ?? 0) + 1)
                s.nextAlarmId = alarmId + 1
            }
            let alarm = Alarm(id: alarmId, watchId: watchId, condition: condition, threshold: threshold,
                              enabled: true, repeating: repeating, sound: sound, vibrate: vibrate, speak: speak,
                              referencePrice: referencePrice, lastTriggeredAt: 0, lastTriggeredPrice: nil,
                              windowHours: windowHours, referenceAt: referenceAt, currency: alarmCurrency)
            if let i = s.alarms.firstIndex(where: { $0.id == alarmId }) { s.alarms[i] = alarm } else { s.alarms.append(alarm) }
        }, reloadWidgets: false)
        Task { _ = await Notifier.requestPermission() }
        return firstAlarm
    }

    /// Schnell-Alarm sofort anlegen (scharf, Ton/Vibration wie neue Alarme) — wie
    /// `AlarmsViewModel.createFromTemplate`. Liefert die Id (für «Rückgängig») und ob es
    /// der allererste Alarm überhaupt ist (Bestätigung zeigen).
    func createAlarm(watchId: Int64, from def: AlarmTemplates.Definition) -> (id: Int64, firstAlarm: Bool) {
        let firstAlarm = !settings.firstAlarmShown && snapshot.alarms.isEmpty
        if !settings.firstAlarmShown { settings.firstAlarmShown = true }
        var newId: Int64 = 0
        mutate({ s in
            let alarmId = max(s.nextAlarmId, (s.alarms.map(\.id).max() ?? 0) + 1)
            s.nextAlarmId = alarmId + 1
            newId = alarmId
            s.alarms.append(Alarm(id: alarmId, watchId: watchId, condition: def.condition, threshold: def.threshold,
                                  enabled: true, repeating: def.repeating, sound: true, vibrate: true, speak: false,
                                  referencePrice: def.referencePrice, lastTriggeredAt: 0, lastTriggeredPrice: nil,
                                  windowHours: def.windowHours, referenceAt: 0, currency: nil))
        }, reloadWidgets: false)
        Task { _ = await Notifier.requestPermission() }
        return (newId, firstAlarm)
    }

    // MARK: Portfolio-Alarme («Portfolio-Wert»)

    /// Alarme «Portfolio-Wert», nach Id.
    var portfolioAlarms: [PortfolioAlarm] { (snapshot.portfolioAlarms ?? []).sorted { $0.id < $1.id } }

    /// Mindestens ein scharfer Portfolio-Alarm (und Portfolio eingeschaltet)?
    var hasActivePortfolioAlarms: Bool {
        settings.portfolioEnabled && (snapshot.portfolioAlarms ?? []).contains(where: \.enabled)
    }

    /// Neuer Portfolio-Alarm (scharf); Beträge in der Umrechnungswährung `currency`.
    func addPortfolioAlarm(kind: PortfolioAlarmKind, threshold: Double, currency: String, repeating: Bool) {
        guard PortfolioAlarmLogic.isValidThreshold(kind, threshold) else { return }
        mutate({ s in
            var list = s.portfolioAlarms ?? []
            let id = max(s.nextPortfolioAlarmId ?? 1, (list.map(\.id).max() ?? 0) + 1)
            list.append(PortfolioAlarm(id: id, kind: kind, threshold: threshold,
                                       currency: kind.isValue ? currency : nil, repeating: repeating))
            s.portfolioAlarms = list
            s.nextPortfolioAlarmId = id + 1
        }, reloadWidgets: false)
        Task { _ = await Notifier.requestPermission() }
    }

    /// Ein-/Ausschalten; eingeschaltet wieder scharf (wie die Kursmarken der Paar-Alarme).
    func setPortfolioAlarmEnabled(_ id: Int64, _ enabled: Bool) {
        mutate({ s in
            guard var list = s.portfolioAlarms, let i = list.firstIndex(where: { $0.id == id }) else { return }
            list[i].enabled = enabled
            if enabled { list[i].referenceAt = 0 }
            s.portfolioAlarms = list
        }, reloadWidgets: false)
    }

    func deletePortfolioAlarm(_ id: Int64) {
        mutate({ s in s.portfolioAlarms?.removeAll { $0.id == id } }, reloadWidgets: false)
    }

    /// Nach jeder neuen Berechnung des Portfolio-Stands (Portfolio-Tab, volle Aktualisierung,
    /// Hintergrund): Alarme prüfen (`PortfolioAlarmLogic`), Zustand speichern, dann melden.
    /// Nur mit eingeschaltetem Portfolio — wie `PortfolioAlarmChecker` (Android).
    func evaluatePortfolioAlarms(_ widget: PortfolioWidgetSnapshot?) {
        guard let widget, hasActivePortfolioAlarms else { return }
        let current = settings
        let reading = PortfolioReading(total: widget.total, currency: widget.currency, totalUsdt: widget.totalUsdt,
                                       changePercent: widget.changePercent, empty: widget.empty)
        let now = TimeUtils.nowMillis
        var list = snapshot.portfolioAlarms ?? []
        var fired: [(alarm: PortfolioAlarm, value: Double)] = []
        var changed = false
        for i in list.indices {
            switch PortfolioAlarmLogic.decide(list[i], reading, now: now, cooldownMinutes: current.alarmCooldownMinutes) {
            case .nothing:
                break
            case .rearm:
                list[i].referenceAt = 0
                changed = true
            case .fire(let value):
                list[i].referenceAt = now
                list[i].lastTriggeredAt = now
                list[i].lastTriggeredValue = value
                list[i].enabled = PortfolioAlarmLogic.enabledAfterFire(repeating: list[i].repeating)
                changed = true
                fired.append((list[i], value))
            }
        }
        guard changed else { return }
        let updated = list
        mutate({ s in s.portfolioAlarms = updated }, reloadWidgets: false)
        // Erst nach dem Speichern melden (sonst wiederholt sich der Alarm, wenn iOS die App dazwischen beendet)
        for item in fired {
            Notifier.showPortfolioAlarm(item.alarm, measured: item.value, settings: current)
        }
    }

    // MARK: Probe-Alarm

    enum AlarmTestOutcome { case sent, sentDuringQuietHours, denied }

    /// Probe-Alarm wie ein echter Kursalarm (Ton, zeitkritisch, Ansage) — ohne Nachtruhe.
    /// Fragt einmal nach der Erlaubnis, falls noch nie gefragt.
    func sendTestAlarm() async -> AlarmTestOutcome {
        var status = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
        if status == .notDetermined {
            _ = await Notifier.requestPermission()
            status = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
        }
        guard status == .authorized || status == .provisional || status == .ephemeral else { return .denied }
        let current = settings
        Notifier.showTestAlarm(settings: current)
        if current.ttsEnabled {
            Speaker.shared.speak(L("alarm_test_title") + ". " + L("alarm_test_text"), rate: current.ttsSpeechRate, flush: true)
        }
        return QuietHours.isQuietNow(current) ? .sentDuringQuietHours : .sent
    }

    func deleteAlarm(_ id: Int64) {
        mutate({ s in s.alarms.removeAll { $0.id == id } }, reloadWidgets: false)
    }

    func setAlarmEnabled(_ id: Int64, _ enabled: Bool) {
        mutate({ s in
            if let i = s.alarms.firstIndex(where: { $0.id == id }) {
                s.alarms[i].enabled = enabled
                // Wieder eingeschaltet: «Nahe am Hoch/Tief», Kursmarken, Funding und Open Interest melden
                // wieder (wie WatchDao.rearmAlarm)
                let condition = s.alarms[i].condition
                if enabled && (condition.isNearExtreme || condition.isPriceThreshold || condition.isDerivatives) {
                    s.alarms[i].referencePrice = nil
                    s.alarms[i].referenceAt = 0
                }
            }
        }, reloadWidgets: false)
    }
}
