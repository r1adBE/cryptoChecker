package com.cryptochecker.app.data

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.ConnectivityManager
import com.cryptochecker.app.domain.logos.CoinLists
import com.cryptochecker.app.domain.logos.CoinLogos
import com.cryptochecker.app.domain.logos.CoinMeta
import com.cryptochecker.marketdata.model.exchange.BinanceAlpha
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coin-Logos, Namen und Alpha-Kennzeichen (Regeln in [CoinLogos], [CoinMeta], [CoinLists]). Nichts
 * wird einzeln geholt — so erfährt niemand, welche Coins in einer Merkliste stehen:
 *
 * 1. Zuerst und gleichzeitig Binance-Symbolliste und Alpha-Liste ([refreshLists]): Namen und
 *    Alpha-Kennzeichen sind wenige Sekunden nach dem Start da — unabhängig von CoinGecko und den
 *    Bildern. Fehlt eine direkt, kommt sie aus `coin_meta.json` (Release «data», nur Binance-Daten).
 *    Vor der ersten Liste gelten gespeicherte Namen bzw. der mitgelieferte Auszug
 *    ([CoinMeta.SNAPSHOT_FILE]).
 * 2. Rangliste von CoinGecko (Rang 1–2500, höchstens einmal pro Tag — CoinGecko: höchstens 24 h
 *    zwischenspeichern), zusammengeführt mit den Listen aus 1 ([CoinMeta.merge]). Fehlte eine Quelle,
 *    bleibt Bekanntes erhalten und der Abruf wird nach einigen Stunden wiederholt.
 * 3. [startSync] lädt die Logos **aller** Coins dieser Liste von den Bildservern von CoinGecko und
 *    Binance, mehrere gleichzeitig je Server; die Bilder der grössten 1000 beginnen schon, während die
 *    übrigen Seiten der Rangliste noch laden. Später fehlen nur neu dazugekommene oder geänderte. Bremst
 *    ein Bild-Server (429/403/503), pausiert nur dieser.
 * 4. Angezeigt wird nur aus Speicher und Datei ([logo]); fehlt ein geladenes Logo, das
 *    mitgelieferte Icon-Paket ([CoinLogos.ICON_PACK_FILE], CC0), sonst Initialen.
 *
 * Alle drei Schalter «Coin-Logos» aus → [startSync] lädt keine Bilder.
 *
 * Dünne Fassade für die bisherigen Aufrufer; die Arbeit liegt in [CoinListSync] (Listen, Rangliste, Abgleich),
 * [LogoImageStore] (Bilder: Dateien, Speicher, Abruf), [AlphaTokens] (Alpha-Liste) und [CoinNames] (Namen,
 * Aktiennamen, TradFi). Jede Klasse hat ihre eigenen Sperren; Reihenfolge siehe [CoinListSync].
 */
@Singleton
class CoinLogoRepository @Inject constructor(
    private val listSync: CoinListSync,
    private val images: LogoImageStore,
    private val alpha: AlphaTokens,
    private val coinNames: CoinNames,
) {
    /** Letzter Abgleich scheiterte am fehlenden Netz: [CoinLogoSync] holt ihn nach ([CoinListSync.retryPending]). */
    val retryPending: StateFlow<Boolean> get() = listSync.retryPending

    /** Namen der Coins und TradFi-Paare ([CoinNames.names]). */
    val names: StateFlow<Map<String, String>> get() = coinNames.names

    /** Name für ein Paar der Merkliste (TradFi beachtet); null = keiner bekannt. */
    fun name(exchangeKey: String, base: String, quote: String, contractType: String): String? =
        coinNames.name(exchangeKey, base, quote, contractType)

    /** Steigt, sobald neue Logos auf dem Gerät liegen (Anzeigen laden dann neu). */
    val revision: StateFlow<Int> get() = images.revision

    /** Paare der Merkliste, die TradFi sind ([CoinNames.tradFiPairs]). */
    val tradFiPairs: StateFlow<Set<String>> get() = coinNames.tradFiPairs

    fun isTradFi(exchangeKey: String, base: String, quote: String, contractType: String): Boolean =
        coinNames.isTradFi(exchangeKey, base, quote, contractType)

    /** Schlüssel für die Plakette eines Paars (TradFi oder Krypto). */
    fun logoKey(exchangeKey: String, base: String, quote: String, contractType: String): String =
        CoinLogos.logoKey(base, isTradFi(exchangeKey, base, quote, contractType))

    /** Neue Liste der TradFi-Paare; true, wenn sie sich geändert hat. */
    fun setTradFiPairs(pairs: Set<String>): Boolean = coinNames.setTradFiPairs(pairs)

    /** Kürzel der Alpha-Token ([AlphaTokens.symbols]). */
    val alphaSymbols: StateFlow<Set<String>> get() = alpha.symbols

    /** Token der Alpha-Liste ([AlphaTokens.tokens]). */
    val alphaTokens: StateFlow<List<BinanceAlpha.Token>> get() = alpha.tokens

    /** Namen der Alpha-Token je Kürzel ([AlphaTokens.names]). */
    val alphaNames: StateFlow<Map<String, String>> get() = alpha.names

    /** Gespeicherte Alpha-Token (nie Netz); liest die gespeicherten Listen beim ersten Aufruf. */
    suspend fun loadAlphaTokens(): List<BinanceAlpha.Token> = listSync.loadAlphaTokens()

    /** Nur die Alpha-Liste holen ([CoinListSync.refreshAlphaOnly]). */
    suspend fun refreshAlphaOnly() = listSync.refreshAlphaOnly()

    /** Binance- und Alpha-Liste holen, wenn fällig ([CoinListSync.refreshLists]). true = neue Listen. */
    suspend fun refreshLists(includeBinance: Boolean = true): Boolean = listSync.refreshLists(includeBinance)

    /** TradFi-Kürzel der gespeicherten Paarlisten ([CoinNames.setTradFiBases]). */
    fun setTradFiBases(bases: Set<String>): Boolean = coinNames.setTradFiBases(bases)

    /** Aktiennamen der Nasdaq-Symbolliste ([CoinNames.refreshStockNames]). */
    suspend fun refreshStockNames() = coinNames.refreshStockNames()

    /** Sofort, ohne Datei und Netz: Bild aus dem Speicher oder null. */
    fun cached(symbol: String): Bitmap? = images.cached(symbol)

    /** Logo für [symbol] aus Speicher oder Datei — nie aus dem Netz ([LogoImageStore.logo]). */
    suspend fun logo(symbol: String): Bitmap? = images.logo(symbol)

    /**
     * Erster Start: die Logos der Start-Coins ([symbols]) vor dem übrigen Abgleich holen, damit die
     * Start-Auswahl nicht erst Initialen zeigt und nach ein paar Sekunden umspringt. Die Start-Coins
     * sind für alle gleich (die grössten Coins) — die Abrufe verraten also nichts über eine
     * Merkliste; einzelne Coins einer Merkliste werden weiterhin nie einzeln geholt.
     * @return true, wenn danach alle Logos da sind (fehlt eines, zeigt die Zeile Initialen)
     */
    suspend fun ensureStarterLogos(symbols: Collection<String>): Boolean =
        images.ensureStarterLogos(symbols, listSync.symbolMapOrEmpty())

    /** Abgleich im Hintergrund ([CoinListSync.startSync]). */
    fun startSync(
        images: Boolean = true,
        onDone: suspend (added: Int) -> Unit = {},
    ) = listSync.startSync(images, onDone)

    /** Läuft gerade ein Abgleich? ([CoinLogoSync] holt erst danach nach.) */
    val isSyncing: Boolean get() = listSync.isSyncing

    /** Aufeinanderfolgende, am Netz gescheiterte Abgleiche ([CoinListSync.networkFailures]). */
    val networkFailures: Int get() = listSync.networkFailures

    /** Zeitpunkt des letzten am Netz gescheiterten Abgleichs (ms). */
    val lastNetworkFailureAt: Long get() = listSync.lastNetworkFailureAt

    /** Gespeicherte Namen in [names] bringen — nur aus der Datei ([CoinNames.loadSavedNames]). */
    suspend fun loadSavedNames() = coinNames.loadSavedNames()
}

/** Gemeinsame Ablage der Logo-Klassen: eine Einstellungsdatei, gleiche Fristen und Hilfen. */
object CoinLogoStorage {
    private const val PREFS = "coin_logos"
    const val IMAGE_DIR = "coin_logos"

    /** Rangliste, Ersatzdatei, Aktiennamen nicht erreichbar: frühestens nach einer Stunde erneut. */
    const val MAP_RETRY_MILLIS = 60L * 60 * 1000

    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Inhalt der Datei; unlesbar oder leer → null. */
    fun readNonBlank(file: File): String? = runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * Nicht getaktetes Netz (WLAN, Ethernet)? Unbekannt oder kein Netz → false (lieber sparsam).
     * Nur für die Zusatzseiten der Rangliste und deren Logos ([CoinLogos.downloadable]).
     */
    fun isUnmetered(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching false
        !manager.isActiveNetworkMetered
    }.getOrDefault(false)
}
