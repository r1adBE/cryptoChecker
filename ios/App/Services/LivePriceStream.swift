import Foundation
import os

/// Live-Kurse per WebSocket für die SICHTBARE Merkliste — wie `LivePriceStream.kt`.
/// Nur solange die App aktiv ist (scenePhase), die Merkliste zu sehen ist, das Gerät online ist
/// und «Live-Kurse in der App» eingeschaltet ist. Je Börse eine `URLSessionWebSocketTask`
/// (bei vielen Paaren mehrere, siehe `LivePlanner`); Börsen ohne WebSocket bleiben bei REST.
///
/// - Anzeige: `LivePrices` (je Zeile beobachtet, nicht `AppData`) höchstens zweimal je Sekunde
///   (`LiveRules.uiIntervalMillis`).
/// - Speichern: gesammelt alle 10 s über `AppData.applyLive` → `PriceRefresher` (derselbe Weg
///   für Alarme und Kurs-Mitteilungen); Widgets höchstens jede Minute und beim Verlassen.
/// - Neu verbinden mit wachsender Pause (`LiveBackoff`); pausierte Börsen (`ExchangeBackoff`)
///   werden nicht verbunden. Ping je nach Börse als Text, sonst WebSocket-Ping.
///
/// Nur App — nie im Widget oder im Hintergrund.
actor LivePriceStream {
    static let shared = LivePriceStream()

    private struct Running {
        var spec: LiveConnectionSpec
        var task: Task<Void, Never>
    }

    private struct Ended {
        var receivedData: Bool
        var rateLimited: Bool
    }

    // Steuerung
    private var pairs: [LivePair] = []
    private var screenVisible = false
    private var appActive = false
    private var enabled = true
    private var online = true

    // Zustand
    private var buffer = LiveBuffer()
    private var watchIds: [LiveExchange: [String: [Int64]]] = [:]
    private var lastDataAt: [LiveExchange: Int64] = [:]
    private var exchangeNames: [String: String] = [:]
    private var tickAt: [Int64: Int64] = [:]
    /// Für welches Paar jede Watch-Id abonniert ist. Geht mit jedem Kurs an `AppData.applyLive`: ein Kurs des alten
    /// Paars nach «Paar bearbeiten» wird dort verworfen (`LiveRules.samePair`) — wie `pairKeys` (Android).
    private var pairKeys: [Int64: LivePairKey] = [:]
    private var connections: [String: Running] = [:]
    private var loops: Task<Void, Never>?
    private var lastWidgetAt: Int64 = 0
    private var publishedExchanges: [String] = []
    private var publishedLiveIds: Set<Int64> = []
    private var running = false

    /// WebSocket-Ping (Protokoll) für Börsen ohne Text-Ping.
    private static let protocolPingMillis: Int64 = 20_000
    private static let heartbeatCheckNanos: UInt64 = 5_000_000_000

    private let session: URLSession = {
        // Verbindungsaufbau; danach halten Pings die Verbindung offen
        let config = PrivateSessionConfig.make(timeout: 120)
        config.waitsForConnectivity = false
        return URLSession(configuration: config)
    }()

    // MARK: Steuerung

    /// Paare der angezeigten Merkliste (gewählte Gruppe). Gleiche Liste: nichts geschieht.
    func setPairs(_ newPairs: [LivePair]) async {
        guard newPairs != pairs else { return }
        // Gleiche Watch-Id, anderes Paar («Paar bearbeiten»): gepufferte Kurse des alten Paars verwerfen
        let old = Dictionary(pairs.map { ($0.watchId, $0) }, uniquingKeysWith: { first, _ in first })
        let changed = Set(newPairs.filter { pair in old[pair.watchId].map { $0 != pair } ?? false }.map(\.watchId))
        if !changed.isEmpty {
            buffer.retain(Set(old.keys).subtracting(changed))
            tickAt = tickAt.filter { !changed.contains($0.key) }
        }
        pairs = newPairs
        await update()
    }

    /// Merkliste zu sehen bzw. nicht mehr (anderer Tab).
    func setScreenVisible(_ visible: Bool) async {
        guard visible != screenVisible else { return }
        screenVisible = visible
        await update()
    }

    /// App aktiv, Einstellung, Netz — von `AppData`.
    func setEnvironment(appActive: Bool, enabled: Bool, online: Bool) async {
        guard appActive != self.appActive || enabled != self.enabled || online != self.online else { return }
        self.appActive = appActive
        self.enabled = enabled
        self.online = online
        await update()
    }

    private func update() async {
        if screenVisible && appActive && enabled && online && !pairs.isEmpty {
            await reconcile()
        } else {
            await stopAll()
        }
    }

    /// Zähler der Steuerschritte: Ein `stopAll`, das während `await flush` von einem neueren `reconcile`
    /// überholt wurde (Actor-Reentrancy), darf dessen Zustand und Anzeige danach nicht mehr löschen.
    private var generation = 0

    private func reconcile() async {
        generation += 1
        let now = EpochTime.nowMillis
        let paused = Set(SharedStorage.exchangeBackoff.filter { ExchangeBackoff.isPaused($0.value, now: now) }.keys)
        let plan = LivePlanner.plan(pairs, pausedExchangeKeys: paused)
        guard !plan.isEmpty else {
            await stopAll()
            return
        }
        running = true
        watchIds = plan.watchIds
        exchangeNames = Dictionary(pairs.map { ($0.exchangeKey, $0.exchangeName) }, uniquingKeysWith: { first, _ in first })
        var ids = Set<Int64>()
        for bySymbol in plan.watchIds.values {
            for list in bySymbol.values { ids.formUnion(list) }
        }
        buffer.retain(ids)
        tickAt = tickAt.filter { ids.contains($0.key) }
        pairKeys = Dictionary(pairs.filter { ids.contains($0.watchId) }.map { ($0.watchId, $0.key) },
                              uniquingKeysWith: { first, _ in first })
        lastDataAt = lastDataAt.filter { plan.watchIds[$0.key] != nil }

        var wanted: [String: LiveConnectionSpec] = [:]
        for spec in plan.connections { wanted[spec.key] = spec }
        // Weggefallene oder geänderte Verbindungen schliessen, neue öffnen
        for (key, current) in connections where wanted[key] != current.spec {
            current.task.cancel()
            connections[key] = nil
        }
        for (key, spec) in wanted where connections[key] == nil {
            let task = Task { await self.runConnection(spec) }
            connections[key] = Running(spec: spec, task: task)
        }
        if loops == nil {
            loops = Task {
                await withTaskGroup(of: Void.self) { group in
                    group.addTask { await self.uiLoop() }
                    group.addTask { await self.dbLoop() }
                }
            }
        }
    }

    private func stopAll() async {
        guard running else { return }
        running = false
        loops?.cancel()
        loops = nil
        for current in connections.values { current.task.cancel() }
        connections.removeAll()
        watchIds = [:]
        lastDataAt = [:]
        tickAt = [:]
        generation += 1
        let mine = generation
        // Zuletzt Empfangenes speichern, damit Widgets und Alarme den neuesten Stand haben
        await flush(force: true)
        // Während des Speicherns neu gestartet? Dann gehören Puffer und Anzeige der neuen Sitzung
        guard mine == generation else { return }
        buffer.clear()
        pairKeys = [:]
        publishedExchanges = []
        publishedLiveIds = []
        await MainActor.run { AppData.shared.clearLive() }
    }

    // MARK: Anzeige und Speichern

    private func uiLoop() async {
        while !Task.isCancelled {
            await Self.sleep(millis: LiveRules.uiIntervalMillis)
            if Task.isCancelled { return }
            await publish()
        }
    }

    private func dbLoop() async {
        while !Task.isCancelled {
            await Self.sleep(millis: LiveRules.dbIntervalMillis)
            if Task.isCancelled { return }
            await flush(force: false)
        }
    }

    private func publish() async {
        let now = EpochTime.nowMillis
        var names = Set<String>()
        for (exchange, at) in lastDataAt where now - at <= LiveRules.freshMillis {
            if let name = exchangeNames[exchange.exchangeKey] { names.insert(name) }
        }
        let exchanges = names.sorted()
        // Paare am Live-Strom: je Paar nach dessen letztem Kurs (`LiveRules.liveIds`)
        let liveIds = LiveRules.liveIds(lastTickAt: tickAt, now: now)
        let quotes = buffer.takeForUi()
        guard quotes != nil || exchanges != publishedExchanges || liveIds != publishedLiveIds else { return }
        publishedExchanges = exchanges
        publishedLiveIds = liveIds
        let ticks = tickAt
        await MainActor.run {
            AppData.shared.setLive(quotes: quotes, exchanges: exchanges, liveIds: liveIds, tickAt: ticks)
        }
    }

    private func flush(force: Bool) async {
        let batch = buffer.takeForDb()
        guard !batch.isEmpty else { return }
        // Zuordnung zum selben Zeitpunkt wie der Stapel (Actor: vor dem ersten `await`)
        let keys = pairKeys
        let now = EpochTime.nowMillis
        let redraw = force || now - lastWidgetAt >= LiveRules.widgetIntervalMillis
        let saved = await AppData.shared.applyLive(batch, pairs: keys, reloadWidgets: redraw)
        if saved {
            if redraw { lastWidgetAt = now }
        } else {
            buffer.markUnsaved(batch.keys)
        }
    }

    /// Nachricht einer Börse verarbeiten; true = mindestens ein Kurs für ein sichtbares Paar.
    private func onText(_ exchange: LiveExchange, _ text: String) -> Bool {
        let ticks = LiveParser.parse(exchange, text)
        guard !ticks.isEmpty, let bySymbol = watchIds[exchange] else { return false }
        let now = EpochTime.nowMillis
        var any = false
        for tick in ticks {
            guard let ids = bySymbol[tick.symbol] else { continue }
            buffer.offer(ids, tick, now: now)
            if tick.price != nil {
                for id in ids { tickAt[id] = now }
                any = true
            }
        }
        if any { lastDataAt[exchange] = now }
        return any
    }

    // MARK: Verbindungen

    /// Schleife einer Verbindung: verbinden, abonnieren, bei Abbruch mit Pause neu verbinden.
    private func runConnection(_ spec: LiveConnectionSpec) async {
        var attempt = 0
        while !Task.isCancelled {
            let now = EpochTime.nowMillis
            if let state = SharedStorage.exchangeBackoff[spec.exchange.exchangeKey],
               ExchangeBackoff.isPaused(state, now: now) {
                // Börse pausiert (zu viele Anfragen): bis dahin kein Verbindungsversuch
                await Self.sleep(millis: min(max(state.pausedUntil - now, 1_000), LiveBackoff.rateLimitedMillis))
                continue
            }
            let startedAt = EpochTime.nowMillis
            let ended = await connectOnce(spec)
            if Task.isCancelled { return }
            // Zurück auf 1 s erst nach stabiler Verbindung; Pause gestreut (wie Android)
            attempt = LiveBackoff.nextAttempt(attempt, receivedData: ended.receivedData,
                                              connectedMillis: EpochTime.nowMillis - startedAt)
            await Self.sleep(millis: LiveBackoff.reconnectDelayMillis(attempt: attempt, rateLimited: ended.rateLimited,
                                                                       random: Double.random(in: 0..<1)))
        }
    }

    private func connectOnce(_ spec: LiveConnectionSpec) async -> Ended {
        guard let url = URL(string: spec.exchange.url) else { return Ended(receivedData: false, rateLimited: false) }
        // User-Agent auch im Handshake, wie bei REST (`ExchangeHTTP`) und Android
        var request = URLRequest(url: url)
        request.setValue(HttpUserAgent.value, forHTTPHeaderField: HttpUserAgent.header)
        let socket = session.webSocketTask(with: request)
        // Ausdrücklich 1 MiB: grössere Nachricht → Fehler → Neuverbindung wie Android
        socket.maximumMessageSize = LiveRules.maxMessageBytes
        socket.resume()
        let clock = LiveClock()
        var received = false

        // Ping der Börse (Text) bzw. WebSocket-Ping; ohne Lebenszeichen neu verbinden
        let heartbeat = Task {
            var lastPing = EpochTime.nowMillis
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: Self.heartbeatCheckNanos)
                if Task.isCancelled { return }
                let now = EpochTime.nowMillis
                // Börse inzwischen pausiert (`ExchangeBackoff`): Verbindung schliessen, die
                // Schleife verbindet erst nach der Pause wieder
                if let state = SharedStorage.exchangeBackoff[spec.exchange.exchangeKey],
                   ExchangeBackoff.isPaused(state, now: now) {
                    socket.cancel(with: .goingAway, reason: nil)
                    return
                }
                if let text = spec.exchange.pingText {
                    if now - lastPing >= spec.exchange.pingIntervalMillis {
                        lastPing = now
                        try? await socket.send(.string(text))
                    }
                    if now - clock.last > LiveRules.staleConnectionMillis {
                        socket.cancel(with: .goingAway, reason: nil)
                        return
                    }
                } else if now - lastPing >= Self.protocolPingMillis {
                    lastPing = now
                    // @Sendable: URLSession ruft die Antwort auf der eigenen Warteschlange (nicht auf diesem Akteur)
                    socket.sendPing { @Sendable error in
                        if error != nil { socket.cancel(with: .goingAway, reason: nil) }
                    }
                }
                // Abo abgelehnt oder ins Leere: Pongs halten die Verbindung offen, aber kein Kurs kommt — neu verbinden
                if LiveRules.silentSubscription(subscribedAt: clock.subscribedAt, receivedData: clock.priced, now: now) {
                    socket.cancel(with: .goingAway, reason: nil)
                    return
                }
            }
        }

        do {
            for message in spec.exchange.subscribeMessages(spec.symbols) {
                try await socket.send(.string(message))
            }
            clock.markSubscribed()
            while !Task.isCancelled {
                let message = try await withTaskCancellationHandler {
                    try await socket.receive()
                } onCancel: {
                    socket.cancel(with: .goingAway, reason: nil)
                }
                clock.touch()
                let text: String
                switch message {
                case .string(let value): text = value
                case .data(let data): text = String(decoding: data, as: UTF8.self)
                @unknown default: continue
                }
                if onText(spec.exchange, text) {
                    received = true
                    clock.markPriced()
                }
            }
        } catch {
            // Getrennt (Netz, Server, abgebrochen): die Schleife entscheidet über den nächsten Versuch
        }
        heartbeat.cancel()
        let status = (socket.response as? HTTPURLResponse)?.statusCode
        socket.cancel(with: .goingAway, reason: nil)
        return Ended(receivedData: received, rateLimited: status == 429 || status == 418)
    }

    private static func sleep(millis: Int64) async {
        try? await Task.sleep(nanoseconds: UInt64(max(0, millis)) * 1_000_000)
    }
}

/// Zeitpunkt der letzten Nachricht einer Verbindung, des Abonnierens und ob schon ein Kurs kam (threadsicher;
/// Empfang und Wächter, `LiveRules.silentSubscription`).
private final class LiveClock: Sendable {
    private struct State: Sendable {
        var last = EpochTime.nowMillis
        /// Zeit des Abonnierens; 0 = noch nicht abonniert.
        var subscribedAt: Int64 = 0
        var priced = false
    }

    private let state = OSAllocatedUnfairLock(initialState: State())

    var last: Int64 { state.withLock { $0.last } }

    /// Zeit des Abonnierens; 0 = noch nicht abonniert.
    var subscribedAt: Int64 { state.withLock { $0.subscribedAt } }

    /// Mindestens ein Kurs für ein sichtbares Paar seit dem Verbinden.
    var priced: Bool { state.withLock { $0.priced } }

    func touch() {
        state.withLock { $0.last = EpochTime.nowMillis }
    }

    func markSubscribed() {
        state.withLock { $0.subscribedAt = EpochTime.nowMillis }
    }

    func markPriced() {
        state.withLock { $0.priced = true }
    }
}
