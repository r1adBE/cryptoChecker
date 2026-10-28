import XCTest
@testable import CryptoChecker

/// `PortfolioCostPartPool` gegen die einfache Liste der Herkunftsteile (`ReferenceParts`: jeder Abgang schreibt alle Teile
/// neu): zufällige Folgen mit Seed aus Zugängen, Teil- und Ganz-Abgängen und Zugängen zu früheren Tagen. Gleiche Zahl
/// und Zeitpunkte der Teile, Summen exakt, Beträge bis auf die Rundung der 34. Stelle; kurze Folgen mit runden Zahlen
/// exakt gleich (wie die Paritäts-Fixtures). Dazu die Umrechnung grosser Ansichten ohne Auslesen (`weightedSum`,
/// `anyTime`, `timeBounds`) gegen die Rechnung Teil für Teil. Wie `PortfolioCostPartPoolTest.kt` (Android).
final class PortfolioCostPartPoolTests: XCTestCase {
    private static let hour: Int64 = 3_600_000
    private static let day: Int64 = 86_400_000
    /// Teile weichen nur in der Rundung ab (Beträge bis 10⁸ USDT, gemessen ≈ 10⁻²⁶).
    private static let tolerance = Decimal(string: "1E-18")!
    /// Umrechnung grosser Ansichten gegen Teil für Teil (gemessen ≈ 10⁻³³).
    private static let relative = Decimal(string: "1E-28")!
    private static let nice = ["0.5", "0.25", "0.1", "0.2", "0.75", "0.125"].map { Decimal(string: $0)! }
    private static let utc = TimeZone(identifier: "UTC")!

    /// Referenzrechnung: Liste, die jeder Abgang Teil für Teil neu schreibt; mit `zone` je Tag ein Teil.
    private struct ReferenceParts {
        let zone: TimeZone?
        var parts: [PortfolioCostPart] = []
        var dayIndex: [LocalDay: Int] = [:]

        mutating func add(_ time: Int64, _ usdt: Decimal) {
            guard usdt != 0 else { return }
            guard let zone else {
                parts.append(PortfolioCostPart(time: time, usdt: usdt))
                return
            }
            let day = LocalDay(date: Date(timeIntervalSince1970: Double(time) / 1000), timeZone: zone)
            if let i = dayIndex[day] {
                parts[i] = PortfolioCostPart(time: parts[i].time, usdt: parts[i].usdt + usdt)
            } else {
                dayIndex[day] = parts.count
                parts.append(PortfolioCostPart(time: time, usdt: usdt))
            }
        }

        mutating func take(_ removed: Decimal, total: Decimal) -> [PortfolioCostPart] {
            guard removed != 0, !parts.isEmpty else { return [] }
            if removed >= total {
                let all = parts
                parts = []
                dayIndex = [:]
                return all
            }
            var out: [PortfolioCostPart] = []
            var left = removed
            for i in parts.indices {
                let p = parts[i]
                let r = i == parts.count - 1 ? left : PortfolioDecimal.divide(p.usdt, removed, by: total)
                left -= r
                out.append(PortfolioCostPart(time: p.time, usdt: r))
                parts[i] = PortfolioCostPart(time: p.time, usdt: p.usdt - r)
            }
            return out
        }
    }

    private static func sum(_ parts: [PortfolioCostPart]) -> Decimal { parts.reduce(Decimal(0)) { $0 + $1.usdt } }

    private static func randomAmount(_ rng: inout PerformanceRandom) -> Decimal {
        let units = Decimal(1 + rng.nextInt(100_000_000))
        let scale = Decimal(sign: .plus, exponent: -rng.nextInt(9), significand: 1)
        return units * scale + Decimal(string: "0.001")!
    }

    private static func randomFraction(_ rng: inout PerformanceRandom) -> Decimal {
        PortfolioDecimal.divide(Decimal(1 + rng.nextInt(999)), Decimal(1_000 + rng.nextInt(7)))
    }

    private func assertParts(_ label: String, _ expected: [PortfolioCostPart], _ actual: [PortfolioCostPart],
                             exact: Bool, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(expected.count, actual.count, "\(label) Anzahl", file: file, line: line)
        for (i, pair) in zip(expected, actual).enumerated() {
            XCTAssertEqual(pair.0.time, pair.1.time, "\(label) Teil \(i) Zeitpunkt", file: file, line: line)
            let diff = abs(pair.0.usdt - pair.1.usdt)
            if exact {
                XCTAssertEqual(diff, 0, "\(label) Teil \(i) \(pair.0.usdt) / \(pair.1.usdt)", file: file, line: line)
            } else {
                XCTAssertLessThanOrEqual(diff, Self.tolerance, "\(label) Teil \(i)", file: file, line: line)
            }
        }
        if !expected.isEmpty {
            // Decimal hat 38 Stellen: Summen in anderer Reihenfolge runden ab der 38. Stelle anders
            let sumDiff = abs(Self.sum(expected) - Self.sum(actual))
            if exact {
                XCTAssertEqual(sumDiff, 0, "\(label) Summe", file: file, line: line)
            } else {
                XCTAssertLessThanOrEqual(sumDiff, Self.tolerance, "\(label) Summe", file: file, line: line)
            }
        }
    }

    /// Pool gegen Referenzrechnung über `steps` zufällige Schritte; `nice`: runde Beträge und Anteile.
    private func compare(seed: UInt64, steps: Int, zone: TimeZone?, nice: Bool, exact: Bool) {
        var rng = PerformanceRandom(seed: seed)
        var reference = ReferenceParts(zone: zone)
        var pool = PortfolioCostPartPool(zone: zone)
        var cost: Decimal = 0
        var time: Int64 = 1_700_000_000_000
        for step in 0..<steps {
            time += Self.hour + Int64(rng.nextInt(Int(2 * Self.day - Self.hour)))
            let roll = rng.nextInt(100)
            if roll < 45 || cost == 0 {
                // Zugang; mit Zeitzone ab und zu zu einem früheren Tag (zusammengefasster Teil)
                let usdt = nice ? Decimal(50 * (1 + rng.nextInt(100))) : Self.randomAmount(&rng)
                let earlier = zone != nil && rng.nextInt(5) == 0
                let at = earlier ? time - Int64(rng.nextInt(Int(20 * Self.day))) : time
                cost += usdt
                reference.add(at, usdt)
                pool.add(at, usdt)
            } else {
                let fraction = nice ? Self.nice[rng.nextInt(Self.nice.count)] : Self.randomFraction(&rng)
                let removed = rng.nextInt(30) == 0 ? cost : PortfolioDecimal.multiply(cost, fraction)
                let expected = reference.take(removed, total: cost)
                let actual = pool.take(removed, total: cost)
                assertParts("Seed \(seed) Schritt \(step)", expected, actual.items, exact: exact)
                cost = removed >= cost ? 0 : cost - removed
            }
        }
        let open = pool.toArray(total: cost)
        assertParts("Seed \(seed) offen", reference.parts, open, exact: exact)
        if !open.isEmpty {
            XCTAssertLessThanOrEqual(abs(Self.sum(open) - cost), Self.tolerance, "Seed \(seed) Summe offen")
        }
    }

    func testMatchesReferenceListOnRandomSequences() {
        for seed in UInt64(1)...24 { compare(seed: seed, steps: 400, zone: nil, nice: false, exact: false) }
        for seed in UInt64(25)...48 { compare(seed: seed, steps: 400, zone: Self.utc, nice: false, exact: false) }
    }

    func testShortSequencesWithRoundNumbersAreExact() {
        // Wie die Paritäts-Fixtures: wenige Schritte, runde Beträge und Anteile → Teil für Teil dieselbe Zahl
        for seed in UInt64(100)...399 {
            compare(seed: seed, steps: 12, zone: seed % 2 == 0 ? Self.utc : nil, nice: true, exact: true)
        }
    }

    func testAPartWithoutLaterTakeKeepsItsExactAmount() {
        var pool = PortfolioCostPartPool()
        pool.add(1, 100)
        pool.add(2, 200)
        // Ein Drittel ab: 33.33…33 und der Rest 66.66…67 (Summe exakt 100)
        let taken = pool.take(100, total: 300)
        XCTAssertEqual(Self.sum(taken.items), 100)
        XCTAssertEqual(taken[0].usdt, Decimal(string: "33.33333333333333333333333333333333"))
        // Neue Teile ohne Abgang danach: exakt ihr Betrag (der letzte Teil ist der Rest zum Einstand)
        let small = Decimal(string: "0.000000012345678901234567")!
        pool.add(3, small)
        pool.add(4, 5)
        let total = 205 + small
        let open = pool.toArray(total: total)
        XCTAssertEqual(open[0].usdt, Decimal(string: "66.66666666666666666666666666666667"))
        XCTAssertEqual(open[2].usdt, small)
        XCTAssertEqual(Self.sum(open), total)
        // Ganz ab: alle Teile, danach leer
        XCTAssertEqual(pool.take(500, total: total).count, 4)
        XCTAssertTrue(pool.isEmpty)
    }

    func testCopiesOfAPoolStayIndependent() {
        // Werttyp wie die einfache Liste: eine Kopie ändert, die andere bleibt
        var a = PortfolioCostPartPool()
        a.add(1, 100)
        a.add(2, 300)
        var b = a
        _ = a.take(200, total: 400)
        b.add(3, 50)
        XCTAssertEqual(a.toArray(total: 200).map(\.usdt), [50, 150])
        XCTAssertEqual(b.toArray(total: 450).map(\.usdt), [100, 300, 50])
        _ = b.take(225, total: 450)
        XCTAssertEqual(b.toArray(total: 225).map(\.usdt), [50, 150, 25])
        XCTAssertEqual(a.toArray(total: 200).map(\.usdt), [50, 150])
    }

    private static let start: Int64 = 1_600_000_000_000

    private static func rate(_ t: Int64) -> Decimal { Decimal(string: "0.9")! + Decimal(Int((t / Self.day) % 13)) / 1000 }

    func testLargeViewsConvertWithoutReadingLikePartByPart() {
        var rng = PerformanceRandom(seed: 7)
        let weight = PortfolioPartWeight { PortfolioCostPartPoolTests.rate($0) }
        // Kurs mit Lücke (fehlender Tag): ab dem ersten Teil dieses Tags fehlt die Umrechnung jeder Ansicht
        let gap = PortfolioPartWeight { t -> Decimal? in
            (t / PortfolioCostPartPoolTests.day) % 97 == 5 ? nil : PortfolioCostPartPoolTests.rate(t)
        }
        let test = PortfolioPartTest { $0 > PortfolioCostPartPoolTests.start + 400 * PortfolioCostPartPoolTests.day }
        for zone in [nil, Self.utc] {
            var pool = PortfolioCostPartPool(zone: zone)
            var cost: Decimal = 0
            var time = Self.start
            var views: [PortfolioCostPartList] = []
            var early: [Int: Decimal?] = [:]
            for _ in 0..<1_500 {
                time += Self.hour + Int64(rng.nextInt(Int(Self.day - Self.hour)))
                if rng.nextInt(100) < 55 || cost == 0 {
                    let usdt = Self.randomAmount(&rng)
                    cost += usdt
                    pool.add(time, usdt)
                } else {
                    let removed = PortfolioDecimal.multiply(cost, Self.randomFraction(&rng))
                    let view = pool.take(removed, total: cost)
                    cost -= removed
                    // Teils sofort umrechnen (der Pool läuft weiter), teils erst am Ende: dasselbe Ergebnis
                    if view.count > PortfolioCostPartList.exactMax && rng.nextInt(2) == 0 {
                        early[views.count] = .some(view.weightedSum(weight))
                    }
                    views.append(view)
                }
            }
            XCTAssertGreaterThan(views.filter { $0.count > PortfolioCostPartList.exactMax }.count, 100)
            var missing = 0
            for (i, view) in views.enumerated() {
                let parts = PortfolioCostPartList(view.items)
                let fast = view.weightedSum(weight)
                let slow = parts.weightedSum(weight)
                if let first = early[i] { XCTAssertEqual(first, fast, "früh gelesen \(i)") }
                if let slow, let fast {
                    let rel = abs(PortfolioDecimal.divide(slow - fast, slow))
                    XCTAssertLessThanOrEqual(rel, Self.relative, "Ansicht \(i): \(slow) / \(fast)")
                } else {
                    XCTFail("Kurs fehlt \(i)")
                }
                XCTAssertEqual(parts.contains { test.of($0.time) }, view.anyTime(test), "Prüfung \(i)")
                XCTAssertEqual(parts.timeBounds, view.timeBounds, "Zeitpunkte \(i)")
            }
            // Zweiter Durchgang mit dem anderen Gewicht (die laufende Summe gilt je Gewicht)
            for (i, view) in views.enumerated() {
                let gapSlow = PortfolioCostPartList(view.items).weightedSum(gap)
                XCTAssertEqual(gapSlow == nil, view.weightedSum(gap) == nil, "fehlender Kurs \(i)")
                if gapSlow == nil { missing += 1 }
            }
            XCTAssertTrue(missing > 0 && missing < views.count, "auch fehlende Kurse geprüft: \(missing)")
            XCTAssertTrue(views.contains { $0.anyTime(test) } && !views.allSatisfy { $0.anyTime(test) },
                          "Prüfung beide Ausgänge")
        }
    }
}
