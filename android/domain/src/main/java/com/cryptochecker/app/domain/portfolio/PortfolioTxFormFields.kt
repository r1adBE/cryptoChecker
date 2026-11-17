// Welche Felder das Buchungsblatt je Art zeigt — Teil von PortfolioTxForm.
package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal

fun PortfolioTxForm.fields(type: PortfolioTxType): PortfolioTxForm.Fields = when (type) {
    PortfolioTxType.BUY, PortfolioTxType.SELL ->
        PortfolioTxForm.Fields(
            price = true,
            feeWithUnit = true,
            coinFeeOnly = false,
            thirdCoinFee = true,
            route = false,
            exchange = true,
            outflow =
            type == PortfolioTxType.SELL
        )
    PortfolioTxType.REWARD, PortfolioTxType.DEPOSIT ->
        PortfolioTxForm.Fields(
            price = true,
            feeWithUnit = false,
            coinFeeOnly = false,
            thirdCoinFee = false,
            route = false,
            exchange = true,
            outflow = false
        )
    // Verschenkt: wie eine Auszahlung (Marktwert je Coin optional, Netzwerkgebühr im Coin)
    PortfolioTxType.WITHDRAWAL, PortfolioTxType.GIFT ->
        PortfolioTxForm.Fields(
            price = true,
            feeWithUnit = false,
            coinFeeOnly = true,
            thirdCoinFee = true,
            route = false,
            exchange = true,
            outflow = true
        )
    // Damit bezahlt: Marktwert je Coin (Erlös = Menge × Wert, kein Geld erhalten); Gebühren wie beim Verkauf
    PortfolioTxType.PAYMENT ->
        PortfolioTxForm.Fields(
            price = true,
            feeWithUnit = true,
            coinFeeOnly = false,
            thirdCoinFee = true,
            route = false,
            exchange = true,
            outflow = true
        )
    PortfolioTxType.TRANSFER ->
        PortfolioTxForm.Fields(
            price = false,
            feeWithUnit = false,
            coinFeeOnly = true,
            thirdCoinFee = true,
            route = true,
            exchange = false,
            outflow = true
        )
    // Tausch: statt Preis der Wert des Tauschs ([swapValue]); Gebühr USDT/Coin, dazu dritter Coin oder erhaltener Coin
    PortfolioTxType.SWAP ->
        PortfolioTxForm.Fields(
            price = false,
            feeWithUnit = true,
            coinFeeOnly = false,
            thirdCoinFee = true,
            route = false,
            exchange = true,
            outflow = true
        )
}
