package com.cryptochecker.app.ui.features.explorer

import com.cryptochecker.app.domain.diagnostics.HttpLogger
import com.cryptochecker.app.domain.market.ExchangeVolumes
import com.cryptochecker.app.domain.starter.StarterCoinSource
import com.cryptochecker.app.domain.watch.DexLiquidityValues
import com.cryptochecker.app.domain.watch.DexTokenAddresses
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Was ein DEX-Pool aus der Suche beim Übernehmen hinterlegt. Ohne eigenen Zustand. */
class ExplorerDexStores @Inject constructor(
    /** Liquidität: unter der Mindestmenge kein Alarm ([com.cryptochecker.app.domain.watch.DexLiquidity]). */
    val liquidity: DexLiquidityValues,
    /** Token-Adresse, fürs Paar-Blatt. */
    val tokenAddress: DexTokenAddresses,
)

/** Welche Börse zuerst kommt: in der Börsenauswahl und beim Paar-Vorschlag der Coin-Suche. Ohne eigenen Zustand. */
class ExplorerExchangeOrder @Inject constructor(
    /** Start-Börse der Region (wie die Start-Merkliste) für den Paar-Vorschlag der Coin-Suche. */
    val starterCoins: StarterCoinSource,
    /** Volumen der Börsen am Tag der Installation: Reihenfolge der Börsenauswahl ([ExchangeOrder]). */
    val ranking: ExchangeVolumes,
)

/**
 * Die letzten Zeilen des HTTP-Mitschnitts (Entwickleroption, nur Debug-Build), überlange gekürzt. Ohne Scope: jedes
 * [ExplorerViewModel] bekommt seinen eigenen Text.
 */
class ExplorerHttpLog @Inject constructor(private val httpLogger: HttpLogger) {
    private val logText = MutableStateFlow("")
    val text: StateFlow<String> = logText.asStateFlow()

    /** Sammelt, bis der Aufrufer abbricht; schreibt sofort, wenn der Logger schon eine Zeile gepuffert hat. */
    suspend fun collect() {
        val recent = ArrayDeque<String>(LOG_MAX_LINES)
        httpLogger.messageFlow.collect { line ->
            if (recent.size == LOG_MAX_LINES) recent.removeFirst()
            recent.addLast(if (line.length > LOG_MAX_LINE_LENGTH) line.take(LOG_MAX_LINE_LENGTH) + "..." else line)
            logText.value = recent.joinToString("\n")
        }
    }
}

private const val LOG_MAX_LINES = 10

private const val LOG_MAX_LINE_LENGTH = 400
