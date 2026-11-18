package com.cryptochecker.app.logos

import com.cryptochecker.app.data.logos.CoinLogoRepository
import com.cryptochecker.app.ui.components.CoinLogoSource
import javax.inject.Inject

/** CoinBadge holt die Logos über das Repository (Speicher → Datei → CoinGecko). */
class RepositoryLogoSource @Inject constructor(private val repository: CoinLogoRepository) : CoinLogoSource {
    override val revision = repository.revision
    override val loading = repository.logosLoading
    override val loadingPercent = repository.logosLoadingPercent
    override val tradFiPairs = repository.tradFiPairs
    override val names = repository.names
    override fun cached(symbol: String) = repository.cached(symbol)
    override suspend fun load(symbol: String) = repository.logo(symbol)
    override suspend fun loadSavedNames() = repository.loadSavedNames()
    override suspend fun firstLoadDone() = repository.logosFirstLoadDone()
    override suspend fun hasLogo(symbol: String) = repository.hasLogo(symbol)
    override suspend fun notYetLoaded(symbols: List<String>) = repository.logosNotYetLoaded(symbols)
    override val missingDismissed = repository.missingLogosDismissed
    override suspend fun loadMissingDismissed() = repository.loadMissingLogosDismissed()
    override fun dismissMissing(keys: Set<String>) = repository.dismissMissingLogos(keys)
}
