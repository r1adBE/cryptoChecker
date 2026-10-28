import Foundation

// MARK: Gruppe des Merkliste-Widgets

/// Gruppe eines Merkliste-Widgets auflösen — wie `WidgetGroupSelection.kt` (Android). Die App kann die
/// Einrichtung eines Widgets (AppIntent) nicht umschreiben; deshalb merkt sie sich Umbenennungen
/// (`renames`, alt → neu), denen das Widget folgt — Android schreibt den neuen Namen direkt in die
/// Widget-Einstellung (`WidgetPrefs.replaceGroup`). Reine Funktionen bis auf `renames`/`markSeen`.
enum WidgetGroupSelection {
    private static let renamesKey = "widget_group_renames"
    private static let seenKey = "watchlist_widget_configs_seen"
    /// Einrichtungen, die so lange nicht gezeichnet wurden, gelten wieder als neu.
    static let seenRetentionMillis: Int64 = 24 * 3_600_000

    /// - nil bleibt nil («Alle»)
    /// - FAV, «Token», «Alpha» und die übrigen eingebauten Filter (`WatchFilter.isBuiltIn`) bleiben (ohne passende
    ///   Paare zeigt das Widget alle)
    /// - eine vorhandene Gruppe bleibt
    /// - eine umbenannte folgt `renames` (auch über mehrere Umbenennungen), sofern es den neuen Namen gibt
    /// - sonst (gelöscht, letztes Paar entfernt) nil = «Alle», nie ein leeres Widget
    static func resolve(_ selection: String?, groupNames: [String?], renames: [String: String] = [:]) -> String? {
        guard let selection else { return nil }
        if WatchFilter.isBuiltIn(selection) { return selection }
        let existing = Set(groupNames.compactMap { $0 })
        var name = selection
        // Höchstens so viele Schritte wie Einträge: schützt vor Schleifen (A → B → A)
        for _ in 0...renames.count {
            if existing.contains(name) { return name }
            guard let next = renames[name] else { return nil }
            name = next
        }
        return nil
    }

    /// Umbenennungen nach «`old` heisst jetzt `new`»: Einträge, die auf `old` zeigten, zeigen auf `new`;
    /// `new` selbst ist kein alter Name mehr.
    static func afterRename(_ renames: [String: String], old: String, new: String) -> [String: String] {
        guard old != new else { return renames }
        var result = renames.mapValues { $0 == old ? new : $0 }
        result[old] = new
        result[new] = nil
        return result.filter { $0.key != $0.value }
    }

    /// Umbenennungen nach dem Löschen der Gruppe `name`: Wer ihr folgte, zeigt wieder «Alle».
    static func afterDelete(_ renames: [String: String], name: String) -> [String: String] {
        var result = renames.filter { $0.value != name }
        result[name] = nil
        return result
    }

    /// Gemerkte Umbenennungen (App Group, die App schreibt, das Widget liest).
    static var renames: [String: String] {
        get { SharedStorage.defaults.dictionary(forKey: renamesKey) as? [String: String] ?? [:] }
        set {
            if newValue.isEmpty { SharedStorage.defaults.removeObject(forKey: renamesKey) }
            else { SharedStorage.defaults.set(newValue, forKey: renamesKey) }
        }
    }

    static func recordRename(old: String, new: String) { renames = afterRename(renames, old: old, new: new) }

    static func recordDelete(_ name: String) { renames = afterDelete(renames, name: name) }

    /// Zuletzt gezeichnete Einrichtungen (`key` → Zeit) nach dem Zeichnen von `key`; `isNew` = diese
    /// Einrichtung wurde seit `seenRetentionMillis` nicht gezeichnet (eben eingerichtet oder geändert).
    /// Alte Einträge fallen weg.
    static func seen(_ seen: [String: Int64], key: String, now: Int64) -> (isNew: Bool, seen: [String: Int64]) {
        var result = seen.filter { now - $0.value >= 0 && now - $0.value < seenRetentionMillis }
        let isNew = result[key] == nil
        result[key] = now
        return (isNew, result)
    }

    /// Schlüssel einer Einrichtung des Merkliste-Widgets (Hintergrund und Gruppe).
    static func configKey(theme: String, group: String?) -> String { theme + "|" + (group ?? "") }

    /// Merkt sich, dass die Einrichtung `key` gezeichnet wird; true = neu (siehe `seen`).
    static func markSeen(_ key: String, now: Int64 = EpochTime.nowMillis) -> Bool {
        let stored = SharedStorage.defaults.dictionary(forKey: seenKey) as? [String: Double] ?? [:]
        let (isNew, updated) = seen(stored.mapValues { Int64($0) }, key: key, now: now)
        SharedStorage.defaults.set(updated.mapValues { Double($0) }, forKey: seenKey)
        return isNew
    }

    /// Wirksame Gruppe und ihre Paare aus `items` (App-Reihenfolge): `selection` wird erst aufgelöst
    /// (`resolve`), dann gefiltert (`WatchFilter.matches`; `alpha` = Paar ist ein Alpha-Token, nur für
    /// «Alpha» gefragt). Gibt es die Gruppe nicht (mehr) oder passt kein Paar, wieder alle Paare und
    /// Gruppe nil («Alle», Titel «Merkliste») — nie ein leeres Widget. Wie `WidgetGroupSelection.pick`
    /// (Android).
    static func pick<T>(_ selection: String?, items: [T], groupName: (T) -> String?, favorite: (T) -> Bool,
                        alpha: (T) -> Bool = { _ in false },
                        renames: [String: String] = [:]) -> (group: String?, items: [T]) {
        guard let group = resolve(selection, groupNames: items.map(groupName), renames: renames) else {
            return (nil, items)
        }
        let needsAlpha = WatchFilter.isAlpha(group)
        let matching = items.filter {
            WatchFilter.matches(group, groupName: groupName($0), favorite: favorite($0), alpha: needsAlpha && alpha($0))
        }
        return matching.isEmpty ? (nil, items) : (group, matching)
    }

    /// Wählbare Einträge der Widget-Einrichtung in der Reihenfolge der Chips der App: «Alle» (nil), FAV,
    /// «Token» (nur mit `WatchFilter.showsTokens`), «Alpha» (nur mit `WatchFilter.showsAlpha`), dann die
    /// eigenen Gruppen alphabetisch (auch «TradFi», «QTLY»). `groupNames`/`alphaFlags` je Paar. Wie
    /// `WidgetGroupSelection.options` (Android).
    static func options(groupNames: [String?], alphaFlags: [Bool]) -> [String?] {
        var result: [String?] = [nil, WatchFilter.favorites]
        if WatchFilter.showsTokens(groupNames) { result.append(WatchFilter.tokens) }
        if WatchFilter.showsAlpha(alphaFlags) { result.append(WatchFilter.alpha) }
        let groups = Array(Set(groupNames.compactMap { $0 }))
            .sorted { $0.localizedCaseInsensitiveCompare($1) == .orderedAscending }
        return result + groups.map { Optional($0) }
    }

    /// So viele Zeichen eines Gruppennamens zeigt der Kopf des Widgets; längere enden mit «…».
    static let titleMaxChars = 16

    /// Titel im Kopf des Merkliste-Widgets: `fallback` («Merkliste») bei «Alle» (`group` nil), sonst die
    /// Beschriftung des Filters (`filterLabel` für FAV, «Token», «Alpha») bzw. der Gruppenname (auch
    /// «TradFi»), auf `titleMaxChars` Zeichen gekürzt. Wie `WidgetGroupSelection.title` (Android).
    static func title(_ group: String?, fallback: String, filterLabel: (String) -> String) -> String {
        guard let group else { return fallback }
        let name = WatchFilter.isBuiltIn(group) ? filterLabel(group) : group
        return truncate(name, max: titleMaxChars)
    }

    /// Höchstens `max` Zeichen; länger: `max` − 1 Zeichen + «…» (wie Android).
    static func truncate(_ text: String, max: Int) -> String {
        let trimmed = text.trimmingCharacters(in: .whitespaces)
        guard max >= 1, trimmed.count > max else { return trimmed }
        let head = String(trimmed.prefix(max - 1))
        return head.replacingOccurrences(of: "\\s+$", with: "", options: .regularExpression) + "…"
    }
}

/// Gehören die gespeicherten Kurse zur Uhrzeit im Kopf des Merkliste-Widgets? Die App setzt
/// `lastRefreshAt` sofort (UserDefaults), schreibt die Kurse aber im Hintergrund
/// (`saveSnapshotInBackground`). Zeichnet das Widget dazwischen (anderes Neuladen, zusammengelegte
/// Anfragen), stünde «Stand 14:55» über den alten Kursen. Reine Logik, testbar — wie Android
/// `ListWidgetDataVersion` (dort: Zeilen und Kopf aus demselben Zeichnen).
enum WidgetDataFreshness {
    /// So viel darf die Uhrzeit nach dem Schreiben liegen (Widget-Aktualisierung: erst Datei, dann Zeit).
    static let toleranceMillis: Int64 = 2_000

    /// true = die Uhrzeit ist neuer als die Datei mit den Kursen (Kurse noch nicht geschrieben).
    /// Unbekannt (0) zählt nie als «dahinter».
    static func dataBehind(refreshAt: Int64, writtenAt: Int64, tolerance: Int64 = toleranceMillis) -> Bool {
        refreshAt > 0 && writtenAt > 0 && refreshAt - writtenAt > tolerance
    }
}
