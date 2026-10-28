package com.cryptochecker.app.data

import android.content.Context
import androidx.core.content.edit
import com.cryptochecker.app.data.remote.callExchange
import com.cryptochecker.app.data.remote.util.HttpBodyLimit
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.logos.CoinLists
import com.cryptochecker.app.domain.logos.CoinLogos
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import com.cryptochecker.app.domain.logos.parseSymbolDirectory
import com.cryptochecker.app.domain.logos.withStockNames

/**
 * Namen der Coins und TradFi-Paare ([names]) und welche Paare TradFi sind ([tradFiPairs]). Die Namen der Listen
 * (Rangliste, Binance-/Alpha-Liste) liefert [CoinListSync]; hier kommen die Aktiennamen der Nasdaq-Symbolliste
 * dazu ([refreshStockNames]) und das Ergebnis wird veröffentlicht. Teil von [CoinLogoRepository].
 *
 * Sperren: [namesMutex] für die Namen der Listen (Datei und Stand), [stockMutex] für die Aktiennamen. Beide werden
 * nie zusammen gehalten; [CoinListSync] ruft mit seinen Sperren (Zuordnung, Listen) die Funktionen hier auf —
 * Reihenfolge also immer CoinListSync → [namesMutex], nie umgekehrt (hier wird nichts von CoinListSync gerufen).
 */
@Singleton
class CoinNames @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val apiClient: OkHttpClient,
) {
    private val prefs by lazy { CoinLogoStorage.prefs(context) }
    private val namesFile by lazy { File(context.noBackupFilesDir, NAMES_FILE) }
    private val stockNamesFile by lazy { File(context.noBackupFilesDir, STOCK_NAMES_FILE) }

    private val namesMutex = Mutex()
    private val stockMutex = Mutex()

    /** Namen aus Rangliste und Binance-Liste (ohne Aktiennamen). */
    @Volatile private var baseNames: Map<String, String> = emptyMap()

    /** [baseNames] stammen nur aus dem mitgelieferten Auszug (frische Listen haben dann Vorrang). */
    @Volatile private var namesFromSnapshot = false

    /** Alle US-Aktien der Nasdaq-Symbolliste: Kürzel → Name; null = noch nicht gelesen. */
    @Volatile private var stockNames: Map<String, String>? = null

    private val _names = MutableStateFlow<Map<String, String>>(emptyMap())

    /**
     * Namen der Coins und TradFi-Paare («BTC» → «Bitcoin», «TRADFI:NVDA» → «NVIDIA»), aus denselben
     * Listen wie die Logos (Schlüssel [CoinLogos.nameKey]); leer, bis die Liste geladen ist.
     */
    val names: StateFlow<Map<String, String>> = _names.asStateFlow()

    /** Name für ein Paar der Merkliste (TradFi beachtet); null = keiner bekannt. */
    fun name(exchangeKey: String, base: String, quote: String, contractType: String): String? =
        _names.value[CoinLogos.nameKey(base, isTradFi(exchangeKey, base, quote, contractType))]

    private val _tradFiPairs = MutableStateFlow(loadTradFiPairs())

    /**
     * Paare der Merkliste, die TradFi sind (Kennungen [CoinLogos.pairKey]); ihr Logo kommt nur aus
     * dem TradFi-Namensraum ([CoinLogos.tradFiKey]). Gespeichert, damit auch Widgets es wissen.
     */
    val tradFiPairs: StateFlow<Set<String>> = _tradFiPairs.asStateFlow()

    fun isTradFi(exchangeKey: String, base: String, quote: String, contractType: String): Boolean =
        CoinLogos.pairKey(exchangeKey, base, quote, contractType) in _tradFiPairs.value

    /** Neue Liste der TradFi-Paare; true, wenn sie sich geändert hat. */
    fun setTradFiPairs(pairs: Set<String>): Boolean {
        if (pairs == _tradFiPairs.value) return false
        prefs.edit { putStringSet(KEY_TRADFI_PAIRS, pairs) }
        _tradFiPairs.value = pairs
        return true
    }

    private val _tradFiBases = MutableStateFlow(loadTradFiBases())

    /** TradFi-Kürzel der gespeicherten Paarlisten ([setTradFiBases]); für die Aktien-Logos ([CoinLogos.withStockLogos]). */
    val tradFiBases: Set<String> get() = _tradFiBases.value

    /**
     * Alle TradFi-Kürzel der gespeicherten Paarlisten der Futures-Börsen der Merkliste (nicht nur
     * die beobachteten): für sie lädt der Abgleich Aktien-Logos ([CoinLogos.withStockLogos]) — für
     * alle gleich, so verrät der Abruf keine einzelne Aktie.
     */
    fun setTradFiBases(bases: Set<String>): Boolean {
        if (bases == _tradFiBases.value) return false
        prefs.edit { putStringSet(KEY_TRADFI_BASES, bases) }
        _tradFiBases.value = bases
        publishNames()
        return true
    }

    /** [names] = Namen der Listen, dazu die Aktiennamen der TradFi-Kürzel ([CoinLogos.withStockNames]). */
    private fun publishNames() {
        _names.value = CoinLogos.withStockNames(baseNames, stockNames.orEmpty(), _tradFiBases.value)
    }

    // ---- Namen der Listen (von CoinListSync)

    /** Noch keine Namen der Listen bekannt (weder gespeichert noch aus dem Auszug)? */
    val isEmpty: Boolean get() = baseNames.isEmpty()

    /** Anzahl der Namen der Listen (Protokoll). */
    val size: Int get() = baseNames.size

    /**
     * Gespeicherte Namen in [names] bringen — nur aus der Datei, nie aus dem Netz (für die Suche über
     * den Namen, auch wenn Logos und Namen ausgeschaltet sind und kein Abgleich läuft). Wartet nie auf
     * einen laufenden Abgleich (eigene Sperre statt der der Rangliste).
     */
    suspend fun loadSavedNames() = namesMutex.withLock { loadSavedNamesLocked() }

    /** Wie [loadSavedNames]; nur mit gehaltener [namesMutex]. */
    private suspend fun loadSavedNamesLocked() {
        if (baseNames.isNotEmpty()) return
        val saved = withContext(Dispatchers.IO) {
            CoinLogos.decodeNames(CoinLogoStorage.readFirst(namesFile, File(context.noBackupFilesDir, OLD_NAMES_FILE)))
        }
        if (saved.isEmpty()) return
        baseNames = saved
        namesFromSnapshot = false
        publishNames()
    }

    /** Start ohne gespeicherte Namen: Namen aus dem mitgelieferten Auszug ([snapshot] erst, wenn nötig). */
    suspend fun showSnapshotIfEmpty(snapshot: () -> Map<String, String>) {
        namesMutex.withLock {
            if (baseNames.isEmpty()) {
                baseNames = snapshot()
                namesFromSnapshot = true
                publishNames()
            }
        }
    }

    /** Neue Binance-/Alpha-Listen: ihre Namen sofort (gespeicherte mit CoinGecko-Namen behalten Vorrang). */
    suspend fun publishListNames(listNames: Map<String, String>) {
        namesMutex.withLock {
            loadSavedNamesLocked()
            val merged = CoinLists.publishNames(baseNames, namesFromSnapshot, listNames)
            withContext(Dispatchers.IO) {
                runCatchingCancellable {
                    namesFile.parentFile?.mkdirs()
                    namesFile.writeText(CoinLogos.encodeNames(merged))
                }.onFailure { Timber.w(it, "Coin-Namen nicht gespeichert") }
            }
            baseNames = merged
            namesFromSnapshot = false
            publishNames()
        }
    }

    /**
     * Namen einer neuen Rangliste übernehmen: vollständig ([full]) ersetzen, sonst nur Lücken füllen
     * ([CoinLogos.withKnown]). Ergebnis: die neuen Namen (gespeichert von [CoinListSync] mit [writeNamesFile]).
     */
    suspend fun applyRankingNames(names: Map<String, String>, full: Boolean): Map<String, String> =
        namesMutex.withLock {
            val n = if (full) names else CoinLogos.withKnown(names, baseNames)
            baseNames = n
            namesFromSnapshot = false
            publishNames()
            n
        }

    /** Namen-Datei schreiben (wirft bei Fehlern; der Aufrufer fasst sie mit der Zuordnung zusammen). */
    fun writeNamesFile(names: Map<String, String>) {
        namesFile.writeText(CoinLogos.encodeNames(names))
    }

    // ---- Aktiennamen (Nasdaq)

    /**
     * Aktiennamen («CAT» → «Caterpillar, Inc.») aus der offiziellen Nasdaq-Symbolliste (zwei
     * Textdateien mit allen US-Aktien, für alle gleich) — nur wenn es TradFi-Kürzel gibt; höchstens
     * einmal pro Woche, nach einem Fehler frühestens nach einer Stunde erneut.
     */
    suspend fun refreshStockNames() = stockMutex.withLock {
        if (_tradFiBases.value.isEmpty()) return@withLock
        if (stockNames == null) {
            stockNames = withContext(Dispatchers.IO) {
                CoinLogos.decodeNames(runCatchingCancellable { stockNamesFile.readText() }.getOrNull())
            }
            publishNames()
        }
        val now = System.currentTimeMillis()
        if (CoinLogos.isFresh(prefs.getLong(KEY_STOCK_NAMES_TIME, 0L), now, CoinLogos.STOCK_NAMES_TTL_MILLIS) && !stockNames.isNullOrEmpty()) return@withLock
        if (CoinLogos.isFresh(prefs.getLong(KEY_STOCK_NAMES_ATTEMPT, 0L), now, CoinLogoStorage.MAP_RETRY_MILLIS)) return@withLock
        prefs.edit { putLong(KEY_STOCK_NAMES_ATTEMPT, now) }
        val fresh = withContext(Dispatchers.IO) {
            val out = LinkedHashMap<String, String>()
            for (url in listOf(CoinLogos.NASDAQ_LISTED_URL, CoinLogos.OTHER_LISTED_URL)) {
                try {
                    CoinLogos.parseSymbolDirectory(apiClient.callExchange(url, null, HttpBodyLimit.BULK_MAX_BYTES), out)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w("Nasdaq-Symbolliste nicht verfügbar: %s", e.message)
                }
            }
            out
        }
        if (fresh.isEmpty()) return@withLock
        withContext(Dispatchers.IO) {
            runCatchingCancellable {
                stockNamesFile.parentFile?.mkdirs()
                stockNamesFile.writeText(CoinLogos.encodeNames(fresh))
            }.onFailure { Timber.w(it, "Aktiennamen nicht gespeichert") }
        }
        prefs.edit {
            putLong(KEY_STOCK_NAMES_TIME, now)
            remove(OLD_KEY_STOCKS_ETAG)
        }
        stockNames = fresh
        publishNames()
    }

    private fun loadTradFiBases(): Set<String> =
        runCatching { prefs.getStringSet(KEY_TRADFI_BASES, null)?.toSet() }.getOrNull() ?: emptySet()

    private fun loadTradFiPairs(): Set<String> =
        runCatching { prefs.getStringSet(KEY_TRADFI_PAIRS, null)?.toSet() }.getOrNull() ?: emptySet()

    private companion object {
        const val KEY_TRADFI_PAIRS = "tradfi_pairs"
        const val KEY_TRADFI_BASES = "tradfi_bases"
        const val KEY_STOCK_NAMES_TIME = "stock_names_time_v1"
        const val KEY_STOCK_NAMES_ATTEMPT = "stock_names_attempt_v1"
        /** Kennung der früheren GitHub-Liste (nicht mehr benutzt, wird gelöscht). */
        const val OLD_KEY_STOCKS_ETAG = "stocks_etag_v1"
        const val STOCK_NAMES_FILE = "coin_logos/stock_names1.txt"
        const val NAMES_FILE = "coin_logos/names5.txt"
        /** Namen der Vorgängerfassung (bis zur ersten neuen Rangliste gelesen). */
        const val OLD_NAMES_FILE = "coin_logos/names4.txt"
    }
}
