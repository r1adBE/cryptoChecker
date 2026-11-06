import Combine
import Foundation
import SwiftUI
import UIKit

/// Hinweis «Logos werden geladen …» unter dem Kopf der Merkliste — nur beim ersten Laden der Logos nach der
/// Installation bzw. nach dem Leeren des Logo-Zwischenspeichers, nie beim täglichen Nachholen gescheiterter Bilder:
/// `visible`, sobald ein solcher Durchgang (`runStarted`) Bilder zu laden hat (`imagesPending` > 0); weg, sobald er
/// endet — fertig, gescheitert oder gebremst —, und in diesem Durchgang nicht wieder. Endet ein Durchgang mit Hinweis,
/// ist das erste Laden erledigt (`runFinished` = true): gemerkt als leere Datei `doneMarker` im Logo-Ordner — sie
/// verschwindet mit dem Ordner und beim Zurücksetzen der App. Coins ganz ohne Logo zählen nie als fehlend.
///
/// Fortschritt «Logos werden geladen · 40 %» (`percent`): erledigte Bilder des Durchgangs (`imageDone`: geladen oder
/// endgültig gescheitert) durch alle zu ladenden — bekannt erst mit der letzten Stufe (`imagesPending` mit `last`,
/// vorher laden noch Seiten der Rangliste: ohne Prozent); höchstens 99 %, solange er läuft (`percentOf`). Bremst ein
/// Bild-Server (`imagesPaused`), bis zum Ende des Durchgangs ohne Prozent (kein stehender Wert). Angezeigt höchstens etwa
/// einmal je Sekunde bzw. je 5 Prozentpunkte (`shouldShow`, `CoinLogoLoading`). Ändert nichts am Laden selbst. Wie
/// Android `LogoLoadingHint.kt`, gemeinsame Testfälle `Parity/coin_logos.json` › `loadingHint` und `loadingPercent`.
struct LogoLoadingHint: Equatable, Sendable {
    /// Merk-Datei «erstes Laden erledigt» im Logo-Ordner (kein Logo heisst so: Namen enden auf «.png»).
    static let doneMarker = ".first_load_done"
    /// Laufende Durchgänge (zwei gleichzeitig: weg erst nach dem letzten).
    private(set) var runs = 0
    /// Die laufenden Durchgänge gehören zum ersten Laden (beim ersten Start gelesen).
    private var firstLoad = false
    /// Hinweis zeigen: das erste Laden läuft und hat Bilder zu laden.
    private(set) var visible = false
    /// Erledigte Bilder des Durchgangs (geladen oder endgültig gescheitert).
    private var done = 0
    /// Alle zu ladenden Bilder des Durchgangs; nil = noch nicht bekannt (Seiten der Rangliste laden noch).
    private var total: Int?
    /// Ein Bild-Server bremst: bis zum Ende des Durchgangs ohne Prozent.
    private var paused = false

    /// Höchstens so oft ein neuer Prozentwert …
    static let showIntervalMillis: Int64 = 1000
    /// … ausser er springt um mindestens so viele Prozentpunkte.
    static let showStep = 5

    /// Fortschritt nach der Regel, ungedrosselt; nil = ohne Prozent.
    var percent: Int? {
        guard visible, !paused, let total else { return nil }
        return Self.percentOf(done: done, total: total)
    }

    /// `firstLoadDone`: das erste Laden ist schon gemerkt (dann nie ein Hinweis).
    mutating func runStarted(firstLoadDone: Bool) {
        if runs == 0 { firstLoad = !firstLoadDone }
        runs += 1
    }

    /// Ein Bild-Abruf des laufenden Durchgangs hat `count` fehlende Bilder; ausserhalb eines Durchgangs nichts.
    /// `last`: die letzte Stufe — damit ist bekannt, wie viele Bilder der Durchgang insgesamt lädt.
    mutating func imagesPending(_ count: Int, last: Bool = false) {
        guard runs > 0 && firstLoad && count > 0 else { return }
        visible = true
        if last { total = done + count }
    }

    /// Ein Bild des Durchgangs ist erledigt: geladen oder endgültig gescheitert (nicht gebremst).
    mutating func imageDone() {
        if visible { done += 1 }
    }

    /// Ein Bild-Server bremst: kein Prozent mehr in diesem Durchgang.
    mutating func imagesPaused() {
        if visible { paused = true }
    }

    /// true = das erste Laden ist damit erledigt (Hinweis stand und der letzte Durchgang endet): merken.
    mutating func runFinished() -> Bool {
        if runs > 0 { runs -= 1 }
        guard runs == 0 else { return false }
        let wasVisible = visible
        visible = false
        done = 0
        total = nil
        paused = false
        return wasVisible
    }

    /// `done` von `total` in ganzen Prozent, abgerundet, 0…99 (100 % zeigt nie — am Ende ist der Hinweis weg).
    static func percentOf(done: Int, total: Int) -> Int {
        guard total > 0 else { return 0 }
        return min(99, max(0, Int(Int64(max(0, done)) * 100 / Int64(total))))
    }

    /// Neuen Wert `next` anzeigen statt `shown` (angezeigt um `shownAt`, ms)? Wechsel von und zu «ohne Prozent»
    /// sofort, sonst höchstens einmal je `showIntervalMillis` oder bei einem Sprung um `showStep` Punkte.
    static func shouldShow(shown: Int?, shownAt: Int64, next: Int?, now: Int64) -> Bool {
        guard next != shown else { return false }
        guard let next, let shown else { return true }
        return now - shownAt >= showIntervalMillis || abs(next - shown) >= showStep
    }
}

/// Stand von `LogoLoadingHint` für die Oberfläche (`WatchlistLogoHint`): Durchgänge von `CoinLogoStore.syncAll`,
/// fehlende Bilder aus `downloadMissing`, Merk-Datei im Logo-Ordner. Wie Android `LogoImageStore.loadingHint`.
@MainActor
final class CoinLogoLoading: ObservableObject {
    static let shared = CoinLogoLoading()
    @Published private(set) var visible = false
    /// Angezeigter Fortschritt (gedrosselt, `LogoLoadingHint.shouldShow`); nil = Hinweis ohne Prozent.
    @Published private(set) var percent: Int?
    private var hint = LogoLoadingHint()
    private var shownAt: Int64 = 0

    private static var markerPath: String {
        CoinLogoStore.directory.appendingPathComponent(LogoLoadingHint.doneMarker).path
    }

    static func runStarted() {
        let done = FileManager.default.fileExists(atPath: markerPath)
        shared.change { $0.runStarted(firstLoadDone: done) }
    }

    static func imagesPending(_ count: Int, last: Bool) { shared.change { $0.imagesPending(count, last: last) } }

    static func imageDone() { shared.change { $0.imageDone() } }

    static func imagesPaused() { shared.change { $0.imagesPaused() } }

    static func runFinished() {
        var done = false
        shared.change { done = $0.runFinished() }
        if done { FileManager.default.createFile(atPath: markerPath, contents: Data()) }
    }

    private func change(_ step: (inout LogoLoadingHint) -> Void) {
        step(&hint)
        // Monotone Uhr (ms) wie die Sperre (`PortfolioLockPolicy.monotonicSeconds`): neuer Prozentwert höchstens etwa
        // einmal je Sekunde, nicht je Bild; nur bei stehender Zeile (beim Ausblenden bleibt der letzte Wert, beim
        // Einblenden gilt sofort der des neuen Durchgangs)
        let now = Int64(PortfolioLockPolicy.monotonicSeconds() * 1000)
        if hint.visible,
           !visible || LogoLoadingHint.shouldShow(shown: percent, shownAt: shownAt, next: hint.percent, now: now) {
            percent = hint.percent
            shownAt = now
        }
        guard visible != hint.visible else { return }
        // Zeile ein- und ausblenden wie Android (Bewegung reduzieren: ohne Überblenden)
        let animation: Animation? = UIAccessibility.isReduceMotionEnabled ? nil : .easeInOut(duration: 0.3)
        withAnimation(animation) { visible = hint.visible }
    }
}
