import Foundation

/// FIFO-Buchhaltung eines Coins — Regeln exakt wie `PortfolioFifo.kt` (dort ausführlich beschrieben):
///  - Kauf, Belohnung, Einzahlung: neuer Posten (ohne Preis mit unbekanntem Einstand).
///  - Reihenfolge der Posten: nach Erwerb (`PortfolioTx.acquisitionTime`, bei einer Einzahlung mit ursprünglichem
///    Kaufdatum dieses), dann Zeit der Transaktion, dann Id; Abgänge verbrauchen immer die ältesten ERWORBENEN Posten.
///    Haltedauer ab dem Erwerb.
///  - Verkauf: verbraucht Posten, älteste zuerst — zuerst die verkaufte Menge, danach die Gebühr im Coin aus den
///    nächsten Posten; Erlös nur nach VERKAUFTER Menge verteilt (Gebühr-Coins: Erlös 0, ihr Einstand = Kosten der
///    Veräusserung in ihrem Posten), Gewinn je Teil = Erlösanteil − Einstand; Teile ohne Kaufpreis (oder Verkauf ohne Preis) unbekannt. Mehr
///    verkauft als erfasst: Bestand gekappt und markiert; der ungedeckte Rest wird eine Veräusserung ohne Kaufpreis
///    (`uncovered`), damit im Steuer-Export kein Verkauf fehlt.
///  - Auszahlung: verbraucht Posten, älteste zuerst, ohne Gewinn und ohne Veräusserung (Einstand in `withdrawals`);
///    Gebühr USDT/dritter Coin: Aufwand (`PortfolioFeeExpenses`).
///  - Gebühr im Coin verbraucht den ganzen Zugang (Kauf/Belohnung/Einzahlung bzw. erhaltener Coin eines Tauschs): kein
///    Posten, Aufwand realisiert (`PortfolioFeeExpenses`).
///  - Umbuchung: Netzwerkgebühr im Coin verbraucht Menge (älteste zuerst), ihr bekannter Einstand geht auf den
///    ältesten verbleibenden Posten mit Kaufpreis über (Kosten des Haltens/Verschiebens, keine Veräusserung — wird mit
///    dem nächsten Verkauf realisiert); ohne solchen Posten wartet er auf den nächsten später hinzukommenden Posten mit
///    Kaufpreis, erst ohne einen solchen bis zum Ende `PortfolioLostFeeCost`; Gebühr USDT + Wert der Gebühr im dritten Coin nach Menge auf alle Posten
///    (Anteil ohne Kaufpreis gehört zu deren unbekanntem Einstand und wird als `PortfolioUnassignedFee` vermerkt; wie
///    je Wallet ebenso der Anteil der Menge über Bestand − Netzwerkgebühr, `PortfolioFeeExpenses.transferUncovered`, markiert oversold).
///  - Herkunft des Einstands: jeder Posten führt seinen Einstand in Teilen mit Zeitpunkt (Erwerb, Umbuchungsgebühr zum
///    Zeitpunkt der Umbuchung, übernommener Netzwerkgebühr-Einstand mit seinen Teilen); ein Abgang nimmt sie anteilig mit
///    (`PortfolioDisposal.costParts`), der Steuer-Export rechnet jeden Teil zum Kurs SEINES Datums um. Die Umbuchungsgebühr
///    auf alle Posten steht nur einmal je Umbuchung da (Anteil je Coin, `FeeStream`), gerechnet erst beim Auslesen
///    (PortfolioFifoParts.swift). Anfangsposten des US-Übergangs tragen nur ihr Erwerbsdatum (Steuerwährung USD, Kurs 1).
///    Geschätzter Wert im Einstand (`PortfolioTx.costEstimated`) bleibt am Posten (auch übernommen, verschoben) — Vermerk am Abgang.
///  - Gebühr im dritten Coin: Veräusserung des Gebühr-Coins (FIFO) zum Gebühr-Preis (`feeLeg`).
///  - Tausch: im abgegebenen Coin wie ein Verkauf (Erlös = Wert − Gebühr USDT − Wert der Gebühr im dritten Coin), im
///    erhaltenen Coin ein Posten `.SWAP` (Einstand = Wert, Erwerb = Zeit des Tauschs, Menge − Gebühr im erhaltenen Coin).
///  - Je Wallet (`perWallet`): je Wallet eine Warteschlange; Umbuchung A → B verschiebt (nach der Netzwerkgebühr) die
///    ältesten Posten von A mit Kaufdatum nach B; fehlt Bestand in einer Wallet, wird nicht aus anderen genommen
///    (ungedeckter Rest bzw. kommt nicht an, `PortfolioWalletShortfall`).
///  - Bestand läuft als Summe mit (kein erneutes Aufsummieren je Schritt).
enum PortfolioFifo {
    /// UTC-Kalendertage zwischen `from` und `to`.
    static func holdingDays(from: Int64, to: Int64) -> Int {
        PortfolioHistory.epochDay(utcMillis: to) - PortfolioHistory.epochDay(utcMillis: from)
    }

    /// `seed`: Anfangsposten je Wallet (`PortfolioLot.wallet` = Anzeigename) vor den Ereignissen — US-Übergang
    /// (`TaxEngines.usTransition`); sonst leer. Wie Android.
    static func run(_ symbol: String, events: [PortfolioLedgerEvent], perWallet: Bool = false,
                    seed: [PortfolioLot] = []) -> PortfolioBookResult {
        let book = PortfolioFifoBook(symbol, perWallet: perWallet)
        book.seed(seed)
        for e in events { book.apply(e) }
        return book.result()
    }
}
