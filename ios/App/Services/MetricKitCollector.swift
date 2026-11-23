import Foundation
import MetricKit

/// Abonniert die Berichte von iOS über Abstürze, Hänger und Enden der App (MetricKit) und legt sie als Codes in
/// `ProcessExitLog` ab — nur auf dem Gerät, nichts wird gesendet. Der Diagnosebericht zeigt sie unter «Process exits»,
/// wie Android (ApplicationExitInfo). Absturzberichte liefert iOS beim nächsten Start, Ende-Zähler einmal am Tag.
/// `@unchecked Sendable`: kein veränderlicher Zustand; MetricKit ruft auf beliebigen Threads.
final class MetricKitCollector: NSObject, MXMetricManagerSubscriber, @unchecked Sendable {
    static let shared = MetricKitCollector()

    /// Beim Start: abonnieren und schon gelieferte Berichte übernehmen.
    func start() {
        let manager = MXMetricManager.shared
        manager.add(self)
        ProcessExitLog.add(manager.pastDiagnosticPayloads.flatMap(Self.entries))
    }

    func didReceive(_ payloads: [MXDiagnosticPayload]) {
        ProcessExitLog.add(payloads.flatMap(Self.entries))
    }

    func didReceive(_ payloads: [MXMetricPayload]) {
        ProcessExitLog.add(payloads.flatMap(Self.entries))
    }

    private static func millis(_ date: Date) -> Int64 { Int64(date.timeIntervalSince1970 * 1000) }

    static func entries(_ payload: MXDiagnosticPayload) -> [ProcessExitEntry] {
        let at = millis(payload.timeStampEnd)
        var out: [ProcessExitEntry] = []
        for crash in payload.crashDiagnostics ?? [] {
            out.append(ProcessExitLog.crash(at: at, exceptionType: crash.exceptionType?.intValue, signal: crash.signal?.intValue))
        }
        for hang in payload.hangDiagnostics ?? [] {
            out.append(ProcessExitLog.timed(at: at, kind: "HANG", seconds: hang.hangDuration.converted(to: .seconds).value))
        }
        for cpu in payload.cpuExceptionDiagnostics ?? [] {
            out.append(ProcessExitLog.timed(at: at, kind: "CPU_EXCEPTION", seconds: cpu.totalCPUTime.converted(to: .seconds).value))
        }
        for _ in payload.diskWriteExceptionDiagnostics ?? [] {
            out.append(ProcessExitLog.timed(at: at, kind: "DISK_WRITE_EXCEPTION", seconds: nil))
        }
        for launch in payload.appLaunchDiagnostics ?? [] {
            out.append(ProcessExitLog.timed(at: at, kind: "SLOW_LAUNCH", seconds: launch.launchDuration.converted(to: .seconds).value))
        }
        return out
    }

    static func entries(_ payload: MXMetricPayload) -> [ProcessExitEntry] {
        guard let exits = payload.applicationExitMetrics else { return [] }
        let at = millis(payload.timeStampEnd)
        let fg = exits.foregroundExitData
        let bg = exits.backgroundExitData
        let foreground = [
            "MEMORY_LIMIT": fg.cumulativeMemoryResourceLimitExitCount, "BAD_ACCESS": fg.cumulativeBadAccessExitCount,
            "ABNORMAL": fg.cumulativeAbnormalExitCount, "ILLEGAL_INSTRUCTION": fg.cumulativeIllegalInstructionExitCount,
            "WATCHDOG": fg.cumulativeAppWatchdogExitCount
        ]
        let background = [
            "MEMORY_LIMIT": bg.cumulativeMemoryResourceLimitExitCount, "MEMORY_PRESSURE": bg.cumulativeMemoryPressureExitCount,
            "BAD_ACCESS": bg.cumulativeBadAccessExitCount, "ABNORMAL": bg.cumulativeAbnormalExitCount,
            "ILLEGAL_INSTRUCTION": bg.cumulativeIllegalInstructionExitCount, "WATCHDOG": bg.cumulativeAppWatchdogExitCount,
            "CPU_LIMIT": bg.cumulativeCPUResourceLimitExitCount, "LOCKED_FILE": bg.cumulativeSuspendedWithLockedFileExitCount,
            "TASK_TIMEOUT": bg.cumulativeBackgroundTaskAssertionTimeoutExitCount
        ]
        return ProcessExitLog.exits(at: at, scope: "foreground", counts: foreground)
            + ProcessExitLog.exits(at: at, scope: "background", counts: background)
    }
}
