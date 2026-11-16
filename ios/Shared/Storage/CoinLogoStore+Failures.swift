import Foundation

// Gedächtnis des Logo-Ladens (Teil von `CoinLogoStore`, aus `CoinLogoStore+Download` wegen der Zeilengrenze):
// gescheiterte Bilder (einen Tag gesperrt), offene Durchgänge je Bild und der letzte Durchgang für den Diagnosebericht.
extension CoinLogoStore {
    /// Rangliste nicht erreichbar: frühestens nach einer Stunde erneut.
    static let mapRetryMillis: Int64 = 60 * 60 * 1000

    // MARK: Diagnose (nur Zahlen)

    private static let lastPassKey = "coin_logos_last_pass"

    /// Letzten Durchgang für den Diagnosebericht merken (`LogoPassSummary`).
    static func recordPass(_ summary: LogoPassSummary) {
        SharedStorage.defaults.set(summary.encode(), forKey: lastPassKey)
    }

    /// Für den Diagnosebericht: Bilder auf dem Gerät, gerade gesperrte, letzter Durchgang. Nie Netz. Wie Android.
    static func diagnostics() -> LogoDiagnostics {
        let files = (try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []
        return LogoDiagnostics(onDevice: files.filter { $0.hasSuffix(".png") }.count,
                               locked: loadFailures(now: EpochTime.nowMillis).count,
                               lastPass: LogoPassSummary.decode(SharedStorage.defaults.string(forKey: lastPassKey)))
    }

    // MARK: Fehlschläge (Name → Zeitpunkt), einen Tag lang nicht wiederholen

    static func loadFailures(now: Int64) -> [String: Int64] {
        let text = SharedStorage.defaults.string(forKey: failedKey) ?? ""
        var out: [String: Int64] = [:]
        for part in text.split(separator: ";") {
            let pieces = part.split(separator: ":", maxSplits: 1)
            guard pieces.count == 2, let at = Int64(pieces[1]), !pieces[0].isEmpty,
                  CoinLogos.isFresh(savedAt: at, now: now, ttl: CoinLogos.failureTTLMillis)
            else { continue }
            out[String(pieces[0])] = at
        }
        return out
    }

    static func saveFailures(_ failures: [String: Int64]) {
        let text = failures.map { "\($0.key):\($0.value)" }.joined(separator: ";")
        SharedStorage.defaults.set(text, forKey: failedKey)
    }

    /// Offene Durchgänge je Bild («name=zahl;…», `CoinLogoRetry.encodeOpenPasses`).
    static let openPassesKey = "coin_logos_open_passes"

    /// Nach einem Durchgang, der nicht am Netz scheiterte: offene Bilder (`open`, gestört bzw. gebremst) zählen, die übrigen
    /// versuchten vergessen (`CoinLogoRetry.nextOpenPasses`). Ergebnis: die Bilder, die jetzt
    /// `CoinLogoRetry.openPassesLimit` Durchgänge in Folge offen sind — sie gelten als gescheitert. Wie Android `noteOpenPasses`.
    static func noteOpenPasses(attempted: [String], open: [String]) -> Set<String> {
        let stored = CoinLogoRetry.decodeOpenPasses(SharedStorage.defaults.string(forKey: openPassesKey))
        let next = CoinLogoRetry.nextOpenPasses(stored, attempted: attempted, open: open)
        let failed = Set(next.filter { CoinLogoRetry.openPassesFail($0.value) }.keys)
        SharedStorage.defaults.set(CoinLogoRetry.encodeOpenPasses(next.filter { !failed.contains($0.key) }), forKey: openPassesKey)
        return failed
    }
}
