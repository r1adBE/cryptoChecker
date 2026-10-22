import Foundation
import UserNotifications

/// Kurs-Mitteilungen mit Obergrenze (Teil von `Notifier`) — wie Android `AppNotifier.showPrices`: höchstens
/// `NotificationBudget.maxPrice` einzeln, der Rest in EINER Sammelmeldung «+N weitere Kurse» im Thread «prices».
/// Eigene Datei, weil Abgleich mit den zugestellten Mitteilungen, Plan und Sammelmeldung zusammen lesbar sein sollen.
extension Notifier {
    static let priceOverflowId = "price-overflow"
    static let userInfoOverflowIds = "overflowIds"
    static let userInfoOverflowLines = "overflowLines"

    /// Kurs-Mitteilungen `watches` zeigen bzw. aktualisieren (ein Durchlauf oder eine Handlung); Reihenfolge =
    /// Vorrang für freie Plätze. `cancelled`: eben entfernte Paare (fallen auch aus der Sammelmeldung). Alle
    /// gemeldeten Paare zählen als gezeigt — einzeln oder in der Sammelmeldung, wie auf Android.
    static func showPrices(_ watches: [Watch], cancelled: [Int64] = []) async {
        let posts = distinctWatches(watches.filter { $0.lastPrice != nil })
        guard !posts.isEmpty || !cancelled.isEmpty else { return }
        let removed = Set(cancelled)
        await PriceRunGate.shared.run { await showPricesNow(posts, cancelled: removed) }
    }

    /// `showPrices`, nacheinander je Prozess (`PriceRunGate`).
    private static func showPricesNow(_ posts: [Watch], cancelled: Set<Int64>) async {
        let state = PriceDeliveredState(await center.deliveredNotifications())
        // Nur Entfernen und keine Sammelmeldung offen: nichts zu tun (häufigster Fall bei Paaren ohne Mitteilung)
        if posts.isEmpty && !state.overflowShown { return }
        let plan = NotificationBudget.planPrices(
            shown: state.prices.filter { !cancelled.contains($0) },
            overflowBefore: state.overflowIds.filter { !cancelled.contains($0) },
            posts: posts.map(\.id),
            slots: NotificationBudget.priceSlots(otherActive: state.other))
        // Zuerst Platz schaffen, dann melden
        if !plan.demoted.isEmpty { center.removeDeliveredNotifications(withIdentifiers: plan.demoted.map(priceId)) }
        let byId = Dictionary(posts.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        for id in plan.individual { if let watch = byId[id] { showPrice(watch) } }
        // Zeilen: frischer Kurs, sonst die bisherige Zeile (Sammelmeldung bzw. verschobene Einzelmeldung)
        var ids: [Int64] = []
        var lines: [String] = []
        for id in plan.overflow {
            let line = byId[id].flatMap(priceLine) ?? state.overflowLines[id] ?? state.titles[id]
            guard let line, !line.isEmpty else { continue }
            ids.append(id)
            lines.append(line)
        }
        if ids.isEmpty {
            if state.overflowShown { center.removeDeliveredNotifications(withIdentifiers: [priceOverflowId]) }
            return
        }
        // Unverändert: nicht neu melden (sonst rückte die Sammelmeldung bei jedem Durchlauf nach oben)
        let linesBefore = state.overflowIds.compactMap { state.overflowLines[$0] }
        if state.overflowShown && ids == state.overflowIds && lines == linesBefore { return }
        postOverflow(ids: ids, lines: lines)
    }

    /// Zeile der Sammelmeldung = Titel der Kurs-Mitteilung: «BTC: 98’450 USDT • Binance».
    static func priceLine(_ watch: Watch) -> String? {
        guard let price = watch.lastPrice else { return nil }
        return L("notification_price_title", watch.baseAsset, PriceFormat.price(price), watch.quoteAsset, watch.marketName)
    }

    /// Sammelmeldung «+N weitere Kurse»; Paare und Zeilen stehen in `userInfo` (der nächste Durchlauf liest sie dort).
    private static func postOverflow(ids: [Int64], lines: [String]) {
        let content = UNMutableNotificationContent()
        content.title = L("notification_price_overflow_title", count: ids.count)
        content.body = NotificationBudget.overflowBody(lines)
        content.sound = nil
        content.threadIdentifier = "prices"
        content.userInfo = [userInfoOverflowIds: ids.map { NSNumber(value: $0) }, userInfoOverflowLines: lines]
        content.interruptionLevel = .passive
        center.add(UNNotificationRequest(identifier: priceOverflowId, content: content, trigger: nil)) { _ in }
    }

    private static func distinctWatches(_ watches: [Watch]) -> [Watch] {
        var seen = Set<Int64>()
        return watches.filter { seen.insert($0.id).inserted }
    }
}

/// Zugestellte Mitteilungen, gelesen für den Plan der Kurs-Mitteilungen (rein, testbar über `init(entries:)`).
struct PriceDeliveredState {
    /// Einzelne Kurs-Mitteilungen (Paar-ids), neueste zuerst.
    var prices: [Int64] = []
    /// Titel je einzelner Kurs-Mitteilung (Zeile, falls sie in die Sammelmeldung wandert).
    var titles: [Int64: String] = [:]
    var overflowShown = false
    var overflowIds: [Int64] = []
    var overflowLines: [Int64: String] = [:]
    /// Andere offene Mitteilungen (Alarme, Markt …).
    var other = 0

    struct Entry {
        var identifier: String
        var date: Date
        var title: String
        var userInfo: [AnyHashable: Any]
    }

    init(_ delivered: [UNNotification]) {
        self.init(entries: delivered.map {
            Entry(identifier: $0.request.identifier, date: $0.date, title: $0.request.content.title,
                  userInfo: $0.request.content.userInfo)
        })
    }

    init(entries: [Entry]) {
        let pricePrefix = "price-"
        for entry in entries.sorted(by: { $0.date > $1.date }) {
            if entry.identifier == Notifier.priceOverflowId {
                overflowShown = true
                let ids = (entry.userInfo[Notifier.userInfoOverflowIds] as? [NSNumber])?.map(\.int64Value) ?? []
                let lines = entry.userInfo[Notifier.userInfoOverflowLines] as? [String] ?? []
                overflowIds = ids
                for (i, id) in ids.enumerated() where i < lines.count { overflowLines[id] = lines[i] }
            } else if entry.identifier.hasPrefix(pricePrefix),
                      let id = Int64(entry.identifier.dropFirst(pricePrefix.count)) {
                prices.append(id)
                titles[id] = entry.title
            } else {
                other += 1
            }
        }
    }
}

/// Hält Kurs-Durchläufe eines Prozesses nacheinander (wie Android `priceRunLock`): sonst plante ein zweiter Durchlauf
/// mit dem Stand vor den Meldungen des ersten und zeigte mehr als `maxPrice` einzeln.
final class PriceRunGate: @unchecked Sendable {
    static let shared = PriceRunGate()
    private let lock = NSLock()
    private var tail: Task<Void, Never>?

    /// Paare, die noch aus der Sammelmeldung müssen (`Notifier.cancelPrice`); nur unter `lock`.
    private var overflowRemovals: [Int64] = []

    /// Paar aus der Sammelmeldung entfernen — gesammelt: Ein Aufruf je Paar (z. B. 2000 beim Löschen vieler Paare)
    /// plant nur EINEN Abgleich, der alle bis dahin gemeldeten Paare mitnimmt.
    func removeFromOverflow(_ watchId: Int64) {
        let first: Bool = lock.withLock {
            overflowRemovals.append(watchId)
            return overflowRemovals.count == 1
        }
        guard first else { return }
        Task {
            // Kurz warten: die übrigen Aufrufe desselben Vorgangs kommen noch dazu
            try? await Task.sleep(nanoseconds: 50_000_000)
            let ids: [Int64] = self.lock.withLock {
                defer { self.overflowRemovals = [] }
                return self.overflowRemovals
            }
            await Notifier.showPrices([], cancelled: ids)
        }
    }

    func run(_ body: @escaping @Sendable () async -> Void) async {
        await enqueue(body).value
    }

    /// Hängt `body` hinter den laufenden Durchlauf (synchron unter der Sperre).
    private func enqueue(_ body: @escaping @Sendable () async -> Void) -> Task<Void, Never> {
        lock.withLock {
            let previous = tail
            let task = Task {
                await previous?.value
                await body()
            }
            tail = task
            return task
        }
    }
}
