import SwiftUI

// Hinweise und Quellen der Steuer-Vorlagen für die Arbeitsunterlage des Export-Blatts.
// Eigene Datei, weil es reine Textzuordnungen sind – `PortfolioExportSheet` bleibt so kurz
// (wie `PortfolioExportTexts.kt`).

extension PortfolioExportSheet {
    /// Hinweise der Vorlage (was die App nicht kann bzw. wie sie zählt), übersetzt — wie `taxTemplateNotes` (Android).
    /// Netzwerkgebühr und Schenkung an Partner nennen die Quelle der Vorlage; Quellen sind Eigennamen (unübersetzt).
    var templateNotes: [String] {
        let keys: [String]
        switch taxTemplate {
        case .US: keys = ["tax_note_specific_id", "tax_note_us_wallet", "tax_note_us_transition"]
        case .AU: keys = ["tax_note_specific_id"]
        // FR/AT: Verkauf gegen einen Stablecoin, den man behält, ist Krypto→Krypto — als Tausch erfassen;
        // AT: Staking/Airdrop mit Anschaffungskosten 0 (§ 27b Abs. 2 EStG), der Zuflusswert zählt nicht
        case .FR: keys = ["tax_note_fr", "tax_note_stable_swap"]
        case .AT: keys = ["tax_note_at", "tax_note_stable_swap", "tax_note_at_staking"]
        case .UK: keys = ["tax_note_uk"]
        case .NEUTRAL, .DE, .CH, .CA, .JP: keys = []
        }
        var notes = keys.map { L($0) }
        if let source = Self.networkFeeSource(taxTemplate) { notes.append(L("tax_note_network_fee", source)) }
        if let source = Self.withdrawalFeeSource(taxTemplate) { notes.append(L("tax_note_withdrawal_fee", source)) }
        // Gebühr USDT/Fiat einer Umbuchung ebenso (gleiche Vorlagen, `TaxTemplate.quoteFeeIsInfo`)
        if taxTemplate.quoteFeeIsInfo { notes.append(L("tax_note_transfer_fee")) }
        if let source = Self.spouseGiftSource(taxTemplate) { notes.append(L("tax_note_spouse_gift", source)) }
        return notes
    }

    /// Quelle der Regel «Netzwerkgebühr = Veräusserung zum Marktwert» (`TaxTemplate.networkFeeIsDisposal`); sonst nil.
    private static func networkFeeSource(_ t: TaxTemplate) -> String? {
        switch t {
        case .UK: return "HMRC CRYPTO22280"
        case .US: return "IRS Notice 2014-21, IRS digital asset FAQ"
        case .CA: return "CRA, Guide for cryptocurrency users"
        case .AU: return "ATO, CGT event A1"
        case .JP: return "国税庁 FAQ"
        case .NEUTRAL, .DE, .AT, .CH, .FR: return nil
        }
    }

    /// Quelle der Regel «Auszahlungsgebühr USDT/Fiat = kein Verlust, nur Information» (`TaxTemplate.quoteFeeIsInfo`); UK
    /// ausdrücklich (CRYPTO22150), sonst die Behörde ohne eigene Regel dazu — wie Android.
    private static func withdrawalFeeSource(_ t: TaxTemplate) -> String? {
        switch t {
        case .UK: return "HMRC CRYPTO22150"
        case .US: return "IRS digital asset FAQ"
        case .CA: return "CRA"
        case .AU: return "ATO"
        case .JP: return "国税庁 FAQ"
        case .NEUTRAL, .DE, .AT, .CH, .FR: return nil
        }
    }

    /// Quelle der Regel «Schenkung an Ehe-/Lebenspartner ohne Gewinn/Verlust» (`TaxTemplate.spouseGiftNeutral`); sonst nil.
    private static func spouseGiftSource(_ t: TaxTemplate) -> String? {
        switch t {
        case .UK: return "TCGA 1992 s. 58"
        case .CA: return "ITA 73(1)"
        case .NEUTRAL, .DE, .AT, .CH, .US, .AU, .FR, .JP: return nil
        }
    }
}
