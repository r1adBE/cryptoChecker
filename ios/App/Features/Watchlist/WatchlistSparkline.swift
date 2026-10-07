import SwiftUI

// MARK: Daten

/// Mini-Charts der Merkliste — wie `SparklineRepository.kt`: 24 Stundenkurse
/// (Schlusskurse) je Basis-Asset gegen USDT. Gezeigt wird nur die Form, deshalb
/// reicht die USDT-Reihe für jede Quote-Währung. Gleiche Quelle wie das
/// Einzel-Widget (`CandleDataSource` mit Ausweich-Kette); geladen über `DayReferenceStore`,
/// der dieselben Kerzen für die 24-h-Veränderung der Pille nutzt.
///
/// Nur im Speicher, 15 Minuten gültig; höchstens vier Abrufe gleichzeitig.
/// Fehlschläge werden kürzer gemerkt, damit ein Paar ohne Kerzen nicht bei
/// jedem Scrollen neu abgefragt wird.
actor WatchlistSparklineStore {
    static let shared = WatchlistSparklineStore()

    /// Gültigkeit einer geladenen Reihe; die Zeile lädt danach neu.
    static let ttlNanos: UInt64 = 15 * 60 * 1_000_000_000
    private static let ttlMillis: Int64 = 15 * 60_000
    private static let failureTtlMillis: Int64 = 5 * 60_000
    private static let maxParallel = 4
    private static let quote = "USDT"

    private var cache: [String: (time: Int64, closes: [Double]?)] = [:]
    private var inFlight: [String: Task<[Double]?, Never>] = [:]
    private var running = 0
    private var waiters: [CheckedContinuation<Void, Never>] = []

    /// Zwischengespeicherte Reihe, auch wenn sie schon etwas alt ist — für den ersten Frame.
    func cached(base: String) -> [Double]? {
        cache[Self.key(base)]?.closes
    }

    /// Frische Reihe (mindestens zwei Werte) oder nil. Gleichzeitige Anfragen
    /// für dasselbe Asset teilen sich einen Abruf.
    func closes(base: String) async -> [Double]? {
        let key = Self.key(base)
        guard !key.isEmpty else { return nil }
        if let entry = cache[key] {
            let age = TimeUtils.nowMillis - entry.time
            let ttl = entry.closes == nil ? Self.failureTtlMillis : Self.ttlMillis
            if age >= 0 && age < ttl { return entry.closes }
        }
        if let pending = inFlight[key] { return await pending.value }

        // Eigene Aufgabe: Bricht die Zeile ab (weggescrollt), wird trotzdem fertig geladen.
        let task = Task { await self.load(key) }
        inFlight[key] = task
        let result = await task.value
        inFlight[key] = nil
        cache[key] = (TimeUtils.nowMillis, result)
        return result
    }

    private func load(_ key: String) async -> [Double]? {
        await acquire()
        defer { release() }
        // Gemeinsame Abfrage mit der 24-h-Veränderung der Pille (gleiche Kerzen, ein Abruf)
        let closes = await DayReferenceStore.shared.series(base: key, quote: Self.quote)?.closes ?? []
        return closes.count >= 2 ? closes : nil
    }

    // Einfache Zählsperre: höchstens `maxParallel` Abrufe gleichzeitig.
    private func acquire() async {
        if running < Self.maxParallel {
            running += 1
            return
        }
        await withCheckedContinuation { continuation in
            waiters.append(continuation)
        }
        // Der Platz wurde von `release()` direkt übergeben — `running` bleibt gleich.
    }

    private func release() {
        if waiters.isEmpty {
            running -= 1
        } else {
            waiters.removeFirst().resume()
        }
    }

    private static func key(_ base: String) -> String {
        base.trimmingCharacters(in: .whitespaces).uppercased()
    }
}

// MARK: Ansicht

/// Linie der Reihe, ohne Achsen. In einem sehr kleinen Rahmen (ausgeblendet) nichts.
struct WatchlistSparklineShape: Shape {
    let values: [Double]

    /// Punkte der Linie im Rahmen; leer, wenn nichts zu zeichnen ist.
    static func points(_ values: [Double], in rect: CGRect) -> [CGPoint] {
        guard values.count >= 2, rect.width >= 8, rect.height >= 4,
              let min = values.min(), let max = values.max() else { return [] }
        let range = max - min
        let inset: CGFloat = 1.5
        let w = rect.width - inset * 2
        let h = rect.height - inset * 2
        return values.enumerated().map { i, v in
            let x = rect.minX + inset + CGFloat(i) * w / CGFloat(values.count - 1)
            // Flache Reihe: Linie in der Mitte
            let y = range > 0 ? rect.minY + inset + h - CGFloat((v - min) / range) * h : rect.midY
            return CGPoint(x: x, y: y)
        }
    }

    func path(in rect: CGRect) -> Path {
        var path = Path()
        let points = Self.points(values, in: rect)
        guard let first = points.first else { return path }
        path.move(to: first)
        for point in points.dropFirst() { path.addLine(to: point) }
        return path
    }
}

/// Fläche unter der Linie bis zum unteren Rand — für die sanfte Füllung.
struct WatchlistSparklineAreaShape: Shape {
    let values: [Double]

    func path(in rect: CGRect) -> Path {
        var path = Path()
        let points = WatchlistSparklineShape.points(values, in: rect)
        guard let first = points.first, let last = points.last else { return path }
        path.move(to: CGPoint(x: first.x, y: rect.maxY))
        for point in points { path.addLine(to: point) }
        path.addLine(to: CGPoint(x: last.x, y: rect.maxY))
        path.closeSubpath()
        return path
    }
}

/// Mini-Chart: Farbe nach Richtung über die ganze Reihe (letzter ≥ erster Wert
/// = steigend), gemäss Einstellung «Kursfarben». Darunter eine sanfte Fläche
/// (Kursfarbe ~12 % → 0 %, hoher Kontrast ~18 %), ohne Achsen und Punkte.
struct WatchlistSparkline: View {
    let values: [Double]
    /// Gezeichneter Anteil von links (0…1) — für «Erst-Hinzufügen»; sonst 1.
    var progress: CGFloat = 1
    @Environment(\.priceColorScheme) private var priceColors
    @Environment(\.priceHighContrast) private var highContrast
    @Environment(\.priceColorsInverted) private var inverted

    var body: some View {
        let up = (values.last ?? 0) >= (values.first ?? 0)
        let color = up ? priceColors.up(highContrast: highContrast, inverted: inverted)
                       : priceColors.down(highContrast: highContrast, inverted: inverted)
        let drawn = min(max(progress, 0), 1)
        ZStack {
            WatchlistSparklineAreaShape(values: values)
                .fill(LinearGradient(colors: [color.opacity(highContrast ? 0.18 : 0.12), color.opacity(0)],
                                     startPoint: .top, endPoint: .bottom))
                // Fläche wächst beim Zeichnen mit der Linie von links
                .mask(alignment: .leading) {
                    GeometryReader { geo in
                        Rectangle().frame(width: geo.size.width * drawn)
                    }
                }
            WatchlistSparklineShape(values: values)
                .trim(from: 0, to: drawn)
                .stroke(color, style: StrokeStyle(lineWidth: 1.75, lineCap: .round, lineJoin: .round))
        }
        // Eigene Beschreibung (24 h, USDT); in der Merkliste steht derselbe Satz
        // im Zeilen-Text, die Zeile fasst ihre Teile zusammen.
        .accessibilityElement()
        .accessibilityLabel(A11y.chart(period: L("widget_range_24h"), values: values))
    }
}

/// Platz für Paar-Spalte und Mini-Chart: Das Chart (56 × 28 pt) erscheint nur,
/// wenn danach für die Paar-Spalte noch `minInfoWidth` bleibt — sonst wird es
/// ausgeblendet (Grösse null). Die Kurs-Spalte daneben bleibt immer unberührt.
/// Erstes Kind: Paar-Spalte; zweites (optional): Mini-Chart.
struct WatchlistSparklineSlot: Layout {
    var minInfoWidth: CGFloat
    var sparkWidth: CGFloat = 56
    /// 28 pt (vorher 22) — bleibt unter der Höhe von Paar-Spalte und Stern (≈ 40 pt),
    /// die Zeile wird dadurch nicht höher.
    var sparkHeight: CGFloat = 28
    var gap: CGFloat = 8

    private func showsSpark(width: CGFloat?, subviews: Subviews) -> Bool {
        guard subviews.count >= 2 else { return false }
        guard let width else { return true }
        return width - sparkWidth - gap >= minInfoWidth
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        guard let info = subviews.first else { return .zero }
        let show = showsSpark(width: proposal.width, subviews: subviews)
        let infoWidth = proposal.width.map { show ? $0 - sparkWidth - gap : $0 }
        let infoSize = info.sizeThatFits(ProposedViewSize(width: infoWidth, height: proposal.height))
        let width = proposal.width ?? (infoSize.width + (show ? sparkWidth + gap : 0))
        return CGSize(width: width, height: max(infoSize.height, show ? sparkHeight : 0))
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard let info = subviews.first else { return }
        let show = showsSpark(width: bounds.width, subviews: subviews)
        let infoWidth = show ? bounds.width - sparkWidth - gap : bounds.width
        info.place(at: CGPoint(x: bounds.minX, y: bounds.midY), anchor: .leading,
                   proposal: ProposedViewSize(width: infoWidth, height: bounds.height))
        if subviews.count >= 2 {
            subviews[1].place(at: CGPoint(x: bounds.maxX, y: bounds.midY), anchor: .trailing,
                              proposal: show ? ProposedViewSize(width: sparkWidth, height: sparkHeight) : .zero)
        }
    }
}
