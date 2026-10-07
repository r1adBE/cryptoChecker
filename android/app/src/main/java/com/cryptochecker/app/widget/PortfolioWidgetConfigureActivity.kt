package com.cryptochecker.app.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.R
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.ui.theme.CryptoCheckerTheme
import com.cryptochecker.app.ui.theme.rememberHighContrast
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Einrichten des Portfolio-Widgets: Anzeige («Nur Umrechnung» / «Umrechnung + USDT»),
 * Hintergrund und Deckkraft. Optional (configuration_optional ab Android 12): Ohne
 * Einrichten gilt «Umrechnung + USDT»; später über «Widget bearbeiten» änderbar.
 */
@AndroidEntryPoint
class PortfolioWidgetConfigureActivity : AppCompatActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            val settings by settingsRepository.settings.collectAsState(initial = settingsRepository.cached)
            CryptoCheckerTheme(
                dark = settings.darkMode ?: isSystemInDarkTheme(),
                accent = settings.accentColor,
                priceColors = settings.priceColorScheme,
                highContrast = rememberHighContrast(settings.highContrast),
                priceColorsInverted = settings.priceColorsInverted,
            ) {
                PortfolioWidgetConfigureContent(widgetId) {
                    setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                    finish()
                }
            }
        }
    }
}

@HiltViewModel
class PortfolioWidgetConfigureViewModel @Inject constructor(
    private val widgetPrefs: WidgetPrefs,
    private val widgetUpdater: WidgetUpdater,
) : ViewModel() {

    fun currentShowUsdt(appWidgetId: Int): Boolean = widgetPrefs.getPortfolioShowUsdt(appWidgetId)
    fun currentOpacity(appWidgetId: Int): Int = widgetPrefs.getOpacity(appWidgetId)
    fun currentTheme(appWidgetId: Int): WidgetTheme = widgetPrefs.getTheme(appWidgetId)

    fun apply(appWidgetId: Int, showUsdt: Boolean, theme: WidgetTheme, opacity: Int, onDone: () -> Unit) {
        viewModelScope.launch {
            widgetPrefs.setPortfolioShowUsdt(appWidgetId, showUsdt)
            widgetPrefs.setTheme(appWidgetId, theme)
            widgetPrefs.setOpacity(appWidgetId, opacity)
            widgetUpdater.updatePortfolio(intArrayOf(appWidgetId))
            onDone()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PortfolioWidgetConfigureContent(
    appWidgetId: Int,
    viewModel: PortfolioWidgetConfigureViewModel = hiltViewModel(),
    onDone: () -> Unit,
) {
    var showUsdt by remember { mutableStateOf(viewModel.currentShowUsdt(appWidgetId)) }
    var theme by remember { mutableStateOf(viewModel.currentTheme(appWidgetId)) }
    var opacity by remember { mutableIntStateOf(viewModel.currentOpacity(appWidgetId)) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.widget_portfolio_name)) }) }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            // Anzeige: nur Umrechnungswährung oder zusätzlich «≈ … USDT»
            Text(stringResource(R.string.widget_portfolio_display), style = MaterialTheme.typography.bodyMedium)
            val displays = listOf(
                false to R.string.widget_portfolio_display_conversion,
                true to R.string.widget_portfolio_display_conversion_usdt,
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                displays.forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = showUsdt == value,
                        onClick = { showUsdt = value },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = displays.size)
                    ) { Text(stringResource(label)) }
                }
            }
            Text(
                stringResource(R.string.widget_portfolio_display_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )

            Text(
                stringResource(R.string.widget_background),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp)
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
                    ) { Text(stringResource(label)) }
                }
            }

            Text(
                stringResource(R.string.widget_opacity, opacity),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp)
            )
            Slider(
                value = opacity.toFloat(),
                onValueChange = { opacity = it.toInt() },
                valueRange = 0f..100f,
                steps = 19
            )

            Button(
                onClick = { viewModel.apply(appWidgetId, showUsdt, theme, opacity, onDone) },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            ) {
                Text(stringResource(R.string.action_save))
            }
        }
    }
}
