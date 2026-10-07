package com.cryptochecker.app.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.R
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.ui.theme.CryptoCheckerTheme
import com.cryptochecker.app.ui.theme.rememberHighContrast
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Einrichten des Einzel-Widgets: Paar, Chart-Zeitraum und -Art, Hintergrund und Deckkraft wählen. */
@AndroidEntryPoint
class SingleWidgetConfigureActivity : AppCompatActivity() {

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
                SingleWidgetConfigureContent(widgetId) {
                    setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                    finish()
                }
            }
        }
    }
}

@HiltViewModel
class SingleWidgetConfigureViewModel @Inject constructor(
    watchRepository: WatchRepository,
    private val widgetPrefs: WidgetPrefs,
    private val widgetUpdater: WidgetUpdater,
) : ViewModel() {

    val watches: StateFlow<List<WatchEntity>> = watchRepository.observeWatches()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun currentWatchId(appWidgetId: Int): Long? = widgetPrefs.getWatchId(appWidgetId)
    fun currentOpacity(appWidgetId: Int): Int = widgetPrefs.getOpacity(appWidgetId)
    fun currentTheme(appWidgetId: Int): WidgetTheme = widgetPrefs.getTheme(appWidgetId)
    fun currentChartRange(appWidgetId: Int): WidgetChartRange = widgetPrefs.getChartRange(appWidgetId)
    fun currentChartType(appWidgetId: Int): WidgetChartType = widgetPrefs.getChartType(appWidgetId)

    fun apply(
        appWidgetId: Int,
        watchId: Long,
        chartRange: WidgetChartRange,
        chartType: WidgetChartType,
        theme: WidgetTheme,
        opacity: Int,
        onDone: () -> Unit,
    ) {
        viewModelScope.launch {
            widgetPrefs.setWatchId(appWidgetId, watchId)
            widgetPrefs.setChartRange(appWidgetId, chartRange)
            widgetPrefs.setChartType(appWidgetId, chartType)
            widgetPrefs.setTheme(appWidgetId, theme)
            widgetPrefs.setOpacity(appWidgetId, opacity)
            widgetUpdater.updateSingle(intArrayOf(appWidgetId))
            onDone()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SingleWidgetConfigureContent(
    appWidgetId: Int,
    viewModel: SingleWidgetConfigureViewModel = hiltViewModel(),
    onDone: () -> Unit,
) {
    val watches by viewModel.watches.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf(viewModel.currentWatchId(appWidgetId)) }
    var theme by remember { mutableStateOf(viewModel.currentTheme(appWidgetId)) }
    var chartRange by remember { mutableStateOf(viewModel.currentChartRange(appWidgetId)) }
    var chartType by remember { mutableStateOf(viewModel.currentChartType(appWidgetId)) }
    var opacity by remember { mutableIntStateOf(viewModel.currentOpacity(appWidgetId)) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.single_widget_title)) }) }
    ) { padding ->
        // Scrollbar: mit der Chart-Art passt nicht mehr alles auf kleine Bildschirme
        Column(modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(stringResource(R.string.single_widget_choose), style = MaterialTheme.typography.bodyMedium)

            if (watches.isEmpty()) {
                Text(
                    stringResource(R.string.watchlist_empty_title),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).padding(top = 8.dp)) {
                items(watches, key = { it.id }) { watch ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .background(
                                if (selected == watch.id) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                else MaterialTheme.colorScheme.surface
                            )
                            .clickable { selected = watch.id }
                            .padding(vertical = 4.dp)
                    ) {
                        RadioButton(selected = selected == watch.id, onClick = { selected = watch.id })
                        Column {
                            Text(watch.displayName, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                watch.marketName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Zeitraum des Mini-Charts
            Text(
                stringResource(R.string.widget_chart_range),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp)
            )
            val ranges = WidgetChartRange.entries
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                ranges.forEachIndexed { index, value ->
                    SegmentedButton(
                        selected = chartRange == value,
                        onClick = { chartRange = value },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = ranges.size)
                    ) { Text(stringResource(value.labelRes)) }
                }
            }

            // Chart-Art: Kerzen (Standard) oder Linie
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
                onClick = { selected?.let { viewModel.apply(appWidgetId, it, chartRange, chartType, theme, opacity, onDone) } },
                enabled = selected != null,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            ) {
                Text(stringResource(R.string.action_save))
            }
        }
    }
}
