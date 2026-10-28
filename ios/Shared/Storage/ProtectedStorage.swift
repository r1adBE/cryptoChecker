import CryptoKit
import Foundation
import Security

// Verschlüsselte Dateien mit Nutzerdaten, Teil 2: welche Dateien und die Überführung beim Start (Format, Schlüssel und
// Lesen/Schreiben in `ProtectedFile.swift`).

/// Welche Dateien verschlüsselt werden und die Überführung beim Start der App.
enum ProtectedStorage {
    /// Eintrag in `files`.
    typealias Entry = (url: URL, coordination: URL, sealedOnly: Bool)

    /// Dateien mit Nutzerdaten, je mit der URL, auf die sich andere Prozesse beim Schreiben abstimmen
    /// (`watchlist.json` und `portfolio_alarms.json` gemeinsam über `watchlist.json`, siehe `SharedStorage.coordinated`),
    /// und ob die App sie nur versiegelt schreibt (`sealedOnly`, `ProtectedFile.write(requireSealed:)`). Unverschlüsselt
    /// entstehen nur die Kopien unlesbarer Dateien (`ProtectedFile.writeCopy` ohne Schlüssel); nur sie versiegelt der
    /// Start. Eine unverschlüsselte `sealedOnly`-Datei bleibt liegen und gilt beim Lesen als beschädigt.
    /// Bewusst unverschlüsselt (keine Beträge, Adressen oder Notizen; gleich wie Android, wo sie ausserhalb der
    /// Datenbank liegen): Kurs-Zwischenspeicher des Portfolio-Widgets (`portfolio_widget_history/hourly/daily/
    /// candle_attempts.json`, gemerkte Kurse `portfolio_last_prices.txt`), «Letzte Prüfungen» (`check_runs.txt`),
    /// Coin-Namen und -Logos, `phemex_scales.json`, `alarm.lease` und die Einstellungen (App-Group-Defaults).
    static var files: [Entry] {
        let watchlist = SharedStorage.watchlistURL
        let alarms = SharedStorage.portfolioAlarmsURL
        return [
            (watchlist, watchlist, true),
            (SharedStorage.unreadableCopyURL(of: watchlist), watchlist, false),
            (alarms, watchlist, true),
            (SharedStorage.unreadableCopyURL(of: alarms), watchlist, false),
            (PortfolioStore.url, PortfolioStore.url, true),
            (SharedStorage.unreadableCopyURL(of: PortfolioStore.url), PortfolioStore.url, false),
            (WatchedAddressStore.url, WatchedAddressStore.url, true),
            (PortfolioWidgetStore.snapshotURL, PortfolioWidgetStore.snapshotURL, true),
            // Alarm-Protokoll: Paare und Schwellen wie die Merkliste
            (AlarmLogStore.url, AlarmLogStore.url, true),
        ]
    }

    struct LaunchOutcome: Equatable {
        /// Verschlüsselt, aber ohne Schlüssel unlesbar: unverändert beiseitegelegt (`<Name>.locked`).
        var setAside: [String] = []
        var encrypted: [String] = []
        /// Bleibt unverschlüsselt (Überführung fehlgeschlagen, Schlüsselbund nicht lesbar oder kein Schlüssel möglich):
        /// nächster Start versucht es erneut.
        var keptPlain: [String] = []
        /// Früher beiseitegelegt (`*.locked`), öffnet aber mit dem Schlüssel: wieder an ihrem Platz (die inzwischen neu
        /// begonnene Datei liegt als `*.replaced` daneben) — wie Android `Decision.Encrypted.restored`.
        var restored: [String] = []

        /// Hinweis «Verschlüsselung noch nicht aktiv» unter Einstellungen › Sicherung — wie Android
        /// `EncryptionPending.showNotice`. Nicht, wenn alles verschlüsselt ist.
        var encryptionPending: Bool { !keptPlain.isEmpty }
    }

    /// Beim Start der App, vor dem ersten Lesen: Schlüssel sicherstellen und Klartext-Kopien (nicht `sealedOnly`)
    /// verschlüsseln.
    /// - Ohne lesbaren Schlüsselbund: nichts anfassen (nächster Start).
    /// - Kein Schlüssel, aber verschlüsselte Dateien (Schlüssel verloren, z. B. Backup ohne Schlüsselbund eingespielt):
    ///   diese beiseitelegen statt löschen, erst dann einen neuen Schlüssel anlegen.
    /// - Unverschlüsselt bleibende Dateien kommen so lange nicht ins Geräte-Backup (`PlainBackupExclusion`).
    @discardableResult
    static func prepareAtLaunch(files: [Entry] = ProtectedStorage.files,
                                keys: StorageKey = .shared, defaults: UserDefaults = .standard) -> LaunchOutcome {
        var outcome = LaunchOutcome()
        let fm = FileManager.default
        let present = files.filter { fm.fileExists(atPath: $0.url.path) }
        let plainCapable = present.filter { !$0.sealedOnly }
        // Schon versiegelt (normal geschrieben, das Merkmal setzte der Schreiber): kein gemerktes Merkmal mehr nötig
        for file in present where isSealedFile(file.url) { PlainBackupExclusion.forget(file.url, defaults: defaults) }
        /// Ohne Schlüssel bleibt jede Klartext-Datei, wie sie ist — vermerkt für den Hinweis (`encryptionPending`).
        func keepingPlain() -> LaunchOutcome {
            var kept = outcome
            for file in plainCapable where fm.fileExists(atPath: file.url.path) && !ProtectedStorage.isSealedFile(file.url) {
                kept.keptPlain.append(file.url.lastPathComponent)
                PlainBackupExclusion.excludeWhilePlain(file.url, defaults: defaults)
            }
            return kept
        }
        let key: SymmetricKey
        switch keys.lookup() {
        case .unavailable:
            return keepingPlain()
        case .key(let stored):
            key = stored
        case .absent:
            for file in present where isSealedFile(file.url) {
                guard let aside = setAside(file.url) else { return keepingPlain() }
                outcome.setAside.append(aside.lastPathComponent)
            }
            guard let created = keys.createIfAbsent() else { return keepingPlain() }
            key = created
        }
        for file in plainCapable where fm.fileExists(atPath: file.url.path) && !isSealedFile(file.url) {
            if encrypt(file.url, key: key, coordination: file.coordination, defaults: defaults) {
                outcome.encrypted.append(file.url.lastPathComponent)
            } else {
                outcome.keptPlain.append(file.url.lastPathComponent)
                PlainBackupExclusion.excludeWhilePlain(file.url, defaults: defaults)
            }
        }
        // Beiseitegelegte Dateien, die mit diesem Schlüssel doch öffnen («kein Schlüssel» war nur vorübergehend, z. B.
        // ein Lesefehler des Schlüsselbunds direkt nach dem Start): zurückholen statt für immer liegen zu lassen
        for file in files {
            for locked in lockedCopies(of: file.url) {
                guard let raw = try? Data(contentsOf: locked), (try? ProtectedContainer.open(raw, key: key)) != nil else { continue }
                if restoreLocked(locked, to: file.url, coordination: file.coordination) {
                    outcome.restored.append(file.url.lastPathComponent)
                }
                break
            }
        }
        return outcome
    }

    /// Beiseitegelegte Fassungen von `url` (`<Name>.locked`, `<Name>.<Zeit>.locked`), neueste zuerst.
    static func lockedCopies(of url: URL) -> [URL] {
        let dir = url.deletingLastPathComponent()
        let name = url.lastPathComponent
        let names = (try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []
        let copies = names.filter { $0 == name + ".locked" || ($0.hasPrefix(name + ".") && $0.hasSuffix(".locked")) }
            .map { dir.appendingPathComponent($0) }
        func modified(_ u: URL) -> Date {
            (try? FileManager.default.attributesOfItem(atPath: u.path)[.modificationDate] as? Date) ?? .distantPast
        }
        return copies.sorted { modified($0) > modified($1) }
    }

    /// `locked` wieder auf `url`; eine dort inzwischen neu begonnene Datei bleibt als `<Name>.replaced` (mit Zeitstempel,
    /// falls schon vorhanden) — nie gelöscht. Abgestimmt mit den Schreibern der anderen Prozesse wie `encrypt`.
    private static func restoreLocked(_ locked: URL, to url: URL, coordination: URL) -> Bool {
        var done = false
        var coordinated = false
        var error: NSError?
        let move = {
            let fm = FileManager.default
            var replaced = url.appendingPathExtension("replaced")
            if fm.fileExists(atPath: replaced.path) {
                replaced = url.appendingPathExtension("\(EpochTime.nowMillis).replaced")
            }
            let hadFile = fm.fileExists(atPath: url.path)
            do {
                if hadFile { try fm.moveItem(at: url, to: replaced) }
                do {
                    try fm.moveItem(at: locked, to: url)
                    done = true
                } catch {
                    // Zurück, wie es war: nie ohne Datei dastehen
                    if hadFile { try? fm.moveItem(at: replaced, to: url) }
                }
            } catch {
                done = false
            }
        }
        NSFileCoordinator(filePresenter: nil).coordinate(writingItemAt: coordination, options: .forMerging, error: &error) { _ in
            coordinated = true
            move()
        }
        if !coordinated { move() }
        return done
    }

    /// Klartext lesen → verschlüsselt in eine Nachbardatei → lesen, öffnen, vergleichen → atomar ersetzen (der Klartext
    /// verschwindet mit dem Umbenennen) → Backup-Merkmal wie vorher (wie vor dem Ausnehmen als Klartext,
    /// `PlainBackupExclusion`). Abgestimmt mit den Schreibern der anderen Prozesse; hat jemand die Datei inzwischen
    /// geändert, bleibt sie für den nächsten Start.
    static func encrypt(_ url: URL, key: SymmetricKey, coordination: URL, defaults: UserDefaults = .standard) -> Bool {
        var done = false
        var coordinated = false
        var error: NSError?
        NSFileCoordinator(filePresenter: nil).coordinate(writingItemAt: coordination, options: .forMerging, error: &error) { _ in
            coordinated = true
            done = encryptNow(url, key: key, defaults: defaults)
        }
        // Wie `SharedStorage.coordinated`: ohne Abstimmung trotzdem (die Prüfung `unchangedFrom` bleibt)
        if !coordinated { done = encryptNow(url, key: key, defaults: defaults) }
        return done
    }

    private static func encryptNow(_ url: URL, key: SymmetricKey, defaults: UserDefaults) -> Bool {
        guard let raw = try? Data(contentsOf: url) else { return false }
        if ProtectedContainer.isSealed(raw) { return true }
        let excluded = PlainBackupExclusion.afterSealing(current: BackupExclusion.isExcluded(url) ?? false,
                                                         remembered: PlainBackupExclusion.remembered(url, defaults: defaults))
        do {
            try ProtectedFile.writeSealed(raw, to: url, key: key, unchangedFrom: raw)
        } catch {
            return false
        }
        BackupExclusion.set(excluded: excluded, for: url)
        PlainBackupExclusion.forget(url, defaults: defaults)
        return true
    }

    /// Beginnt die Datei mit dem Kopf des Behälters? (liest nur die ersten Bytes)
    static func isSealedFile(_ url: URL) -> Bool {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return false }
        defer { try? handle.close() }
        let head = (try? handle.read(upToCount: ProtectedContainer.headerSize)) ?? Data()
        return ProtectedContainer.isSealed(head)
    }

    /// `<Name>.locked` (bzw. mit Zeitstempel, falls schon vorhanden); nil = nicht verschoben.
    private static func setAside(_ url: URL) -> URL? {
        var target = url.appendingPathExtension("locked")
        if FileManager.default.fileExists(atPath: target.path) {
            target = url.appendingPathExtension("\(EpochTime.nowMillis).locked")
        }
        do {
            try FileManager.default.moveItem(at: url, to: target)
            return target
        } catch {
            return nil
        }
    }
}
