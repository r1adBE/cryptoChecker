package com.cryptochecker.app.widget

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.cryptochecker.app.domain.watch.WatchFilter
import com.cryptochecker.app.data.CoinLogoRepository
import com.cryptochecker.app.domain.watch.AssetKind
import com.cryptochecker.app.ui.components.ALPHA_GLYPH
import com.cryptochecker.app.ui.features.watchlist.assetKind
import kotlinx.coroutines.flow.combine
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.data.WatchRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.R
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.ui.theme.CryptoCheckerTheme
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.rememberHighContrast
import com.cryptochecker.app.ui.theme.tabularNumbers
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Einstellung beim Ablegen (und Neu-Einrichten) eines Widgets: Hintergrund
 * System/Dunkel/Hell und Deckkraft. Die Akzentfarbe kommt aus der App.
 */
@AndroidEntryPoint
class WidgetConfigureActivity : AppCompatActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Bricht der Nutzer ab, darf kein Widget entstehen.
        setResult(Activity.RESULT_CANCELED)

        val widgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContent {
            val settings by settingsRepository.settings
                .collectAsState(initial = settingsRepository.cached)
            val dark = settings.darkMode ?: isSystemInDarkTheme()

            val highContrast = rememberHighContrast(settings.highContrast)
            CryptoCheckerTheme(
                dark = dark,
                accent = settings.accentColor,
                priceColors = settings.priceColorScheme,
                highContrast = highContrast,
                priceColorsInverted = settings.priceColorsInverted,
            ) {
                WidgetConfigureContent(
                    appWidgetId = widgetId,
                    accent = settings.accentColor,
                    priceColors = settings.priceColorScheme,
                    highContrast = highContrast,
                    priceColorsInverted = settings.priceColorsInverted,
                    onDone = { finishWithSuccess(widgetId) }
                )
            }
        }
    }

    private fun finishWithSuccess(widgetId: Int) {
        setResult(
            Activity.RESULT_OK,
            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        )
        finish()
    }
}

@HiltViewModel
class WidgetConfigureViewModel @Inject constructor(
    private val widgetPrefs: WidgetPrefs,
    private val widgetUpdater: WidgetUpdater,
    watchRepository: WatchRepository,
    coinLogoRepository: CoinLogoRepository,
) : ViewModel() {

    /** Gruppe und «Alpha-Token?» je Paar der Merkliste; null bis geladen. */
    private val pairs: StateFlow<WidgetGroupPairs?> = combine(
        watchRepository.observeWatches(),
        coinLogoRepository.tradFiPairs,
        coinLogoRepository.alphaSymbols,
    ) { list, tradFiPairs, alphaSymbols ->
        WidgetGroupPairs(
            groupNames = list.map { it.groupName },
            alphaFlags = list.map { it.assetKind(tradFiPairs, alphaSymbols) == AssetKind.ALPHA },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Wählbare Einträge in der Reihenfolge der Chips der App ([WidgetGroupSelection.options]): «Alle»,
     * FAV, «Token», «Alpha», eigene Gruppen; null bis geladen.
     */
    val options: StateFlow<List<String?>?> = pairs
        .map { p -> p?.let { WidgetGroupSelection.options(it.groupNames, it.alphaFlags) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Gilt die Auswahl [group] noch ([WatchFilter.isAvailable])? Vor dem Laden ja, damit eine
     * gespeicherte Gruppe nicht verloren geht.
     */
    fun isAvailable(group: String?): Boolean =
        pairs.value?.let { WatchFilter.isAvailable(group, it.groupNames, it.alphaFlags) } ?: true

    /** Gespeicherte Werte — beim Neu-Einrichten vorausgewählt. */
    fun currentOpacity(appWidgetId: Int): Int = widgetPrefs.getOpacity(appWidgetId)
    fun currentTheme(appWidgetId: Int): WidgetTheme = widgetPrefs.getTheme(appWidgetId)
    fun currentGroup(appWidgetId: Int): String? = widgetPrefs.getGroup(appWidgetId)

    fun apply(
        appWidgetId: Int,
        opacityPercent: Int,
        theme: WidgetTheme,
        group: String?,
        onDone: () -> Unit,
    ) {
        viewModelScope.launch {
            widgetPrefs.setOpacity(appWidgetId, opacityPercent)
            widgetPrefs.setTheme(appWidgetId, theme)
            widgetPrefs.setGroup(appWidgetId, group)
            widgetUpdater.update(intArrayOf(appWidgetId))
            onDone()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun WidgetConfigureContent(
    appWidgetId: Int,
    accent: com.cryptochecker.app.settings.AccentColor,
    priceColors: com.cryptochecker.app.settings.PriceColorScheme,
    highContrast: Boolean,
    priceColorsInverted: Boolean,
    onDone: () -> Unit,
    viewModel: WidgetConfigureViewModel = hiltViewModel(),
) {
    var opacity by remember { mutableIntStateOf(viewModel.currentOpacity(appWidgetId)) }
    var theme by remember { mutableStateOf(viewModel.currentTheme(appWidgetId)) }
    var group by remember { mutableStateOf(viewModel.currentGroup(appWidgetId)) }
    val loadedOptions by viewModel.options.collectAsStateWithLifecycle()
    // Vor dem Laden nur «Alle» und FAV (gibt es immer)
    val options = loadedOptions ?: listOf(null, WatchFilter.FAVORITES)
    // Handy-Einstellung, nicht der Modus der App (der kann fest Hell/Dunkel sein)
    val systemDark = remember { com.cryptochecker.app.util.SystemTheme.isDark() }
    val previewDark = when (theme) {
        WidgetTheme.SYSTEM -> systemDark
        WidgetTheme.DARK -> true
        WidgetTheme.LIGHT -> false
    }
    val background = WidgetColors.of(accent, previewDark, priceColors, highContrast, priceColorsInverted)

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.widget_configure_title)) }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.widget_configure_hint),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Hintergrund: System / Dunkel / Hell
            Text(
                text = stringResource(R.string.widget_background),
                style = MaterialTheme.typography.bodyMedium
            )
            val themes = listOf(
                WidgetTheme.SYSTEM to R.string.theme_system,
                WidgetTheme.DARK to R.string.theme_dark,
                WidgetTheme.LIGHT to R.string.theme_light,
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                themes.forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = theme == value,
                        onClick = { theme = value },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = themes.size)
                    ) {
                        Text(stringResource(label))
                    }
                }
            }

            Text(
                text = stringResource(R.string.widget_opacity, opacity),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp)
            )
            Slider(
                value = opacity.toFloat(),
                onValueChange = { opacity = it.toInt() },
                // Mindestens 30 %: darunter ist die Schrift auf hellem Hintergrundbild kaum lesbar (5-%-Schritte)
                valueRange = WidgetColors.MIN_OPACITY.toFloat()..100f,
                steps = (100 - WidgetColors.MIN_OPACITY) / 5 - 1
            )

            // Gruppe: «Alle» (Standard), «FAV» (Favoriten), «Token» (alles ausser TradFi), «Alpha» (Alpha-Tokens)
            // und die eigenen Gruppen
            run {
                Text(
                    text = stringResource(R.string.group_title),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Text(
                    text = stringResource(R.string.widget_group_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)
                ) {
                    options.forEach { option ->
                        GroupOptionChip(
                            option = option,
                            // Nicht (mehr) vorhandene Auswahl gilt als «Alle»
                            selected = if (option == null) {
                                group == null || (loadedOptions != null && !viewModel.isAvailable(group))
                            } else {
                                group == option
                            },
                            onClick = { group = option },
                        )
                    }
                }
            }

            // Vorschau, damit die Wirkung der Deckkraft sichtbar wird
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(Color(background.colorWithOpacity(opacity)))
                    .padding(Spacing.md)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "BTC/USDT",
                        color = Color(background.textColor),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "↗ +1.24%",
                        color = Color(background.upColor),
                        style = MaterialTheme.typography.bodyLarge.tabularNumbers()
                    )
                }
            }

            Button(
                onClick = {
                    // Nicht (mehr) vorhandene Gruppe (oder «Token» ohne TradFi- und andere Paare) = «Alle»
                    viewModel.apply(
                        appWidgetId, opacity, theme,
                        group?.takeIf { viewModel.isAvailable(it) },
                        onDone
                    )
                },
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.lg)
            ) {
                Text(stringResource(R.string.action_save))
            }
        }
    }
}

/** Gruppe und «Alpha-Token?» je Paar der Merkliste (gleiche Reihenfolge). */
internal data class WidgetGroupPairs(val groupNames: List<String?>, val alphaFlags: List<Boolean>)

/**
 * Ein Eintrag der Gruppen-Auswahl: «Alle» (null), «Favoriten», «Token», «Alpha-Tokens» (mit «α» wie der
 * Chip der App) oder der Gruppenname.
 */
@Composable
private fun GroupOptionChip(option: String?, selected: Boolean, onClick: () -> Unit) {
    val label = when {
        option == null -> stringResource(R.string.group_all)
        WatchFilter.isFavorites(option) -> stringResource(R.string.group_favorites)
        WatchFilter.isTokens(option) -> stringResource(R.string.group_tokens)
        WatchFilter.isAlpha(option) -> stringResource(R.string.group_alpha)
        else -> option
    }
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(if (WatchFilter.isAlpha(option)) "$ALPHA_GLYPH $label" else label) },
        modifier = Modifier.semantics { contentDescription = label }
    )
}
