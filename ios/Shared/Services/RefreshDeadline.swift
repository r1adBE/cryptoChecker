import Foundation

/// Feste Endzeit eines Durchlaufs (Hintergrund-Aktualisierung: iOS gibt rund 30 s). Wird an alle
/// Schritte weitergereicht — Kurse, Tages-Bezüge, Alarm-Daten, Lease, Nebenprüfungen —, damit keiner
/// über das Ende hinaus wartet. Ohne Endzeit (App im Vordergrund) gilt keine Grenze.
/// Nur iOS: Android (WorkManager) hat rund 10 Minuten.
struct RefreshDeadline: Sendable, Equatable {
    /// Bleibt immer für Speichern und Abschluss.
    static let saveReserveMillis: Int64 = 2_000

    /// Endzeit (ms seit 1970).
    let endMillis: Int64

    func remaining(now: Int64 = EpochTime.nowMillis) -> Int64 { endMillis - now }

    /// Zeit für einen Schritt: höchstens `wanted`, und `reserve` bleibt übrig; 0 = keine Zeit mehr.
    func allowance(wanted: Int64, reserve: Int64 = saveReserveMillis, now: Int64 = EpochTime.nowMillis) -> Int64 {
        max(0, min(wanted, remaining(now: now) - reserve))
    }

    /// Reicht die Restzeit (nach `reserve`) für mindestens `minimum`?
    func allows(_ minimum: Int64, reserve: Int64 = saveReserveMillis, now: Int64 = EpochTime.nowMillis) -> Bool {
        remaining(now: now) - reserve >= minimum
    }

    /// Wartezeit in Nanosekunden: `wanted` ohne Endzeit, sonst begrenzt (`allowance`).
    static func nanos(wanted: Int64, deadline: RefreshDeadline?, now: Int64 = EpochTime.nowMillis) -> UInt64 {
        let millis = deadline?.allowance(wanted: wanted, now: now) ?? wanted
        return UInt64(max(0, millis)) * 1_000_000
    }

    /// Führt `operation` aus, wartet aber höchstens `millis`: danach wird sie abgebrochen und nil geliefert —
    /// auch wenn sie den Abbruch nicht beachtet (dann läuft sie verwaist weiter, das Ergebnis zählt nicht).
    /// Bricht der Aufrufer ab, endet das Warten sofort.
    static func run<T: Sendable>(within millis: Int64, _ operation: @escaping @Sendable () async -> T) async -> T? {
        let work = Task { await operation() }
        let first = FirstResult<T>()
        await withTaskCancellationHandler {
            await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                first.set(continuation)
                Task {
                    let value = await work.value
                    first.finish(value)
                }
                Task {
                    try? await Task.sleep(nanoseconds: UInt64(max(0, millis)) * 1_000_000)
                    first.finish(nil)
                }
            }
        } onCancel: {
            first.finish(nil)
        }
        let value = first.value
        if value == nil { work.cancel() }
        return value
    }
}

/// Zusatzdaten, die manche Alarme brauchen. Jede Stufe ist unabhängig (laufen gleichzeitig); mit Endzeit
/// fällt eine Stufe weg, die nicht mehr anfangen kann oder nicht rechtzeitig fertig wird — ihre Alarme
/// werden diese Runde ausgelassen (bleiben unverändert scharf).
enum AlarmDataStage: CaseIterable, Hashable, Sendable {
    /// Stundenvolumen (Volumen-Spike).
    case volume
    /// Funding/Open Interest.
    case derivatives
    /// Hoch/Tief der Zeiträume («Nahe am Hoch/Tief»).
    case nearExtreme
    /// Faktor in die Alarmwährung («≈ Umrechnung»).
    case convert

    /// Mindestens so viel Restzeit, damit ein Versuch lohnt (Zwischenspeicher antworten sofort,
    /// Netzabfragen schneidet die Endzeit ab).
    var minMillis: Int64 {
        switch self {
        case .convert: return 2_000
        case .volume, .derivatives, .nearExtreme: return 3_000
        }
    }

    /// Stufe, die `alarm` braucht; nil = nur der Kurs (Kursmarke, Prozent, Bewegung ohne Umrechnung).
    static func needed(for alarm: Alarm) -> AlarmDataStage? {
        if alarm.condition.isDerivatives { return .derivatives }
        if alarm.condition.isNearExtreme { return .nearExtreme }
        if alarm.condition == .VOLUME_SPIKE { return .volume }
        if alarm.convertCurrency != nil { return .convert }
        return nil
    }

    /// Braucht `alarm` mehr als den Kurs?
    static func needsExtraData(_ alarm: Alarm) -> Bool { needed(for: alarm) != nil }

    /// Welche der benötigten Stufen laufen: ohne Grenze (`allowanceMillis` nil) alle, sonst nur die,
    /// deren Mindestzeit noch hineinpasst.
    static func plan(_ needed: Set<AlarmDataStage>, allowanceMillis: Int64?) -> Set<AlarmDataStage> {
        guard let allowanceMillis else { return needed }
        return needed.filter { $0.minMillis <= allowanceMillis }
    }
}

/// Erstes Ergebnis gewinnt (Ende der Arbeit, Zeitgrenze oder Abbruch); setzt die Fortsetzung genau einmal fort.
final class FirstResult<T>: @unchecked Sendable { // @unchecked: veränderlicher Zustand nur unter `lock` (NSLock)
    private let lock = NSLock()
    private var continuation: CheckedContinuation<Void, Never>?
    private var done = false
    private var stored: T?

    var value: T? {
        lock.lock(); defer { lock.unlock() }
        return stored
    }

    func set(_ continuation: CheckedContinuation<Void, Never>) {
        lock.lock()
        if done {
            lock.unlock()
            continuation.resume()
            return
        }
        self.continuation = continuation
        lock.unlock()
    }

    func finish(_ value: T?) {
        lock.lock()
        guard !done else { lock.unlock(); return }
        done = true
        stored = value
        let pending = continuation
        continuation = nil
        lock.unlock()
        pending?.resume()
    }
}
