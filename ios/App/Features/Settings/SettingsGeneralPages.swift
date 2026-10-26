import SwiftUI

// Runde 23f: Unterseiten hinter den Zeilen der Hauptseite — wie `SettingsPages.kt`, je Gruppe eine
// Datei (Allgemein, Darstellung, Alarme, Portfolio, Daten, Über) wie in Android.

/// Währung (Liste mit Suchfeld, Kürzel und Name in der App-Sprache): gilt für Merkliste («≈»), Portfolio, Alarme, Krypto-Markt, Widget.
@MainActor
struct CurrencySettingsPage: View {
    @EnvironmentObject private var data: AppData
    @State private var query = ""

    init() {}

    /// Eine früher gesetzte, nicht mehr gelistete Währung trotzdem anzeigen.
    private var codes: [String] {
        let selection = data.settings.portfolioCurrency
        return FxRateSource.currencies.contains(selection) ? FxRateSource.currencies : FxRateSource.currencies + [selection]
    }

    /// Name in der App-Sprache («Schweizer Franken»); unbekannt → Kürzel.
    private static func name(_ code: String) -> String {
        let locale = Locale(identifier: Bundle.main.preferredLocalizations.first ?? "en")
        guard let name = locale.localizedString(forCurrencyCode: code) else { return code }
        return name.prefix(1).uppercased(with: locale) + name.dropFirst()
    }

    private var visible: [(code: String, name: String)] {
        let options = codes.map { (code: $0, name: Self.name($0)) }
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return options }
        return options.filter { $0.code.localizedCaseInsensitiveContains(q) || $0.name.localizedCaseInsensitiveContains(q) }
    }

    var body: some View {
        SettingsCardsPage(title: L("settings_row_currency")) {
            SettingsChoiceSearchField(query: $query, placeholder: L("currency_search"))
            SettingsCard {
                let selection = data.settings.portfolioCurrency
                if visible.isEmpty {
                    SettingsHint(text: L("explorer_search_empty"), top: 8)
                }
                ForEach(visible, id: \.code) { option in
                    SettingsChoiceRow(title: option.name, selected: option.code == selection) {
                        Text(option.code)
                            .font(.body.weight(.bold).monospacedDigit())
                            .frame(width: 48, alignment: .leading)
                    } action: {
                        if option.code != selection { data.settings.portfolioCurrency = option.code }
                    }
                }
            }
            .settingsAnchor("currency.conversion")
            SettingsHint(text: L("settings_conversion_currency_hint"), top: 2)
                .padding(.horizontal, 16)
        }
        .haptic(.selection, trigger: data.settings.portfolioCurrency)
    }
}

/// Aktualisierung: «Wie aktuell?» (Sparsam oder Live, solange offen), darunter das
/// Hintergrund-Intervall — wie `UpdatesPage` (Android, dort zusätzlich «Immer kurz»).
@MainActor
struct UpdatesSettingsPage: View {
    @EnvironmentObject private var data: AppData

    init() {}

    private var settings: AppSettings { data.settings }

    var body: some View {
        SettingsCardsPage(title: L("settings_row_updates")) {
            UpdateModeCard()
                .settingsAnchor("updates.mode")
            SettingsCard {
                ChoiceRow(
                    title: L("settings_background_interval"),
                    options: AppSettings.backgroundIntervalChoices,
                    selection: settings.backgroundIntervalMinutes,
                    label: { L("settings_minutes", $0) },
                    onSelect: { data.settings.backgroundIntervalMinutes = $0 }
                )
                .settingsAnchor("updates.background_interval")
                // iOS bestimmt den Zeitpunkt im Hintergrund; bei offener App pünktlich
                SettingsHint(text: L("settings_ios_background_hint"))
            }
            // «Letzte Prüfungen»: wann iOS wirklich geprüft hat
            CheckRunsCard()
                .settingsAnchor("updates.check_runs")
        }
    }
}

/// «Wie aktuell?»: Wahlen als Auswahlzeilen mit ehrlichem Satz (Intervall aus den Feinheiten);
/// «Sparsam» ist empfohlen (Standard); «Live, solange offen» für Profis. iOS ohne «Immer kurz» (`UpdateMode.iosChoices`).
/// Wie `UpdateModeCard` (Android).
@MainActor
private struct UpdateModeCard: View {
    @EnvironmentObject private var data: AppData

    var body: some View {
        // Eine Ansicht (VStack), damit `.settingsAnchor` von aussen nicht auf Banner und Karte verteilt wird
        VStack(alignment: .leading, spacing: 12) {
            // Warnung, wenn iOS im Hintergrund gar nicht oder kaum prüfen darf
            BackgroundOffBanner()
            SettingsCard(title: L("update_mode_title")) {
                ForEach(UpdateMode.iosChoices, id: \.self) { mode in
                    row(mode)
                }
            }
            .haptic(.selection, trigger: data.settings.updateMode)
        }
    }

    private func row(_ mode: UpdateMode) -> some View {
        let background: String = L("settings_minutes", data.settings.backgroundIntervalMinutes)
        let selected = data.settings.updateMode == mode
        return SettingsChoiceRow(title: title(mode), subtitle: subtitle(mode, background: background), selected: selected) {
            if !selected { data.settings.updateMode = mode }
        }
    }

    private func title(_ mode: UpdateMode) -> String {
        switch mode {
        // Sparsam ist Standard und empfohlen; Live nur für Profis (wie Android)
        case .SPARSAM: return L("update_mode_sparsam") + " · " + L("update_mode_recommended")
        // «Immer kurz» steht nie zur Wahl (`iosChoices`); der Fall gilt wie überall als Live
        case .LIVE_OFFEN, .IMMER_KURZ: return L("update_mode_live") + " · " + L("update_mode_for_pros")
        }
    }

    private func subtitle(_ mode: UpdateMode, background: String) -> String {
        switch mode {
        // Eigene iOS-Sätze: iOS entscheidet, wann im Hintergrund geprüft wird — kein «etwa alle 15 Min.»
        case .SPARSAM: return L("update_mode_sparsam_hint_ios")
        case .LIVE_OFFEN, .IMMER_KURZ: return L("update_mode_live_hint_ios")
        }
    }
}

/// Merkliste: Namen, Mini-Chart, «≈ Umrechnung», Aktivitätskarte; dazu Basis der %-Änderung und «Token & TradFi».
@MainActor
struct WatchlistSettingsPage: View {
    @EnvironmentObject private var data: AppData

    init() {}

    var body: some View {
        SettingsCardsPage(title: L("settings_row_watchlist")) {
            SettingsCard {
                SwitchRow(
                    title: L("settings_watchlist_names"),
                    subtitle: L("settings_watchlist_names_hint"),
                    isOn: $data.settings.watchlistNames
                )
                .settingsAnchor("watchlist.names")
                RowDivider()
                SwitchRow(
                    title: L("settings_watchlist_sparkline"),
                    subtitle: L("settings_watchlist_sparkline_hint"),
                    isOn: $data.settings.watchlistSparkline
                )
                .settingsAnchor("watchlist.sparkline")
                RowDivider()
                SwitchRow(
                    title: L("settings_show_converted"),
                    subtitle: L("settings_show_converted_hint", data.settings.portfolioCurrency),
                    isOn: $data.settings.showConverted
                )
                .settingsAnchor("watchlist.converted")
                RowDivider()
                SwitchRow(
                    title: L("settings_watchlist_activity_card"),
                    subtitle: L("settings_watchlist_activity_card_hint"),
                    isOn: $data.settings.watchlistActivityCard
                )
                .settingsAnchor("watchlist.activity")
            }
            // Basis der %-Änderung (Review D13: von «Erweitert» hierher — sie bestimmt, was jede Zeile zeigt;
            // gilt auch für Puls und Widgets). Wie Android
            SettingsCard {
                SettingsNavRow(title: L("settings_change_basis", "%"), icon: .system("percent"),
                               value: A11y.changeSummary(data.settings.changeBasis)) {
                    ChangeBasisSettingsPage()
                }
                .settingsAnchor("watchlist.change_basis")
                RowDivider()
                // «Token & TradFi»: was die Paar-Auswahl zeigt (Build 132 von «Erweitert» hierher — alles zur Merkliste
                // an einem Ort). Wie Android
                SettingsNavRow(title: L("settings_asset_scope_title"), icon: .system("building.columns"),
                               value: L(AssetScopePicker.titleKey(data.settings.assetScope))) {
                    MarketsSettingsPage()
                }
                .settingsAnchor("watchlist.markets")
            }
        }
    }
}

/// «Token & TradFi» (Zeile auf «Merkliste»): was die Paar-Auswahl zeigt,
/// dazu Alpha-Tokens und QTLY (gehören zu Token). Gleiche Anker wie zuvor — wie `MarketsPage` (Android).
@MainActor
struct MarketsSettingsPage: View {
    @EnvironmentObject private var data: AppData

    init() {}

    var body: some View {
        SettingsCardsPage(title: L("settings_asset_scope_title")) {
            SettingsCard {
                AssetScopePicker(selection: data.settings.assetScope) { data.settings.assetScope = $0 }
                    .settingsAnchor("futures.scope")
                RowDivider()
                alphaTokensRow
                    .settingsAnchor("futures.alpha")
                RowDivider()
                rollingFuturesRow
                    .settingsAnchor("futures.rolling")
            }
            // Gemerkte Paare bleiben: unter der Karte, eingerückt wie die Gruppenüberschrift
            SettingsHint(text: L("settings_asset_scope_hint"), top: 2)
                .padding(.horizontal, 16)
        }
    }

    /// «inkl. Alpha-Tokens» über QTLY; gehört zu Token, bei «Nur TradFi» gesperrt wie QTLY — wie Android.
    private var alphaTokensRow: some View {
        let enabled = data.settings.assetScope.allowsTokens
        return SwitchRow(
            title: L("settings_asset_scope_alpha"),
            subtitle: L(enabled ? "settings_asset_scope_alpha_hint" : "settings_asset_scope_alpha_tradfi_hint"),
            isOn: $data.settings.includeAlphaTokens,
            enabled: enabled
        )
    }

    /// QTLY: bei «Nur TradFi» gesperrt und ausgegraut; der Wert bleibt und gilt weiter für gemerkte
    /// Laufzeit-Futures (Aktualisierung, `PriceFetcher`).
    private var rollingFuturesRow: some View {
        let enabled = data.settings.assetScope.allowsTokens
        return SwitchRow(
            title: L("settings_rolling_futures_qtly"),
            subtitle: L(enabled ? "settings_rolling_futures_hint" : "settings_rolling_futures_tradfi_hint"),
            isOn: $data.settings.includeRollingFutures,
            enabled: enabled
        )
    }
}

/// «Nur Token / Nur TradFi / Token und TradFi» als Einfach-Auswahl (`SettingsChoiceRow`) mit Symbolen
/// wie bei den Gruppen-Chips der Merkliste: Token = Raute (`TokenIconShape`), TradFi = Bank,
/// beides = beide Symbole — wie `AssetScopeList` (Android). Symbole sind Dekoration; VoiceOver liest
/// Titel, Untertitel und «ausgewählt».
@MainActor
struct AssetScopePicker: View {
    let selection: AssetScope
    let onSelect: (AssetScope) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(AssetScope.allCases, id: \.self) { scope in
                SettingsChoiceRow(title: L(Self.titleKey(scope)), subtitle: L(Self.hintKey(scope)),
                                  selected: scope == selection) {
                    AssetScopeIcons(scope: scope)
                } action: {
                    if scope != selection { onSelect(scope) }
                }
            }
        }
        .padding(.vertical, 4)
        .haptic(.selection, trigger: selection)
    }

    static func titleKey(_ scope: AssetScope) -> String {
        switch scope {
        case .TOKENS: return "settings_asset_scope_tokens"
        case .TRADFI: return "settings_asset_scope_tradfi"
        case .BOTH: return "settings_asset_scope_both"
        }
    }

    static func hintKey(_ scope: AssetScope) -> String {
        switch scope {
        case .TOKENS: return "settings_asset_scope_tokens_hint"
        case .TRADFI: return "settings_asset_scope_tradfi_hint"
        case .BOTH: return "settings_asset_scope_both_hint"
        }
    }
}

/// Feste Breite (zwei Symbole), damit die Titel aller drei Zeilen bündig stehen.
@MainActor
struct AssetScopeIcons: View {
    let scope: AssetScope
    @ScaledMetric(relativeTo: .body) private var size: CGFloat = 20

    var body: some View {
        HStack(spacing: 4) {
            if scope.allowsTokens {
                TokenIconShape()
                    .frame(width: size, height: size)
            }
            if scope.allowsTradFi {
                Image(systemName: "building.columns.fill")
                    .resizable()
                    .scaledToFit()
                    .frame(width: size * 0.8, height: size * 0.8)
                    .frame(width: size, height: size)
            }
        }
        .foregroundStyle(AppColors.onSurfaceVariant)
        .frame(width: size * 2 + 4, alignment: .leading)
        .accessibilityHidden(true)
    }
}
