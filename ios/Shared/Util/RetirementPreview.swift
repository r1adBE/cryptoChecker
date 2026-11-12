#if DEBUG
import Foundation

/// Debug-Schalter «Stichtag vorziehen» (Entwickler-Einstellungen, nur im Debug-Build): stellt die Uhr der
/// Stilllegungs-Regel vor (`ExchangeRetirement.debugOffsetMillis`) — nicht die des Geräts, damit TLS-Zertifikate und alle
/// übrigen Börsen normal weiterlaufen. Stufen: aus → Ankündigung (kurz nach dem 1. Januar 2027) → Stichtag (kurz nach
/// dem 1. Februar 2027) → aus. Gespeichert wird der Versatz zur Gerätezeit (`SharedStorage.defaults`, die Uhr der Regel
/// läuft weiter); App und Widgets setzen ihn beim Start (`apply`). Die Apple Watch folgt nicht (eigenes Gerät); sie
/// bekommt vom iPhone nur «kein Kurs». Wie Android `RetirementPreview`.
enum RetirementPreview {
    /// Stufen des Schalters mit dem Zeitpunkt, auf den die Uhr der Regel springt (nil = Gerätezeit).
    enum Step: Int, CaseIterable {
        case off, notice, end

        var target: Int64? {
            switch self {
            case .off: return nil
            case .notice: return ExchangeRetirement.bitpandaNoticeMillis + RetirementPreview.margin
            case .end: return ExchangeRetirement.bitpandaEndMillis + RetirementPreview.margin
            }
        }
    }

    static let stepKey = "debug_retirement_preview_step"
    static let offsetKey = "debug_retirement_preview_offset"
    /// Kurz nach dem Zeitpunkt (eine Minute), damit die Regel sicher umgeschaltet hat.
    static let margin: Int64 = 60_000

    /// Gespeicherten Versatz anwenden (App- und Widget-Start).
    static func apply() {
        ExchangeRetirement.debugOffsetMillis = (SharedStorage.defaults.object(forKey: offsetKey) as? NSNumber)?.int64Value ?? 0
    }

    /// Aktuelle Stufe.
    static var current: Step { Step(rawValue: SharedStorage.defaults.integer(forKey: stepKey)) ?? .off }

    /// Nächste Stufe wählen und die Uhr der Regel gleich darauf stellen; liefert die neue Stufe.
    @discardableResult
    static func next() -> Step {
        let step = Step(rawValue: (current.rawValue + 1) % Step.allCases.count) ?? .off
        let device = Int64((Date().timeIntervalSince1970 * 1000).rounded())
        let offset = step.target.map { $0 - device } ?? 0
        SharedStorage.defaults.set(step.rawValue, forKey: stepKey)
        SharedStorage.defaults.set(NSNumber(value: offset), forKey: offsetKey)
        ExchangeRetirement.debugOffsetMillis = offset
        return step
    }
}
#endif
