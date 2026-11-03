import Combine
import Foundation
import SwiftUI
import UIKit

/// Hinweis «Logos werden geladen …» unter dem Kopf der Merkliste — nur beim ersten Laden der Logos nach der
/// Installation bzw. nach dem Leeren des Logo-Zwischenspeichers, nie beim täglichen Nachholen gescheiterter Bilder:
/// `visible`, sobald ein solcher Durchgang (`runStarted`) Bilder zu laden hat (`imagesPending` > 0); weg, sobald er
/// endet — fertig, gescheitert oder gebremst —, und in diesem Durchgang nicht wieder. Endet ein Durchgang mit Hinweis,
/// ist das erste Laden erledigt (`runFinished` = true): gemerkt als leere Datei `doneMarker` im Logo-Ordner — sie
/// verschwindet mit dem Ordner und beim Zurücksetzen der App. Coins ganz ohne Logo zählen nie als fehlend. Ändert
/// nichts am Laden selbst. Wie Android `LogoLoadingHint.kt`, gemeinsame Testfälle `Parity/coin_logos.json` ›
/// `loadingHint`.
struct LogoLoadingHint: Equatable, Sendable {
    /// Merk-Datei «erstes Laden erledigt» im Logo-Ordner (kein Logo heisst so: Namen enden auf «.png»).
    static let doneMarker = ".first_load_done"
    /// Laufende Durchgänge (zwei gleichzeitig: weg erst nach dem letzten).
    private(set) var runs = 0
    /// Die laufenden Durchgänge gehören zum ersten Laden (beim ersten Start gelesen).
    private var firstLoad = false
    /// Hinweis zeigen: das erste Laden läuft und hat Bilder zu laden.
    private(set) var visible = false

    /// `firstLoadDone`: das erste Laden ist schon gemerkt (dann nie ein Hinweis).
    mutating func runStarted(firstLoadDone: Bool) {
        if runs == 0 { firstLoad = !firstLoadDone }
        runs += 1
    }

    /// Ein Bild-Abruf des laufenden Durchgangs hat `count` fehlende Bilder; ausserhalb eines Durchgangs nichts.
    mutating func imagesPending(_ count: Int) {
        if runs > 0 && firstLoad && count > 0 { visible = true }
    }

    /// true = das erste Laden ist damit erledigt (Hinweis stand und der letzte Durchgang endet): merken.
    mutating func runFinished() -> Bool {
        if runs > 0 { runs -= 1 }
        guard runs == 0 else { return false }
        let done = visible
        visible = false
        return done
    }
}

/// Stand von `LogoLoadingHint` für die Oberfläche (`WatchlistLogoHint`): Durchgänge von `CoinLogoStore.syncAll`,
/// fehlende Bilder aus `downloadMissing`, Merk-Datei im Logo-Ordner. Wie Android `LogoImageStore.loadingHint`.
@MainActor
final class CoinLogoLoading: ObservableObject {
    static let shared = CoinLogoLoading()
    @Published private(set) var visible = false
    private var hint = LogoLoadingHint()

    private static var markerPath: String {
        CoinLogoStore.directory.appendingPathComponent(LogoLoadingHint.doneMarker).path
    }

    static func runStarted() {
        let done = FileManager.default.fileExists(atPath: markerPath)
        shared.change { $0.runStarted(firstLoadDone: done) }
    }

    static func imagesPending(_ count: Int) { shared.change { $0.imagesPending(count) } }

    static func runFinished() {
        var done = false
        shared.change { done = $0.runFinished() }
        if done { FileManager.default.createFile(atPath: markerPath, contents: Data()) }
    }

    private func change(_ step: (inout LogoLoadingHint) -> Void) {
        step(&hint)
        guard visible != hint.visible else { return }
        // Zeile ein- und ausblenden wie Android (Bewegung reduzieren: ohne Überblenden)
        let animation: Animation? = UIAccessibility.isReduceMotionEnabled ? nil : .easeInOut(duration: 0.3)
        withAnimation(animation) { visible = hint.visible }
    }
}
