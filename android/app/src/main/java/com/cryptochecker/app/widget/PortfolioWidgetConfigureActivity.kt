package com.cryptochecker.app.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
 * Einrichten des Portfolio-Widgets: Ansicht (Übersicht / Chart / Liste), Anzeige («Nur USDT» / «Umrechnung» / «Umrechnung + USDT»),
 * Zeitraum (24 h / 7 T / 30 T / 1 J, für Veränderung und Chart), Chart-Art (Kerzen / Linie),
 * Hintergrund und Deckkraft — je Widget gespeichert ([WidgetPrefs]). Optional
 * (configuration_optional ab Android 12): Ohne Einrichten gilt Chart, «Umrechnung» (bei USD «Nur USDT»),
 * 24 h und Linie; die Chart-Art erscheint nur bei der Ansicht «Chart», der Zeitraum nicht bei «Liste»; später über «Widget bearbeiten» änderbar.
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
                PortfolioWidgetConfigureContent(widgetId, settings.portfolioCurrency) {
                    setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                    finish()
                }
            }
        }
    }
}

/** Gewählte Einstellungen eines Portfolio-Widgets. */
data class PortfolioWidgetChoice(
    val view: PortfolioWidgetView,
    val display: PortfolioDisplay,
    val range: PortfolioChartRange,
    val chartType: WidgetChartType,
    val theme: WidgetTheme,
    val opacity: Int,
)

@HiltViewModel
class PortfolioWidgetConfigureViewModel @Inject constructor(
    private val widgetPrefs: WidgetPrefs,
    private val widgetUpdater: WidgetUpdater,
) : ViewModel() {

    fun currentDisplay(appWidgetId: Int, currency: String): PortfolioDisplay =
        widgetPrefs.getPortfolioDisplay(appWidgetId, currency)
    fun currentView(appWidgetId: Int): PortfolioWidgetView = widgetPrefs.getPortfolioView(appWidgetId)
    fun currentRange(appWidgetId: Int): PortfolioChartRange = widgetPrefs.getPortfolioRange(appWidgetId)
    fun currentChartType(appWidgetId: Int): WidgetChartType = widgetPrefs.getPortfolioChartType(appWidgetId)
    fun currentOpacity(appWidgetId: Int): Int = widgetPrefs.getOpacity(appWidgetId)
    fun currentTheme(appWidgetId: Int): WidgetTheme = widgetPrefs.getTheme(appWidgetId)

    fun apply(appWidgetId: Int, choice: PortfolioWidgetChoice, onDone: () -> Unit) {
        viewModelScope.launch {
            widgetPrefs.setPortfolioView(appWidgetId, choice.view)
            widgetPrefs.setPortfolioDisplay(appWidgetId, choice.display)
            widgetPrefs.setPortfolioRange(appWidgetId, choice.range)
            widgetPrefs.setPortfolioChartType(appWidgetId, choice.chartType)
            widgetPrefs.setTheme(appWidgetId, choice.theme)
            widgetPrefs.setOpacity(appWidgetId, choice.opacity)
            widgetUpdater.updatePortfolio(intArrayOf(appWidgetId))
            onDone()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PortfolioWidgetConfigureContent(
    appWidgetId: Int,
    currency: String,
    viewModel: PortfolioWidgetConfigureViewModel = hiltViewModel(),
    onDone: () -> Unit,
) {
    // Anzeige erst fest, wenn gewählt: bis dahin folgt der Vorschlag der (geladenen) Umrechnungswährung
    var chosenDisplay by remember { mutableStateOf<PortfolioDisplay?>(null) }
    val display = chosenDisplay ?: viewModel.currentDisplay(appWidgetId, currency)
    var view by remember { mutableStateOf(viewModel.currentView(appWidgetId)) }
    var range by remember { mutableStateOf(viewModel.currentRange(appWidgetId)) }
    var chartType by remember { mutableStateOf(viewModel.currentChartType(appWidgetId)) }
    var theme by remember { mutableStateOf(viewModel.currentTheme(appWidgetId)) }
    var opacity by remember { mutableIntStateOf(viewModel.currentOpacity(appWidgetId)) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.widget_portfolio_name)) }) }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            // Ansicht: Übersicht (Gesamtkarte), Chart (bisher), Liste (Positionen)
            Text(stringResource(R.string.widget_portfolio_view), style = MaterialTheme.typography.bodyMedium)
            val views = listOf(
                PortfolioWidgetView.OVERVIEW to R.string.widget_portfolio_view_overview,
                PortfolioWidgetView.CHART to R.string.widget_portfolio_view_chart,
                PortfolioWidgetView.LIST to R.string.widget_portfolio_view_list,
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                views.forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = view == value,
                        onClick = { view = value },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = views.size)
                    ) { Text(stringResource(label)) }
                }
            }

            // Anzeige: drei Möglichkeiten als Liste (die Bezeichnungen sind für Segmente zu lang)
            Text(
                stringResource(R.string.widget_portfolio_display),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp)
            )
            val displays = listOf(
                PortfolioDisplay.USDT to R.string.widget_portfolio_display_usdt,
                PortfolioDisplay.CONVERSION to R.string.widget_portfolio_display_conversion,
                PortfolioDisplay.CONVERSION_USDT to R.string.widget_portfolio_display_conversion_usdt,
            )
            Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp).selectableGroup()) {
                displays.forEach { (value, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = display == value, role = Role.RadioButton) { chosenDisplay = value }
                            .padding(vertical = 2.dp)
                    ) {
                        RadioButton(selected = display == value, onClick = null)
                        Text(
                            stringResource(label),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    }
                }
            }
            Text(
                stringResource(R.string.widget_portfolio_display_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )

            // Zeitraum für Veränderung und Chart: nicht bei der Ansicht «Liste» (die Wahl bleibt gespeichert)
            if (view.usesRange) {
                Text(
                    stringResource(R.string.widget_portfolio_period),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 16.dp)
                )
                val ranges = listOf(
                    PortfolioChartRange.DAY to R.string.widget_range_short_24h,
                    PortfolioChartRange.WEEK to R.string.widget_range_short_7d,
                    PortfolioChartRange.MONTH to R.string.widget_range_short_30d,
                    PortfolioChartRange.YEAR to R.string.widget_range_short_1y,
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    ranges.forEachIndexed { index, (value, label) ->
                        SegmentedButton(
                            selected = range == value,
                            onClick = { range = value },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = ranges.size)
                        ) { Text(stringResource(label)) }
                    }
                }
            }

            // Chart-Art: nur bei der Ansicht «Chart» (die Wahl bleibt gespeichert)
            if (view.usesChartType) {
                Text(
                    stringResource(R.string.widget_chart_type),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 16.dp)
                )
                val chartTypes = listOf(
                    WidgetChartType.CANDLES to R.string.widget_chart_candles,
                    WidgetChartType.LINE to R.string.widget_chart_line,
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    chartTypes.forEachIndexed { index, (value, label) ->
                        SegmentedButton(
                            selected = chartType == value,
                            onClick = { chartType = value },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = chartTypes.size)
                        ) { Text(stringResource(label)) }
                    }
                }
            }

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
                // Mindestens 30 %: darunter ist die Schrift auf hellem Hintergrundbild kaum lesbar (5-%-Schritte)
                valueRange = WidgetColors.MIN_OPACITY.toFloat()..100f,
                steps = (100 - WidgetColors.MIN_OPACITY) / 5 - 1
            )

            Button(
                onClick = {
                    viewModel.apply(appWidgetId, PortfolioWidgetChoice(view, display, range, chartType, theme, opacity), onDone)
                },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            ) {
                Text(stringResource(R.string.action_save))
            }
        }
    }
}
