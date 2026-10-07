import Foundation

/// Empfindlichkeit von «Ungewöhnliche Aktivität» (Einstellung «Empfindlichkeit») — wie
/// `ActivitySensitivity.kt`. `factor` skaliert alle ⚡-Schwellen (Kurs-z, Mindestbewegung,
/// Volumen, Funding, Open Interest): grösser = strenger, also weniger Meldungen. Gilt an
/// EINER Stelle (`SignalThresholds.severity(of:)`) für Karte, ⚡ an den Zeilen und Meldungen.
/// Rohwerte = Namen der Android-Sicherung.
enum ActivitySensitivity: String, CaseIterable, Codable, Sendable {
    /// Strenger: Schwellen ×1,5, in der Karte nur die 3 stärksten.
    case LESS
    /// Bisherige Schwellen (Standard).
    case NORMAL
    /// Lockerer: Schwellen ×0,75.
    case MORE

    var factor: Double {
        switch self {
        case .LESS: return 1.5
        case .NORMAL: return 1.0
        case .MORE: return 0.75
        }
    }

    /// Höchstens so viele Coins in der Karte «Hier passiert gerade etwas»; nil = alle.
    var maxCardCoins: Int? { self == .LESS ? 3 : nil }

    /// Gespeicherter Name; unbekannt oder fehlend (ältere Sicherung) = `.NORMAL`.
    static func from(name: String?) -> ActivitySensitivity {
        name.flatMap { ActivitySensitivity(rawValue: $0) } ?? .NORMAL
    }
}

/// ⚡-Schwellen einer Empfindlichkeit; `normal` = die Werte aus `ActivityAnalyzer`.
struct SignalThresholds: Equatable, Sendable {
    var priceZ: Double
    var priceMinMovePercent: Double
    var priceStrongZ: Double
    var priceStrongMovePercent: Double
    var volumeRatio: Double
    var volumeStrongRatio: Double
    var fundingPercent: Double
    var fundingStrongPercent: Double
    var oiPercent: Double
    var oiStrongPercent: Double

    static let normal = SignalThresholds(
        priceZ: ActivityAnalyzer.signalPriceZ,
        priceMinMovePercent: ActivityAnalyzer.signalPriceMinMovePercent,
        priceStrongZ: ActivityAnalyzer.signalPriceStrongZ,
        priceStrongMovePercent: ActivityAnalyzer.signalPriceStrongMovePercent,
        volumeRatio: ActivityAnalyzer.signalVolumeRatio,
        volumeStrongRatio: ActivityAnalyzer.signalVolumeStrongRatio,
        fundingPercent: ActivityAnalyzer.signalFundingPercent,
        fundingStrongPercent: ActivityAnalyzer.signalFundingStrongPercent,
        oiPercent: ActivityAnalyzer.signalOiPercent,
        oiStrongPercent: ActivityAnalyzer.signalOiStrongPercent
    )

    static func of(_ sensitivity: ActivitySensitivity) -> SignalThresholds {
        normal.scaled(sensitivity.factor)
    }

    /// Alle Schwellen mal `factor`.
    func scaled(_ factor: Double) -> SignalThresholds {
        guard factor != 1 else { return self }
        return SignalThresholds(
            priceZ: priceZ * factor,
            priceMinMovePercent: priceMinMovePercent * factor,
            priceStrongZ: priceStrongZ * factor,
            priceStrongMovePercent: priceStrongMovePercent * factor,
            volumeRatio: volumeRatio * factor,
            volumeStrongRatio: volumeStrongRatio * factor,
            fundingPercent: fundingPercent * factor,
            fundingStrongPercent: fundingStrongPercent * factor,
            oiPercent: oiPercent * factor,
            oiStrongPercent: oiStrongPercent * factor
        )
    }

    /// Stärke eines (möglichen) Signals unter diesen Schwellen; nil = nicht auffällig.
    /// Werte wie in `ActivitySignal` (Kurs: value = Bewegung %, factor = |z|).
    func severity(of signal: ActivitySignal) -> ActivitySignalSeverity? {
        let value = abs(signal.value)
        // Ungültige Werte (NaN) nie als auffällig werten — wie früher die direkten Vergleiche
        if value.isNaN || signal.factor?.isNaN == true { return nil }
        switch signal.kind {
        case .PRICE_MOVE:
            // Ältere gespeicherte Signale ohne z: nur nach der Bewegung beurteilen
            let z = signal.factor.map { abs($0) }
            if let z, z < priceZ { return nil }
            if value < priceMinMovePercent { return nil }
            if let z, z >= priceStrongZ { return .STRONG }
            return value >= priceStrongMovePercent ? .STRONG : .NOTABLE
        case .VOLUME_SPIKE:
            return Self.grade(signal.value, notable: volumeRatio, strong: volumeStrongRatio)
        case .OPEN_INTEREST_JUMP:
            return Self.grade(value, notable: oiPercent, strong: oiStrongPercent)
        case .FUNDING_EXTREME:
            return Self.grade(value, notable: fundingPercent, strong: fundingStrongPercent)
        }
    }

    private static func grade(_ value: Double, notable: Double, strong: Double) -> ActivitySignalSeverity? {
        if value >= strong { return .STRONG }
        if value >= notable { return .NOTABLE }
        return nil
    }
}
