@file:OptIn(ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.about

import com.cryptochecker.app.ui.components.SectionTitle
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Eigene Zeile für die Änderungen an der Börsen-Anbindung, wie in der Datei `LICENSE` des Projekts. */
const val OWNER_COPYRIGHT = "Copyright (c) 2026 r1AD <riad.work@outlook.com>"

private const val ASSET_MARKETDATA = "licenses/MIT-marketdata.txt"
private const val ASSET_FONT = "licenses/Rubik-OFL.txt"
private const val ASSET_APACHE = "licenses/Apache-2.0.txt"
private const val APACHE_NAME = "Apache License 2.0"

/** Wichtigste Fremdbibliotheken aus `app/build.gradle` (alle unter der Apache License 2.0). */
private val ANDROID_LIBRARIES = listOf(
    "AndroidX Core, AppCompat, Activity, Lifecycle, Navigation",
    "Jetpack Compose (UI, Material 3, ConstraintLayout)",
    "AndroidX Room",
    "AndroidX WorkManager",
    "AndroidX DataStore",
    "AndroidX Biometric",
    "Kotlin, kotlinx.coroutines",
    "Dagger / Hilt",
    "OkHttp",
    "Timber"
)

/**
 * Runde 14: Lizenzhinweise (Börsen-Anbindung unter MIT, Schrift Rubik unter OFL,
 * Android-Bibliotheken unter Apache 2.0). Die Lizenztexte liegen unübersetzt in
 * `assets/licenses/`; übersetzt sind nur Titel und Einleitungen.
 */
@Composable
fun LicensesSheet(onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Gleich in voller Höhe: ändert sich der Inhalt (Laden, Auswahl), springt das Blatt nicht
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 16.dp)
        ) {
            Text(
                stringResource(R.string.about_licenses),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() }
            )
            LicensesContent()
        }
    }
}

/** Inhalt «Lizenzhinweise» (auswählbar): als Blatt ([LicensesSheet]) oder als Seite der Einstellungen. */
@Composable
fun LicensesContent() {
    val context = LocalContext.current
    val marketdata by produceState("") { value = readAsset(context, ASSET_MARKETDATA) }
    val font by produceState("") { value = readAsset(context, ASSET_FONT) }
    val apache by produceState("") { value = readAsset(context, ASSET_APACHE) }
    SelectionContainer {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Runde 15: die App selbst ist quelloffen (MIT)
            BodyText(stringResource(R.string.licenses_app_intro))

            SectionTitle(stringResource(R.string.licenses_marketdata_title))
            BodyText(stringResource(R.string.licenses_marketdata_intro))
            LicenseText(marketdata)
            BodyText(stringResource(R.string.licenses_marketdata_changes, OWNER_COPYRIGHT))

            SectionDivider()
            SectionTitle(stringResource(R.string.licenses_font_title))
            LicenseText(font)

            SectionDivider()
            SectionTitle(stringResource(R.string.licenses_libraries_title))
            BodyText(stringResource(R.string.licenses_libraries_intro))
            ANDROID_LIBRARIES.forEach { name ->
                Text(
                    "• $name — $APACHE_NAME",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            LicenseText(apache)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = SectionTitle.style,
        color = SectionTitle.color,
        modifier = Modifier
            .padding(top = 16.dp, bottom = 4.dp)
            .semantics { heading() }
    )
}

@Composable
private fun BodyText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun LicenseText(text: String) {
    if (text.isEmpty()) return
    Text(
        text.trimEnd(),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        modifier = Modifier.padding(top = 16.dp)
    )
}

/** Liest einen Lizenztext aus `assets/` (leer, falls die Datei fehlt). */
private suspend fun readAsset(context: Context, path: String): String = withContext(Dispatchers.IO) {
    runCatching { context.assets.open(path).bufferedReader().use { it.readText() } }.getOrDefault("")
}
