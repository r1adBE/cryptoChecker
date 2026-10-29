import Foundation

// CSV-Import in AppData: importierte Transaktionen und Verknüpfungen gespeicherter Seiten abseits des Haupt-Threads
// zusammenstellen (mit Fortschritt und abbrechbar) und dann in EINEM Schreibvorgang speichern, ebenfalls abseits des
// Haupt-Threads. Eigene Datei, weil
// `AppData+Portfolio.swift` an der Längengrenze ist (wie `PortfolioRepository.insertImported` auf Android).

/// Ergebnis von `AppData.insertImportedPortfolioTxs(_:batch:links:fallbacks:progress:)`.
enum PortfolioImportWrite: Equatable {
    /// Geschrieben: Anzahl angelegter Transaktionen (0 = alle schon da) und verknüpfter gespeicherter Seiten
    /// (`linked`, wie Android `CsvImportWritten`).
    case written(Int, linked: Int = 0)
    /// Nicht geschrieben (gesperrte bzw. nicht lesbare Datei): der Import meldet den Fehler statt «0 importiert».
    case failed
    /// «Abbrechen» vor dem Schreiben: nichts gespeichert.
    case cancelled

    /// Geschrieben (auch 0 neue Transaktionen) — gilt auch nach einem «Abbrechen» während des Schreibens.
    var isWritten: Bool {
        if case .written = self { return true }
        return false
    }
}

/// Ergebnis des losgelösten Schreibens in `AppData.setPortfolioFileInBackground`.
struct PortfolioDiskWrite: Sendable {
    let result: PortfolioStore.SaveResult
    /// `portfolio.json` lag nach dem Schreibversuch da (`PortfolioStore.keepsUnsealed`).
    let storedFileExists: Bool
    /// Nummer dieses Schreibens (`PortfolioWriteOrder`).
    let generation: Int
    /// Neueste zuerst (`PortfolioStore.newestFirst`), leer bei `.failed`.
    let sorted: [PortfolioTx]
}

extension AppData {
    /// Schreib-Reihenfolge von `portfolio.json` (synchrone Schreibwege und Import im Hintergrund).
    nonisolated static let portfolioWrites = PortfolioWriteOrder()

    /// Schreibwege im Hintergrund nacheinander (`editPortfolio`, Schreiben des Imports).
    static let portfolioEditGate = PortfolioEditGate()

    /// Ein Schreibweg des Nutzers (Ändern, Löschen, Zurücklegen, Leeren, Import zurücknehmen) auf dem aktuellen Stand:
    /// `edit` (auf dem MainActor, nur Wertkopien) baut den neuen Stand samt Ergebnis; ohne neuen Stand (nil) gilt das
    /// Ergebnis ohne Schreiben. Kodieren, Verschlüsseln, Schreiben, Sortieren und Widget laufen abseits des Haupt-Threads
    /// (`setPortfolioFileInBackground`), der Stand erscheint erst nach dem Schreiben (`present`, z. B. mit Animation).
    /// Nacheinander (`portfolioEditGate`); hat ein synchroner Schreibweg inzwischen geschrieben, wird `edit` auf dem neuen
    /// Stand wiederholt. Nicht geschrieben: `failed` (Grund in `portfolioWriteLocked`).
    func editPortfolio<T>(failed: T, present: (() -> Void) -> Void = { $0() },
                          _ edit: (PortfolioFile) -> (file: PortfolioFile?, result: T)) async -> T {
        await Self.portfolioEditGate.acquire()
        defer { Self.portfolioEditGate.release() }
        for _ in 0..<PortfolioImportSave.maxRounds {
            let generation = Self.portfolioWrites.current
            let (next, result) = edit(currentPortfolioFile())
            guard let next else { return result }
            switch await setPortfolioFileInBackground(next, generation: generation, present: present) {
            case .saved: return result
            case .failed: return failed
            case .superseded: continue
            }
        }
        return failed
    }

    /// Importierte Transaktionen anlegen (gleiche Bereinigung wie von Hand) und gespeicherte Seiten verknüpfter
    /// Umbuchungen ändern (`links`, `withImported`) — wie `PortfolioRepository.insertImportedLinked`. Zeilen, deren
    /// Herkunftsschlüssel (`importKey` oder `linkedImportKey`) schon da ist, werden übersprungen. `batch` = Kennung des
    /// Importlaufs (`PortfolioImportBatch.newId`) für alle angelegten und verknüpften Transaktionen (nil = keine).
    /// Zusammengestellt (Fortschritt gedrosselt an `progress`) und geschrieben wird losgelöst abseits des Haupt-Threads
    /// (`PortfolioImportSave`, `setPortfolioFileInBackground`), in EINEM Schreibvorgang. Abgebrochen (Aufgabe
    /// abgebrochen) vor dem Schreiben: `.cancelled`, nichts geschrieben — es gibt keinen Teil-Import und keine halbe
    /// Verknüpfung.
    func insertImportedPortfolioTxs(
        _ list: [PortfolioTx], batch: String?, links: [CsvStoredTransfer] = [], fallbacks: [String: Int64] = [:],
        progress: @escaping @Sendable (CsvImportProgress) -> Void
    ) async -> PortfolioImportWrite {
        let current: @MainActor () -> (file: PortfolioFile, generation: Int) = { [unowned self] in
            (self.currentPortfolioFile(), AppData.portfolioWrites.current)
        }
        let write: @MainActor (PortfolioFile, Int) async -> PortfolioBackgroundWrite = { [unowned self] file, gen in
            // Nach einem laufenden Schreibweg (`editPortfolio`): dessen Stand ist dann übernommen, die Nummer verrät ihn
            await AppData.portfolioEditGate.acquire()
            defer { AppData.portfolioEditGate.release() }
            return await self.setPortfolioFileInBackground(file, generation: gen)
        }
        return await PortfolioImportSave(current: current, write: write)
            .run(list, batch: batch, links: links, fallbacks: fallbacks, progress: progress)
    }

    /// Wie `setPortfolioFile`, aber Kodieren, Verschlüsseln, Schreiben und Sortieren losgelöst abseits des
    /// Haupt-Threads; auf dem MainActor nur noch der neue Stand (`portfolioFile`, `portfolio`, übernommen in `present`).
    /// Schreibt nur, wenn seit `generation` niemand die Datei geschrieben hat; schreibt nach diesem Schreiben ein anderer,
    /// bevor der Stand übernommen ist, ebenfalls `.superseded` (dessen Stand enthält diese Änderung nicht).
    func setPortfolioFileInBackground(_ file: PortfolioFile, generation: Int,
                                      present: (() -> Void) -> Void = { $0() }) async -> PortfolioBackgroundWrite {
        portfolioWriteLocked = false
        // Nicht (ganz) lesbare Datei nie still überschreiben (wie `setPortfolioFile`; selten, bleibt hier)
        if !portfolioIntact {
            guard PortfolioStore.preserveUnreadable(includeInBackup: settings.portfolioSystemBackup) else {
                portfolioWriteLocked = true
                return .failed
            }
            portfolioIntact = true
        }
        let include = settings.portfolioSystemBackup
        let disk = await Task.detached(priority: .userInitiated) { () -> PortfolioDiskWrite? in
            guard let done = AppData.portfolioWrites.write(ifUnchangedSince: generation, {
                (save: PortfolioStore.save(file, includeInBackup: include),
                 stored: FileManager.default.fileExists(atPath: PortfolioStore.url.path))
            }) else { return nil }
            let sorted = done.result.save == .failed ? [] : PortfolioStore.newestFirst(file.transactions)
            return PortfolioDiskWrite(result: done.result.save, storedFileExists: done.result.stored,
                                      generation: done.generation, sorted: sorted)
        }.value
        guard let disk, AppData.portfolioWrites.current == disk.generation else { return .superseded }
        switch disk.result {
        case .saved: portfolioSavePending = SharedStorage.portfolioAlarmsPending || watchedAddressSavePending
        case .needsKey:
            guard PortfolioStore.keepsUnsealed(storedFileExists: disk.storedFileExists) else {
                portfolioWriteLocked = true
                return .failed
            }
            portfolioSavePending = true
        case .failed: return .failed
        }
        present {
            portfolioFile = file
            portfolio = disk.sorted
        }
        updatePortfolioWidgetInBackground(file.transactions)
        return .saved
    }

    /// Portfolio-Widget mit den bekannten Kursen nachführen, abseits des Haupt-Threads; die Marke der Reihenfolge
    /// (`OrderedSerialRunner`) gilt ab hier, wo der Stand erfasst ist — eine später erfasste Aufnahme gewinnt.
    func updatePortfolioWidgetInBackground(_ transactions: [PortfolioTx]) {
        let currency = settings.portfolioCurrency
        let ticket = PortfolioWidgetStore.updates.ticket()
        Task.detached(priority: .utility) {
            let all = AddressBalance.withAddresses(transactions, addresses: WatchedAddressStore.load().addresses)
            let prices = PortfolioPriceSource.cached(all.flatMap(\.coins))
            PortfolioWidgetStore.update(ticket: ticket, transactions: all, prices: prices, currency: currency,
                                        rate: FxRateSource.cached(currency))
        }
    }

    /// `base` samt den bereinigten importierten Zeilen aus `list` (neue Ids wie `PortfolioStore.insert` mit Id 0, ohne
    /// für jede Zeile die ganze Liste zu durchsuchen); Zeilen mit schon vorhandenem Herkunftsschlüssel übersprungen.
    /// Dazu die gespeicherten Seiten verknüpfter Umbuchungen (`links`, im Lauf `batch`): inzwischen gelöschte, schon
    /// verknüpfte oder nicht speicherbare fallen weg, und ihre aufgehende neue Zeile (`fallbacks`: Herkunftsschlüssel →
    /// Id der gespeicherten Seite, `CsvTransferMatch.fallbacks`) wird dann als eigene Zeile angelegt statt verloren zu
    /// gehen; sonst bleibt sie weg (`list` enthält sie, `CsvTransferMatch.withFallbacks`). Wie Android
    /// `insertImportedLinked`. `onRow` zählt weggelassene Ersatzzeilen mit. Rein, für den Hintergrund. Wirft
    /// `CsvImportCancelled`, wenn `cancelled` vor einer Zeile wahr ist (`base` bleibt).
    nonisolated static func withImported(_ list: [PortfolioTx], batch: String?, in base: PortfolioFile,
                                         links: [CsvStoredTransfer] = [], fallbacks: [String: Int64] = [:],
                                         cancelled: () -> Bool,
                                         onRow: (Int) -> Void) throws -> (file: PortfolioFile, count: Int, linked: Int) {
        var file = base
        // Verknüpfungen zuerst auf dem Stand vor dem Anlegen (wie Android innerhalb derselben Datenbank-Transaktion)
        var linkedIds = Set<Int64>()
        for u in links {
            guard let i = file.transactions.firstIndex(where: { $0.id == u.id }),
                  file.transactions[i].linkedImportKey == nil,
                  let clean = cleanedPortfolioTx(PortfolioCsvImporter.linked(file.transactions[i], u, batch: batch ?? ""))
            else { continue }
            file.transactions[i] = clean
            linkedIds.insert(u.id)
        }
        let rows = list.filter { tx in tx.importKey.flatMap { fallbacks[$0] }.map { !linkedIds.contains($0) } ?? true }
        let left = list.count - rows.count
        var known = importKeys(file.transactions)
        var maxId = file.transactions.map(\.id).max() ?? 0
        let count = try CsvImportInsert.run(rows, known: &known, keys: { [$0.importKey, $0.linkedImportKey].compactMap { $0 } },
                                            cancelled: cancelled, onRow: { onRow($0 + left) }) { tx in
            var item = tx
            item.id = 0
            if let batch { item.importBatch = batch }
            guard var clean = cleanedPortfolioTx(item) else { return false }
            // Wie `PortfolioStore.insert` mit Id 0: nächste freie Id (grösser als alle vorhandenen), angehängt
            clean.id = max(file.nextId, maxId + 1)
            file.transactions.append(clean)
            file.nextId = max(file.nextId, clean.id + 1, maxId + 1)
            maxId = clean.id
            return true
        }
        return (file: file, count: count, linked: linkedIds.count)
    }
}
