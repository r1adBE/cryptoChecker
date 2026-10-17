import SwiftUI
import UIKit

// Teile einer Merklisten-Zeile: Paarname, links Logo/Badge mit Paar und Börse (`info`), rechts Kurs mit
// Änderung und Umrechnung (`priceColumn`), Aufblitzen beim Kurswechsel.
// Eigene Datei, weil `WatchlistRow` nur Gesten, VoiceOver-Text und Anordnung regelt (wie `WatchlistRowParts.kt`).

extension WatchlistRow {
    /// Paar in zwei Teilen: Basis gross und halbfett («BTC»), dahinter klein und grau «/USDT», auf
    /// gemeinsamer Grundlinie. Die Basis — das Wichtigste der Zeile — wird nie zugunsten der Quote
    /// gekürzt: «/QUOTE» erscheint nur, wenn es ganz Platz hat, sonst entfällt es (die Quote steht
    /// ohnehin beim Kurs). Wie Android `PairName`.
    private var pairName: some View {
        // Deaktiviert: onSurfaceVariant statt abgeblasst (Kontrast AA, L-13)
        let base = Text(watch.baseAsset)
            .font(.headline)
            .foregroundStyle(off ? AppColors.onSurfaceVariant : AppColors.onSurface)
        return ViewThatFits(in: .horizontal) {
            HStack(alignment: .firstTextBaseline, spacing: 0) {
                base.lineLimit(1)
                Text(verbatim: "/" + watch.quoteAsset)
                    .font(.footnote)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .lineLimit(1)
            }
            // Ohne Quote; die Basis nur als letzter Ausweg (überlange Symbole) gekürzt
            base
                .lineLimit(DenseRowText.maxLines(typeScale))
                .truncationMode(.tail)
        }
    }

    var info: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: Spacing.xs) {
                pairName
                    // Build 46: Das Paar geht vor — «Was bewegt sich mit?» klappt bei wenig Platz auf ⚡ zusammen
                    .layoutPriority(1)
                // «Erst-Hinzufügen»: kurzes Häkchen (Banner und Ansage sagen dasselbe)
                if showCheck {
                    Image(systemName: "checkmark.circle.fill")
                        .scaledFont(size: 14, weight: .semibold, relativeTo: .headline)
                        .foregroundStyle(accent.primary)
                        .transition(reduceMotion ? AnyTransition.opacity
                                    : AnyTransition.scale(scale: 0.4).combined(with: .opacity))
                        .accessibilityHidden(true)
                }
                if hasActivity && !off && !sorting {
                    WatchlistActivityBolt(action: onActivity)
                        .padding(.leading, Spacing.xs)
                        .transition(.scale.combined(with: .opacity))
                }
                if let contract = watch.contractType.shortName {
                    Text(contract)
                        .scaledFont(size: 11, weight: .bold, design: .rounded, relativeTo: .caption2)
                        .foregroundStyle(accent.primary)
                        .padding(.horizontal, Spacing.xs)
                        .padding(.vertical, 2)
                        .background(accent.tint(0.12), in: Capsule())
                        .lineLimit(1)
                        .fixedSize()
                        .dynamicTypeSize(...DynamicTypeSize.accessibility2)
                }
            }
            .frame(minHeight: namesEnabled ? line1 : nil, alignment: .leading)

            // «Namen anzeigen»: Name unter dem Paar («Bitcoin», «NVIDIA»), ohne bekannten Namen «–»;
            // im Zeilensatz enthalten. Gleiche Zeilenhöhe wie die %-Änderung rechts.
            if namesEnabled {
                Text(coinName ?? "–")
                    .font(.footnote)
                    .foregroundStyle(off ? AppColors.onSurfaceVariant : AppColors.onSurface.opacity(0.8))
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .frame(minHeight: line2, alignment: .leading)
                    .accessibilityHidden(true)
            }

            HStack(spacing: Spacing.xs) {
                let line = timeLine
                if line.warning {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .scaledFont(size: 11, weight: .semibold, relativeTo: .caption2)
                        .foregroundStyle(AppColors.error)
                        .accessibilityHidden(true)
                }
                // Build 57: Lesegrösse (`.subheadline`) statt `.footnote` — wie Android `body`
                Text(line.text)
                    .font(.subheadline.monospacedDigit())
                    // Veraltet in voller Fehlerfarbe (abgeschwächt unter 4.5:1 auf hellen Flächen)
                    .foregroundStyle(line.warning || (stale && watch.lastUpdate > 0) ? AppColors.error
                        : AppColors.onSurfaceVariant)
                    .lineLimit(line.warning ? 2 : DenseRowText.maxLines(typeScale))
                    .layoutPriority(-1)

                // Art vor der Glocke: Token (Raute), TradFi (Bank), Alpha («α») wie die Chips;
                // neutral wie die Glocke, in der Themenfarbe nur mit gewähltem passendem Chip
                AssetKindIconView(kind: kind)
                    .foregroundStyle(highlight.highlights(kind) ? accent.primary : AppColors.onSurfaceVariant)
                    .opacity(iconOpacity)
                // Kursmitteilung (Entscheid 16. Oktober 2026, wieder in der Zeile): schlichte Glocke = an,
                // durchgestrichene = aus (Paar aus oder Hauptschalter aus). Danach die Alarmglocke (mit Wellen),
                // nur bei mind. einem aktiven Alarm — wie Android
                let priceOn: Bool = A11ySpeech.priceNotificationOn(pairOn: watch.notificationEnabled,
                                                                   masterOn: priceNotificationsOn)
                Image(systemName: priceOn ? "bell" : "bell.slash")
                    .scaledFont(size: 11, relativeTo: .caption2)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .opacity(iconOpacity)
                    .accessibilityLabel(L(priceOn ? "a11y_price_notification_on" : "a11y_price_notification_off"))
                if alarmCount > 0 {
                    HStack(spacing: 2) {
                        Image(systemName: "bell.and.waves.left.and.right.fill").scaledFont(size: 11, relativeTo: .caption2)
                            .opacity(iconOpacity)
                        Text(verbatim: LocaleNumbers.integer(alarmCount)).font(.caption.weight(.semibold).monospacedDigit())
                    }
                    // Textfarbe, nicht die Themenfarbe; deaktiviert onSurfaceVariant
                    .foregroundStyle(off ? AppColors.onSurfaceVariant : AppColors.onSurface)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(L("watchlist_alarms_count", count: alarmCount))
                }
            }
            .frame(minHeight: namesEnabled ? line3 : nil, alignment: .leading)

            // «nicht erreichbar» steht schon in der Zeitzeile: dann keine zweite Fehlerzeile;
            // deaktiviert: kein alter Fehler (es wird bewusst nicht abgefragt)
            if !off, let error = watch.lastError, !error.isEmpty,
               !(watch.lastUpdate > 0 && ConnectionErrors.isRetryable(error)) {
                Text(ConnectionErrors.display(error))
                    .font(.caption)
                    .foregroundStyle(ConnectionErrors.isNotTraded(error) ? AppColors.onSurfaceVariant : AppColors.error)
                    .lineLimit(1)
            }

            // Eigene Notiz (#233), dezent unter dem Paar
            if let note = watch.note {
                HStack(alignment: .firstTextBaseline, spacing: 4) {
                    Image(systemName: "note.text")
                        .scaledFont(size: 11, weight: .semibold, relativeTo: .caption2)
                        .foregroundStyle(accent.primary.opacity(0.8))
                        .opacity(iconOpacity)
                    Text(note)
                        .font(.caption.italic())
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .lineLimit(2)
                }
                .accessibilityElement(children: .combine)
                .accessibilityLabel("\(L("note_title")): \(note)")
            }
        }
    }

    @ViewBuilder
    var priceColumn: some View {
        // Mit «Namen anzeigen» gleicher Abstand und gleiche Zeilenhöhen wie links
        VStack(alignment: .trailing, spacing: namesEnabled ? 2 : 4) {
            if watch.lastPrice == nil && loading {
                SkeletonPulse {
                    VStack(alignment: .trailing, spacing: 4) {
                        SkeletonBlock(width: 80, height: 14)
                        SkeletonBlock(width: 48, height: 12)
                    }
                }
            } else {
                HStack(spacing: Spacing.xs) {
                    if loading {
                        ProgressView().controlSize(.mini)
                    }
                    priceText
                        .padding(.horizontal, 4)
                        .background(
                            RoundedRectangle(cornerRadius: Radius.chip, style: .continuous)
                                .fill(flashColor.opacity(0.28 * flash))
                        )
                }
                // Erster Kurs während «Erst-Hinzufügen»: sanft hereinwachsen statt nur einblenden
                .transition(celebrationIndex != nil && !reduceMotion
                            ? AnyTransition.opacity.combined(with: .scale(scale: 0.85, anchor: .trailing))
                            : AnyTransition.opacity)
                .frame(minHeight: namesEnabled ? line1 : nil, alignment: .trailing)
                if watch.lastPrice != nil {
                    // %-Änderung aus fremden Kerzen: kleines «*» daneben (Quelle im VoiceOver-Satz und im Blatt)
                    WatchlistDayChangePill(watch: watch, marker: changeMarker != nil)
                        .frame(minHeight: namesEnabled ? line2 : nil, alignment: .trailing)
                }
                if let converted {
                    // Build 57: wie die Zeitzeile links (`.subheadline`)
                    Text(converted)
                        .font(AppFont.amount(.subheadline))
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .lineLimit(1)
                        .padding(.trailing, 2)
                        .frame(minHeight: namesEnabled ? line3 : nil, alignment: .trailing)
                }
            }
        }
        .fixedSize(horizontal: true, vertical: false)
        // Ziffern rollen nur bei einer echten Kursänderung (nicht beim ersten Zeichnen oder
        // Scrollen); mit «Bewegung reduzieren» ohne Animation
        .animation(reduceMotion ? nil : .snappy, value: watch.lastPrice)
    }

    /// Kurs (rollende Ziffern) und dahinter die Gegenwährung klein und grau wie der Börsenname
    /// links — immer «Kurs Währung» von links nach rechts, wie Android `RowPrice`.
    private var priceText: some View {
        HStack(alignment: .firstTextBaseline, spacing: 4) {
            Text(PriceFormat.price(watch.lastPrice))
                .font(AppFont.amount(.body, weight: .semibold))
                // Veraltet bzw. deaktiviert: gedämpft, aber lesbar (statt halber Deckkraft)
                .foregroundStyle(stale || off ? AppColors.onSurfaceVariant : AppColors.onSurface)
                .contentTransition(.numericText(value: watch.lastPrice ?? 0))
            if let price = watch.lastPrice, price > 0 {
                Text(watch.quoteAsset)
                    .font(.footnote)
                    .foregroundStyle(AppColors.onSurfaceVariant)
            }
        }
        .lineLimit(1)
        .minimumScaleFactor(0.75)
        .environment(\.layoutDirection, .leftToRight)
    }

    /// Kurz aufblitzende Fläche hinter dem Kurs: Steigend- bzw. Fallend-Farbe.
    private var flashColor: Color {
        flashUp ? priceColors.up(highContrast: highContrast, inverted: inverted)
            : priceColors.down(highContrast: highContrast, inverted: inverted)
    }
}
