import Darwin
import Foundation

/// Misst App-Start → erstes Bild der Merkliste (aus dem Zwischenspeicher): kalt ab Prozessstart,
/// sonst ab dem Start der App-Oberfläche (`AppStartTiming`). Nur lokal (Bericht «Ablauf»),
/// keine Telemetrie. Wie `StartupClock.kt`.
enum AppStartClock {
    nonisolated(unsafe) private static var uiCreatedAt: Int64 = 0
    nonisolated(unsafe) private static var pending = false

    /// `CryptoCheckerApp.init`.
    static func onAppInit() {
        uiCreatedAt = nowMillis()
        pending = true
    }

    /// Einmal je Start: Dauer bis jetzt; nil = schon gemeldet oder unplausibel.
    @MainActor static func onFirstWatchlistFrame() -> Int64? {
        guard pending else { return nil }
        pending = false
        return AppStartTiming.elapsed(processStart: processStartMillis(), uiCreated: uiCreatedAt, firstFrame: nowMillis())
    }

    private static func nowMillis() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    /// Startzeit dieses Prozesses (Wanduhr, ms); nil, wenn nicht lesbar.
    private static func processStartMillis() -> Int64? {
        var info = kinfo_proc()
        var size = MemoryLayout<kinfo_proc>.stride
        var mib: [Int32] = [CTL_KERN, KERN_PROC, KERN_PROC_PID, getpid()]
        guard sysctl(&mib, u_int(mib.count), &info, &size, nil, 0) == 0 else { return nil }
        let start = info.kp_proc.p_un.__p_starttime
        return Int64(start.tv_sec) * 1000 + Int64(start.tv_usec) / 1000
    }
}
