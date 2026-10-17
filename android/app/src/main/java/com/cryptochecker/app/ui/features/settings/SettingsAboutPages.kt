package com.cryptochecker.app.ui.features.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.R
import com.cryptochecker.app.ui.components.SwitchRow
import com.cryptochecker.app.ui.features.about.AboutContent
import com.cryptochecker.app.ui.features.about.AboutFeatures
import com.cryptochecker.app.ui.features.about.EXCHANGE_REQUEST_URL
import com.cryptochecker.app.ui.features.about.PRIVACY_POLICY_URL
import com.cryptochecker.app.ui.features.about.SOURCE_CODE_URL

// ── Über ─────────────────────────────────────────────────────────────────────

/** Tipps auf die Version, die «Entwickler» freischalten — wie iOS (`AboutSettingsPage`). */
private const val DEVELOPER_UNLOCK_TAPS = 7

/** Ab so vielen fehlenden Tipps zählt ein Hinweis herunter («Noch 3-mal tippen …»). */
private const val DEVELOPER_COUNTDOWN_FROM = 3

/**
 * Über die App: Zweck, Version (7 Tipps schalten «Entwickler» frei), Hinweis zu den Daten; darunter
 * Datenschutz, Börse wünschen, Quellcode und Lizenzen (bis Build 52 auf der Hauptseite).
 */
@Composable
internal fun AboutPage(
    onBack: () -> Unit,
    onOpenPage: (SettingsPage) -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uriHandler = LocalUriHandler.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var versionTaps by remember { mutableIntStateOf(0) }
    // Texte über LocalResources (Lint: kein getString über LocalContext in Compose)
    val resources = LocalResources.current
    val unlockedText = stringResource(R.string.developer_unlocked)
    // Letzter Hinweis: wird beim nächsten Tipp ersetzt, damit sich keine Meldungen stauen
    var lastToast by remember { mutableStateOf<Toast?>(null) }
    fun showToast(text: String) {
        lastToast?.cancel()
        lastToast = Toast.makeText(context, text, Toast.LENGTH_SHORT).also { it.show() }
    }
    SettingsSubPage(title = stringResource(R.string.settings_row_about), onBack = onBack) {
        GroupCard {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                AboutContent(
                    showHeading = false,
                    onVersionTap = {
                        if (!settings.developerUnlocked) {
                            versionTaps++
                            val left = DEVELOPER_UNLOCK_TAPS - versionTaps
                            if (left == 0) {
                                viewModel.unlockDeveloper()
                                showToast(unlockedText)
                            } else if (left in 1..DEVELOPER_COUNTDOWN_FROM) {
                                showToast(resources.getQuantityString(R.plurals.developer_taps_left, left, left))
                            }
                        }
                    }
                )
            }
        }
        // Build 53: Links und Lizenzen von der Hauptseite hierher (Google Play verlangt den Link zur
        // Datenschutzerklärung auch in der App). Ohne Browser wirft der Standard-Handler — nie abstürzen.
        NavCard {
            SettingsAnchor("about.privacy") {
                SettingsNavRow(
                    title = stringResource(R.string.about_privacy_policy),
                    icon = R.drawable.ic_settings_privacy,
                    external = true,
                    onClick = { runCatching { uriHandler.openUri(PRIVACY_POLICY_URL) } }
                )
            }
            SettingsAnchor("about.exchange") {
                SettingsNavRow(
                    title = stringResource(R.string.about_request_exchange),
                    icon = R.drawable.ic_settings_exchange,
                    external = true,
                    onClick = { runCatching { uriHandler.openUri(EXCHANGE_REQUEST_URL) } }
                )
            }
            SettingsAnchor("about.source") {
                SettingsNavRow(
                    title = stringResource(R.string.about_source_code),
                    icon = R.drawable.ic_settings_source,
                    external = true,
                    onClick = { runCatching { uriHandler.openUri(SOURCE_CODE_URL) } }
                )
            }
            SettingsAnchor("about.licenses") {
                SettingsNavRow(
                    title = stringResource(R.string.about_licenses),
                    icon = R.drawable.ic_settings_licenses,
                    onClick = { onOpenPage(SettingsPage.LICENSES) }
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        // Was die App kann (früher im Begrüßungsblatt)
        GroupCard {
            AboutFeatures(modifier = Modifier.padding(16.dp))
        }
    }
}

/** Lizenzhinweise als eigene Seite (wie alle Zeilen der Einstellungen). */
@Composable
internal fun LicensesPage(onBack: () -> Unit) {
    SettingsSubPage(title = stringResource(R.string.about_licenses), onBack = onBack) {
        GroupCard {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                com.cryptochecker.app.ui.features.about.LicensesContent()
            }
        }
    }
}

/** Widgets hinzufügen als eigene Seite (wie alle Zeilen der Einstellungen). */
@Composable
internal fun WidgetsPage(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsSubPage(title = stringResource(R.string.settings_widgets), onBack = onBack) {
        GroupCard {
            com.cryptochecker.app.ui.components.AddWidgetsContent(portfolioEnabled = settings.portfolioEnabled)
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
