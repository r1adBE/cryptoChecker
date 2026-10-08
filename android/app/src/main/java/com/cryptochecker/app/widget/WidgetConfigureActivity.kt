package com.cryptochecker.app.widget

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
import androidx.compose.foundation.shape.RoundedCornerShape
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
) : ViewModel() {

    /** Vorhandene Gruppen der Merkliste, alphabetisch; null bis geladen. */
    val groups: StateFlow<List<String>?> = watchRepository.observeWatches()
        .map { list -> list.mapNotNull { it.groupName }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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
    val loadedGroups by viewModel.groups.collectAsStateWithLifecycle()
    val groups = loadedGroups.orEmpty()
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
                valueRange = 0f..100f,
                steps = 19
            )

            // Gruppe: nur sichtbar, wenn es Gruppen gibt; «Alle» ist Standard
            if (groups.isNotEmpty() || group != null) {
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
                    FilterChip(
                        selected = group == null || (loadedGroups != null && group !in groups),
                        onClick = { group = null },
                        label = { Text(stringResource(R.string.group_all)) }
                    )
                    groups.forEach { name ->
                        FilterChip(
                            selected = group == name,
                            onClick = { group = name },
                            label = { Text(name) }
                        )
                    }
                }
            }

            // Vorschau, damit die Wirkung der Deckkraft sichtbar wird
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(14.dp))
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
                        text = "▲ +1.24%",
                        color = Color(background.upColor),
                        style = MaterialTheme.typography.bodyLarge.tabularNumbers()
                    )
                }
            }

            Button(
                onClick = {
                    // Nicht (mehr) vorhandene Gruppe = «Alle»
                    viewModel.apply(
                        appWidgetId, opacity, theme,
                        group?.takeIf { loadedGroups == null || it in groups },
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
