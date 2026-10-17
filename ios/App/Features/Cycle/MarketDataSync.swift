import Foundation
import SwiftUI

// Gemeinsamer Ablauf jeder Markt-Karte (Teil von `CycleViewModel`): erst den Zwischenspeicher zeigen, dann
// mit Zeitlimit frisch laden, Stand und Quelle merken, Fehler abfangen.
// Eigene Datei, weil alle Lader denselben Ablauf nutzen (wie `MarketDataSync.kt`).

extension CycleViewModel {
    /// Gemeinsamer Ablauf je Bereich:
    /// 1. Noch nichts gezeigt → gespeicherten Stand von der Platte sofort zeigen.
    /// 2. Stand jünger als `ttl` (und nicht erzwungen) → fertig.
    /// 3. Sonst laden: Kreisel nur, wenn nichts gezeigt wird; mit Daten still im Hintergrund.
    ///    Erfolg → zeigen und speichern; Fehler → Gezeigtes bleibt, sonst «gescheitert».
    /// Läuft die Abfrage eines Bereichs schon, startet nur ein erzwungenes Laden neu.
    func load<T: Codable & Sendable>(
        _ key: String,
        ttl: Int64,
        timeout: Double,
        force: Bool,
        minForce: Int64 = 0,
        shown: Bool,
        apply: @escaping @MainActor (CycleLoad<T>) -> Void,
        _ fetch: @escaping @Sendable () async throws -> Sourced<T>
    ) {
        var hasValue = shown
        if !hasValue, let entry = CycleCache.read(key, as: T.self) {
            apply(.loaded(entry.value))
            stamps[key] = entry.savedAt
            providers[key] = entry.provider
            hasValue = true
        }
        // Gezeigt, aber ohne Stand (z. B. Coin gewechselt) zählt wie «nichts bekannt»
        let savedAt = hasValue ? stamps[key] : nil
        // Ohne Netz: Gezeigtes stehen lassen (Pille «Offline · Stand …»), kein vergeblicher Abruf — wie Android
        if savedAt != nil && !NetworkStatus.shared.isOnline { return }
        guard CycleCachePolicy.needsRefresh(savedAt: savedAt, now: TimeUtils.nowMillis, ttl: ttl, force: force,
                                            minForce: minForce)
        else { return }
        if tasks[key] != nil && !force {
            // Läuft schon (z. B. Coin hin und zurück gewechselt): nur den Ladezustand zeigen
            if !hasValue { apply(.loading) }
            return
        }

        tasks[key]?.cancel()
        let generation = (generations[key] ?? 0) + 1
        generations[key] = generation
        if !hasValue { apply(.loading) }
        refreshingKeys.insert(key)
        let keepShown = hasValue
        tasks[key] = Task { [weak self] in
            let value = try? await AsyncTimeout.run(seconds: timeout, fetch)
            guard let self, self.generations[key] == generation else { return }
            self.tasks[key] = nil
            self.refreshingKeys.remove(key)
            if let value {
                let now = TimeUtils.nowMillis
                apply(.loaded(value.value))
                self.stamps[key] = now
                self.providers[key] = value.provider
                CycleCache.write(key, value.value, savedAt: now, provider: value.provider)
            } else if !keepShown {
                apply(.failed)
            }
            // Daten bereit: vielleicht darf die nächste Karte erscheinen
            self.advanceReveal()
        }
    }
}
