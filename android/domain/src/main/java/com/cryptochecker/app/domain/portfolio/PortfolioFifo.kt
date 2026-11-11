package com.cryptochecker.app.domain.portfolio


/**
 * FIFO-Buchhaltung eines Coins (reines Kotlin, Spiegel: `Shared/Portfolio/PortfolioFifo.swift`):
 *  - Kauf, Belohnung, Einzahlung: neuer Posten (erhaltene Menge, Einstand Menge × Preis + Gebühr USDT + Wert
 *    der Gebühr im dritten Coin, Erwerb, Id, Art). Ohne Preis: Posten mit unbekanntem Einstand.
 *  - Reihenfolge der Posten: nach Erwerb ([PortfolioTrade.acquisitionTime] — bei einer Einzahlung mit
 *    ursprünglichem Kaufdatum dieses), dann Zeit der Transaktion, dann Id. Eine Einzahlung von früher gekaufter
 *    Coins reiht sich also VOR jüngere Käufe ein; Verkäufe, Auszahlungen und Gebühren verbrauchen immer die
 *    ältesten ERWORBENEN Posten. Haltedauer ab dem Erwerb.
 *  - Verkauf (auch Damit bezahlt, [PortfolioTxType.PAYMENT], zum Marktwert): verbraucht Posten, älteste zuerst (Menge + Gebühr im Coin). Die Gebühr im Coin gehört zur Veräusserung:
 *    zuerst die verkaufte Menge, DANACH die Gebühr-Coins aus den nächsten Posten (FIFO in einem Zug). Erlös nur nach
 *    VERKAUFTER Menge verteilt; ein Teil aus Gebühr-Coins hat Erlös 0 und sein Einstand zählt als Kosten der Veräusserung
 *    (Verlust in DIESEM Posten mit dessen Haltedauer) — so stimmt die Aufteilung kurz-/langfristig (DE-Praxis: die
 *    Gebühr ist Teil der Veräusserung). Je Teil Gewinn = Erlösanteil − Einstand des Teils; Teile aus Posten ohne Kaufpreis (oder
 *    Verkauf ohne Preis) haben unbekannten Gewinn — die bekannten zählen, «realisiert unvollständig».
 *    Mehr verkauft als erfasst: Bestand und Kennzahlen gekappt (markiert, [PortfolioBookResult.oversold]); für
 *    den Steuer-Export wird der ungedeckte Rest trotzdem eine Veräusserung mit seinem Erlösanteil, ohne
 *    Kaufpreis ([PortfolioDisposal.uncovered]) — so fehlt kein Verkauf.
 *  - Auszahlung: verbraucht Posten, älteste zuerst, OHNE Gewinn (kein Verkauf, keine Veräusserung); Einstand
 *    der verbrauchten Posten in [PortfolioBookResult.withdrawals]. Gebühr USDT/dritter Coin: Aufwand ([PortfolioFeeExpenses]).
 *  - Umbuchung: nichts bewegt sich, Posten behalten ihr Datum. Netzwerkgebühr im Coin verbraucht Menge,
 *    älteste zuerst; deren bekannter Einstand geht auf den ältesten verbleibenden Posten MIT Kaufpreis über
 *    (Summe des Einstands bleibt wie beim Ø). Ohne solchen Posten (übrig nur Posten ohne Kaufpreis) geht er nicht still
 *    verloren: Er geht auf den nächsten später hinzukommenden Posten mit Kaufpreis, sonst [PortfolioLostFeeCost] /
 *    [CoinPosition.lostFeeCostUsdt] (kein realisierter Verlust). Begründung: Die Gebühr ist
 *    Teil der Kosten, die Coins zu halten bzw. zu verschieben — sie ist keine Veräusserung (kein Erlös) und
 *    kein Verlust im Jahr der Umbuchung, sondern erhöht den Einstand der übrigen Coins; sie landet beim
 *    ältesten Posten, damit sie mit dem nächsten Verkauf realisiert wird. Gebühr USDT + Wert der Gebühr
 *    im dritten Coin verteilen sich nach Menge auf alle Posten; der Anteil von Posten ohne Kaufpreis gehört
 *    zu deren unbekanntem Einstand (wie beim Ø), vermerkt als [PortfolioUnassignedFee]; ebenso (wie je Wallet) der Anteil
 *    der Menge, die Bestand − Netzwerkgebühr übersteigt ([PortfolioFeeExpenses.transferUncovered], markiert [PortfolioBookResult.oversold]).
 *  - Herkunft des Einstands: jeder Posten führt seinen Einstand in Teilen mit Zeitpunkt ([PortfolioCostPart]: Erwerb,
 *    zugeschlagene Umbuchungsgebühr zum Zeitpunkt der Umbuchung, übernommener Netzwerkgebühr-Einstand mit seinen Teilen);
 *    ein Abgang nimmt die Teile anteilig mit ([PortfolioDisposal.costParts]) — der Steuer-Export rechnet so jeden Teil
 *    zum Devisenkurs SEINES Datums um (Posten 2018 + Umbuchungsgebühr 2024: je zum eigenen Kurs; Umbuchungsgebühr einmal
 *    je Umbuchung, gerechnet erst beim Auslesen: [PortfolioFifoParts]). [TaxEngines.usTransition]: Anfangsposten nur mit Erwerbsdatum (USD).
 *    Geschätzter Wert im Einstand ([PortfolioTrade.costEstimated]) bleibt am Posten (auch übernommen, verschoben) — Vermerk am Abgang.
 *  - Gebühr im dritten Coin: im Gebühr-Coin eine Veräusserung (FIFO) zum Gebühr-Preis ([PortfolioDisposal.feeLeg]);
 *    Gewinn nur mit Gebühr-Preis bekannt. In der Haupttransaktion zählt ihr Wert zum Einstand (Kauf) bzw.
 *    mindert den Erlös (Verkauf).
 *  - Tausch ([PortfolioTxType.SWAP]): im abgegebenen Coin eine Veräusserung wie ein Verkauf (Menge + Gebühr im Coin,
 *    Erlös = Wert des Tauschs − Gebühr USDT − Wert der Gebühr im dritten Coin; Wert unbekannt → Erlös unbekannt); im
 *    erhaltenen Coin ein neuer Posten ([PortfolioTxType.SWAP]) mit erhaltener Menge (− Gebühr im erhaltenen Coin),
 *    Einstand = Wert des Tauschs (unbekannt → Posten ohne Kaufpreis), Erwerb = Zeit des Tauschs. Jede Gebühr zählt so
 *    genau einmal (wie Verkauf + Kauf mit Gebühr im erhaltenen Coin).
 *  - Je Wallet ([CostMethod.FIFO_WALLET], `perWallet`): je Wallet eine eigene Warteschlange ([PortfolioWallets]).
 *    Kauf, Belohnung, Einzahlung, Verkauf, Tausch, Auszahlung in der Wallet ihrer Börse; Gebühr-Abgang in der Wallet
 *    der Haupttransaktion. Umbuchung von A nach B: in A zuerst die Netzwerkgebühr (älteste Posten), dann die Menge;
 *    die verschobenen Teile kommen mit Kaufdatum und Einstand nach B; der Einstand der Netzwerkgebühr geht auf den
 *    ältesten verschobenen Teil mit Kaufpreis (sonst den ältesten in A, sonst in B), Gebühr USDT + Wert der Gebühr im dritten Coin
 *    nach Menge auf die verschobenen Teile. Reicht der Bestand einer Wallet nicht, wird NICHT aus anderen Wallets
 *    genommen: Verkauf/Tausch/Gebühr-Abgang → ungedeckter Rest ([PortfolioDisposal.uncovered]), Umbuchung → der Rest
 *    kommt nicht an; jedes Mal ein [PortfolioWalletShortfall] und [PortfolioBookResult.oversold].
 *  - Gebühr als Aufwand ([PortfolioFeeExpenses]): Gebühr im Coin verbraucht den ganzen Zugang (Kauf/Belohnung/
 *    Einzahlung bzw. erhaltener Coin eines Tauschs) → kein Posten, Aufwand realisiert; Gebühr USDT/dritter Coin einer
 *    Auszahlung → Aufwand realisiert.
 *  - Netzwerkgebühr-Einstand ohne bezahlten Posten: wartet in der Warteschlange und geht auf den nächsten Posten MIT
 *    Kaufpreis, der später hinzukommt (Kauf, Tausch, Einzahlung, verschobener Posten); erst wenn bis zum Ende keiner
 *    kommt, steht er als [PortfolioLostFeeCost] da. Bestand läuft als Summe mit (kein erneutes Aufsummieren je Schritt).
 */
object PortfolioFifo {

    private const val DAY_MILLIS = 86_400_000L

    /**
     * Kalendertage zwischen [from] und [to] in [zone] (Standard UTC — so im Feld `holdingDays` der
     * Veräusserungen, gleich wie iOS). Die Steuer-Liste ([TaxExport.gainRows]) rechnet mit derselben
     * Funktion in ihrer Zeitzone — beide Wege können sich nicht widersprechen.
     */
    fun holdingDays(from: Long, to: Long, zone: java.time.ZoneId = java.time.ZoneOffset.UTC): Long =
        if (zone == java.time.ZoneOffset.UTC) {
            Math.floorDiv(to, DAY_MILLIS) - Math.floorDiv(from, DAY_MILLIS)
        } else {
            java.time.temporal.ChronoUnit.DAYS.between(
                java.time.Instant.ofEpochMilli(from).atZone(zone).toLocalDate(),
                java.time.Instant.ofEpochMilli(to).atZone(zone).toLocalDate(),
            )
        }

    /**
     * [seed]: Anfangsposten je Wallet ([PortfolioLot.wallet] = Anzeigename) vor den Ereignissen — US-Übergang
     * ([TaxEngines.usTransition]); sonst leer.
     */
    fun run(
        symbol: String,
        events: List<PortfolioLedgerEvent>,
        perWallet: Boolean = false,
        seed: List<PortfolioLot> = emptyList(),
    ): PortfolioBookResult {
        val book = PortfolioFifoBook(symbol, perWallet)
        book.seed(seed)
        for (e in events) book.apply(e)
        return book.result()
    }
}
