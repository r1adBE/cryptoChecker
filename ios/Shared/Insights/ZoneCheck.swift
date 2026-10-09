import Foundation

/// Prüft die Bitcoin-Marktphase und meldet einen Wechsel (z. B. Neutral → Bull) —
/// wie `ZoneCheckWorker.doWork`, aber ohne eigene Planung (der Aufrufer
/// entscheidet, wann es läuft). Die zuletzt gesehene Zone liegt in
/// `SharedStorage.defaults`; beim ersten Lauf wird nur gemerkt, nicht gemeldet.
enum ZoneCheck {
    static let keyZone = "last_zone"
    static let keyFearGreed = "last_fear_greed"

    /// - Returns: false, wenn die Marktphase nicht geprüft werden konnte
    ///   (Android: `Result.retry()`), sonst true.
    @discardableResult
    static func run(settings: AppSettings) async -> Bool {
        let defaults = SharedStorage.defaults
        await checkFearGreed(below: settings.fearGreedBelow, above: settings.fearGreedAbove, defaults: defaults)
        if !settings.zoneAlerts {
            // Ausgeschaltet: alte Zone vergessen, damit beim Wiedereinschalten
            // nicht ein längst vergangener Wechsel gemeldet wird.
            defaults.removeObject(forKey: keyZone)
            return true
        }
        do {
            let zone = CycleModel.evaluate(try await CycleDataSource.fetch()).zone
            let last = defaults.string(forKey: keyZone).flatMap { MarketZone(rawValue: $0) }
            if let last, last != zone {
                Notifier.showZoneChange(fromKey: last.labelKey, toKey: zone.labelKey)
            }
            defaults.set(zone.rawValue, forKey: keyZone)
            return true
        } catch {
            return false
        }
    }

    /// Fear & Greed: einmal melden, wenn der Index die eingestellte Grenze
    /// unter- bzw. überschreitet — nicht bei jedem Lauf erneut.
    private static func checkFearGreed(below: Int, above: Int, defaults: UserDefaults) async {
        if below <= 0 && above <= 0 {
            defaults.removeObject(forKey: keyFearGreed)
            return
        }
        guard let value = try? await InsightsDataSource.fearGreed().value else { return }
        let last: Int? = defaults.object(forKey: keyFearGreed) != nil ? defaults.integer(forKey: keyFearGreed) : nil
        if below > 0 && value <= below && (last == nil || (last ?? 0) > below) {
            Notifier.showFearGreed(value: value, below: below)
        } else if above > 0 && value >= above && (last == nil || (last ?? 0) < above) {
            Notifier.showFearGreed(value: value, above: above)
        }
        defaults.set(value, forKey: keyFearGreed)
    }
}
