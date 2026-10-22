import Foundation
import SwiftUI
import UserNotifications

/// Alarme der Paare, Portfolio-Alarme und der Probe-Alarm.
extension AppData {
    // MARK: Alarme

    /// Merkliste gerade nicht lesbar (`watchlistLocked`): Ein Alarm ginge verloren. Der Bildschirm fragt vorher und zeigt
    /// den Hinweis; `saveAlarm` und `createAlarm` legen dann nichts an — wie Android `AlarmsViewModel.alarmSaveBlocked`.
    var alarmSaveBlocked: Bool { WatchlistStorageLock.blocksAlarmSave(locked: watchlistLocked) }

    /// Speichert einen Alarm (id 0 = neu) — Bezugskurs und Zustand wie in `AlarmsViewModel.save`.
    /// - Returns: true, wenn es der allererste Alarm ist (Bestätigung zeigen);
    ///   `firstAlarmShown` ist dann schon gesetzt.
    @discardableResult
    func saveAlarm(watchId: Int64, id: Int64, condition: AlarmCondition, threshold: Double,
                   repeating: Bool, sound: Bool, vibrate: Bool, speak: Bool, windowHours: Int,
                   currency: String? = nil) -> Bool {
        // Paar inzwischen gelöscht (z. B. Editor aus einer alten Mitteilung): kein verwaister Alarm — wie Android
        guard !alarmSaveBlocked, watch(watchId) != nil else { return false }
        // Seit Build 104 (F-08): Merkliste wird gerade nicht gespeichert — erst fragen, nach «Trotzdem» erneut (wie Android)
        guard !watchlistWriteRefused(retry: { [weak self] in
            _ = self?.saveAlarm(watchId: watchId, id: id, condition: condition, threshold: threshold, repeating: repeating,
                                sound: sound, vibrate: vibrate, speak: speak, windowHours: windowHours, currency: currency)
        }) else { return false }
        // Neuer Alarm an einem vollen Paar (`WatchlistLimits`): nichts anlegen — der Bildschirm prüft vorher und sagt es
        guard id != 0 || WatchlistLimits.canAddAlarm(alarms(for: watchId).count) else { return false }
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
        else {
            // Nur ein frischer Kurs taugt als Bezug; sonst setzt ihn die nächste Abfrage
            let w = watch(watchId)
            referencePrice = AlarmEdit.freshReference(
                lastPrice: w?.lastPrice, lastUpdate: w?.lastUpdate ?? 0, now: TimeUtils.nowMillis,
                staleAfterMillis: LiveStatus.pollingStaleAfterMillis(backgroundIntervalMinutes: settings.backgroundIntervalMinutes))
        }

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
            let edited = Alarm(id: alarmId, watchId: watchId, condition: condition, threshold: threshold,
                               enabled: true, repeating: repeating, sound: sound, vibrate: vibrate, speak: speak,
                               referencePrice: referencePrice, lastTriggeredAt: 0, lastTriggeredPrice: nil,
                               windowHours: windowHours, referenceAt: referenceAt, currency: alarmCurrency)
            // Nur Ton, Vibration, Ansage oder Wiederholung geändert: Zustand (ausgelöst/gemeldet, Bezug,
            // Ein/Aus) bleibt; neu scharf nur bei geänderter Bedingung (`AlarmEdit.merged`). Stand aus `s`,
            // falls eine Prüfung den Alarm inzwischen geändert hat.
            let stored = id == 0 ? nil : s.alarms.first(where: { $0.id == alarmId })
            let alarm = AlarmEdit.merged(existing: stored, edited: edited)
            if let i = s.alarms.firstIndex(where: { $0.id == alarmId }) { s.alarms[i] = alarm } else { s.alarms.append(alarm) }
        }, reloadWidgets: false)
        // Bearbeitet: alte Mitteilung weg — ihr «1 h später» schaltete den Alarm sonst mit altem Stand wieder ein
        if id != 0 { Notifier.cancelAlarm(id) }
        // Alarm angelegt: nach «Später» die kurze Erklärung noch einmal (`NotificationPrePrompt`)
        NotificationPrePrompt.request(.alarmCreated)
        return firstAlarm
    }

    /// Schnell-Alarm sofort anlegen (scharf, Ton/Vibration wie neue Alarme) — wie
    /// `AlarmsViewModel.createFromTemplate`. Liefert die Id (für «Rückgängig») und ob es
    /// der allererste Alarm überhaupt ist (Bestätigung zeigen).
    func createAlarm(watchId: Int64, from def: AlarmTemplates.Definition) -> (id: Int64, firstAlarm: Bool) {
        guard !alarmSaveBlocked, watch(watchId) != nil, WatchlistLimits.canAddAlarm(alarms(for: watchId).count) else {
            return (0, false)
        }
        // Seit Build 104 (F-08): wie `saveAlarm`
        guard !watchlistWriteRefused(retry: { [weak self] in _ = self?.createAlarm(watchId: watchId, from: def) }) else {
            return (0, false)
        }
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
        // Alarm angelegt: nach «Später» die kurze Erklärung noch einmal (`NotificationPrePrompt`)
        NotificationPrePrompt.request(.alarmCreated)
        return (newId, firstAlarm)
    }

    // MARK: Portfolio-Alarme («Portfolio-Wert»)

    /// Alarme «Portfolio-Wert», nach Id.
    var portfolioAlarms: [PortfolioAlarm] { (snapshot.portfolioAlarms ?? []).sorted { $0.id < $1.id } }

    /// Mindestens ein scharfer Portfolio-Alarm (und Portfolio eingeschaltet)?
    var hasActivePortfolioAlarms: Bool {
        settings.portfolioEnabled && (snapshot.portfolioAlarms ?? []).contains(where: \.enabled)
    }

    /// Neuer Portfolio-Alarm (scharf); Beträge in der Umrechnungswährung `currency`. Portfolio-Alarme liegen im selben
    /// Stand wie die Merkliste: Ist sie gesperrt (`alarmSaveBlocked`), ginge der Alarm verloren — dann nichts anlegen
    /// (der Bildschirm fragt vorher und zeigt den Hinweis). Android: eigene Datenbank, dort keine Sperre nötig.
    /// - Returns: true = angelegt.
    @discardableResult
    func addPortfolioAlarm(kind: PortfolioAlarmKind, threshold: Double, currency: String, repeating: Bool) -> Bool {
        guard !alarmSaveBlocked, PortfolioAlarmLogic.isValidThreshold(kind, threshold) else { return false }
        mutate({ s in
            var list = s.portfolioAlarms ?? []
            let id = max(s.nextPortfolioAlarmId ?? 1, (list.map(\.id).max() ?? 0) + 1)
            list.append(PortfolioAlarm(id: id, kind: kind, threshold: threshold,
                                       currency: kind.isValue ? currency : nil, repeating: repeating))
            s.portfolioAlarms = list
            s.nextPortfolioAlarmId = id + 1
        }, reloadWidgets: false)
        // Alarm angelegt: nach «Später» die kurze Erklärung noch einmal (`NotificationPrePrompt`)
        NotificationPrePrompt.request(.alarmCreated)
        return true
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
        // Seit Build 104: Vermerk «wird gemeldet» des gelöschten Alarms weg (wie Android)
        AlarmDelivery.portfolio.remove([id])
        // Seit Build 104: die Mitteilung des gelöschten Alarms gleich mit entfernen (wie Android)
        Notifier.cancelPortfolioAlarm(id)
    }

    /// Alarmwährungen, die einen Devisenkurs brauchen (`PortfolioAlarmLogic.currenciesNeedingRate`).
    private func portfolioAlarmCurrencies(_ widget: PortfolioWidgetSnapshot) -> Set<String> {
        let alarms = (snapshot.portfolioAlarms ?? []).filter { $0.enabled && $0.kind.isValue }
        return PortfolioAlarmLogic.currenciesNeedingRate(alarms.map(\.currency), readingCurrency: widget.currency)
    }

    /// Devisenkurse für die Portfolio-Alarme vorab laden (Speicher von `FxRateSource`), damit
    /// `evaluatePortfolioAlarms` sie findet — auch im Hintergrund, wo noch nichts geladen ist.
    func prefetchPortfolioAlarmRates(_ widget: PortfolioWidgetSnapshot?) async {
        guard let widget, hasActivePortfolioAlarms else { return }
        for code in portfolioAlarmCurrencies(widget) { _ = await FxRateSource.usdTo(code) }
    }

    /// Nach jeder neuen Berechnung des Portfolio-Stands (Portfolio-Tab, volle Aktualisierung,
    /// Hintergrund): Alarme prüfen (`PortfolioAlarmLogic`), melden und erst nach bestätigter Mitteilung
    /// speichern (`AlarmDelivery`, wie die Paar-Alarme). Nur mit eingeschaltetem Portfolio — wie
    /// `PortfolioAlarmChecker` (Android). Ohne Mitteilungs-Erlaubnis löst kein Alarm aus (`QuietHours.mayFire`):
    /// er bleibt scharf.
    func evaluatePortfolioAlarms(_ widget: PortfolioWidgetSnapshot?) async {
        guard widget != nil, hasActivePortfolioAlarms else { return }
        // Erlaubnis zuerst abfragen; danach läuft die Auswertung ohne Unterbruch auf dem MainActor (Stand erst
        // jetzt lesen). Meldet eine andere Prüfung gerade, diesmal nicht: sie hat ihre Alarme noch nicht
        // gespeichert, beide würden sonst dieselben melden.
        let deliverable = await Notifier.isAuthorized()
        guard let widget, hasActivePortfolioAlarms, !portfolioAlarmsDelivering else { return }
        var current = settings
        // Einstellungen nicht zugänglich: Portfolio-Sperre gilt als an (Mitteilung ohne Beträge)
        current.appLock = appLockEffective
        // Alarme in einer anderen Währung als der Stand (z. B. nach Wechsel CHF → EUR): mit Devisenkurs umrechnen
        var usdTo: [String: Double] = [:]
        for code in portfolioAlarmCurrencies(widget) {
            if let rate = FxRateSource.cached(code) { usdTo[code] = rate }
        }
        // Teilweise alter Kurs (wesentlich), fehlende Kurse und nie geladene Adressen: diesmal nicht prüfen
        let reading = widget.alarmReading(usdTo: usdTo, usdtUsdLive: DepegAlarmCheck.liveUsdtUsd(now: TimeUtils.nowMillis))
        let now = TimeUtils.nowMillis
        var list = snapshot.portfolioAlarms ?? []
        var fired: [(alarm: PortfolioAlarm, value: Double)] = []
        var rearmed = false
        // Nachtruhe: Alarme warten bis zum Ende und melden sich dann mit Ton (wie Paar-Alarme)
        let holdAlarms = QuietHours.holdsAlarmsNow(current)
        for i in list.indices {
            switch PortfolioAlarmLogic.decide(list[i], reading, now: now, cooldownMinutes: current.alarmCooldownMinutes) {
            case .nothing:
                break
            case .rearm:
                list[i].referenceAt = 0
                rearmed = true
            case .fire(let value):
                fired.append((list[i], value))
            }
        }
        if rearmed {
            let updated = list
            mutate({ s in s.portfolioAlarms = updated }, reloadWidgets: false)
        }
        guard !fired.isEmpty else { return }
        portfolioAlarmsDelivering = true
        // Melden und Speichern zu Ende führen, auch wenn die App gerade in den Hintergrund geht
        let backgroundTask = BackgroundTaskGuard.begin("portfolioAlarms")
        defer {
            portfolioAlarmsDelivering = false
            backgroundTask.end()
        }
        for (alarm, value) in fired {
            // Nachtruhe oder keine Mitteilungs-Erlaubnis: nichts speichern, nichts melden — der Alarm bleibt
            // scharf, auch ein wiederholender; unbestätigte Mitteilung: frühestens nach der Abklingzeit erneut
            _ = await AlarmDelivery.portfolio.deliver(
                alarmId: alarm.id, now: now, cooldownMinutes: current.alarmCooldownMinutes, holdAlarms: holdAlarms,
                canShow: { deliverable },
                show: {
                    await Notifier.showPortfolioAlarm(alarm, measured: value, settings: current,
                                                      usdtAssumed: PortfolioAlarmLogic.usdtAssumed(alarm, reading))
                },
                mark: { await self.markPortfolioAlarmTriggered(alarm, value: value, at: now) },
                // Seit Build 104 wie die Paar-Alarme: nicht gespeichert → Mitteilung weg; nach einem Prozess-Ende
                // zählt nur eine nach dem Vermerk gezeigte Mitteilung als zugestellt (`alreadyDelivered`)
                retract: { Notifier.cancelPortfolioAlarm(alarm.id) },
                isShown: { markerAt in await Notifier.isPortfolioAlarmDelivered(alarm.id, since: markerAt) })
        }
    }

    /// Gemeldeten Portfolio-Alarm speichern (einmalige: abschalten) — nur, wenn er noch so ist wie ausgewertet
    /// (wie `markTriggered` in Android); abgewartet auf die Platte, damit ein Ende der App ihn nicht verliert — seit
    /// Build 103 ohne den Hauptfaden zu blockieren (`snapshotWritesSettled` statt `flushSnapshotWrites`).
    private func markPortfolioAlarmTriggered(_ evaluated: PortfolioAlarm, value: Double, at now: Int64) async -> Bool {
        guard var list = snapshot.portfolioAlarms, let i = list.firstIndex(where: { $0.id == evaluated.id }) else {
            return false
        }
        let stored = list[i]
        guard stored.enabled, stored.kind == evaluated.kind, stored.threshold == evaluated.threshold,
              stored.repeating == evaluated.repeating, stored.currency == evaluated.currency,
              stored.referenceAt == evaluated.referenceAt else { return false }
        list[i].referenceAt = now
        list[i].lastTriggeredAt = now
        list[i].lastTriggeredValue = value
        list[i].enabled = PortfolioAlarmLogic.enabledAfterFire(repeating: stored.repeating)
        let updated = list
        mutate({ s in s.portfolioAlarms = updated }, reloadWidgets: false)
        await SharedStorage.snapshotWritesSettled()
        return true
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
        // Gleiche Regel wie echte Alarme: erlaubt und sichtbar (nicht nur Ton)
        guard status != .notDetermined, await Notifier.isAuthorized() else { return .denied }
        let current = settings
        Notifier.showTestAlarm(settings: current)
        if current.ttsEnabled {
            Speaker.shared.speak(L("alarm_test_title") + ". " + L("alarm_test_text"), rate: current.ttsSpeechRate, flush: true)
        }
        return QuietHours.isQuietNow(current) ? .sentDuringQuietHours : .sent
    }

    func deleteAlarm(_ id: Int64) {
        guard !watchlistWriteRefused(retry: { [weak self] in
            _ = self?.deleteAlarm(id)
        }) else { return }
        mutate({ s in s.alarms.removeAll { $0.id == id } }, reloadWidgets: false)
        // Seine Mitteilung weg: ihr «Anpassen» zeigte sonst auf einen Alarm, den es nicht mehr gibt (wie Android)
        Notifier.cancelAlarm(id)
        // Alarm-Protokoll: seine letzte Prüfung gehört zum Alarm (Protokoll-Einträge bleiben); Datei abseits des Hauptfadens
        Task.detached(priority: .utility) { AlarmLogStore.removeAlarm(id) }
        // Seit Build 104: Vermerk «wird gemeldet» des gelöschten Alarms weg (wie Android)
        AlarmDelivery.pairs.remove([id])
    }

    func setAlarmEnabled(_ id: Int64, _ enabled: Bool) {
        guard !watchlistWriteRefused(retry: { [weak self] in
            _ = self?.setAlarmEnabled(id, enabled)
        }) else { return }
        mutate({ s in
            if let i = s.alarms.firstIndex(where: { $0.id == id }) {
                // Wieder eingeschaltet: «Nahe am Hoch/Tief», Kursmarken, Funding und Open Interest melden
                // wieder (wie WatchDao.rearmAlarm)
                s.alarms[i] = AlarmEdit.settingEnabled(s.alarms[i], enabled)
            }
        }, reloadWidgets: false)
        // Ausgeschaltet: seine Mitteilung weg — «1 h später» darauf schaltete ihn sonst wieder ein
        if !enabled { Notifier.cancelAlarm(id) }
    }
}

/// Portfolio-Alarme werden gerade gemeldet (`evaluatePortfolioAlarms`): eine zweite Prüfung wartet die nächste
/// Runde ab. Global, weil Erweiterungen keine gespeicherten Eigenschaften haben; nur auf dem MainActor.
@MainActor private var portfolioAlarmsDelivering = false
