import Foundation

// Reine Kurs-Speicherungen des Live-Stroms zusammenfassen (Teil von `AppData`): Bei jedem Live-Takt nur Kurs, Änderung
// und Zeit zu speichern hiesse, alle paar Sekunden die ganze Merkliste zu kodieren, zu verschlüsseln und zu schreiben.
// Darum höchstens alle `PriceSaveThrottle.intervalMillis` — die erste nach einer Pause sofort, weitere gesammelt am
// Ende des Abstands (immer der dann aktuelle Stand). Sofort bleibt alles andere: ausgelöste Alarme,
// Mitteilungen, Fehlerzustände, jede Änderung des Nutzers (`mutate` ohne `quiet`) — ein solches volles Schreiben nimmt
// wartende Kurse samt Widget-Wünschen mit (`foldPendingPriceSave`). Beim Wechsel in den Hintergrund und vor jedem Lesen
// von der Platte werden wartende Kurse sofort geschrieben (`flushPriceSave`).

/// Abstand und wartende Widget-Aktualisierungen der zusammengefassten Kurs-Speicherungen — rein, ohne Uhr und Platte.
struct PriceSaveThrottle: Equatable {
    /// Höchstens eine reine Kurs-Speicherung je 30 s.
    static let intervalMillis: Int64 = 30_000

    /// Was mit einer angefragten Kurs-Speicherung geschieht.
    enum Decision: Equatable {
        /// Jetzt schreiben (`take`).
        case saveNow
        /// Vorgemerkt: nach `afterMillis` schreiben, wenn `fires(token:)` dann noch gilt.
        case schedule(afterMillis: Int64, token: Int)
        /// Schon vorgemerkt: kommt mit der wartenden Speicherung (immer der dann aktuelle Stand).
        case waiting
    }

    /// Widgets, die nach dem Schreiben neu laden sollen; `all` = alle Arten.
    struct Widgets: Equatable {
        var all = false
        var kinds: Set<String> = []
        var isEmpty: Bool { !all && kinds.isEmpty }

        /// Diese Wünsche mit denen eines anderen Schreibens (`reloadWidgets`, `widgetKinds`, nil = alle) vereinigt.
        func merged(reloadWidgets: Bool, widgetKinds: [String]?) -> (reload: Bool, kinds: [String]?) {
            if isEmpty { return (reloadWidgets, widgetKinds) }
            if all || (reloadWidgets && widgetKinds == nil) { return (true, nil) }
            let own = reloadWidgets ? (widgetKinds ?? []) : []
            return (true, kinds.union(own).sorted())
        }
    }

    private(set) var lastSaveAt: Int64?
    private(set) var pending = false
    private(set) var token = 0
    private(set) var widgets = Widgets()

    /// Reine Kurs-Speicherung um `now` (ms) angefragt; Widget-Wünsche aller wartenden Anfragen werden vereinigt.
    mutating func request(now: Int64, reloadWidgets: Bool, widgetKinds: [String]?) -> Decision {
        if reloadWidgets {
            if let widgetKinds { widgets.kinds.formUnion(widgetKinds) } else { widgets.all = true }
        }
        if pending { return .waiting }
        // Uhr zurückgestellt (now vor der letzten Speicherung): wie nach einer Pause sofort
        if let last = lastSaveAt, now >= last, now - last < Self.intervalMillis {
            pending = true
            token &+= 1
            return .schedule(afterMillis: last + Self.intervalMillis - now, token: token)
        }
        return .saveNow
    }

    /// Gilt die vorgemerkte Speicherung `token` noch (nicht schon geschrieben, nicht ersetzt)?
    func fires(token: Int) -> Bool { pending && token == self.token }

    /// Jetzt geschrieben (um `now`): wartende Widget-Wünsche zurückgeben, Vormerkung erledigt.
    mutating func take(now: Int64) -> Widgets {
        let wanted = widgets
        lastSaveAt = now
        pending = false
        widgets = Widgets()
        return wanted
    }
}

extension AppData {
    /// Reine Kurse aus `mutate(…, quiet: true)` speichern — sofort oder zusammengefasst (`PriceSaveThrottle`).
    func requestPriceSave(reloadWidgets: Bool, widgetKinds: [String]?) {
        switch priceSaves.request(now: EpochTime.nowMillis, reloadWidgets: reloadWidgets, widgetKinds: widgetKinds) {
        case .saveNow:
            writePendingPrices()
        case .schedule(let afterMillis, let token):
            Task { [weak self] in
                try? await Task.sleep(nanoseconds: UInt64(max(0, afterMillis)) * 1_000_000)
                guard let self, self.priceSaves.fires(token: token) else { return }
                self.writePendingPrices()
            }
        case .waiting:
            break
        }
    }

    /// Wartende Kurse sofort schreiben (Wechsel in den Hintergrund, vor dem Lesen von der Platte); nichts wartend: nichts.
    func flushPriceSave() {
        guard priceSaves.pending else { return }
        writePendingPrices()
    }

    /// Ein volles Schreiben (`mutate` ohne `quiet`) enthält den aktuellen Stand, also auch wartende Kurse: Vormerkung
    /// erledigt (die vorgemerkte Speicherung schreibt nicht noch einmal), Abstand ab jetzt; Rückgabe: die Widget-Wünsche
    /// dieses Schreibens vereinigt mit den wartenden.
    func foldPendingPriceSave(reloadWidgets: Bool, widgetKinds: [String]?) -> (reload: Bool, kinds: [String]?) {
        priceSaves.take(now: EpochTime.nowMillis).merged(reloadWidgets: reloadWidgets, widgetKinds: widgetKinds)
    }

    /// Wartende Kurse verwerfen («App zurücksetzen»: der Stand wird ohnehin neu geschrieben).
    func discardPendingPriceSave() {
        priceSaves = PriceSaveThrottle()
    }

    /// Den aktuellen Stand als reine Kurs-Speicherung schreiben (mit Barriere) und die gewünschten Widgets danach
    /// neu laden.
    private func writePendingPrices() {
        let widgets = priceSaves.take(now: EpochTime.nowMillis)
        writeSnapshot(snapshot, durability: .barrier, reloadWidgets: !widgets.isEmpty,
                      widgetKinds: widgets.all ? nil : widgets.kinds.sorted())
    }
}
