package com.cryptochecker.app.domain.portfolio

/** Höchstlängen der Textfelder einer Transaktion: gleich beim Erfassen und beim Speichern. */
object PortfolioTxLimits {
    /** Höchstlänge eines Gebühr-Coins (wie die Coin-Eingabe des Erfassen-Blatts). */
    const val MAX_FEE_COIN = 15

    /** Börse: höchstens so viele sichtbare Zeichen. */
    const val MAX_EXCHANGE = 40
}
