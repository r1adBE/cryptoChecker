import Foundation

/// Pause je Börse (#19): Antwortet eine Börse mit HTTP 429/418 («zu viele Anfragen») oder
/// scheitert sie zweimal hintereinander nur an Zeitüberschreitungen, wird sie eine Weile
/// übersprungen — 30 s, 1, 2, 4, 8 Min., höchstens 15 Min.; ein «Retry-After» der Börse gilt,
/// wenn es länger ist. Ihre Paare behalten solange den letzten Kurs; der Bericht zeigt
/// «pausiert bis 19:45 (zu viele Anfragen)». Ein Erfolg setzt alles zurück.
/// Reine Logik — wie `ExchangeBackoff.kt`.
enum ExchangeBackoff {

    /// Erste Pause.
    static let baseMillis: Int64 = 30_000
    /// Längste Pause (auch ein längeres «Retry-After» wird darauf gekürzt).
    static let maxMillis: Int64 = 15 * 60_000
    /// So viele Durchläufe in Folge nur mit Zeitüberschreitungen → Pause.
    static let timeoutRuns = 2

    /// Was ein Durchlauf bei einer Börse erlebt hat.
    enum Outcome: Sendable { case success, rateLimited, timeout, other }

    struct State: Equatable, Sendable {
        /// Pausen in Folge ohne Erfolg dazwischen — bestimmt die Dauer der nächsten.
        var strikes = 0
        /// Bis dahin wird die Börse übersprungen (0 = keine Pause).
        var pausedUntil: Int64 = 0
        /// `.RATE_LIMIT` oder `.TIMEOUT`; nil = noch nie pausiert.
        var reason: RefreshFailure? = nil
        /// Durchläufe in Folge, die nur an Zeitüberschreitungen scheiterten.
        var timeoutRuns = 0
    }

    /// Dauer der n-ten Pause in Folge (1 → 30 s, 2 → 1 Min., 3 → 2 Min. … höchstens 15 Min.).
    static func delayMillis(_ strike: Int) -> Int64 {
        if strike <= 1 { return baseMillis }
        let shift = min(strike - 1, 10)
        return min(baseMillis << Int64(shift), maxMillis)
    }

    /// Pausiert? Eine Pause, die weiter als die längste mögliche (`groupMaxMillis`) in der Zukunft endet, gilt als
    /// abgelaufen (Uhr zurückgestellt oder Wert kaputt) — sonst bliebe die Börse lange stumm. Wie Android.
    static func isPaused(_ state: State?, now: Int64) -> Bool {
        guard let state else { return false }
        return state.pausedUntil > now && state.pausedUntil - now <= groupMaxMillis
    }

    /// Neuer Zustand nach einem Durchlauf; nil = nichts zu merken (alles gut).
    /// `banned`: HTTP 418 («IP gesperrt») — mindestens `banDefaultMillis` wie bei Kerzen, ein kurzer
    /// neuer Versuch verlängerte die Sperre. `shared`: Börse mit gemeinsamer Pause (`sharedGroup`,
    /// Binance) — «Retry-After» gilt wie bei Kerzen bis `groupMaxMillis` statt nur bis `maxMillis`.
    static func next(_ state: State?, outcome: Outcome, now: Int64, retryAfterMillis: Int64? = nil,
                     banned: Bool = false, shared: Bool = false) -> State? {
        let s = state ?? State()
        switch outcome {
        case .success:
            return nil
        case .rateLimited:
            let strikes = s.strikes + 1
            let own = banned ? max(delayMillis(strikes), banDefaultMillis) : delayMillis(strikes)
            let asked = min(max(retryAfterMillis ?? 0, 0), shared ? groupMaxMillis : maxMillis)
            // Eine laufende Pause (z. B. gemeinsame von Kerzen) nie verkürzen
            return State(strikes: strikes, pausedUntil: max(now + max(own, asked), s.pausedUntil),
                         reason: .RATE_LIMIT, timeoutRuns: 0)
        case .timeout:
            let runs = s.timeoutRuns + 1
            if runs < timeoutRuns {
                var copy = s
                copy.timeoutRuns = runs
                return copy
            }
            let strikes = s.strikes + 1
            return State(strikes: strikes, pausedUntil: now + delayMillis(strikes), reason: .TIMEOUT, timeoutRuns: 0)
        case .other:
            // Anderer Fehler: keine Pause, Zeitüberschreitungen nicht mehr «in Folge»
            var copy = s
            copy.timeoutRuns = 0
            return copy.strikes == 0 && copy.pausedUntil == 0 ? nil : copy
        }
    }

    /// Eigenen Stand (`ours`, ausgehend von `initial`) mit dem inzwischen gespeicherten (`stored`) zusammenführen:
    /// Schlüssel, die dieser Durchlauf nicht geändert hat, bleiben wie gespeichert; hat ein anderer Prozess einen
    /// geänderten Schlüssel inzwischen ebenfalls neu gemerkt, gilt die spätere Pause. Eine bei uns aufgehobene
    /// Pause (Erfolg) fällt nur weg, wenn niemand sie seither neu gemerkt hat.
    static func merge(ours: [String: State], initial: [String: State], stored: [String: State]) -> [String: State] {
        var result = stored
        for key in Set(ours.keys).union(initial.keys) where ours[key] != initial[key] {
            let theirs = stored[key]
            let theirsChanged = theirs != initial[key]
            guard let mine = ours[key] else {
                if !theirsChanged { result[key] = nil }
                continue
            }
            if let theirs, theirsChanged, theirs.pausedUntil > mine.pausedUntil { continue }
            result[key] = mine
        }
        return result
    }

    /// Ergebnis einer Börse aus den Ursachen ihrer Fehler: Schon ein «zu viele Anfragen»
    /// pausiert; sonst zählt jeder Kurs als Erfolg; nur Zeitüberschreitungen → `.timeout`.
    static func outcome(failures: [RefreshFailure], updated: Int) -> Outcome {
        if failures.contains(.RATE_LIMIT) { return .rateLimited }
        if updated > 0 || failures.isEmpty { return .success }
        if failures.allSatisfy({ $0 == .TIMEOUT }) { return .timeout }
        return .other
    }

    /// Längstes beachtetes «Retry-After» (1 Tag); grössere Angaben werden gekürzt — sonst liefe
    /// `Sekunden × 1000` über (wie Android).
    static let maxRetryAfterSeconds: Int64 = 86_400

    /// «Retry-After»-Kopfzeile → Sekunden (0 … `maxRetryAfterSeconds`): Zahl oder HTTP-Datum. Unlesbar oder fehlend → nil.
    static func parseRetryAfterSeconds(_ header: String?, now: Int64) -> Int64? {
        let value = (header ?? "").trimmingCharacters(in: .whitespaces)
        if value.isEmpty { return nil }
        if let seconds = Int64(value) { return min(max(seconds, 0), maxRetryAfterSeconds) }
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "GMT")
        f.dateFormat = "EEE, d MMM yyyy HH:mm:ss zzz"
        guard let date = f.date(from: value) else { return nil }
        // In Double rechnen und erst gekürzt umwandeln (ein Datum weit in der Zukunft passt nicht in Int64)
        let deltaMillis = date.timeIntervalSince1970 * 1000 - Double(now)
        guard deltaMillis.isFinite else { return nil }
        return Int64(min(max(deltaMillis, 0), Double(maxRetryAfterSeconds * 1000))) / 1000
    }

    /// HTTP 418 («IP gesperrt», Binance) in einem der Fehlertexte?
    static func banned(_ errors: [String?]) -> Bool {
        errors.contains { RefreshReportLogic.httpStatus($0) == 418 }
    }

    /// Anhang an den Fehlertext von HTTP-Fehlern, z. B. «HTTP 429 (retry-after 30 s)».
    static func retryAfterSuffix(_ seconds: Int64?) -> String {
        guard let seconds else { return "" }
        return " (retry-after \(seconds) s)"
    }

    /// Längstes «Retry-After» aus Fehlertexten (Millisekunden); nil = keines.
    static func retryAfterMillis(_ errors: [String?]) -> Int64? {
        guard let regex = try? NSRegularExpression(pattern: #"retry-after (\d+) s"#) else { return nil }
        let values: [Int64] = errors.compactMap { error in
            guard let lower = error?.lowercased(),
                  let match = regex.firstMatch(in: lower, range: NSRange(lower.startIndex..., in: lower)),
                  let range = Range(match.range(at: 1), in: lower) else { return nil }
            return Int64(lower[range])
        }
        return values.max().map { min($0, maxRetryAfterSeconds) * 1000 }
    }

    /// Zustände je Börse als kurzer Text (wie Android).
    static func encode(_ states: [String: State]) -> String {
        states.keys.sorted().compactMap { key in
            guard let s = states[key] else { return nil }
            return [key, String(s.strikes), String(s.pausedUntil), s.reason?.rawValue ?? "", String(s.timeoutRuns)]
                .joined(separator: "|")
        }.joined(separator: ";")
    }

    /// Unlesbare Einträge fallen weg.
    static func decode(_ text: String?) -> [String: State] {
        guard let text, !text.isEmpty else { return [:] }
        var result: [String: State] = [:]
        for entry in text.split(separator: ";", omittingEmptySubsequences: false) {
            let parts = entry.split(separator: "|", omittingEmptySubsequences: false).map(String.init)
            guard parts.count == 5, !parts[0].isEmpty,
                  let strikes = Int(parts[1]), let until = Int64(parts[2]), let runs = Int(parts[4]) else { continue }
            result[parts[0]] = State(strikes: strikes, pausedUntil: until,
                                     reason: RefreshFailure(rawValue: parts[3]), timeoutRuns: runs)
        }
        return result
    }

    // MARK: - Gemeinsame Pause aller Binance-Endpunkte

    /// Binance zählt Anfragen je IP über alle diese Hosts zusammen; nach 429/418 verlängern
    /// weitere Anfragen — gleich an welchen Endpunkt — die Sperre. Deshalb pausieren Kerzen,
    /// Futures-Kennzahlen, Aktivität und Kurse gemeinsam (Schlüssel `binanceGroup`).
    /// Binance Alpha (www.binance.com) gehört dazu; Binance.US (api.binance.us) ist eine eigene
    /// Börse und gehört nicht dazu.
    static let binanceHosts: Set<String> = [
        "api.binance.com", "data-api.binance.vision", "fapi.binance.com", "dapi.binance.com", "www.binance.com",
    ]

    /// Börsen-Schlüssel der Kursabfrage, die dieselben Hosts nutzen.
    static let binanceMarketKeys: Set<String> = ["Binance", "BinanceFutures"]

    static let binanceGroup = "binance"

    /// Längste gemeinsame Pause: ein «Retry-After» von Binance gilt bis zu dieser Dauer.
    static let groupMaxMillis: Int64 = 6 * 60 * 60_000

    /// Mindestpause nach 418 ohne «Retry-After» (IP schon gesperrt, Binance: ab 2 Min.).
    static let banDefaultMillis: Int64 = 2 * 60_000

    /// Gruppe, deren Endpunkte sich eine Pause teilen (Hostname oder Börsen-Schlüssel); nil = keine.
    static func sharedGroup(_ hostOrMarketKey: String) -> String? {
        binanceHosts.contains(hostOrMarketKey) || binanceMarketKeys.contains(hostOrMarketKey) ? binanceGroup : nil
    }

    /// «Zu viele Anfragen» (429) oder «IP gesperrt» (418).
    static func isRateLimitCode(_ httpCode: Int) -> Bool { httpCode == 429 || httpCode == 418 }

    /// Neue gemeinsame Pause nach 429/418 ausserhalb der Kursabfrage — wie `groupPause` in
    /// `ExchangeBackoff.kt`. Ohne «Retry-After» gilt die Stufe aus `delayMillis` (nach 418
    /// mindestens `banDefaultMillis`); ein längeres «Retry-After» gilt bis `groupMaxMillis`.
    /// Eine laufende Pause wird nie verkürzt, und Antworten, die während der Pause noch eintreffen
    /// (parallel gestartete Anfragen), erhöhen die Stufe nicht. Kein Erfolgssignal: Liegt das Ende
    /// der letzten Pause länger als `maxMillis` zurück, beginnt die Zählung neu.
    static func groupPause(_ state: State?, now: Int64, httpCode: Int, retryAfterSeconds: Int64?) -> State {
        let recent: State? = state.flatMap { now - $0.pausedUntil <= maxMillis ? $0 : nil }
        let strikes: Int
        if let recent {
            strikes = recent.pausedUntil > now ? max(recent.strikes, 1) : recent.strikes + 1
        } else {
            strikes = 1
        }
        var own = delayMillis(strikes)
        if httpCode == 418 { own = max(own, banDefaultMillis) }
        let asked: Int64 = retryAfterSeconds.map { min(max($0, 0), groupMaxMillis / 1000) * 1000 } ?? 0
        let until = max(now + max(own, asked), recent?.pausedUntil ?? 0)
        return State(strikes: strikes, pausedUntil: until, reason: .RATE_LIMIT, timeoutRuns: 0)
    }

    // MARK: - Gemeinsame Pause von Spot und Futures auf demselben Host

    /// Börsen-Schlüssel der Kursabfrage, deren Spot- und Futures-Teil denselben Host und damit dasselbe Limit nutzen
    /// (Wert = Host). Die Pause gilt je Schlüssel; ohne diese Zuordnung pausierte ein 429 nur den einen Teil, und der
    /// andere fragte weiter beim schon bremsenden Host. Binance läuft breiter über `sharedGroup` (`BlockedSources`).
    static let hostGroups: [String: String] = [
        "BingX": "open-api.bingx.com", "BingXFutures": "open-api.bingx.com",
        "Bybit": "api.bybit.com", "BybitFutures": "api.bybit.com",
        "Okex": "www.okx.com", "OkexFutures": "www.okx.com",
        "Phemex": "api.phemex.com", "PhemexFutures": "api.phemex.com",
        "Bitget": "api.bitget.com", "BitgetFutures": "api.bitget.com",
        "Woo": "api.woox.io", "WooFutures": "api.woox.io",
    ]

    /// Laufende «zu viele Anfragen»-Pausen (`.RATE_LIMIT`) auf die übrigen Schlüssel derselben Host-Gruppe
    /// (`hostGroups`) übertragen — wie die gemeinsame Binance-Pause im `PriceRefresher`. Eine längere eigene Pause
    /// bleibt; Pausen wegen Zeitüberschreitung bleiben je Schlüssel (sie sagen nichts über das Limit). Wie Android.
    static func withHostGroupPauses(_ states: [String: State], now: Int64) -> [String: State] {
        var result = states
        for (key, state) in states {
            guard let group = hostGroups[key], state.reason == .RATE_LIMIT, isPaused(state, now: now) else { continue }
            for (other, otherGroup) in hostGroups where other != key && otherGroup == group {
                let current = result[other]
                if let current, isPaused(current, now: now), current.pausedUntil >= state.pausedUntil { continue }
                var copy = current ?? State(strikes: 1)
                copy.pausedUntil = state.pausedUntil
                copy.reason = .RATE_LIMIT
                copy.timeoutRuns = 0
                result[other] = copy
            }
        }
        return result
    }
}

/// App-Start bis zum ersten Bild der Merkliste, nur lokal gemessen (Bericht «Ablauf») — wie
/// `AppStartTiming.kt`.
enum AppStartTiming {
    /// Lag zwischen Prozessstart und Aufbau der Oberfläche mehr als das, lief der Prozess schon.
    static let coldGapMillis: Int64 = 5_000
    /// Unplausibel lange Werte werden verworfen.
    static let maxMillis: Int64 = 60_000

    static func elapsed(processStart: Int64?, uiCreated: Int64, firstFrame: Int64) -> Int64? {
        var from = uiCreated
        if let processStart, processStart <= uiCreated, uiCreated - processStart <= coldGapMillis {
            from = processStart
        }
        let millis = firstFrame - from
        return millis >= 0 && millis <= maxMillis ? millis : nil
    }
}

/// Kein Netz (#22/#23): nicht aktualisieren; kommt das Netz zurück, genau EINE Aktualisierung.
enum OfflineGate {
    static func resumeOnChange(previous: Bool?, online: Bool) -> Bool { previous == false && online }
}
