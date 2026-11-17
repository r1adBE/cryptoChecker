// Alle Listen und Logos in einem Durchgang abgleichen — Teil von CoinListSync.
package com.cryptochecker.app.data

import android.content.Context
import androidx.core.content.edit
import com.cryptochecker.app.data.logos.CoinLogoStorage
import com.cryptochecker.app.data.logos.LogoDownloads
import com.cryptochecker.app.data.logos.LogoFiles
import com.cryptochecker.app.data.logos.LogoImageStore
import com.cryptochecker.app.data.remote.callExchange
import com.cryptochecker.app.data.remote.util.HttpBodyLimit
import com.cryptochecker.app.data.util.ConnectivityMonitor
import com.cryptochecker.app.domain.exceptions.NetworkError
import com.cryptochecker.app.domain.exceptions.TimeoutError
import com.cryptochecker.app.domain.logos.CoinLists
import com.cryptochecker.app.domain.logos.CoinLogoRetry
import com.cryptochecker.app.domain.logos.CoinLogos
import com.cryptochecker.app.domain.logos.CoinMeta
import com.cryptochecker.app.domain.logos.alphaSymbols
import com.cryptochecker.app.domain.logos.hasAlphaIds
import com.cryptochecker.app.domain.logos.keepAlphaIds
import com.cryptochecker.app.domain.logos.withStockLogos
import com.cryptochecker.app.domain.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.util.appScope
import com.cryptochecker.marketdata.model.exchange.BinanceAlpha
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import timber.log.Timber

/**
 * Rangliste holen und alle fehlenden Logos laden, in Stufen nacheinander ([LogoImageStore.downloadMissing]): erst die
 * bei Binance gelisteten Krypto-Kürzel (Symbolliste, schon da), während die Seiten der Rangliste laden; dann die
 * grössten 1000; zuletzt die ganze Zuordnung (nur noch, was fehlt). Scheitert ein Bild: Ersatz-Adressen
 * ([logoAlternates]).
 */
internal suspend fun CoinListSync.syncAll(): Int = coroutineScope {
    val stages = Channel<Pair<Map<String, String>, Boolean>>(Channel.UNLIMITED)
    val worker = async {
        var total = LogoDownloads()
        // Erst ein vollständiger Durchgang (nichts am Netz gescheitert, gebremst oder offen) erledigt das erste Laden
        imageStore.loadingHint.during(complete = { d: LogoDownloads -> d.complete && !mapNetworkFailed && !listsNetworkFailed }) {
            for (stage in stages) total += imageStore.downloadMissing(stage.first, logoAlternates(), stage.second)
            total
        }
    }
    val current = try {
        symbolMap { partial -> stages.send(CoinLogos.withStockLogos(partial, names.tradFiBases) to false) }
    } catch (e: Throwable) {
        stages.close()
        throw e
    }
    // Aktien-Logos für alle TradFi-Kürzel ohne Eintrag dazu; die letzte Stufe (true) versucht alle noch fehlenden Bilder
    stages.send(CoinLogos.withStockLogos(current, names.tradFiBases) to true)
    stages.close()
    val result = worker.await()
    _retryPending.value = listsNetworkFailed || mapNetworkFailed || result.network
    imagesPausedUntil = result.pausedUntil
    result.added
}
