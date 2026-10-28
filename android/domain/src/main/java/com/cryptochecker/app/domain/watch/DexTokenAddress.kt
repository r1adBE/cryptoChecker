package com.cryptochecker.app.domain.watch

import com.cryptochecker.marketdata.model.exchange.DexScreener

/**
 * Vertragsadresse des Basis-Tokens eines DEX-Pools («baseToken.address» aus der DexScreener-Suche) fürs Paar-Blatt:
 * Gleichnamige Token (zweimal «PEPE») sind nur über die Adresse sicher zu unterscheiden. Gemerkt wird sie beim
 * Hinzufügen je Pool-Kennung ([DexLiquidity.key]) in einer kleinen Einstellungsdatei — nicht im Paar selbst, weil
 * Basis, Gegenwährung und Kennung zur Identität des Paars gehören.
 * Reine Logik — wie `DexTokenAddress.swift`.
 */
object DexTokenAddress {

    /** Längste angenommene Adresse; EVM 42, Solana bis 44 Zeichen — mehr ist keine Adresse. */
    const val MAX_LENGTH = 128

    /** Bereinigte Adresse; null = keine (leer, zu lang oder mit Leerraum mitten drin). */
    fun cleaned(address: String?): String? {
        val trimmed = address?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.length > MAX_LENGTH || trimmed.any { it.isWhitespace() }) return null
        return trimmed
    }

    /** Gekürzt für die Anzeige («0x1234…abcd», wie in der Trefferliste der Suche). */
    fun shown(address: String): String = DexScreener.shortAddress(address)
}
