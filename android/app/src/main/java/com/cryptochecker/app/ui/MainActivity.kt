package com.cryptochecker.app.ui

import com.cryptochecker.app.util.AppVisibility
import com.cryptochecker.app.util.StartupClock
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import com.cryptochecker.app.service.PriceServiceController
import com.cryptochecker.app.lock.AppLockState
import com.cryptochecker.app.data.CoinLogoRepository
import com.cryptochecker.app.ui.components.CoinLogoSource
import com.cryptochecker.app.ui.components.LocalCoinLogoSource
import com.cryptochecker.app.ui.components.LocalPortfolioCoinLogoSource
import com.cryptochecker.app.ui.components.LocalCoinNameSource
import com.cryptochecker.app.ui.navigation.AppNavHost
import com.cryptochecker.app.ui.theme.CryptoCheckerTheme
import com.cryptochecker.app.ui.theme.rememberHighContrast
import com.cryptochecker.app.settings.AppearanceApplier
import com.cryptochecker.app.settings.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var appearanceApplier: AppearanceApplier

    @Inject lateinit var appLockState: AppLockState

    @Inject lateinit var serviceController: PriceServiceController

    @Inject lateinit var widgetUpdater: com.cryptochecker.app.widget.WidgetUpdater

    @Inject lateinit var coinLogoRepository: CoinLogoRepository

    @Inject lateinit var coinLogoSync: com.cryptochecker.app.data.CoinLogoSync

    /** Ziel aus einer App-Verknüpfung («add», «alarms», «cycle»). */
    private val openTarget = androidx.compose.runtime.mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Ab Android 16 (targetSdk 36) zeichnet das System ohnehin randlos.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            // App-Start bis zum ersten Bild der Merkliste (Bericht «Ablauf»)
            StartupClock.onUiCreated()
            openTarget.value = intent?.getStringExtra(EXTRA_OPEN)
            // Neue Oberfläche: ein neuer Prozess sperrt das Portfolio (sofern die Sperre an ist),
            // ein Neuaufbau kurz nach dem Verlassen nicht (siehe AppLockState.onColdStart)
            appLockState.onColdStart()
        }

        setContent {
            val settings by settingsRepository.settings.collectAsState(initial = settingsRepository.cached)
            // Ausserhalb der Navigation gemerkt: Tab-Zustand bleibt über Neuzusammensetzungen
            val navController = rememberNavController()

            // Vorschaubild in «Zuletzt verwendet» ohne Werte, solange die Portfolio-Sperre an ist
            LaunchedEffect(settings.appLock) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    setRecentsScreenshotEnabled(!settings.appLock)
                }
            }

            CryptoCheckerTheme(
                dark = settings.darkMode ?: isSystemInDarkTheme(),
                accent = settings.accentColor,
                priceColors = settings.priceColorScheme,
                highContrast = rememberHighContrast(settings.highContrast),
                priceColorsInverted = settings.priceColorsInverted,
            ) {
                // Coin-Logos je Schalter (App, Portfolio); aus → null, keine Plakette
                val repositorySource = remember { RepositoryLogoSource(coinLogoRepository) }
                CompositionLocalProvider(
                    LocalCoinLogoSource provides repositorySource.takeIf { settings.coinLogos },
                    LocalPortfolioCoinLogoSource provides repositorySource.takeIf { settings.portfolioCoinLogos },
                    LocalCoinNameSource provides repositorySource.takeIf { settings.watchlistNames },
                ) {
                    // Die App ist nie ganz gesperrt: Die Portfolio-Sperre prüft AppNavHost
                    // für den Portfolio-Tab und seine Unterseiten.
                    // Benachrichtigungs-Erlaubnis erst, wenn eine Meldung eingeschaltet wird.
                    AppNavHost(
                        navigation = navController,
                        openTarget = openTarget.value,
                        onOpenTargetHandled = { openTarget.value = null }
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AppVisibility.onStart()
        appLockState.onForeground()
        // Live-Dienst nur mit sichtbarer App (wieder) starten: Nach einem Neustart
        // (ab Android 15 kein dataSync-Dienst aus BOOT_COMPLETED) oder nach dem
        // 6-Stunden-Limit (onTimeout) läuft er erst wieder, wenn der Nutzer die App
        // öffnet. Läuft er schon, ist der Aufruf harmlos.
        lifecycleScope.launch {
            if (settingsRepository.current().liveService) serviceController.start()
        }
        // Coin-Logos: fehlende (alle Coins der Rangliste, nie einzeln) im Hintergrund nachladen
        lifecycleScope.launch { coinLogoSync.startIfEnabled() }
        // System-Kontrast geändert? Widgets mit den passenden Farben neu zeichnen.
        lifecycleScope.launch {
            runCatching { widgetUpdater.updateIfContrastChanged() }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_OPEN)?.let { openTarget.value = it }
    }

    /**
     * App-Icon erst beim Verlassen umschalten: Geschieht es, während die App
     * sichtbar ist, beenden manche Android-Versionen sie sofort. Beim Drehen
     * des Bildschirms (Neuaufbau) nicht.
     */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            AppVisibility.onStop()
            appLockState.onBackground()
            val settings = settingsRepository.cached
            val systemDark = (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
            appearanceApplier.applyLauncherIcon(settings.accentColor, settings.darkMode ?: systemDark)
        }
    }

    companion object {
        const val EXTRA_WATCH_ID = "watch_id"

        /** Von den App-Verknüpfungen gesetzt (res/xml/shortcuts.xml). */
        const val EXTRA_OPEN = "open"

        /** Ziel «Warum?» aus einem Alarm: Merkliste mit «Warum bewegt sich das?» des Paars. */
        private const val WHY_PREFIX = "why/"

        fun openWhyTarget(watchId: Long): String = "$WHY_PREFIX$watchId"

        /** Paar aus einem Ziel [openWhyTarget]; null bei anderen Zielen. */
        fun whyWatchId(target: String?): Long? =
            target?.takeIf { it.startsWith(WHY_PREFIX) }?.removePrefix(WHY_PREFIX)?.toLongOrNull()
    }
}

/** CoinBadge holt die Logos über das Repository (Speicher → Datei → CoinGecko). */
private class RepositoryLogoSource(private val repository: CoinLogoRepository) : CoinLogoSource {
    override val revision = repository.revision
    override val tradFiPairs = repository.tradFiPairs
    override val names = repository.names
    override fun cached(symbol: String) = repository.cached(symbol)
    override suspend fun load(symbol: String) = repository.logo(symbol)
}
