import SwiftUI
import UIKit

// Kopf des Aktionsblatts: Paar, Kurs mit der Zeile «↗ +41.71% · 24h · Aktualisiert HH:MM», Hinweise zu
// Kerzen und Quelle, VoiceOver-Text des Kurses und der Hinweis bei deaktivierten Paaren.
// Eigene Datei, weil der Kopf eigene Regeln hat (Reihenfolge laut DEVELOPMENT.md) und das Blatt sonst zu lang
// wird (wie `WatchActionsHeader.kt`).

extension WatchActionsSheet {
    // MARK: Kopf

    func header(_ watch: Watch, isPreview: Bool) -> some View {
        HStack(spacing: Spacing.md) {
            CoinBadge(symbol: watch.baseAsset, size: 48, logo: CoinLogos.allowed(forMarket: watch.marketKey),
                      pair: watch.logoPairKey)
            VStack(alignment: .leading, spacing: 2) {
                Text(watch.displayName)
                    .font(AppFont.headline)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                // Vorschau: «Binance · Nicht in der Merkliste»
                Text(isPreview ? "\(watch.marketName) · \(L("watch_preview_not_watched"))" : watch.marketName)
                    .font(.subheadline)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                // DEX-Pool: Token-Adresse gekürzt, Tippen kopiert (wie Android)
                DexTokenAddressLine(watch: watch)
            }
            Spacer(minLength: 0)
            // «Paar bearbeiten»: 48 pt Tippfläche; Vorschau: nichts zu bearbeiten
            if !isPreview {
                Button {
                    Haptics.play(.selection)
                    editPair = true
                } label: {
                    Image(systemName: "pencil")
                        .font(.title3.weight(.semibold))
                        .foregroundStyle(accent.primary)
                        .frame(width: 48, height: 48)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(L("watch_edit_title"))
            }
        }
    }

    /// «Veränderung aus Binance-Kerzen», wenn die Änderung aus fremden Kerzen kommt; sonst nil.
    private func candleNote(_ watch: Watch) -> String? {
        guard watch.lastPrice != nil, let candleSource else { return nil }
        return L("change_candle_source", candleSource)
    }

    /// Hinweis unter dem Kurs: Herkunft aus der Zeile (`rowSource`), sonst `candleNote`.
    private func sourceNote(_ watch: Watch) -> String? {
        if watch.lastPrice != nil, let rowSource { return WatchlistRow.rowSourceText(rowSource) }
        return candleNote(watch)
    }

    /// Kurs im Kopf für VoiceOver; deaktiviert, nicht erreichbar und nicht mehr gehandelt stehen schon
    /// als Text darunter — wie `sheetPriceSpoken` (Android).
    private func priceSpoken(_ watch: Watch, isPreview: Bool) -> String {
        let now = TimeUtils.nowMillis
        let notTraded = ConnectionErrors.isNotTraded(watch.lastError)
        let outdatedAfter = isPreview ? 0 : data.staleAfterMillis(for: watch.id)
        let freshness = A11ySpeech.freshness(
            disabled: watch.disabled && !isPreview, notTraded: notTraded,
            failed: !(watch.lastError ?? "").isEmpty && !notTraded,
            lastUpdate: watch.lastUpdate, now: now, outdatedAfter: outdatedAfter)
        let outdated = freshness == .outdated || freshness == .unreachableOutdated
        var stale: String?
        if outdated {
            stale = A11ySpeech.freshnessText(.outdated, lastUpdate: watch.lastUpdate, now: now, market: watch.marketName)
        } else if !notTraded, (watch.lastError ?? "").isEmpty, !(watch.disabled && !isPreview), outdatedAfter > 0,
                  PriceFreshness.staleReason(watch, now: now, staleAfter: outdatedAfter) == .trade {
            // Abruf frisch, letzter Handel laut Börse > 2 h (Regel der Alarmsperre): wie die Zeile — wie Android
            stale = L("watchlist_row_last_trade_age", WatchlistTime.ago(watch.exchangeTime ?? 0, now: now))
        }
        let price = A11ySpeech.spokenPrice(PriceFormat.priceWithCurrency(watch.lastPrice, watch.quoteAsset),
                                           quote: watch.quoteAsset)
        return A11ySpeech.join([L("a11y_price", price), stale])
    }

    /// Stand in der Zeile der Änderung (wie Android `updatedInline`): mit Kurs, ohne Fehler, nicht
    /// deaktiviert und nicht als Vorschau ohne Stand — sonst steht der Grund in der eigenen Zeile.
    static func updatedInline(_ watch: Watch, isPreview: Bool) -> Bool {
        watch.lastPrice != nil && (watch.lastError ?? "").isEmpty && !(watch.disabled && !isPreview)
            && !(isPreview && watch.lastUpdate <= 0)
    }

    func priceBlock(_ watch: Watch, isPreview: Bool) -> some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            Text(PriceFormat.priceWithCurrency(watch.lastPrice, watch.quoteAsset))
                .displayFont()
                .lineLimit(1)
                .minimumScaleFactor(0.5)
                .contentTransition(.numericText(value: watch.lastPrice ?? 0))
                // VoiceOver: Kurs (Fiat als Wort) und, wenn veraltet, «Kurs veraltet, zuletzt
                // aktualisiert vor 45 Minuten» — sichtbar steht nur die Uhrzeit darunter (wie Android)
                .accessibilityLabel(priceSpoken(watch, isPreview: isPreview))
            if watch.lastPrice != nil {
                // Eine Zeile unter dem Kurs: «↗ +41.71% · 24h · Aktualisiert 01:42:19» (wie Android)
                SheetChangeLine(watch: watch, note: candleNote(watch),
                                showUpdated: Self.updatedInline(watch, isPreview: isPreview))
            }
            // Herkunft aus der Zeile (L-14): «Änderung & Chart: Binance»; sonst die Änderung aus fremden
            // Kerzen (z. B. Kraken-Paar → Binance) — klein darunter sagen, woher (wie Android)
            if let note = sourceNote(watch) {
                Text(note)
                    .font(.caption2)
                    .foregroundStyle(AppColors.onSurfaceVariant)
            }
            // Bitcoin: «1 CHF = 1’234 Sats» in der Umrechnungswährung (Kurs mit dem bestehenden Faktor)
            if Sats.isBitcoin(watch.baseAsset), !watch.isNotTraded,
               let sats = Sats.perUnit(price: watch.lastPrice, rate: satsRate) {
                Text(L("sats_per_unit", LocaleNumbers.integer(1), data.settings.portfolioCurrency,
                       LocaleNumbers.decimal(sats, maxDecimals: Sats.decimals(sats), grouping: true)))
                    .font(.footnote.monospacedDigit())
                    .foregroundStyle(AppColors.onSurfaceVariant)
            }
            if watch.disabled && !isPreview {
                // Deaktiviert: ruhiger Hinweis mit Stand des letzten Kurses und gleich «Aktivieren»
                disabledNotice(watch)
            } else if let error = watch.lastError, !error.isEmpty, watch.lastPrice != nil, ConnectionErrors.isRetryable(error) {
                // Börse nicht erreichbar, älterer Kurs steht: ruhiger Satz plus «Erneut versuchen»
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(L("error_market_unreachable"))
                        .font(.footnote)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Button(L("try_again")) {
                        if isPreview { previewReload += 1 } else { Task { await data.refreshOne(watch.id) } }
                    }
                    .font(.footnote.weight(.semibold))
                    .buttonStyle(.borderless)
                    .tint(accent.primary)
                    .disabled(refreshState.refreshingWatchIds.contains(watch.id))
                }
            } else if let error = watch.lastError, !error.isEmpty, !ConnectionErrors.isNotTraded(error) {
                // Jeder andere Fehler (auch ohne älteren Kurs): Fehlertext plus «Erneut versuchen» als nächster
                // Schritt — nur «nicht gehandelt» bleibt ohne Knopf (wie Android)
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(ConnectionErrors.display(error))
                        .font(.footnote)
                        .foregroundStyle(AppColors.error)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Button(L("try_again")) {
                        if isPreview { previewReload += 1 } else { Task { await data.refreshOne(watch.id) } }
                    }
                    .font(.footnote.weight(.semibold))
                    .buttonStyle(.borderless)
                    .tint(accent.primary)
                    .disabled(refreshState.refreshingWatchIds.contains(watch.id))
                }
            } else if let error = watch.lastError, !error.isEmpty {
                Text(ConnectionErrors.display(error))
                    .font(.footnote)
                    .foregroundStyle(AppColors.onSurfaceVariant)
            } else if isPreview && watch.lastUpdate <= 0 {
                // Vorschau: Kurs wird noch geholt
                Text(L("loading_hint"))
                    .font(.footnote)
                    .foregroundStyle(AppColors.onSurfaceVariant)
            } else if watch.lastPrice == nil {
                // Ohne Kurs nur die Uhrzeit; mit Kurs steht sie schon in der Zeile der Änderung
                Text(L("watchlist_updated", PriceFormat.time(watch.lastUpdate)))
                    .font(.footnote.monospacedDigit())
                    .foregroundStyle(AppColors.onSurfaceVariant)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: Radius.large, style: .continuous)
                .fill(LinearGradient(
                    colors: [accent.tint(0.16), accent.tint(0.04)],
                    startPoint: .topLeading, endPoint: .bottomTrailing))
        )
        .overlay(
            RoundedRectangle(cornerRadius: Radius.large, style: .continuous)
                .strokeBorder(accent.tint(0.25), lineWidth: 1)
        )
        .animation(.snappy, value: watch.lastPrice)
    }

    /// Deaktiviertes Paar: «Deaktiviert · Keine Abfragen, keine Mitteilungen», Stand des letzten
    /// Kurses und daneben «Aktivieren» — auch bei zugeklapptem «Mehr» sofort erreichbar (wie Android).
    private func disabledNotice(_ watch: Watch) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Image(systemName: "pause.circle")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(AppColors.onSurfaceVariant)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(L("watch_disabled") + " · " + L("watch_disable_hint"))
                    .font(.footnote)
                    .foregroundStyle(AppColors.onSurface)
                if watch.lastUpdate > 0 {
                    Text(L("watchlist_updated", PriceFormat.time(watch.lastUpdate)))
                        .font(.footnote.monospacedDigit())
                        .foregroundStyle(AppColors.onSurfaceVariant)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Button(L("watch_enable")) { toggleDisabled(watch) }
                .font(.footnote.weight(.semibold))
                .buttonStyle(.borderless)
                .tint(accent.primary)
        }
    }
}

/// «↗ +41.71% · 24h · Aktualisiert: 01:42:19» unter dem Kurs: Änderung farbig ohne Pille, Zeitraum
/// (%-Basis, wie `A11y.changeShortLabel`) und Stand klein und grau. `showUpdated` false (Fehler,
/// deaktiviert, Vorschau ohne Stand): nur «↗ +41.71% · 24h». Liest die %-Basis selbst, damit auch
/// die Vorschau (rollende 24 h über `.environment`) stimmt — wie Android.
private struct SheetChangeLine: View {
    let watch: Watch
    let note: String?
    let showUpdated: Bool
    @Environment(\.changeView) var changeView

    var body: some View {
        var view = changeView
        view.showPeriod = false
        let updated = L("watchlist_updated", PriceFormat.time(watch.lastUpdate))
        // Börse meldet den Mittelkurs (z. B. Hyperliquid), keinen letzten Handel: so benennen; fehlte der Mittelkurs
        // (dünnes Orderbuch), steht der Mark-Preis da — dann so. WOO X Spot: Schluss der jüngsten Stundenkerze. Wie Android
        let market = MarketsConfig.market(watch.marketKey)
        let mid: String
        switch PriceKindLabel.of(priceIsMid: market?.priceIsMid == true,
                                 markFallback: MarkFallbackStore.contains(watch.id),
                                 hourlyClose: market?.priceIsHourlyClose == true) {
        case .mark: mid = " · " + L("watch_price_mark_fallback")
        case .mid: mid = " · " + L("watch_price_mid")
        case .hourly: mid = " · " + L("watch_price_hourly")
        case nil: mid = ""
        }
        let updatedPart: String = showUpdated ? " · " + updated : ""
        let tail: String = " · " + A11y.changeShortLabel(changeView.basis) + mid + updatedPart
        return HStack(alignment: .firstTextBaseline, spacing: 0) {
            WatchlistDayChangePill(watch: watch, size: .medium, note: note)
                .environment(\.changeView, view)
                .fixedSize()
                .layoutPriority(1)
            Text(tail)
                .font(.footnote.monospacedDigit())
                .foregroundStyle(AppColors.onSurfaceVariant)
                .lineLimit(1)
                .truncationMode(.tail)
        }
    }
}
