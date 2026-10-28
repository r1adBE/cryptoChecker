package com.cryptochecker.app.data

import android.content.Context
import androidx.core.content.edit
import com.cryptochecker.app.domain.logos.CoinLogos
import com.cryptochecker.app.domain.logos.CoinMeta
import com.cryptochecker.marketdata.model.exchange.BinanceAlpha
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import com.cryptochecker.app.domain.logos.alphaNames
import com.cryptochecker.app.domain.logos.alphaSymbols
import com.cryptochecker.app.domain.logos.alphaTokens

/**
 * Binance-Alpha-Liste als Ergebnis: Kürzel für «α» ([symbols], gespeichert), Token für die Alpha-Paare von
 * Binance ([tokens]) und ihre Namen ([names]). Geholt wird sie von [CoinListSync] ([CoinListSync.refreshLists]);
 * hier nur Speichern und Veröffentlichen. Keine eigene Sperre: jeder Wert ist ein einzelner StateFlow bzw. ein
 * einzelner Eintrag der Einstellungen. Teil von [CoinLogoRepository].
 */
@Singleton
class AlphaTokens @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val prefs by lazy { CoinLogoStorage.prefs(context) }

    private val _symbols = MutableStateFlow(loadSymbols())

    /**
     * Kürzel der Alpha-Token ([CoinLogos.alphaSymbols]): Binance-Alpha-Liste ohne regulär an
     * Binance Spot gelistete. Kommt als Erstes im Abgleich ([CoinListSync.refreshLists]) und ist gespeichert; bis
     * dahin aus dem mitgelieferten Auszug ([CoinMeta.SNAPSHOT_FILE]). Für das Symbol «α»
     * ([com.cryptochecker.app.domain.watch.AssetKind]) und «inkl. Alpha-Tokens».
     */
    val symbols: StateFlow<Set<String>> = _symbols.asStateFlow()

    private val _tokens = MutableStateFlow<List<BinanceAlpha.Token>>(emptyList())

    /**
     * Token der Alpha-Liste für die Alpha-Paare von Binance ([BinanceAlpha.pairs], Kennung `alphaId`) und
     * «gleicher Token»: aus den gespeicherten Listen ([CoinListSync.loadAlphaTokens] lädt sie einmal), nach jeder
     * neuen Alpha-Liste ersetzt. Leer, bis geladen.
     */
    val tokens: StateFlow<List<BinanceAlpha.Token>> = _tokens.asStateFlow()

    private val _names = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Namen der Alpha-Token je Kürzel ([CoinLogos.alphaNames]) — weniger Fehltreffer bei «α» ([com.cryptochecker.app.domain.watch.AssetKind]). */
    val names: StateFlow<Map<String, String>> = _names.asStateFlow()

    /** Neue Alpha-Liste speichern (auch leer: «schon einmal geholt»). */
    fun storeSymbols(symbols: Set<String>) {
        prefs.edit { putStringSet(KEY_ALPHA_SYMBOLS, symbols) }
        _symbols.value = symbols
    }

    /** Frisch geholte Alpha-Liste speichern, mit Zeitpunkt (Frische für [CoinListSync.refreshLists]). */
    fun storeFetched(symbols: Set<String>, now: Long) {
        storeSymbols(symbols)
        prefs.edit { putLong(KEY_ALPHA_TIME, now) }
    }

    /** Zeitpunkt der zuletzt direkt geholten Alpha-Liste (0 = nie). */
    fun fetchedAt(): Long = prefs.getLong(KEY_ALPHA_TIME, 0L)

    /** Kürzel aus dem mitgelieferten Auszug, ohne zu speichern (gilt, bis eine Liste kommt). */
    fun showSnapshot(symbols: Set<String>) {
        _symbols.value = symbols
    }

    /** Token und ihre Namen aus [entries] veröffentlichen. */
    fun publishTokens(entries: List<CoinLogos.AlphaEntry>) {
        val tokens = CoinLogos.alphaTokens(entries)
        _tokens.value = tokens
        _names.value = CoinLogos.alphaNames(tokens)
    }

    /** Wurde die Alpha-Liste schon einmal gespeichert? Sonst einmal die ganze Logo-Liste holen. */
    fun hasStoredSymbols(): Boolean = runCatching { prefs.contains(KEY_ALPHA_SYMBOLS) }.getOrDefault(false)

    private fun loadSymbols(): Set<String> =
        runCatching { prefs.getStringSet(KEY_ALPHA_SYMBOLS, null)?.toSet() }.getOrNull() ?: emptySet()

    private companion object {
        const val KEY_ALPHA_SYMBOLS = "alpha_symbols_v1"
        /** Zeitpunkt der zuletzt geholten Alpha-Liste bzw. des letzten Versuchs allein ([CoinListSync.refreshAlphaOnly]). */
        const val KEY_ALPHA_TIME = "alpha_time_v1"
    }
}
