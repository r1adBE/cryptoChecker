import SwiftUI

/// Portfolio ⋯ › «Adressen»: beobachtete öffentliche Adressen (nur lesen) — wie `PortfolioAddressSheet.kt`. Liste mit
/// Guthaben, Stand bzw. Fehler, Entfernen mit Rückfrage; darunter Hinzufügen (Netz automatisch erkannt, Bezeichnung
/// optional). Vor der ersten Adresse/xpub ein Datenschutz-Hinweis mit den Anbietern; Seed-Wörter und private Schlüssel lehnt
/// die Prüfung ab (`AddressValidation.Problem.SECRET`). xpub/ypub/zpub: eigener Hinweis (alle Adressen der Wallet auf
/// dem Gerät, gesendet werden nur einzelne). ETH-Zeilen: Token-Guthaben und Schalter «Tokens mitzählen».
@MainActor
struct PortfolioAddressSheet: View {
    @EnvironmentObject private var data: AppData
    @ObservedObject private var model = PortfolioModel.shared
    @Environment(\.appAccent) private var accent
    @Environment(\.dismiss) private var dismiss
    /// Erste Adresse mit ausgeschaltetem Portfolio hat es eingeschaltet (wie die erste Transaktion).
    var onPortfolioEnabled: () -> Void = {}

    // Nur im Speicher (eine versehentlich eingefügte Seed-Phrase wird nirgends gesichert)
    @State private var input = ""
    @State private var label = ""
    @State private var saveError: String?
    @State private var askPrivacy = false
    @State private var askRemove: WatchedAddress?
    @State private var askDiscard = false
    @State private var now = EpochTime.nowMillis
    /// Beim Hinzufügen: Coin schon mit eigenen Transaktionen erfasst → fragen «Zählen» / «Nur anzeigen».
    @State private var askOverlap: AddressChain?
    /// «Zählen» nach der Warnung zu einer gleich grossen Einzahlung: für dieses Blatt bestätigt (nur im Speicher,
    /// allgemeiner Hinweis bleibt) — wie Android.
    @State private var countConfirmed: Set<Int64> = []
    /// «Einstand für Adressguthaben» einer Adresse (eigenes Blatt darüber).
    @State private var lotsFor: WatchedAddress?
    /// Eigenes Banner im Blatt («entfernt» mit «Rückgängig», Sperre): das des Portfolio-Tabs läge dahinter — wie Android.
    @State private var banner: WatchlistBannerMessage?

    var body: some View {
        let addresses = data.watchedAddresses
        let check = AddressValidation.check(input)
        let problem = check.problem.flatMap { $0 == .EMPTY ? nil : Self.message($0) }
        let canAdd = check.problem == nil && WatchedAddressRules.canAdd(addresses)
        NavigationStack {
            List {
                ForEach(addresses) { a in
                    row(a)
                        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                            Button { askRemove = a } label: { // ohne Rolle, Zeile bleibt bis zur Rückfrage
                                Label(L("portfolio_address_remove"), systemImage: "trash")
                            }
                            .tint(AppColors.destructive)
                        }
                        .accessibilityActions {
                            Button(L("portfolio_address_remove")) { askRemove = a }
                        }
                }
                Section {
                    TextField(placeholder: L("portfolio_address_hint"), text: $input, axis: .vertical)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        // Nur ASCII, keine Vorschläge aus der Adresse (wie Android)
                        .keyboardType(.asciiCapable)
                        .font(.body.monospaced())
                        .onChange(of: input) { saveError = nil }
                    if let err = saveError ?? problem {
                        Text(err).font(.footnote).foregroundStyle(AppColors.error)
                    } else if let chain = check.chain {
                        Text(chain.coin).font(.footnote).foregroundStyle(AppColors.onSurfaceVariant)
                    }
                    TextField(placeholder: L("portfolio_address_label"), text: $label)
                        .onChange(of: label) { _, value in
                            if value.unicodeScalars.count > WatchedAddressRules.maxLabel {
                                label = String(String.UnicodeScalarView(value.unicodeScalars.prefix(WatchedAddressRules.maxLabel)))
                            }
                        }
                    if !WatchedAddressRules.canAdd(addresses) {
                        Text(L("address_limit")).font(.footnote).foregroundStyle(AppColors.error)
                    }
                    Button {
                        if AddressServer.consentNeeded(addresses, check: check) { askPrivacy = true } else { addChecked(check) }
                    } label: {
                        Text(L("portfolio_address_add")).frame(maxWidth: .infinity)
                    }
                    .disabled(!canAdd)
                } footer: {
                    // Wer die Adresse sieht (Anbieter des erkannten Netzes); xpub: was er verrät und wie viele Anfragen
                    Text(Self.privacy(check, btcServer: data.settings.addressServerBtc))
                }
            }
            .navigationTitle(L("portfolio_addresses_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    // Angefangene Eingabe: erst nachfragen (Wischen ist dann gesperrt) — wie Android
                    Button(L("action_close")) {
                        if dirty { askDiscard = true } else { dismiss() }
                    }
                }
            }
            .task {
                while !Task.isCancelled {
                    try? await Task.sleep(nanoseconds: 30_000_000_000)
                    now = EpochTime.nowMillis
                }
            }
            .alert(L("portfolio_address_overlap_title"), isPresented: Binding(
                get: { askOverlap != nil }, set: { if !$0 { askOverlap = nil } }
            ), presenting: askOverlap) { _ in
                // «Nur anzeigen» ist die sichere Wahl (kein Doppelzählen) und steht vorn (fett) — wie Android
                Button(L("portfolio_address_show_only_short")) { add(check, countInPortfolio: false, overlapChecked: true) }
                    .keyboardShortcut(.defaultAction)
                Button(L("portfolio_address_count")) { add(check, countInPortfolio: true, overlapChecked: true) }
                Button(L("action_cancel"), role: .cancel) {}
            } message: { chain in
                Text(L("portfolio_address_overlap_ask", chain.coin))
            }
            .sheet(item: $lotsFor) { a in
                PortfolioAddressLotsSheet(addressId: a.id)
                    .environmentObject(data)
                    .environment(\.appAccent, accent)
                    .presentationDetents([.large])
                    .presentationDragIndicator(.visible)
            }
            .alert(L("portfolio_addresses_title"), isPresented: $askPrivacy) {
                Button(L("portfolio_address_add")) { addChecked(check) }
                Button(L("action_cancel"), role: .cancel) {}
            } message: {
                Text(Self.privacy(check, btcServer: data.settings.addressServerBtc))
            }
            .confirmationDialog(L("portfolio_address_remove"), isPresented: Binding(
                get: { askRemove != nil }, set: { if !$0 { askRemove = nil } }
            ), titleVisibility: .visible, presenting: askRemove) { target in
                Button(L("portfolio_address_remove"), role: .destructive) { remove(target) }
                Button(L("action_cancel"), role: .cancel) {}
            } message: { target in
                Text(Self.removeMessage(target))
            }
            .overlay(alignment: .bottom) {
                WatchlistBanner(message: $banner) { _ in }
            }
        }
        .tint(accent.primary)
        .interactiveDismissDisabled(dirty)
        .confirmationDialog(L("portfolio_discard_title"), isPresented: $askDiscard, titleVisibility: .visible) {
            Button(L("portfolio_discard_confirm"), role: .destructive) { dismiss() }
            Button(L("portfolio_discard_keep"), role: .cancel) {}
        }
    }

    /// Entfernen, dann «Rückgängig» im Banner (holt die ganze Adresse samt Posten und Verlauf zurück); gesperrt: Hinweis
    /// statt still nichts zu tun — wie Android.
    private func remove(_ target: WatchedAddress) {
        switch data.removeWatchedAddress(target.id) {
        case .removed(let removed):
            Haptics.play(.destructive)
            let name = target.label ?? WatchedAddressRules.shortAddress(target.address)
            banner = WatchlistBannerMessage(
                text: L("watchlist_removed", name),
                action: WatchlistBannerAction.undo {
                    switch data.restoreWatchedAddress(removed) {
                    case .locked: showLocked()
                    // Nicht zurückgeholt: Grund nennen statt still nichts zu tun (wie Android)
                    case .failed: showRestoreFailed(WatchedAddressUndo.failure(data.watchedAddresses, removed))
                    case .removed, .notFound: break
                    }
                }
            )
        case .locked:
            showLocked()
        // Nicht geschrieben: nennen statt still nichts zu tun (wie Android)
        case .failed:
            banner = WatchlistBannerMessage(text: L("portfolio_address_remove_failed"), kind: .error, long: true)
        case .notFound:
            break
        }
    }

    private func showLocked() {
        banner = WatchlistBannerMessage(text: L("portfolio_storage_locked"), kind: .error, long: true)
    }

    /// Text zum gescheiterten «Rückgängig» (`WatchedAddressUndo.failure`) — wie Android `restoreFailedText`.
    private func showRestoreFailed(_ failure: WatchedAddressUndo.RestoreFailure) {
        let text: String
        switch failure {
        case .duplicate: text = L("address_duplicate")
        case .limit: text = L("address_limit")
        case .notSaved: text = L("portfolio_address_restore_failed")
        }
        banner = WatchlistBannerMessage(text: text, kind: .error, long: true)
    }

    /// Angefangene Eingabe (Adresse oder Bezeichnung).
    private var dirty: Bool {
        !input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            || !label.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    /// Eine Adresse: Bezeichnung oder gekürzte Adresse, Netz, Guthaben, Stand bzw. Fehler; xpub mit Anzahl benutzter
    /// Adressen; ETH mit Token-Guthaben (> 0) und Schalter «Tokens mitzählen» — wie Android.
    private func row(_ a: WatchedAddress) -> some View {
        let balance = PortfolioDecimal.parse(a.lastBalance)
        let amount = balance.map { PortfolioFormat.exactAmount($0, a.chain.coin) } ?? "— \(a.chain.coin)"
        let shown = data.settings.hidePortfolioAmounts && balance != nil ? "•••" : amount
        let status: String? = {
            if let error = a.lastError {
                // xpub über der Anfragen-Grenze: eigener Hinweis (wiederholen hilft nicht)
                let head = Self.errorText(error)
                return head + (a.lastUpdated > 0 ? " · " + WatchlistTime.ago(a.lastUpdated, now: now) : "")
            }
            if a.lastUpdated > 0 {
                // Token-Guthaben, die der letzte Abruf nicht bestätigt hat (TON: volle Jetton-Seite): «teilweise veraltet»
                let partlyStale = a.tokenHoldings().contains { a.staleTokens.contains($0.key) }
                let age = WatchlistTime.ago(a.lastUpdated, now: now) // am Zeilenanfang gross: «Vor 2 Std. · Etherscan»
                return WatchlistTime.lineStart(age, ago: age) + (a.lastProvider.map { " · \($0)" } ?? "")
                    + (partlyStale ? " · " + L("portfolio_address_tokens_partly_stale") : "")
            }
            return nil
        }()
        let tokens = a.tokenHoldings()
        let tokenParts: [String] = tokens.map { item -> String in
            let amount = PortfolioDecimal.parse(item.amount).map { PortfolioFormat.exactAmount($0, item.label) }
                ?? "\(item.amount) \(item.label)"
            // Netz dazu (Arbitrum, BNB Smart Chain …), damit z. B. USDC auf zwei Netzen unterscheidbar bleibt
            guard let net = ChainAssets.network(item.network) else { return amount }
            return "\(amount) (\(net.name))"
        }
        let overlap = WatchedAddressRules.overlap(a, ownCoins: ownCoins)
        // Eigene Einzahlungen/Umbuchungen mit gleicher Menge wie Guthaben bzw. beobachteter Zugang (± 1 %)
        let matches: [PortfolioTx] = countConfirmed.contains(a.id) ? [] : WatchedAddressRules.transferMatches(a, txs: data.portfolio)
        let matchText: String = data.settings.hidePortfolioAmounts ? "•••" : matches.map { t -> String in
            PortfolioFormat.exactAmount(t.amountExact, PortfolioCalculator.normalizeCoin(t.coin))
        }.joined(separator: ", ")
        let tokenText: String = data.settings.hidePortfolioAmounts ? "•••" : tokenParts.joined(separator: " · ")
        return VStack(alignment: .leading, spacing: 4) {
            VStack(alignment: .leading, spacing: 2) {
                Text(a.label ?? WatchedAddressRules.shortAddress(a.address))
                    .font(.body)
                    .lineLimit(1)
                Text((a.label != nil ? WatchedAddressRules.shortAddress(a.address) + " · " : "") + shown)
                    .font(.subheadline)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .lineLimit(1)
                if a.isXpub {
                    Text(L("portfolio_address_xpub_used", a.xpub?.usedCount ?? 0))
                        .font(.footnote)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .lineLimit(1)
                    // Vollständige Suche zu gross, nur schnell gesucht (`XpubCache.limitedFallback`): dezenter Hinweis
                    if a.xpub?.limitedFallback == true {
                        Text(L("portfolio_address_xpub_limited")).font(.caption).foregroundStyle(AppColors.onSurfaceVariant).fixedSize(horizontal: false, vertical: true)
                    }
                }
                if !tokens.isEmpty {
                    Text(tokenText)
                        .font(.footnote)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .lineLimit(2)
                }
                if let status {
                    Text(status)
                        .font(.caption2)
                        .foregroundStyle(a.lastError != nil ? AppColors.error : AppColors.onSurfaceVariant)
                        .lineLimit(1)
                }
                if a.unknownTokens > 0 && a.countTokens {
                    Text(L("portfolio_address_unknown_tokens", count: a.unknownTokens))
                        .font(.caption2)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                }
                // Gleich grosse eigene Einzahlung/Umbuchung: deutliche Warnung; sonst dieselben Coins wie eigene
                // Transaktionen: Hinweis (zählt doppelt, wenn es dieselben sind)
                if !matches.isEmpty && a.countInPortfolio {
                    Text(L("portfolio_address_overlap_tx", matchText))
                        .font(.caption2)
                        .foregroundStyle(AppColors.error)
                        .fixedSize(horizontal: false, vertical: true)
                } else if !overlap.isEmpty && a.countInPortfolio {
                    Text(L("portfolio_address_overlap", overlap.joined(separator: ", ")))
                        .font(.caption2)
                        .foregroundStyle(AppColors.error)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .accessibilityElement(children: .combine)
            // Gleich grosse Einzahlung: dieselbe Wahl wie beim Hinzufügen — «Nur anzeigen» (sicher, vorn) oder «Zählen»
            if !matches.isEmpty && a.countInPortfolio {
                HStack(spacing: 16) {
                    Button(L("portfolio_address_show_only_short")) {
                        data.setWatchedAddressCountInPortfolio(a.id, on: false)
                    }
                    .font(.subheadline.weight(.semibold))
                    Button(L("portfolio_address_count")) { countConfirmed.insert(a.id) }
                        .font(.subheadline)
                }
                .buttonStyle(.borderless)
                .tint(accent.primary)
                .frame(minHeight: 44)
            } else if !overlap.isEmpty && a.countInPortfolio {
                // Hinweis bleibt, solange die Adresse zählt — «Nur anzeigen» mit einem Tipp (wie Android)
                Button(L("portfolio_address_show_only_short")) {
                    data.setWatchedAddressCountInPortfolio(a.id, on: false)
                }
                .font(.subheadline.weight(.semibold))
                .buttonStyle(.borderless)
                .tint(accent.primary)
                .frame(minHeight: 44)
            }
            if a.chain == .ETH {
                // Weitere EVM-Netze derselben 0x-Adresse (Ethereum immer)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 4) {
                        ForEach(ChainAssets.evm, id: \.id) { net in
                            let selected = a.networkSelected(net.id)
                            Button {
                                let current = ChainAssets.evm.map(\.id).filter { a.networkSelected($0) }
                                let next = selected ? current.filter { $0 != net.id } : current + [net.id]
                                data.setWatchedAddressNetworks(a.id, networks: next)
                                Task { await model.refresh(force: false) }
                            } label: {
                                Label(net.name, systemImage: selected ? "checkmark.circle.fill" : "circle")
                                    .font(.caption)
                            }
                            .buttonStyle(.bordered)
                            .tint(selected ? accent.primary : AppColors.onSurfaceVariant)
                            .accessibilityAddTraits(selected ? .isSelected : [])
                        }
                    }
                }
            }
            if ChainAssets.hasTokens(a.chain) {
                Toggle(L("portfolio_address_tokens"), isOn: Binding(
                    get: { a.countTokens },
                    set: { on in
                        data.setWatchedAddressCountTokens(a.id, on: on)
                        // An: Token gleich lesen, dann Kurse der neuen Coins
                        if on { Task { await model.refresh(force: false) } }
                    }
                ))
                .font(.subheadline)
            }
            // «Nur anzeigen, nicht zum Portfolio zählen»
            Toggle(L("portfolio_address_show_only"), isOn: Binding(
                get: { !a.countInPortfolio },
                set: { showOnly in data.setWatchedAddressCountInPortfolio(a.id, on: !showOnly) }
            ))
            .font(.subheadline)
            // Einstand für das Guthaben (Posten wie Käufe in dieser Adresse); Stand je Coin, wenn erfasst
            if a.countInPortfolio {
                ForEach(AddressCostLots.coverage(a, trades: data.portfolio, all: data.watchedAddresses), id: \.coin) { c in
                    Text(L("address_lots_status", lotAmount(c.lotted, c.coin), lotAmount(c.base, c.coin)))
                        .font(.caption)
                        .foregroundStyle(c.capped && a.lastBalance != nil ? AppColors.error : AppColors.onSurfaceVariant)
                }
                Button(L("address_lots_action")) { lotsFor = a }
                    .font(.subheadline)
                    .buttonStyle(.borderless)
            }
        }
    }

    /// Menge mit Coin, verborgen mit «Beträge verbergen».
    private func lotAmount(_ value: Decimal, _ coin: String) -> String {
        data.settings.hidePortfolioAmounts ? "•••" : PortfolioFormat.exactAmount(value, coin)
    }

    /// Coins aus eigenen Transaktionen: Überschneidung mit einer Adresse = möglicherweise doppelt.
    private var ownCoins: Set<String> { Set(data.portfolio.map { PortfolioCalculator.normalizeCoin($0.coin) }) }

    /// Nach dem Datenschutz-Hinweis: bei Überschneidung mit eigenen Transaktionen erst fragen.
    private func addChecked(_ check: AddressValidation.Check) {
        guard let chain = check.chain else { return }
        if WatchedAddressRules.overlapAtAdd(chain, ownCoins: ownCoins) { askOverlap = chain } else { add(check) }
    }

    private func add(_ check: AddressValidation.Check, countInPortfolio: Bool = true, overlapChecked: Bool = false) {
        guard let chain = check.chain else { return }
        let outcome = data.addWatchedAddress(chain: chain, address: input, label: label, countInPortfolio: countInPortfolio,
                                             overlapChecked: overlapChecked)
        Haptics.play(outcome.haptic)
        switch outcome {
        case .added:
            input = ""
            label = ""
            saveError = nil
            // Erste Adresse mit ausgeschaltetem Portfolio: einschalten (Tab erscheint sofort)
            if PortfolioOff.enableOnSave(isNew: true, portfolioEnabled: data.settings.portfolioEnabled) {
                data.settings.portfolioEnabled = true
                onPortfolioEnabled()
            }
            // Neue Adresse ist sofort fällig; danach Kurse und Gesamtwert
            Task { await model.refresh(force: false) }
        case .invalid(let problem):
            saveError = Self.message(problem)
        case .duplicate:
            saveError = L("address_duplicate")
        case .limitReached:
            saveError = L("address_limit")
        case .locked:
            saveError = L("portfolio_storage_locked")
        }
    }
}
