import Foundation
import Accessibility
import SwiftUI
import UIKit

/// Abgleich der Paarliste der gewählten Börse: läuft gerade und/oder letzter Fehlertext
/// (Gegenstück zu `PairListSync` in `ExplorerSelection.kt`).
struct ExplorerPairListSync: Equatable {
    var inProgress = false
    var error: String? = nil
}

/// Paarlisten der Suche: `reading` solange die gespeicherten Listen gelesen werden, `missing` Börsen ohne
/// Liste auf dem Gerät, `progress` solange fehlende auf Wunsch laden (wie `SearchLists` in Android).
struct ExplorerSearchLists: Equatable {
    var reading = false
    var missing = 0
    var progress: ExplorerSearchProgress?
}

/// Was die Kursvorschau des gewählten Paars gerade zeigt.
enum ExplorerPriceState {
    case loading
    case loaded(Ticker, MarketPair)
    case failed(String)
}

/// Kurze Meldung am unteren Rand (Android: Snackbar).
struct ExplorerMessage: Equatable, Identifiable {
    let id = UUID()
    let text: String
    /// Knopf «Ansehen» springt zur Merkliste.
    var showView: Bool = true
    /// Bleibt länger sichtbar (z. B. Ergebnis der Sammelaufnahme).
    var long: Bool = false
    /// Gesetzt: statt «Ansehen» der Knopf «Alarm setzen» für diesen Eintrag (erstes Paar, noch ohne Alarm).
    var alarmWatchId: Int64? = nil
    /// Gesetzt: DEX-Suche gescheitert — statt «Ansehen» der Knopf «Erneut versuchen» für diesen Begriff.
    var retryDexQuery: String? = nil
    /// Gesetzt: nach dem Hinzufügen mehrerer Paare statt «Ansehen» der Knopf «Rückgängig» für diese neuen Paare.
    var undoIds: [Int64]? = nil
    /// Ende der Aufnahme zu `undoIds` (ms): «Entfernen» in der Rückfrage prüft damit das Zeitfenster
    /// (`BulkUndoConfirm.undoOffered`).
    var undoFinishedAt: Int64 = 0
    /// Art der Meldung: Rahmen und Symbol (übernommen = Erfolg; gesperrt, voll, DEX-Suche gescheitert = Fehler) — wie
    /// Android `ExplorerFeedback`.
    var kind: SnackbarKind = .info
}

/// Steuerung der Seite «Paar hinzufügen» (Gegenstück zu `ExplorerViewModel.kt`): Coin-Suche (Coin zuerst,
/// dann seine Paare über alle Börsen), «Erweitert / nach Börse» (Börse → Coin → Gegenwährung), Kurs zeigen,
/// mehrere Paare sammeln, DEX-Pools finden.
///
/// Datenschutz der Suche: Sie läuft nur auf dem Gerät — gespeicherte Paarlisten und das Namensverzeichnis.
/// Fehlt die Liste einer Börse, wird sie nicht still geholt; die Seite bietet an, die vollständigen
/// öffentlichen Paarlisten zu laden (`loadMissingLists`) — dieselbe Antwort für alle, nie etwas zu einem Coin.
@MainActor
final class ExplorerViewModel: ObservableObject {
    static let dexKey = "DexScreener"

    let data = AppData.shared

    /// Aus, wenn ein gemerktes Paar bearbeitet wird (`WatchEditSheet`, wie `WatchEditViewModel.kt`):
    /// dann wirkt nur der TradFi-Filter von `PairCache`, nicht «Token & TradFi» der Auswahl.
    let appliesAssetScope: Bool

    /// Jede bekannte Börse ausser der Platzhalter-Börse, alphabetisch (ohne Gross/klein).
    let allMarkets: [Market] = MarketsConfig.all
        .filter { !($0 is UnknownMarket) }
        .sorted { $0.name.lowercased() < $1.name.lowercased() }

    // MARK: Veröffentlichter Zustand – Börse

    @Published var exchange: Market?
    @Published var pairList: MarketPairsInfo?
    @Published var canReloadPairs = false
    @Published var pairListSync = ExplorerPairListSync()

    // MARK: Veröffentlichter Zustand – Paar

    @Published var coins: [String] = []
    @Published var quotes: [String] = []
    @Published var contracts: [FuturesContractType] = []
    @Published var coin: String?
    @Published var quote: String?
    @Published var contract: FuturesContractType?
    @Published var selectedPair: MarketPair?
    @Published var price: ExplorerPriceState?

    // MARK: Veröffentlichter Zustand – Sammelaufnahme

    @Published var bulkQuotes: [String] = []
    @Published var bulkQuote: String?
    @Published var bulkPairs: [MarketPair] = []
    @Published var bulkEmpty = false
    /// Werte für die Schnellfilter von «Alle …-Paare», je Paar-Kennung. Stammen aus genau einer
    /// Sammelabfrage der Börse (nie einzeln je Paar); ohne Sammelabfrage bleibt es leer, Filter fehlen.
    @Published var bulkStats: [String: ExplorerPairStats] = [:]

    // MARK: Veröffentlichter Zustand – Suche

    @Published var searchQuery = ""
    /// Coins zum Suchbegriff (Coin zuerst).
    @Published var coinHits: [ExplorerCoinHit] = []
    /// Einzelne Paare zum Suchbegriff — gezeigt, wenn `showPairHits` (z. B. «eth usdt», «BTCUSDT»).
    @Published var pairHits: [ExplorerSearchHit] = []
    @Published var showPairHits = false
    /// Angetippter Coin und seine Paare über alle geladenen Listen, der Vorschlag zuerst.
    @Published var pickedCoin: ExplorerCoinHit?
    @Published var pickedCoinPairs: [ExplorerSearchHit] = []
    /// Stand der Paarlisten für die Suche (fehlende, Laden).
    @Published var searchLists = ExplorerSearchLists()
    /// Letzte Suchbegriffe (max. 8, jüngster vorne), nur lokal gespeichert.
    @Published var recentSearches: [String] = ExplorerRecentSearches.load()

    // MARK: Veröffentlichter Zustand – DEX

    @Published var dexResults: [DexPool] = []
    @Published var dexSearching = false
    @Published var dexNoResults = false

    @Published var message: ExplorerMessage?

    // MARK: Laufende Arbeiten

    var exchangeLoadTask: Task<Void, Never>?
    var syncTask: Task<Void, Never>?
    var priceTask: Task<Void, Never>?
    /// Paar, für das zuletzt ein Kurs angefordert wurde.
    var priceKey: String?
    var dexTask: Task<Void, Never>?
    var bulkStatsTask: Task<Void, Never>?
    var bulkStatsKey: String?
    var bulkStatsAt = Date.distantPast

    // Suche: gespeicherte Listen aller Börsen (vollständig; gefiltert wird erst bei der Anzeige)
    var searchCache: [String: MarketPairsInfo] = [:]
    var searchMarkets: [Market] = []
    /// Börsen, deren Liste noch nicht auf dem Gerät liegt, die sie aber laden können.
    var unloadedMarkets: [Market] = []
    var pendingListMarkets: [Market] = []
    var listsLoadedCount = 0
    var storedListsTask: Task<Void, Never>?
    var missingListsTask: Task<Void, Never>?
    var searchTask: Task<Void, Never>?
    /// Coin-Namen in Suchform («bitcoin» findet BTC); einmal je Sitzung aus den gespeicherten
    /// Namen gebaut, ohne Abruf (wie `nameIndex` in `ExplorerViewModel.kt`).
    var nameIndex: ExplorerNameIndex = .empty
    var nameIndexTask: Task<Void, Never>?

    init(appliesAssetScope: Bool = true) {
        self.appliesAssetScope = appliesAssetScope
    }

    // MARK: Filter «Token & TradFi»

    /// Welche Börsen und Paare Auswahl, Suche und Sammelaufnahme anbieten.
    var assetScope: AssetScope {
        guard appliesAssetScope else { return .BOTH }
        return data.settings.assetScope
    }

    /// Kürzel der Alpha-Tokens, die verborgen bleiben, solange «inkl. Alpha-Tokens» aus ist (aus der
    /// gespeicherten Logo-Liste, ohne Abruf); leer, wenn es an ist oder ein Paar bearbeitet wird.
    var hiddenAlpha: Set<String> {
        if !appliesAssetScope { return [] }
        return AssetScope.hiddenAlphaSymbols(includeAlpha: data.settings.includeAlphaTokens,
                                             alphaSymbols: CoinLogoStore.alphaSymbols)
    }

    /// Börsen für die Auswahl (bei «Nur TradFi» nur solche mit TradFi-Kontrakten).
    var markets: [Market] {
        let scope = assetScope
        return allMarkets.filter { scope.allowsMarket($0.key) }
    }

    // MARK: Abgeleitete Werte

    var isDex: Bool { exchange?.key == Self.dexKey }
    var hasPairs: Bool { !coins.isEmpty }
    var syncing: Bool { pairListSync.inProgress }
    var pairSelected: Bool { hasPairs && coin != nil && quote != nil }

    /// Kontraktfeld nur für Futures (also nicht bloss «Spot» allein).
    var hasContracts: Bool {
        guard let only = contracts.first else { return false }
        return contracts.count > 1 || only != .none
    }

    /// Wählbar erst, wenn es mehr als eine Laufzeit gibt.
    var contractSelectable: Bool { hasContracts && contracts.count > 1 }

    nonisolated static func contractName(_ type: FuturesContractType) -> String {
        if type == .none { return L("explorer_contract_spot") }
        return type.backupName
    }

    // MARK: Rückmeldung

    /// Das allererste Paar: «BTC/USDT wird jetzt überwacht» bleibt länger stehen und wird für
    /// VoiceOver angesagt; beim nächsten Öffnen der Merkliste spielt deren Zeile ihren Moment ab.
    /// Die Erfolgs-Haptik löst wie immer der Aufrufer aus.
    func showFirstAdd(_ watchId: Int64) {
        let text = data.celebrateFirstAdd(watchId)
        // Hat das Paar noch keinen Alarm, bietet die Meldung «Alarm setzen» an (wie Android)
        let hasAlarm = (data.activeAlarmCounts[watchId] ?? 0) != 0
        show(ExplorerMessage(text: text, long: true, alarmWatchId: hasAlarm ? nil : watchId, kind: .success))
        AccessibilityNotification.Announcement(text).post()
    }

    func show(_ note: ExplorerMessage) {
        withAnimation(.spring(duration: 0.35)) { message = note }
    }

    func haptic(_ event: HapticEvent) {
        Haptics.play(event)
    }

    /// Erst wenn neue Paare (mit Meldungen) dazukommen, um Erlaubnis für Mitteilungen bitten — vorher die kurze
    /// Erklärung (`NotificationPrePrompt`, nur wenn das System wirklich fragen würde).
    func requestNotifications() {
        NotificationPrePrompt.request(.pairsAdded)
    }
}
