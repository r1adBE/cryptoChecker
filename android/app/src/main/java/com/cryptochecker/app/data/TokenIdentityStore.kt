package com.cryptochecker.app.data

import android.content.Context
import com.cryptochecker.app.data.local.WatchDao
import com.cryptochecker.app.domain.watch.TokenIdentity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Volle Namen der Coins für die Prüfung «gleicher Token» ([TokenIdentity], K-8): je Börse aus ihrer
 * Paarliste (beim Abgleich der Paare, [saveMarket]) und von Binance aus der Symbolliste der Logos
 * ([saveBinance]). Keine eigene Anfrage — nur, was ohnehin geladen wird. Eine Datei im Ordner ohne
 * Sicherung (wie der Paar-Zwischenspeicher neu ladbar). Wie `TokenIdentityStore.swift`.
 */
@Singleton
class TokenIdentityStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val watchDao: WatchDao,
) {
    private val file: File get() = File(context.noBackupFilesDir, FILE)
    private val lock = Any()

    @Volatile
    private var index: TokenIdentity.NameIndex? = null

    private fun current(): TokenIdentity.NameIndex {
        index?.let { return it }
        synchronized(lock) {
            index?.let { return it }
            val loaded = TokenIdentity.decode(runCatching { file.readText() }.getOrNull())
            index = loaded
            return loaded
        }
    }

    private fun update(change: (TokenIdentity.NameIndex) -> TokenIdentity.NameIndex) {
        synchronized(lock) {
            val before = current()
            val next = change(before)
            if (next == before) return
            index = next
            runCatching {
                val tmp = File(file.parentFile, "$FILE.tmp")
                tmp.writeText(TokenIdentity.encode(next))
                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
            }.onFailure { Timber.d(it, "Coin-Namen nicht gespeichert") }
        }
    }

    /** Name des Coins [base] in der Paarliste der Börse [marketKey]; null = unbekannt. */
    fun pairName(marketKey: String, base: String): String? = current().pairName(marketKey, base)

    /** Namen der Coins der Börse [marketKey] (Kürzel gross, [TokenIdentity.key] → Name); leer = unbekannt. */
    fun marketNames(marketKey: String): Map<String, String> = current().markets[marketKey].orEmpty()

    /** `fullName` von Binance für [base]; null = unbekannt. */
    fun binanceName(base: String): String? = current().binanceName(base)

    /** Namen aus einer frisch geladenen Paarliste; ohne Namen bleibt der bisherige Stand. */
    fun saveMarket(marketKey: String, names: Map<String, String>) {
        val cleaned = TokenIdentity.cleaned(names)
        if (cleaned.isEmpty()) return
        update { it.copy(markets = it.markets + (marketKey to cleaned)) }
    }

    /** Namen aus der Binance-Symbolliste; leer = bisheriger Stand. */
    fun saveBinance(names: Map<String, String>) {
        val cleaned = TokenIdentity.cleaned(names)
        if (cleaned.isEmpty()) return
        update { it.copy(binance = cleaned) }
    }

    /**
     * Name des Coins [coin] laut den aktiven Paaren der Merkliste (alle Börsen mit Namen); widersprechen
     * sie sich, null ([TokenIdentity.consensus]) — dann nur Kürzel + Kursband wie bisher.
     */
    suspend fun watchName(coin: String): String? {
        val key = TokenIdentity.key(coin)
        val watches = try {
            watchDao.getWatches()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d(e, "Coin-Namen: Merkliste nicht lesbar")
            return null
        }
        val names = watches.filter { !it.disabled && TokenIdentity.key(it.baseAsset) == key }
            .map { pairName(it.marketKey, it.baseAsset) }
        return TokenIdentity.consensus(key, names)
    }

    /**
     * Darf eine Binance-Quelle für [base] einspringen, wenn die eigene Seite [ownName] heisst? Nein nur bei
     * klar verschiedenen Namen ([TokenIdentity.compatible]); unbekannt = ja (Kursband entscheidet).
     */
    fun binanceAllowed(base: String, ownName: String?): Boolean =
        ownName == null || TokenIdentity.compatible(base, ownName, binanceName(base))

    /**
     * Darf die Ausweich-Reihe von [provider] (gemeinsamer Zwischenspeicher je Kürzel) beim Paar [base] an
     * [marketKey] erscheinen? Nein bei klar anderem Namen ([TokenIdentity.fallbackSeriesAllowed]) — dann
     * lieber nichts als die Reihe eines anderen Tokens.
     */
    fun fallbackSeriesAllowed(marketKey: String, base: String, provider: String?): Boolean {
        val providerName = TokenIdentity.PROVIDER_MARKETS[provider?.trim()]?.let { pairName(it, base) }
        return TokenIdentity.fallbackSeriesAllowed(base, pairName(marketKey, base), provider, binanceName(base), providerName)
    }

    /**
     * Darf die Quelle [host] der Kerzen-Kette für [base] einspringen, wenn die eigene Seite [ownName] heisst?
     * Gleiche Namenssperre wie Mini-Chart und Widget ([TokenIdentity.chainSourceAllowed]).
     */
    fun chainSourceAllowed(base: String, ownName: String?, host: String): Boolean {
        val provider = com.cryptochecker.app.domain.market.DataFreshness.candleProvider(host)
        val providerName = TokenIdentity.PROVIDER_MARKETS[provider]?.let { pairName(it, base) }
        return TokenIdentity.chainSourceAllowed(base, ownName, host, binanceName(base), providerName)
    }

    private companion object {
        const val FILE = "token_names.json"
    }
}
