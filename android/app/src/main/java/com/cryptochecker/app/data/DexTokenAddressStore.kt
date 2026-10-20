package com.cryptochecker.app.data

import android.content.Context
import androidx.core.content.edit
import com.cryptochecker.app.domain.watch.DexLiquidity
import com.cryptochecker.app.domain.watch.DexTokenAddress
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Vertragsadresse des Basis-Tokens je DEX-Pool ([DexTokenAddress]), gemerkt beim Hinzufügen aus der Suche. Kleine
 * Einstellungsdatei statt einer Spalte (wie [DexLiquidityStore]) — ohne Datenbankmigration, nicht in der Sicherung;
 * fehlt sie (Paar vor Build 91 hinzugefügt), zeigt das Paar-Blatt einfach keine Adresse. Wie
 * `DexTokenAddressStore` (iOS).
 */
@Singleton
class DexTokenAddressStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    /** Adresse aus der Suche merken; leer/ungültig = nichts ändern (eine frühere Adresse bleibt). */
    fun put(pairId: String?, address: String?) {
        val key = DexLiquidity.key(pairId) ?: return
        val value = DexTokenAddress.cleaned(address) ?: return
        prefs(context).edit { putString(key, value) }
    }

    companion object {
        private const val PREFS = "dex_token_address"

        private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        /** Gemerkte Adresse des Pools [pairId]; null = unbekannt. Für das Paar-Blatt (liest nur die kleine Datei). */
        fun get(context: Context, pairId: String?): String? =
            DexLiquidity.key(pairId)?.let { DexTokenAddress.cleaned(prefs(context).getString(it, null)) }
    }
}
