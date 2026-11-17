package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.data.local.DatabaseEncryptionNotice
import com.cryptochecker.app.data.portfolio.PortfolioCoinDeletion
import com.cryptochecker.app.data.portfolio.PortfolioDatabase
import com.cryptochecker.app.data.portfolio.PortfolioTxEntity
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.ui.components.HapticEvent
import com.cryptochecker.app.ui.components.rememberHaptics
import com.cryptochecker.app.ui.features.watchlist.WatchlistBanner
import com.cryptochecker.app.ui.theme.SnackbarKind
import com.cryptochecker.resources.R

/**
 * Banner des Portfolio-Tabs ([PortfolioScreen]): automatisch «Nur anzeigen» bei später aufgetauchter Überschneidung einer
 * Adresse (mit «Rückgängig») und der Dauerhinweis «nur im Arbeitsspeicher». Bei offenem Adressen-Blatt erst danach
 * (das Banner läge hinter dem Blatt).
 */
@Composable
internal fun PortfolioBannerEffects(viewModel: PortfolioViewModel, banner: WatchlistBanner, addressesOpen: Boolean) {
    // Texte über LocalResources (Lint: kein getString über LocalContext in Compose)
    val resources = LocalResources.current
    // Adresse mit später aufgetauchter Überschneidung: einmal automatisch «Nur anzeigen» — sichtbar, mit «Rückgängig»
    val overlapNotice by viewModel.addressOverlapNotice.collectAsStateWithLifecycle()
    LaunchedEffect(overlapNotice, addressesOpen) {
        val notice = overlapNotice?.takeIf { !addressesOpen } ?: return@LaunchedEffect
        viewModel.consumeAddressOverlapNotice()
        val text = resources.getString(
            R.string.portfolio_address_overlap_auto,
            notice.names.joinToString(", "),
            notice.coins.joinToString(", "),
        )
        banner.show(text) { viewModel.undoAddressOverlap(notice) }
    }

    // Kein Schlüssel: Portfolio nur im Arbeitsspeicher, nie unverschlüsselt gespeichert — sagen, nicht still (wie iOS
    // «portfolio_save_pending»); der nächste Start versucht es erneut
    val storageInMemory by DatabaseEncryptionNotice.inMemory.collectAsStateWithLifecycle()
    val portfolioInMemory = PortfolioDatabase.DB_NAME in storageInMemory
    // Variante: Datei mit gespeichertem Stand ist da, nur der Schlüssel war gerade nicht lesbar — der Stand kommt nach
    // einem Neustart zurück, Änderungen dieses Laufs nicht
    val storageKept by DatabaseEncryptionNotice.storedKept.collectAsStateWithLifecycle()
    val portfolioKept = PortfolioDatabase.DB_NAME in storageKept
    val closeLabel = stringResource(R.string.action_close)
    // Dauerhinweis (wie die Merkliste): steht, solange der Zustand gilt, und kommt nach jedem anderen Banner wieder.
    // Gesperrtes Schreiben: PortfolioStorageLock
    LaunchedEffect(portfolioInMemory, portfolioKept, addressesOpen) {
        if (!portfolioInMemory || addressesOpen) {
            banner.clearResting()
            return@LaunchedEffect
        }
        val text = if (portfolioKept) R.string.portfolio_storage_kept_in_memory else R.string.portfolio_storage_in_memory
        banner.showResting(resources.getString(text), closeLabel)
    }
}

/**
 * Coin löschen nach Wischen: Rückfrage mit der Zahl der Transaktionen ([DeleteCoinDialog]), dann alle weg und «Rückgängig»
 * im Banner. Nicht geschrieben: gesperrt bzw. Fehler nennen statt «entfernt» (wie iOS). [onClose] schliesst die Rückfrage.
 */
@Composable
internal fun DeleteCoinFlow(
    viewModel: PortfolioViewModel,
    banner: WatchlistBanner,
    transactions: List<PortfolioTxEntity>,
    coin: String,
    txCounts: Map<String, Int>,
    onClose: () -> Unit,
) {
    val resources = LocalResources.current
    val coinHaptics = rememberHaptics()
    val notSaved = { result: PortfolioWriteResult ->
        banner.show(resources.getString(result.notSavedText()), SnackbarKind.ERROR)
    }
    fun deleteCoin() {
        viewModel.deleteCoin(coin, onFailed = notSaved) { removed ->
            if (removed.isNotEmpty()) coinHaptics.play(HapticEvent.DESTRUCTIVE)
            // Zurück kommen auch die Transaktionen, die nur ihre Gebühr in diesem Coin verloren; gezählt werden die gelöschten
            val key = PortfolioCalculator.normalizeCoin(coin)
            val count = removed.count { PortfolioCoinDeletion.involves(it, key) }
            val text = resources.getQuantityString(R.plurals.portfolio_coin_removed, count, coin, count)
            banner.show(text) { viewModel.restore(removed) { if (it != PortfolioWriteResult.OK) notSaved(it) } }
        }
    }
    DeleteCoinDialog(
        coin = coin,
        count = txCounts[PortfolioCalculator.normalizeCoin(coin)] ?: 0,
        feeRefs = PortfolioCoinDeletion.counts(transactions, coin).second,
        onConfirm = {
            onClose()
            deleteCoin()
        },
        onDismiss = onClose,
    )
}
