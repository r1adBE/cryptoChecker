package com.cryptochecker.app.ui.features.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.R
import com.cryptochecker.app.ui.components.SwitchRow
import com.cryptochecker.app.ui.features.about.AboutContent
import com.cryptochecker.app.ui.features.about.AboutFeatures

// ── Über ─────────────────────────────────────────────────────────────────────

/** Über die App: Zweck, Version (sieben Tipps schalten «Entwickler» frei), Hinweis zu den Daten. */
@Composable
internal fun AboutPage(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var versionTaps by remember { mutableIntStateOf(0) }
    val unlockedText = stringResource(R.string.developer_unlocked)
    SettingsSubPage(title = stringResource(R.string.settings_row_about), onBack = onBack) {
        GroupCard {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                AboutContent(
                    showHeading = false,
                    showLinks = false,
                    onVersionTap = {
                        if (!settings.developerUnlocked) {
                            versionTaps++
                            if (versionTaps == 7) {
                                viewModel.unlockDeveloper()
                                Toast.makeText(context, unlockedText, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
            }
        }
        // Was die App kann (früher im Begrüßungsblatt)
        GroupCard {
            AboutFeatures(modifier = Modifier.padding(16.dp))
        }
    }
}

/** Entwickler: HTTP-Protokoll unten auf der Seite «Paar hinzufügen». */
@Composable
internal fun DeveloperPage(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsSubPage(title = stringResource(R.string.settings_section_developer), onBack = onBack) {
        GroupCard {
            SettingsAnchor("developer.http_log") {
                SwitchRow(
                    title = stringResource(R.string.settings_http_log),
                    subtitle = stringResource(R.string.settings_http_log_hint),
                    checked = settings.showHttpLog,
                    onCheckedChange = viewModel::setShowHttpLog
                )
            }
        }
    }
}
