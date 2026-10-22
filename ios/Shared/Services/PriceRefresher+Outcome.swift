import Foundation

// Ergebnis eines Durchlaufs (Teil von `PriceRefresher`): Kurs- und Alarm-Änderungen, ausstehende und zurückgestellte
// Alarme, Melden und Übertragen auf die Datei.

extension PriceRefresher {
    struct PriceUpdate: Sendable {
        var price: Double?
        var time: Int64
        var error: String?
        /// Veränderung über 24 Stunden zum neuen Kurs (siehe `DayChange`); nil = kein Bezug.
        var change24h: Double? = nil
        /// Letzte echte Kursänderung (`PriceFreshness.nextPriceChangedAt`); 0 = nicht gesetzt.
        var priceChangedAt: Int64 = 0
        /// Zeit laut Börse, nil = keine (`Ticker.exchangeTime`).
        var exchangeTime: Int64? = nil
        /// Abgefragtes Paar (`Watch.identity`): Wurde der Eintrag inzwischen auf ein anderes Paar geändert (oder
        /// durch eine Wiederherstellung mit gleicher Id ersetzt), gehört das Ergebnis nicht mehr zu ihm (`apply`).
        /// nil = ohne Prüfung.
        var pair: WatchIdentity.Key? = nil
    }

    /// Änderung eines Alarms durch die Auswertung. Übernommen wird sie nur, wenn der Alarm noch so
    /// ist wie ausgewertet (`matches`) — sonst bleibt die Bearbeitung des Nutzers.
    struct AlarmUpdate: Sendable {
        /// nil = nicht anfassen (Wiederscharfstellen, neuer Bezug).
        var enabled: Bool?
        var referencePrice: Double?
        var referenceAt: Int64
        var lastTriggeredAt: Int64
        var lastTriggeredPrice: Double?
        /// Ausgewerteter Stand: Bedingung, Schwelle, Fenster, Wiederholung.
        var condition: AlarmCondition
        var threshold: Double
        var windowHours: Int
        var repeating: Bool
        /// Ausgewertete Alarm-Währung (nil = Quote-Währung des Paars) — seit Build 102 geprüft wie Android
        /// (`currency IS :currency` in allen bedingten UPDATEs).
        var currency: String?
        /// Ausgewertetes Paar (`Watch.identity`): Gehört die Watch-Id des Alarms beim Speichern zu einem anderen Paar
        /// (Bearbeiten, Wiederherstellung mit gleicher Id), wird nichts übertragen — wie Android, das den Alarm nur
        /// für tatsächlich geschriebene Paare speichert. nil = ohne Prüfung.
        var pair: WatchIdentity.Key? = nil

        /// `evaluated` = Alarm wie ausgewertet, `result` = Zustand danach; `setsEnabled` nur beim Auslösen.
        init(evaluated: Alarm, result: Alarm, setsEnabled: Bool, pair: WatchIdentity.Key? = nil) {
            self.pair = pair
            enabled = setsEnabled ? result.enabled : nil
            referencePrice = result.referencePrice
            referenceAt = result.referenceAt
            lastTriggeredAt = result.lastTriggeredAt
            lastTriggeredPrice = result.lastTriggeredPrice
            condition = evaluated.condition
            threshold = evaluated.threshold
            windowHours = evaluated.windowHours
            repeating = evaluated.repeating
            currency = evaluated.currency
        }

        /// Alarm unverändert (eingeschaltet, gleiche Bedingung, Schwelle, Fenster und Alarm-Währung; beim Auslösen
        /// auch gleiche Wiederholung)? Wie die bedingten UPDATEs in Android (`WatchDao`).
        func matches(_ alarm: Alarm) -> Bool {
            alarm.enabled && alarm.condition == condition && alarm.threshold == threshold
                && alarm.windowHours == windowHours && alarm.currency == currency
                && (enabled == nil || alarm.repeating == repeating)
        }

        /// Überträgt die Änderung auf den Alarm `id` in `snapshot`, wenn er noch so ist wie ausgewertet (und, mit
        /// `pair`, sein Eintrag noch dasselbe Paar ist).
        /// - Returns: true = übertragen; false = inzwischen bearbeitet, abgeschaltet, gelöscht oder Paar geändert.
        @discardableResult
        func apply(toAlarm id: Int64, in snapshot: inout SharedStorage.Snapshot) -> Bool {
            guard let i = snapshot.alarms.firstIndex(where: { $0.id == id }), matches(snapshot.alarms[i]) else {
                return false
            }
            if let pair {
                let watchId = snapshot.alarms[i].watchId
                guard snapshot.watches.first(where: { $0.id == watchId })?.identity == pair else { return false }
            }
            if let enabled { snapshot.alarms[i].enabled = enabled }
            snapshot.alarms[i].referencePrice = referencePrice
            snapshot.alarms[i].referenceAt = referenceAt
            snapshot.alarms[i].lastTriggeredAt = lastTriggeredAt
            snapshot.alarms[i].lastTriggeredPrice = lastTriggeredPrice
            return true
        }
    }

    /// Ausgelöster Alarm: erst melden, dann speichern (`Outcome.deliverAlarms`, wie Android
    /// `AlarmDelivery`). Kommt die Mitteilung nicht an, bleibt der Alarm scharf; beendet iOS den Prozess
    /// zwischen Melden und Speichern, meldet er sich frühestens nach der Abklingzeit noch einmal.
    struct PendingAlarm: Sendable {
        var watch: Watch
        var alarm: Alarm
        var price: Double
        var volumeRatio: Double? = nil
        var text: String? = nil
        /// Zustand nach dem Auslösen — gespeichert erst nach bestätigter Mitteilung.
        var update: AlarmUpdate
        /// Ansage, sobald gemeldet und gespeichert; nil = keine.
        var speech: String? = nil
        /// Kurs und Währung für den Text «jetzt …», wenn die Schwelle in einer anderen Währung steht
        /// (`Alarm.convertCurrency`); nil = `price` in der Quote-Währung des Paars.
        var shownPrice: Double? = nil
        var shownCurrency: String? = nil
        /// Alarm-Protokoll: Eintrag dieser Prüfung, Ergebnis erst nach dem Melden (`deliverAlarms`); nil = nicht erfasst.
        var logEntry: AlarmLogEntry? = nil
    }

    /// Mitteilungs-Weg der Paar-Alarme für `Outcome.deliverAlarms`; Tests setzen einen Fake ein.
    struct AlarmNotifier: Sendable {
        /// Kann eine Mitteilung gerade sichtbar ankommen?
        var canShow: @Sendable () async -> Bool
        /// Meldet; true erst, wenn die Mitteilung bestätigt ist.
        var show: @Sendable (PendingAlarm) async -> Bool
        /// Entfernt eine gezeigte Mitteilung wieder (Alarm liess sich danach nicht speichern).
        var retract: @Sendable (PendingAlarm) async -> Void = { _ in }

        static let system = AlarmNotifier(
            canShow: { await Notifier.isAuthorized() },
            show: { item in
                await Notifier.showAlarm(item.watch, item.alarm, price: item.shownPrice ?? item.price,
                                         volumeRatio: item.volumeRatio, text: item.text, currency: item.shownCurrency)
            },
            retract: { item in Notifier.cancelAlarm(item.alarm.id) })
    }

    /// Zurückgestellte Alarm-Prüfung (siehe `Outcome.deferredAlarms`): je Paar mit neuem Kurs der
    /// Stand davor/danach; `prefetchByWatch` = aktive Alarme mit Zusatzdaten aus dem mitgegebenen Stand.
    struct DeferredAlarms: Sendable {
        struct Item: Sendable {
            var watch: Watch
            var updated: Watch
            var price: Double
            var fetched: Fetched
        }
        var items: [Item] = []
        var prefetchByWatch: [Int64: [Alarm]] = [:]
        var isEmpty: Bool { items.isEmpty }
    }

    struct Outcome: Sendable {
        var prices: [Int64: PriceUpdate] = [:]
        /// Änderungen ohne Auslösen (wieder scharf, neuer Bezug) — mit den Kursen gespeichert.
        var alarms: [Int64: AlarmUpdate] = [:]
        /// Ausgelöste Alarme — nach dem Speichern der Kurse melden und erst dann speichern (`deliverAlarms`).
        var pendingAlarms: [PendingAlarm] = []
        /// Lease der Alarm-Auswertung (nil = keine Alarme ausgewertet); freigegeben
        /// von `deliverAlarms` / `releaseAlarmLease()`, spätestens beim Aufräumen.
        var alarmLease: AlarmLease? = nil
        /// Watch-Id → (gemeldeter Kurs, Zeitpunkt).
        var notified: [Int64: (Double, Int64)] = [:]
        /// Kursansagen je Paar (Watch-Id, Text) — Alarm-Ansagen stehen in `PendingAlarm.speech`;
        /// der Sprecher behält je Paar nur die neueste wartende Ansage.
        var priceSpeech: [(watchId: Int64, text: String)] = []
        var checked = 0
        var failed = 0
        var durationMillis: Int64 = 0
        /// Bericht «Letzte Aktualisierung»; nil bei einer Einzelabfrage (`onlyWatchId`).
        var report: RefreshReport? = nil
        /// Paare pausierter Börsen (`ExchangeBackoff`): nicht angefragt, Kurs und Zustand bleiben.
        var pausedIds: Set<Int64> = []
        /// %-Basis und Tagesbeginn, mit denen dieser volle Durchlauf gerechnet hat (nil bei einer
        /// Einzelabfrage) — der Aufrufer speichert ihn mit (`SharedStorage.changeStamp`).
        var changeStamp: ChangeStamp? = nil
        /// Neue Basis oder neuer Tag gegenüber dem gespeicherten Stempel: Paare ohne neuen Kurs
        /// verlieren ihre alte Veränderung (sie gehört nicht mehr dazu).
        var changeStampChanged = false
        /// Tages-Basen: Paare mit neuem Kurs, aber noch ohne Bezug (Watch-Id → Kurs, Zeit) —
        /// `lateDayChanges` trägt sie nach, sobald die Kerzen da sind.
        var missingDayChange: [Int64: (price: Double, time: Int64)] = [:]
        /// Wartet (höchstens 90 s) auf die noch laufenden Bezüge und liefert die Veränderungen der
        /// `missingDayChange`-Paare; nil ohne solche Paare oder bei rollender Basis.
        var lateDayChanges: (@Sendable () async -> [Int64: Double])? = nil
        /// Nur mit Endzeit (Hintergrund): Alarme, die Zusatzdaten brauchen — erst nach dem Speichern
        /// der Kurse prüfen (`checkDeferredAlarms`), damit Kurse und Kursalarme nie auf sie warten.
        var deferredAlarms = DeferredAlarms()
        /// Alarm-Protokoll (`AlarmLog`): Prüfungen ohne Meldung; die der ausgelösten stehen in `PendingAlarm.logEntry`.
        var alarmLog: [AlarmLogEntry] = []
        /// Aus Live-Kursen: Protokoll gedrosselt schreiben (`AlarmLogStore.record`).
        var alarmLogLive = false

        /// NACH `apply(to:)` und dem Speichern der Kurse aufrufen (die Lease ist noch gehalten): je
        /// ausgelöstem Alarm zuerst melden, prüfen, dass die Mitteilung angekommen ist, und erst dann den
        /// Zustand nach dem Auslösen speichern (`AlarmDelivery`, wie Android). Danach die Lease freigeben —
        /// der nächste Prozess sieht so nur gespeicherte, also wirklich gemeldete Auslösungen.
        /// - Parameter current: Alarme des gespeicherten Stands: inzwischen bearbeitet, abgeschaltet oder
        ///   gelöscht → keine Mitteilung.
        /// - Parameter save: überträgt den Zustand eines Alarms auf den gespeicherten Stand (App: Stand im
        ///   Speicher und Datei; Hintergrund und Widget: `saveAlarmOnDisk`); true = gespeichert.
        /// - Parameter reloadStored: liest die Alarme vor jeder Meldung frisch von der Platte; nil = nicht
        ///   lesbar (dann gilt allein `current`, Fail-open). Tests: Fake.
        /// - Parameter notifier, delivery, now: Mitteilungs-Weg, Speicher der unbestätigten Versuche und Uhr (Tests: Fakes).
        /// - Parameter snoozedUntil: Ende einer «1 h später»-Pause je Alarm (`AlarmSnooze`); Tests: Fake.
        /// - Parameter recordLog: Alarm-Protokoll übernehmen (Einträge, aus Live-Kursen?) — nach dem Melden, mit dem
        ///   Ergebnis jedes gemeldeten Alarms; nil = nicht protokollieren (Tests).
        /// - Returns: gemeldete und gespeicherte Alarme (für die Ansagen).
        func deliverAlarms(settings: AppSettings, current: [Alarm],
                           save: @escaping @Sendable (Int64, AlarmUpdate) async -> Bool,
                           reloadStored: @Sendable () async -> [Alarm]? = { SharedStorage.readSnapshot().snapshot?.alarms },
                           notifier: AlarmNotifier = .system, delivery: AlarmDelivery = .pairs,
                           now: @Sendable () -> Int64 = { TimeUtils.nowMillis },
                           snoozedUntil: @Sendable (Int64) -> Int64? = { AlarmSnooze.until($0) },
                           recordLog: (@Sendable ([AlarmLogEntry], Bool) -> Void)? = nil) async -> [PendingAlarm] {
            // Protokoll: Ergebnis je gemeldetem Alarm, nur mitgelesen (`AlarmLog.deliveryTrace`)
            var resolvedLog: [AlarmLogEntry] = []
            defer {
                // Erst die Lease freigeben, dann protokollieren: das Schreiben der Datei hält keinen anderen Prozess auf
                alarmLease?.release()
                recordLog?(alarmLog + resolvedLog, alarmLogLive)
            }
            guard !pendingAlarms.isEmpty else { return [] }
            let byId = Dictionary(current.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
            // Erlaubnis und Nachtruhe einmal für alle; die Auswertung hat beides schon geprüft
            // (`QuietHours.mayFire`), seither kann sich aber etwas geändert haben
            let deliverable = await notifier.canShow()
            let holdAlarms = QuietHours.holdsAlarmsNow(settings)
            var delivered: [PendingAlarm] = []
            for item in pendingAlarms {
                guard let stored = byId[item.alarm.id], item.update.matches(stored) else { continue }
                // Lease vor jeder Meldung auffrischen: Langes Melden (viele Alarme) liesse sie sonst nach
                // `AlarmLease.staleAfterSeconds` verwaisen, ein zweiter Prozess übernähme und meldete doppelt.
                // Lease verloren (übernommen): keine weiteren Meldungen — die Alarme bleiben scharf
                if let lease = alarmLease, !lease.refresh() { break }
                // Frisch von der Platte: Hat ein anderer Prozess den Alarm seit der Auswertung schon
                // ausgelöst (z. B. nach übernommener Lease), bearbeitet oder gelöscht → nicht noch einmal melden
                if let onDisk = await reloadStored() {
                    guard let fresh = onDisk.first(where: { $0.id == item.alarm.id }), item.update.matches(fresh),
                          fresh.lastTriggeredAt <= item.alarm.lastTriggeredAt else { continue }
                }
                let shownBox = LockedBox<Bool?>(nil)
                let at = now()
                // «1 h später» aus der Mitteilung: nur das Melden wartet (wie die Nachtruhe), die Prüfung bleibt gleich
                let snoozed = AlarmSnooze.isSnoozed(until: snoozedUntil(item.alarm.id), now: at)
                let saved = await delivery.deliver(
                    alarmId: item.alarm.id, now: at, cooldownMinutes: settings.alarmCooldownMinutes,
                    holdAlarms: holdAlarms, snoozed: snoozed, canShow: { deliverable },
                    show: {
                        let shown = await notifier.show(item)
                        shownBox.current = shown
                        return shown
                    },
                    mark: { await save(item.alarm.id, item.update) },
                    retract: { await notifier.retract(item) })
                if saved { delivered.append(item) }
                if let entry = item.logEntry,
                   let trace = AlarmLog.deliveryTrace(holdAlarms: holdAlarms, canShow: deliverable,
                                                      shown: shownBox.current, saved: saved, snoozed: snoozed) {
                    resolvedLog.append(AlarmLog.resolved(entry, trace))
                }
            }
            return delivered
        }

        /// Ausgelöste Alarme ohne die, deren Eintrag in `watches` (frisch gespeicherter Stand) inzwischen ein anderes
        /// Paar ist (Bearbeiten, Wiederherstellung mit gleicher Id): Sie gehören nicht mehr zum abgefragten Kurs und
        /// werden nicht gemeldet — der Alarm bleibt scharf. Vor `deliverAlarms` in App, Hintergrund und Widget.
        func keepingCurrentPairs(_ watches: [Watch]) -> Outcome {
            var kept = self
            let identities = Dictionary(watches.map { ($0.id, $0.identity) }, uniquingKeysWith: { first, _ in first })
            kept.pendingAlarms.removeAll { identities[$0.watch.id] != $0.watch.identity }
            return kept
        }

        /// Ergebnis verworfen (z. B. von iOS abgebrochen, nicht gespeichert): nichts melden,
        /// Lease freigeben — die Alarme sind dann weiterhin scharf und melden beim nächsten Mal.
        func releaseAlarmLease() {
            alarmLease?.release()
        }

        /// Überträgt die Ergebnisse auf den aktuellen Stand.
        func apply(to snapshot: inout SharedStorage.Snapshot) {
            // Einträge, deren Paar sich seit der Abfrage geändert hat: weder Kurs noch Alarm-Bezug übernehmen
            var changedPair = Set<Int64>()
            for i in snapshot.watches.indices {
                let id = snapshot.watches[i].id
                if let pair = prices[id]?.pair, pair != snapshot.watches[i].identity {
                    changedPair.insert(id)
                    continue
                }
                if let u = prices[id] {
                    if let price = u.price {
                        snapshot.watches[i].previousPrice = snapshot.watches[i].lastPrice
                        snapshot.watches[i].lastPrice = price
                        snapshot.watches[i].lastUpdate = u.time
                        snapshot.watches[i].lastError = nil
                        snapshot.watches[i].change24h = u.change24h
                        snapshot.watches[i].priceChangedAt = u.priceChangedAt
                        snapshot.watches[i].exchangeTime = u.exchangeTime
                    } else {
                        // Nur kurze Codes speichern, keine rohen Fehlertexte (Hosts, URLs) — `StoredError`
                        snapshot.watches[i].lastError = StoredError.sanitize(u.error) ?? L("something_went_wrong")
                        if changeStampChanged { snapshot.watches[i].change24h = nil }
                    }
                } else if changeStampChanged && pausedIds.contains(id) {
                    // Pausierte Börse: Veränderung gehört zur alten Basis
                    snapshot.watches[i].change24h = nil
                }
                if let (price, at) = notified[id] {
                    snapshot.watches[i].notifiedPrice = price
                    snapshot.watches[i].notifiedAt = at
                }
            }
            // Nur auf unveränderte Alarme: eine Bearbeitung während der Abfrage bleibt
            for (id, update) in alarms {
                if !changedPair.isEmpty, let alarm = snapshot.alarms.first(where: { $0.id == id }),
                   changedPair.contains(alarm.watchId) { continue }
                update.apply(toAlarm: id, in: &snapshot)
            }
        }
    }

    /// `save` für `Outcome.deliverAlarms` ohne Stand im Speicher (Hintergrund-Aufgabe, Widget): überträgt
    /// den Zustand eines gemeldeten Alarms abgestimmt auf die Datei. true = übertragen und geschrieben.
    static func saveAlarmOnDisk(_ id: Int64, _ update: AlarmUpdate) -> Bool {
        var applied = false
        let saved = SharedStorage.updateSnapshot { applied = update.apply(toAlarm: id, in: &$0) }
        return saved != nil && applied
    }
}
