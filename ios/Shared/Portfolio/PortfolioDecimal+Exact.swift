import Foundation

// Teilung und Multiplikation von `PortfolioDecimal`: exakt über die ganzzahligen Mantissen, einmal gerundet auf
// `significantDigits` gültige Stellen (halbe auf gerade) — wie Android `BigDecimal.divide`/`multiply` mit
// `MathContext.DECIMAL128`. `Decimal` selbst rechnet auf 38 Stellen; erst darauf und dann auf 34 zu runden wiche
// gelegentlich in der letzten Stelle ab (376761.99 / 41.47463084 → …302 statt …303), ebenso ein Produkt über 38 Stellen.

extension PortfolioDecimal {

    /// `a / b`, einmal gerundet — wie `a.divide(b, MathContext.DECIMAL128)` (Android). Auch abgeleitete Preise und
    /// Umrechnungen des CSV-Imports (Android `CsvImport.derive`).
    static func divide(_ a: Decimal, _ b: Decimal) -> Decimal { exact(a, nil, b) }

    /// `a × b / c`, das Produkt exakt, einmal gerundet — wie `a.multiply(b).divide(c, MathContext.DECIMAL128)` (Android).
    /// `divide(a * b, c)` wiche ab, sobald das Produkt mehr als 38 Stellen hat (`Decimal` rundete es vor der Teilung).
    static func divide(_ a: Decimal, _ b: Decimal, by c: Decimal) -> Decimal { exact(a, b, c) }

    /// `a × b`, einmal gerundet — wie `a.multiply(b, MathContext.DECIMAL128)` (Android).
    static func multiply(_ a: Decimal, _ b: Decimal) -> Decimal { exact(a, b, nil) }

    /// `a × (b ?? 1) / (c ?? 1)` exakt, einmal gerundet. NaN, ∞, Teilung durch 0 oder ein Ergebnis ausserhalb des Bereichs
    /// von `Decimal` (bei Werten innerhalb von `withinLimits` nie): mit `Decimal` gerechnet und gerundet.
    private static func exact(_ a: Decimal, _ b: Decimal?, _ c: Decimal?) -> Decimal {
        let operands = [a] + [b, c].compactMap { $0 }
        guard operands.allSatisfy({ !$0.isNaN && $0.isFinite }), c != 0 else { return approximate(a, b, c) }
        if a == 0 || b == 0 { return 0 }
        var x = a
        NSDecimalCompact(&x)
        var numerator = BigMagnitude(mantissaOf: x)
        var exponent = Int(x.exponent)
        if var y = b {
            NSDecimalCompact(&y)
            numerator = numerator.multiplied(by: BigMagnitude(mantissaOf: y))
            exponent += Int(y.exponent)
        }
        var denominator = BigMagnitude(limbs: [1])
        if var z = c {
            NSDecimalCompact(&z)
            denominator = BigMagnitude(mantissaOf: z)
            exponent -= Int(z.exponent)
        }
        let digits = significantDigits
        var q: BigMagnitude
        // Rest gegen die halbe letzte Stelle: −1 darunter, 0 genau die Hälfte, 1 darüber
        let half: Int
        if denominator.isOne {
            // Produkt: Stellen über 34 abschneiden
            let drop = max(0, numerator.digitCount - digits)
            q = numerator
            half = q.dropDigits(drop)
            exponent += drop
        } else {
            // Zähler × 10^shift ÷ Nenner hat 34 oder 35 Stellen vor dem Komma
            let shift = digits + denominator.digitCount - numerator.digitCount
            if shift >= 0 { numerator.scale(shift) } else { denominator.scale(-shift) }
            let division = numerator.divided(by: denominator)
            q = division.quotient
            exponent -= shift
            if q.compare(BigMagnitude.power(digits)) >= 0 {
                let last = q.divide(bySmall: 10)
                exponent += 1
                half = last == 5 ? (division.rest.isZero ? 0 : 1) : (last < 5 ? -1 : 1)
            } else {
                var twice = division.rest
                twice.multiplyAdd(2, 0)
                half = twice.compare(denominator)
            }
        }
        if half > 0 || (half == 0 && q.isOdd) { q.multiplyAdd(1, 1) }
        guard (-128...127).contains(exponent) else { return approximate(a, b, c) }
        let negative = ((a < 0) != ((b ?? 1) < 0)) != ((c ?? 1) < 0)
        var result = Decimal(sign: negative ? .minus : .plus, exponent: exponent, significand: q.decimal)
        NSDecimalCompact(&result)
        return result
    }

    /// Ausweg ohne exakte Rechnung (siehe `exact`): `Decimal`-Arithmetik, danach auf 34 Stellen.
    private static func approximate(_ a: Decimal, _ b: Decimal?, _ c: Decimal?) -> Decimal {
        var r = a
        if let b { r *= b }
        if let c { r /= c }
        return roundSignificant(r)
    }
}

/// Ganze Zahl ohne Vorzeichen beliebiger Grösse (32-Bit-Stücke, das niedrigste zuerst) — nur für `PortfolioDecimal.exact`.
private struct BigMagnitude {
    var limbs: [UInt32]

    /// 10⁰ … 10⁹ (passen in 32 Bit).
    private static let smallPowers: [UInt32] = [1, 10, 100, 1_000, 10_000, 100_000, 1_000_000, 10_000_000, 100_000_000,
                                                1_000_000_000]

    init(limbs: [UInt32]) {
        self.limbs = limbs
        trim()
    }

    /// Mantisse von `value` (Betrag, Exponent unbeachtet).
    init(mantissaOf value: Decimal) {
        let m = value._mantissa
        let length = Int(value._length)
        let words: [UInt16] = [m.0, m.1, m.2, m.3, m.4, m.5, m.6, m.7].enumerated().map { $0.offset < length ? $0.element : 0 }
        self.init(limbs: (0..<4).map { UInt32(words[2 * $0]) | UInt32(words[2 * $0 + 1]) << 16 })
    }

    /// 10^count.
    static func power(_ count: Int) -> BigMagnitude {
        var p = BigMagnitude(limbs: [1])
        p.scale(count)
        return p
    }

    var isZero: Bool { limbs.isEmpty }

    var isOne: Bool { limbs == [1] }

    var isOdd: Bool { (limbs.first ?? 0) & 1 == 1 }

    /// Anzahl Bits (0 für 0).
    private var bitWidth: Int {
        guard let top = limbs.last else { return 0 }
        return limbs.count * 32 - top.leadingZeroBitCount
    }

    /// Anzahl Dezimalziffern (0 für 0). 2^(bits−1) ≤ Zahl < 2^bits: so viele Ziffern wie 2^(bits−1) oder eine mehr.
    var digitCount: Int {
        let bits = bitWidth
        guard bits > 0 else { return 0 }
        // Ziffern von 2^(bits−1) = ⌊(bits−1)·log₁₀2⌋ + 1 (log₁₀2 ≈ 0.30103, genau bis weit über 1000 Bits)
        let low = (bits - 1) * 30_103 / 100_000 + 1
        return compare(.power(low)) >= 0 ? low + 1 : low
    }

    /// Als `Decimal` — exakt, solange die Zahl höchstens 38 Stellen hat (hier höchstens 35).
    var decimal: Decimal {
        limbs.reversed().reduce(Decimal(0)) { $0 * Decimal(4_294_967_296) + Decimal($1) }
    }

    private mutating func trim() {
        while limbs.last == 0 { limbs.removeLast() }
    }

    /// × 10^count.
    mutating func scale(_ count: Int) {
        var left = count
        while left > 0 {
            let n = min(left, 9)
            multiplyAdd(Self.smallPowers[n], 0)
            left -= n
        }
    }

    /// × `factor` + `add` (beide höchstens 10⁹).
    mutating func multiplyAdd(_ factor: UInt32, _ add: UInt32) {
        var carry = UInt64(add)
        for i in limbs.indices {
            let v = UInt64(limbs[i]) * UInt64(factor) + carry
            limbs[i] = UInt32(truncatingIfNeeded: v)
            carry = v >> 32
        }
        if carry > 0 { limbs.append(UInt32(carry)) }
        trim()
    }

    /// Produkt (schriftlich, je 32 Bit).
    func multiplied(by other: BigMagnitude) -> BigMagnitude {
        var out = [UInt32](repeating: 0, count: limbs.count + other.limbs.count)
        for (i, x) in limbs.enumerated() {
            var carry: UInt64 = 0
            for (j, y) in other.limbs.enumerated() {
                // höchstens (2³²−1)² + 2·(2³²−1) = 2⁶⁴−1: kein Überlauf
                let v = UInt64(x) * UInt64(y) + UInt64(out[i + j]) + carry
                out[i + j] = UInt32(truncatingIfNeeded: v)
                carry = v >> 32
            }
            out[i + other.limbs.count] = UInt32(truncatingIfNeeded: carry)
        }
        return BigMagnitude(limbs: out)
    }

    /// ÷ `divisor` (1 … 10⁹), ganzzahlig; gibt den Rest zurück.
    mutating func divide(bySmall divisor: UInt32) -> UInt32 {
        var rest: UInt64 = 0
        for i in limbs.indices.reversed() {
            let v = rest << 32 | UInt64(limbs[i])
            limbs[i] = UInt32(truncatingIfNeeded: v / UInt64(divisor))
            rest = v % UInt64(divisor)
        }
        trim()
        return UInt32(truncatingIfNeeded: rest)
    }

    /// ÷ 10^count, abgeschnitten; der abgeschnittene Teil gegen die Hälfte von 10^count: −1 darunter, 0 genau die
    /// Hälfte, 1 darüber (count 0: −1, nichts abgeschnitten).
    mutating func dropDigits(_ count: Int) -> Int {
        guard count > 0 else { return -1 }
        // Alle Ziffern unter der Rundungsziffer: nur ob eine davon nicht 0 ist
        var sticky = false
        var left = count - 1
        while left > 0 {
            let n = min(left, 9)
            if divide(bySmall: Self.smallPowers[n]) != 0 { sticky = true }
            left -= n
        }
        let last = divide(bySmall: 10)
        return last == 5 ? (sticky ? 1 : 0) : (last < 5 ? -1 : 1)
    }

    /// −1, 0 oder 1 wie `self` < = > `other`.
    func compare(_ other: BigMagnitude) -> Int {
        if limbs.count != other.limbs.count { return limbs.count < other.limbs.count ? -1 : 1 }
        for i in limbs.indices.reversed() where limbs[i] != other.limbs[i] { return limbs[i] < other.limbs[i] ? -1 : 1 }
        return 0
    }

    /// Ganzzahlige Teilung durch `divisor` (> 0): Quotient und Rest. Passt der Teiler in 64 Bit (die meisten Mengen und
    /// Beträge), je 32 Bit auf einmal; sonst schriftlich je 32 Bit mit geschätzter Quotientenziffer (Knuth, Algorithmus D).
    func divided(by divisor: BigMagnitude) -> (quotient: BigMagnitude, rest: BigMagnitude) {
        let n = divisor.limbs.count
        if n <= 2 {
            let d = divisor.limbs.reversed().reduce(UInt64(0)) { $0 << 32 | UInt64($1) }
            var q = [UInt32](repeating: 0, count: limbs.count)
            var rest: UInt64 = 0
            for i in limbs.indices.reversed() {
                // (rest·2³² + Stück) ÷ d: rest < d, also passt der Quotient in 32 Bit
                let step = d.dividingFullWidth((high: rest >> 32, low: rest << 32 | UInt64(limbs[i])))
                q[i] = UInt32(truncatingIfNeeded: step.quotient)
                rest = step.remainder
            }
            return (BigMagnitude(limbs: q), BigMagnitude(limbs: [UInt32(truncatingIfNeeded: rest), UInt32(rest >> 32)]))
        }
        guard limbs.count >= n, let top = divisor.limbs.last else { return (BigMagnitude(limbs: []), self) }
        // Normieren: höchstes Bit des Teilers gesetzt, dann ist die geschätzte Ziffer höchstens 2 zu gross
        let shift = top.leadingZeroBitCount
        let v = Self.shifted(divisor.limbs, shift, extra: false)
        var u = Self.shifted(limbs, shift, extra: true)
        let base: UInt64 = 1 << 32
        var q = [UInt32](repeating: 0, count: limbs.count - n + 1)
        for j in q.indices.reversed() {
            let head = UInt64(u[j + n]) << 32 | UInt64(u[j + n - 1])
            var qhat = head / UInt64(v[n - 1])
            var rhat = head % UInt64(v[n - 1])
            while qhat >= base || qhat * UInt64(v[n - 2]) > (rhat << 32 | UInt64(u[j + n - 2])) {
                qhat -= 1
                rhat += UInt64(v[n - 1])
                if rhat >= base { break }
            }
            // u[j…j+n] −= qhat × v
            var borrow: Int64 = 0
            var carry: UInt64 = 0
            for i in 0..<n {
                let p = qhat * UInt64(v[i]) + carry
                carry = p >> 32
                let t = Int64(u[i + j]) - borrow - Int64(p & 0xFFFF_FFFF)
                u[i + j] = UInt32(truncatingIfNeeded: t)
                borrow = t < 0 ? 1 : 0
            }
            let t = Int64(u[j + n]) - borrow - Int64(carry)
            u[j + n] = UInt32(truncatingIfNeeded: t)
            if t < 0 {
                // Eins zu viel: Teiler zurückzählen
                qhat -= 1
                var sum: UInt64 = 0
                for i in 0..<n {
                    sum = UInt64(u[i + j]) + UInt64(v[i]) + (sum >> 32)
                    u[i + j] = UInt32(truncatingIfNeeded: sum)
                }
                u[j + n] = UInt32(truncatingIfNeeded: UInt64(u[j + n]) + (sum >> 32))
            }
            q[j] = UInt32(truncatingIfNeeded: qhat)
        }
        // Rest = u[0..<n] zurückgeschoben
        let rest = (0..<n).map { i -> UInt32 in
            let pair = UInt64(i + 1 < n ? u[i + 1] : 0) << 32 | UInt64(u[i])
            return UInt32(truncatingIfNeeded: pair >> UInt64(shift))
        }
        return (BigMagnitude(limbs: q), BigMagnitude(limbs: rest))
    }

    /// `limbs` × 2^shift (shift 0 … 31); `extra`: ein Stück mehr für den Übertrag.
    private static func shifted(_ limbs: [UInt32], _ shift: Int, extra: Bool) -> [UInt32] {
        var out: [UInt32] = []
        out.reserveCapacity(limbs.count + 1)
        var carry: UInt32 = 0
        for x in limbs {
            let w = UInt64(x) << UInt64(shift)
            out.append(UInt32(truncatingIfNeeded: w) | carry)
            carry = UInt32(truncatingIfNeeded: w >> 32)
        }
        if extra { out.append(carry) }
        return out
    }
}
