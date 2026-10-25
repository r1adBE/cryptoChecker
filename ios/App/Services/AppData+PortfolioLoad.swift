import Foundation

// Portfolio beim Start im Hintergrund lesen (Teil von `AppData`): Das erste Bild wartet nicht auf Entschlüsseln und
// Dekodieren von `portfolio.json`. Jeder Zugriff vorher (`portfolio`, `portfolioFile`, `portfolioIntact`) liest den
// Stand sofort selbst (`ensurePortfolioLoaded`, wie bisher beim Start) — es gibt keinen sichtbaren «noch nicht
// gelesenen» Stand, und kein Schreiben kann einen ungelesenen Stand überschreiben. Portfolio-Alarme und das
// Portfolio-Widget lesen die Datei ohnehin selbst (`PortfolioWidgetStore`).

/// Ergebnis eines Lesens von `portfolio.json` (abseits des Hauptfadens möglich).
struct PortfolioRead: Sendable {
    let file: PortfolioFile
    let intact: Bool
    /// Transaktionen neueste zuerst (`PortfolioStore.newestFirst`).
    let newestFirst: [PortfolioTx]
    /// Bytes der Datei, wenn Einträge unlesbar waren (`PortfolioRestoreNotice`); sonst nil.
    let restoreData: Data?

    /// Liest und sortiert (ohne Hauptfaden-Bezug).
    static func read() -> PortfolioRead {
        let loaded = PortfolioStore.loadChecked()
        let raw = loaded.file.dropped > 0 ? try? ProtectedFile.read(PortfolioStore.url) : nil
        return PortfolioRead(file: loaded.file, intact: loaded.intact,
                             newestFirst: PortfolioStore.newestFirst(loaded.file.transactions), restoreData: raw)
    }
}

/// Stand des Portfolios in `AppData`; `loaded` false = noch nicht gelesen (dann gelten die Werte nicht).
struct PortfolioLoadState {
    var file = PortfolioFile()
    /// false = `portfolio.json` war beim Lesen da, aber nicht (ganz) lesbar: vor jedem Schreiben neu lesen bzw. die Datei
    /// sichern, statt sie mit einem leeren Stand zu überschreiben.
    var intact = true
    /// Transaktionen, neueste zuerst (wie `PortfolioDao.observeAll`).
    var list: [PortfolioTx] = []
    private(set) var loaded = false

    /// Gelesenen Stand übernehmen — nur den ersten: Ein späteres Lesen (z. B. das im Hintergrund nach einem Zugriff)
    /// ersetzt nie einen schon übernommenen oder inzwischen geänderten Stand. true = übernommen.
    mutating func adopt(_ read: PortfolioRead) -> Bool {
        guard !loaded else { return false }
        file = read.file
        intact = read.intact
        list = read.newestFirst
        loaded = true
        return true
    }
}

extension AppData {
    /// Portfolio-Transaktionen, neueste zuerst (wie `PortfolioDao.observeAll`). Veröffentlicht jede Zuweisung wie
    /// `@Published`.
    var portfolio: [PortfolioTx] {
        get {
            ensurePortfolioLoaded()
            return portfolioState.list
        }
        set {
            ensurePortfolioLoaded()
            objectWillChange.send()
            portfolioState.list = newValue
        }
    }

    var portfolioFile: PortfolioFile {
        get {
            ensurePortfolioLoaded()
            return portfolioState.file
        }
        set {
            ensurePortfolioLoaded()
            portfolioState.file = newValue
        }
    }

    /// false = `portfolio.json` war beim Lesen da, aber nicht (ganz) lesbar (`PortfolioLoadState.intact`).
    var portfolioIntact: Bool {
        get {
            ensurePortfolioLoaded()
            return portfolioState.intact
        }
        set {
            ensurePortfolioLoaded()
            portfolioState.intact = newValue
        }
    }

    /// Gelesen und nicht (ganz) lesbar — ohne das Lesen auszulösen (noch nicht gelesen: false; das Lesen im Hintergrund
    /// prüft danach selbst, `adoptPortfolioRead`).
    var portfolioUnreadable: Bool { portfolioState.loaded && !portfolioState.intact }

    /// Noch nicht gelesen: jetzt sofort lesen (Hauptfaden, wie bisher beim Start). Ohne Veröffentlichung — vorher konnte
    /// niemand einen anderen Stand sehen (jeder Zugriff kommt hier vorbei). Zuerst das Lesen beim Start (es verschlüsselt
    /// die Dateien vor dem ersten Lesen, `LaunchRead`).
    func ensurePortfolioLoaded() {
        guard !portfolioState.loaded else { return }
        ensureSnapshotLoaded()
        guard !portfolioState.loaded else { return }
        adoptPortfolioRead(PortfolioRead.read())
    }

    /// Nach dem Lesen beim Start (`finishLaunchLoad`): `portfolio.json` abseits des Hauptfadens lesen und übernehmen,
    /// falls bis dahin niemand zugegriffen hat.
    func startPortfolioPrefetch() {
        Task { [weak self] in
            let read = await Task.detached(priority: .userInitiated) { PortfolioRead.read() }.value
            guard let self, self.adoptPortfolioRead(read) else { return }
            // Nicht (ganz) lesbar: Ein Nachlesen (Entsperren, Rückkehr nach vorne) sah den Stand vielleicht noch als
            // ungelesen — jetzt nachholen, sobald die geschützten Daten zugänglich sind
            if !read.intact { self.reloadProtectedFilesIfNeeded() }
        }
    }

    /// Gelesenen Stand übernehmen (nur den ersten) samt der Folge wie bisher beim Start: unlesbare Einträge einmal
    /// nennen. true = übernommen.
    @discardableResult
    private func adoptPortfolioRead(_ read: PortfolioRead) -> Bool {
        guard portfolioState.adopt(read) else { return false }
        // Unlesbare Einträge (z. B. aus einem Geräte-Backup) nicht still verwerfen: beim nächsten Öffnen
        // des Portfolios einmal nennen (`PortfolioRestoreNotice`)
        if let raw = read.restoreData { PortfolioRestoreNotice.record(dropped: read.file.dropped, fileData: raw) }
        return true
    }
}
