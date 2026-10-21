import Foundation

// Texte des Adressen-Blatts (Teil von `PortfolioAddressSheet`): Anbieter und Datenschutz-Hinweis, Meldung zu einem
// Prüfergebnis und die Rückfrage beim Entfernen. Eigene Datei, weil das Blatt sonst die Zeilengrenze überschreitet.

extension PortfolioAddressSheet {
    /// Text der Rückfrage: Bezeichnung und Adresse, dazu was mitgeht (Eröffnungsposten, Guthaben-Verlauf) — wie Android.
    static func removeMessage(_ target: WatchedAddress) -> String {
        let head = target.label.map { "\($0)\n\(target.address)" } ?? target.address
        let removal = WatchedAddressUndo.removal(target)
        let also: String?
        if removal.lots > 0 && removal.history {
            also = L("portfolio_address_remove_lots_history", count: removal.lots)
        } else if removal.lots > 0 {
            also = L("portfolio_address_remove_lots", count: removal.lots)
        } else if removal.history {
            also = L("portfolio_address_remove_history")
        } else {
            also = nil
        }
        return also.map { head + "\n\n" + $0 } ?? head
    }

    /// Anbieter, die die Adresse zu sehen bekommen: des erkannten Netzes (0x: alle EVM-Netze), sonst alle.
    static func hosts(_ chain: AddressChain?) -> String {
        (chain.map { ChainBalance.hosts($0) } ?? AddressBalance.privacyHosts).joined(separator: ", ")
    }

    /// Datenschutz-Hinweis zur Eingabe: Anbieter; bei xpub/ypub/zpub/Taproot-Konto dazu, was der Schlüssel verrät;
    /// Taproot-Konto: welcher Adresstyp abgeleitet wird; einfacher xpub: Hinweis auf den Deskriptor — wie Android.
    static func privacy(_ check: AddressValidation.Check) -> String {
        let base = L("portfolio_address_privacy", hosts(check.chain))
        // Gap-Regel offen nennen: Eine benutzte Adresse hinter `XpubScan.gap` unbenutzten in Folge fehlt im Guthaben
        let keyNote = "\n\n" + L("portfolio_address_xpub_privacy", hosts(.BTC))
            + "\n\n" + L("portfolio_address_xpub_gap", XpubScan.gap)
        switch check.kind {
        case .XPUB: return base + keyNote + "\n\n" + L("portfolio_address_taproot_hint")
        case .YPUB, .ZPUB: return base + keyNote
        case .TR_XPUB: return base + keyNote + "\n\n" + L("portfolio_address_taproot_account")
        default: return base
        }
    }

    /// Meldung zu einem Prüfergebnis — wie `problemRes` (Android).
    static func message(_ problem: AddressValidation.Problem) -> String {
        switch problem {
        case .EMPTY, .UNKNOWN_FORMAT: return L("address_invalid")
        case .BAD_CHECKSUM: return L("address_checksum")
        case .TESTNET: return L("address_testnet")
        case .EXTENDED_KEY: return L("address_xpub_multisig")
        case .UNSUPPORTED_VERSION: return L("address_unsupported")
        case .ENS_NAME: return L("address_ens")
        case .SECRET: return L("address_secret")
        case .WRONG_CHAIN: return L("address_wrong_chain")
        case .BEP2_SHUTDOWN: return L("address_bep2_shutdown")
        case .DOGE_UNSUPPORTED: return L("address_doge_unsupported")
        case .BCH_UNSUPPORTED: return L("address_bch_unsupported")
        }
    }
}
