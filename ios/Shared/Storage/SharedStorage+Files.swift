import Foundation

// Dateien der Merkliste und der Portfolio-Alarme (Teil von `SharedStorage`): lesen, schreiben, geschützte Datei der
// Portfolio-Alarme, unlesbare Kopie, neuere Kurse und Alarm-Zustände übernehmen.

extension SharedStorage {
    // MARK: Dateien (ohne Abstimmung — nur innerhalb von `coordinated` aufrufen; testbar über URLs)

    static func readFiles(watchlist: URL, portfolioAlarms: URL) -> SnapshotRead {
        let fm = FileManager.default
        var snap: Snapshot
        if fm.fileExists(atPath: watchlist.path) {
            // Verschlüsselt (Build 59, `ProtectedFile`): ohne Schlüssel «nicht zugreifbar», beschädigt wie kaputt
            let data: Data
            do {
                data = try ProtectedFile.read(watchlist)
            } catch ProtectedFile.ReadError.damaged(let raw) {
                preserveUnreadable(raw, of: watchlist)
                return .unreadable
            } catch {
                AppLog.storage.error("Merkliste nicht lesbar (gesperrt oder Zugriff verweigert)")
                return .inaccessible
            }
            let counter = DropCounter()
            let decoder = JSONDecoder()
            decoder.userInfo[DropCounter.key] = counter
            guard let decoded = try? decoder.decode(Snapshot.self, from: data) else {
                preserveUnreadable(data, of: watchlist)
                return .unreadable
            }
            if counter.count > 0 { preserveUnreadable(data, of: watchlist) }
            snap = decoded
        } else if fm.fileExists(atPath: portfolioAlarms.path) {
            snap = Snapshot()
        } else {
            return .missing
        }
        // Portfolio-Alarme: die eigene Datei geht vor (das Feld in `watchlist.json` stammt von vor der Umstellung)
        if fm.fileExists(atPath: portfolioAlarms.path) {
            let alarmsData: Data?
            do {
                alarmsData = try ProtectedFile.read(portfolioAlarms)
            } catch ProtectedFile.ReadError.damaged(let raw) {
                alarmsData = raw // beschädigt verschlüsselt: wie eine kaputte Datei (gesichert, nicht überschrieben)
            } catch {
                alarmsData = nil
            }
            if let data = alarmsData {
                let counter = DropCounter()
                let decoder = JSONDecoder()
                decoder.userInfo[DropCounter.key] = counter
                if let file = try? decoder.decode(PortfolioAlarmsFile.self, from: data) {
                    if counter.count > 0 { preserveUnreadable(data, of: portfolioAlarms) }
                    snap.portfolioAlarms = file.alarms
                    snap.nextPortfolioAlarmId = max(file.nextId, snap.nextPortfolioAlarmId ?? 1)
                } else {
                    // Kaputt: gesichert; ohne Feld in `watchlist.json` bleibt nil → Datei wird nicht überschrieben
                    preserveUnreadable(data, of: portfolioAlarms)
                }
            } else {
                // Nicht zugreifbar: nichts davon übernehmen und die Datei beim Schreiben nicht anfassen
                snap.portfolioAlarms = nil
                snap.nextPortfolioAlarmId = nil
            }
        }
        return .ok(snap)
    }

    /// Schreibt zuerst die Portfolio-Alarme (falls bekannt), dann `watchlist.json` ohne sie. Erst wenn die
    /// eigene Datei sicher geschrieben ist, fällt das Feld aus `watchlist.json` (Umstellung ohne Verlust,
    /// beliebig oft wiederholbar); bis dahin ist `watchlist.json` ohne «Portfolio in Systemsicherung»
    /// vom Geräte-Backup ausgenommen.
    /// - Returns: true, wenn `watchlist.json` geschrieben wurde.
    @discardableResult
    static func writeFiles(_ snapshot: Snapshot, watchlist: URL, portfolioAlarms: URL,
                           includePortfolioInBackup: Bool) -> Bool {
        var main = snapshot
        if let alarms = snapshot.portfolioAlarms {
            let next = nextId(stored: snapshot.nextPortfolioAlarmId ?? 1, used: alarms.map(\.id))
            let file = PortfolioAlarmsFile(alarms: alarms, nextId: next)
            switch writePortfolioAlarms(file, to: portfolioAlarms, includeInBackup: includePortfolioInBackup) {
            case .written:
                main.portfolioAlarms = nil
                main.nextPortfolioAlarmId = nil
            case .needsKey:
                // Ohne Schlüssel nie im Klartext — auch nicht in `watchlist.json`: Die eigene Datei behält ihren letzten
                // versiegelten Stand, die Änderung bleibt im Speicher (`AppData`) und geht mit dem nächsten Speichern
                // hinaus (`portfolioAlarmsPending`). Ausnahme: `watchlist.json` liegt noch im alten Format unverschlüsselt
                // da und trägt die Portfolio-Alarme schon selbst — dann bleiben sie dort (nichts Neues im Klartext,
                // aber nichts verloren, falls die App vor dem nächsten Speichern endet)
                if FileManager.default.fileExists(atPath: portfolioAlarms.path)
                    || !plainWatchlistHoldsPortfolioAlarms(watchlist) {
                    main.portfolioAlarms = nil
                    main.nextPortfolioAlarmId = nil
                }
                portfolioAlarmsPending = true
            case .failed:
                break
            }
        }
        guard let data = try? JSONEncoder().encode(main) else { return false }
        do {
            try ProtectedFile.write(data, to: watchlist)
        } catch {
            AppLog.storage.error("Merkliste nicht geschrieben")
            return false
        }
        // Schlug das Schreiben von `portfolio_alarms.json` fehl, bleiben die Portfolio-Schwellen in
        // `watchlist.json` (Entfernen könnte sie verlieren, falls die App vor dem nächsten Speichern
        // endet; das nächste Speichern versucht die eigene Datei erneut). Solange sie dort stehen und
        // «Portfolio in Systemsicherung» aus ist, kommt `watchlist.json` nicht ins Geräte-Backup.
        // Sonst nichts zu tun: das atomare Schreiben hat eine neue Datei ohne Merkmal angelegt
        // (= wieder im Backup), sobald die Schwellen draussen sind.
        if main.portfolioAlarms != nil && !includePortfolioInBackup {
            BackupExclusion.set(excluded: true, for: watchlist)
        }
        return true
    }

    /// `watchlist.json` ist unverschlüsselt und enthält bereits Portfolio-Alarme (Format vor `portfolio_alarms.json`)?
    static func plainWatchlistHoldsPortfolioAlarms(_ url: URL) -> Bool {
        guard let raw = try? Data(contentsOf: url), !ProtectedContainer.isSealed(raw),
              let object = try? JSONSerialization.jsonObject(with: raw) as? [String: Any],
              let list = object["portfolioAlarms"] as? [Any] else { return false }
        return !list.isEmpty
    }

    /// Ergebnis von `writePortfolioAlarms`.
    enum PortfolioAlarmsWrite { case written, needsKey, failed }

    /// Portfolio-Alarme warten auf den Schlüssel (nicht versiegelt speicherbar, nie im Klartext): `AppData` nennt das und
    /// schreibt erneut, sobald die App wieder vorne ist. Gesetzt von `writeFiles`, gelöscht nach dem nächsten Schreiben.
    /// Geschrieben auf der Schreib-Warteschlange (`snapshotWriter`), gelesen von AppData auf dem Main-Thread:
    /// darum hinter einer Sperre statt `nonisolated(unsafe)`.
    static var portfolioAlarmsPending: Bool {
        get { portfolioAlarmsPendingBox.current }
        set { portfolioAlarmsPendingBox.current = newValue }
    }
    private static let portfolioAlarmsPendingBox = LockedBox(false)

    /// Schreibt nur bei geändertem Inhalt (die Merkliste wird mit Live-Kursen alle paar Sekunden
    /// gespeichert) und setzt danach das Backup-Merkmal neu (das atomare Schreiben verliert es). Versiegelt
    /// (`requireSealed`): ohne Schlüssel `needsKey` statt Klartext.
    private static func writePortfolioAlarms(_ file: PortfolioAlarmsFile, to url: URL, includeInBackup: Bool) -> PortfolioAlarmsWrite {
        // Feste Schlüssel-Reihenfolge: sonst kann derselbe Inhalt jedes Mal anders aussehen und der
        // Vergleich unten schreibt unnötig
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        guard let data = try? encoder.encode(file) else { return .failed }
        if ProtectedFile.holds(data, at: url) {
            portfolioAlarmsPending = false
            return .written
        }
        do {
            try ProtectedFile.write(data, to: url, requireSealed: true)
        } catch ProtectedFile.ReadError.locked {
            AppLog.storage.error("Portfolio-Alarme warten auf den Schlüssel")
            return .needsKey
        } catch {
            AppLog.storage.error("Portfolio-Alarme nicht geschrieben")
            return .failed
        }
        portfolioAlarmsPending = false
        BackupExclusion.set(excluded: !includeInBackup, for: url)
        return .written
    }

    /// Kopie einer (ganz oder teilweise) unlesbaren Datei neben dem Original, z. B.
    /// `watchlist.unreadable.json` — bevor ein Schreiben die übersprungenen Einträge verwirft.
    /// Eine schon vorhandene gleiche Kopie bleibt, wie sie ist. Die Kopie der Portfolio-Alarme bleibt
    /// wie die Datei selbst ausserhalb der Geräte-Backups, solange «Portfolio in Systemsicherung» aus ist.
    private static func preserveUnreadable(_ data: Data, of url: URL) {
        let copy = unreadableCopyURL(of: url)
        // Verschlüsselt wie das Original (`ProtectedFile.writeCopy`); eine gleiche Kopie bleibt
        guard (try? ProtectedFile.writeCopy(data, to: copy)) != nil else { return }
        if url.lastPathComponent == portfolioAlarmsURL.lastPathComponent {
            BackupExclusion.set(excluded: !loadSettings().portfolioSystemBackup, for: copy)
        }
    }

    static func unreadableCopyURL(of url: URL) -> URL {
        url.deletingPathExtension().appendingPathExtension("unreadable").appendingPathExtension("json")
    }

    /// Kurse, die ein anderer Prozess (Widget) neuer geschrieben hat, in den Stand der App übernehmen —
    /// nur für dasselbe Paar (gleiche Id und gleiches Paar an derselben Börse), sonst gilt die App.
    static func mergeNewerPrices(from disk: Snapshot, into s: inout Snapshot) {
        let byId = Dictionary(disk.watches.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        for i in s.watches.indices {
            guard let d = byId[s.watches[i].id], d.samePair(as: s.watches[i]) else { continue }
            if d.lastUpdate > s.watches[i].lastUpdate {
                s.watches[i].lastPrice = d.lastPrice
                s.watches[i].previousPrice = d.previousPrice
                s.watches[i].lastUpdate = d.lastUpdate
                s.watches[i].lastError = d.lastError
                s.watches[i].change24h = d.change24h
                s.watches[i].priceChangedAt = d.priceChangedAt
                s.watches[i].exchangeTime = d.exchangeTime
            }
            if d.notifiedAt > s.watches[i].notifiedAt {
                s.watches[i].notifiedPrice = d.notifiedPrice
                s.watches[i].notifiedAt = d.notifiedAt
            }
        }
    }

    /// Auslösungen, die ein anderer Prozess (Widget) gemeldet und gespeichert hat, in den Stand der App
    /// übernehmen — nur für denselben, unveränderten Alarm (gleiches Paar, Bedingung, Schwelle, Fenster,
    /// Wiederholung, Währung) und nur, wenn die Auslösung neuer ist als die der App. Abgeschaltet bleibt,
    /// was der Nutzer in der App abgeschaltet hat; Wieder-scharf-Stellen und Bezüge des anderen Prozesses
    /// holt die nächste Prüfung ohnehin nach.
    static func mergeNewerAlarmTriggers(from disk: Snapshot, into s: inout Snapshot) {
        let byId = Dictionary(disk.alarms.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        for i in s.alarms.indices {
            let app = s.alarms[i]
            guard let d = byId[app.id], d.lastTriggeredAt > app.lastTriggeredAt,
                  d.watchId == app.watchId, d.condition == app.condition, d.threshold == app.threshold,
                  d.windowHours == app.windowHours, d.repeating == app.repeating, d.currency == app.currency
            else { continue }
            s.alarms[i].enabled = app.enabled && d.enabled
            s.alarms[i].referencePrice = d.referencePrice
            s.alarms[i].referenceAt = d.referenceAt
            s.alarms[i].lastTriggeredAt = d.lastTriggeredAt
            s.alarms[i].lastTriggeredPrice = d.lastTriggeredPrice
        }
    }
}
