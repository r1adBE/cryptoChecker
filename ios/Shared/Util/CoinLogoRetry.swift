import Foundation

/// Wo Logos gezeigt werden (Schalter unter Darstellung › Coin-Logos), wie Android `CoinLogoUse`.
enum CoinLogoUse {
    /// Portfolio-Logos zählen nur mit eingeschaltetem Portfolio-Tab.
    static func portfolio(_ s: AppSettings) -> Bool { s.portfolioCoinLogos && s.portfolioEnabled }

    static func needed(_ s: AppSettings) -> Bool { s.coinLogos || s.widgetCoinLogos || portfolio(s) }
}

/// Nachholen des Logo-Abgleichs nach einem Netzfehler — gleiche Regeln wie Android `CoinLogoRetry`.
/// Fehlt in den ersten Sekunden nach der Installation das Internet, gilt das nicht als «erledigt»:
/// Ein Netzfehler sperrt weder die Stunde bis zum nächsten Abruf der Liste noch einzelne Bilder für
/// einen Tag. Sobald wieder Netz da ist (`NetworkStatus`) oder die App in den Vordergrund kommt,
/// läuft der Abgleich erneut. Die übrigen Grenzen (6 h, 24 h) bleiben.
enum CoinLogoRetry {
    /// Ab so vielen Bildern gilt «alle fehlgeschlagen» als Netzfehler (nicht als kaputte Bilder).
    static let allFailedMin = 3

    /// Fehlgeschlagener Abruf der Liste: als Versuch speichern (eine Stunde Pause)? Nur bei einem
    /// Server-Fehler, nie bei einem Netzfehler.
    static func recordsAttempt(networkFailure: Bool) -> Bool { !networkFailure }

    /// Fehlgeschlagene Bild-Abrufe (`failed` von `attempted`) am fehlenden Netz? Dann nicht einen Tag
    /// sperren. Ohne Netz immer; mit Netz nur, wenn wirklich alle (mindestens `allFailedMin`) scheiterten.
    static func downloadsFailedForNetwork(online: Bool, failed: Int, attempted: Int) -> Bool {
        failed > 0 && (!online || (failed == attempted && attempted >= allFailedMin))
    }

    /// Abgleich jetzt nachholen? Nur, wenn der letzte am Netz scheiterte und jetzt Netz da ist.
    static func syncNeeded(pendingAfterNetworkFailure: Bool, online: Bool) -> Bool {
        pendingAfterNetworkFailure && online
    }

    /// Wartezeit nach dem ersten am Netz gescheiterten Abgleich (ms).
    static let firstRetryMillis: Int64 = 5_000
    /// Ab dem zweiten Fehlschlag: 15 s, verdoppelt je weiterem Fehlschlag …
    static let retryBaseMillis: Int64 = 15_000
    /// … höchstens 15 Minuten — so entsteht nie eine Flut von Abrufen.
    static let retryMaxMillis: Int64 = 15 * 60_000

    /// Mindestabstand nach `failures` aufeinanderfolgenden, am Netz gescheiterten Abgleichen:
    /// 0 → sofort, 1 → 5 s, 2 → 15 s, 3 → 30 s, 4 → 60 s … höchstens `retryMaxMillis` (wie Android).
    static func backoffMillis(failures: Int) -> Int64 {
        if failures <= 0 { return 0 }
        if failures == 1 { return firstRetryMillis }
        return min(retryBaseMillis << Int64(min(failures - 2, 10)), retryMaxMillis)
    }

    /// Wie lange vor dem nächsten Nachholen noch warten (ms)? 0 = jetzt. Kam das Netz erst lange
    /// nach dem Fehlschlag zurück, sofort; geht die Uhr zurück, die volle Wartezeit.
    static func retryDelayMillis(failures: Int, lastFailureAt: Int64, now: Int64) -> Int64 {
        let backoff = backoffMillis(failures: failures)
        if backoff == 0 { return 0 }
        if now < lastFailureAt { return backoff }
        return min(max(lastFailureAt + backoff - now, 0), backoff)
    }

    // MARK: Bild-Server bremst (Build 46)

    /// Antwort eines Bild-Servers, die eine Pause verlangt statt «Bild kaputt»: 429, 403, 503 — wie Android.
    static func pausesImages(httpCode: Int) -> Bool { httpCode == 429 || httpCode == 403 || httpCode == 503 }
    /// Pause ohne Angabe des Servers.
    static let defaultImagePauseMillis: Int64 = 15 * 60_000
    /// Höchstens so lange (auch bei längerer «Retry-After»-Angabe).
    static let imagePauseMaxMillis: Int64 = 6 * 60 * 60_000

    /// Pause für einen Bild-Server («Retry-After» in Sekunden), 1 Min. … 6 h.
    static func imagePauseMillis(retryAfterSeconds: Int64?) -> Int64 {
        let wait = retryAfterSeconds.map { min(max($0, 0), imagePauseMaxMillis / 1000) * 1000 } ?? defaultImagePauseMillis
        return min(max(wait, 60_000), imagePauseMaxMillis)
    }

    /// Host einer HTTPS-Adresse, klein; nil, wenn keine.
    static func hostOf(_ url: String) -> String? {
        guard url.hasPrefix("https://") else { return nil }
        let host = String(url.dropFirst("https://".count).prefix { $0 != "/" && $0 != "?" && $0 != ":" }).lowercased()
        return host.isEmpty ? nil : host
    }

    /// Pausen je Host speichern: «host=bis;host=bis».
    static func encodePauses(_ pauses: [String: Int64]) -> String {
        pauses.sorted { $0.key < $1.key }.map { "\($0.key)=\($0.value)" }.joined(separator: ";")
    }

    /// Gespeicherte Pausen, nur noch laufende (`now` < bis, höchstens `imagePauseMaxMillis` voraus).
    static func decodePauses(_ text: String?, now: Int64) -> [String: Int64] {
        guard let text else { return [:] }
        var out: [String: Int64] = [:]
        for part in text.split(separator: ";") {
            guard let eq = part.firstIndex(of: "=") else { continue }
            let host = part[..<eq].trimmingCharacters(in: .whitespaces)
            guard let until = Int64(part[part.index(after: eq)...].trimmingCharacters(in: .whitespaces)) else { continue }
            if host.isEmpty || until <= now || until - now > imagePauseMaxMillis { continue }
            out[host] = until
        }
        return out
    }
}
