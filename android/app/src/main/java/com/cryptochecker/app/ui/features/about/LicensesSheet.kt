@file:OptIn(ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.about

import com.cryptochecker.app.ui.components.SectionTitle
import com.cryptochecker.app.ui.components.sectionTitleMarker
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
import com.cryptochecker.app.domain.market.DataFreshness
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lizenz der App selbst — gleicher Text wie die Datei `LICENSE` des Projekts, samt den ursprünglichen
 * MIT-Vermerken von BitcoinChecker (mobnetic, Aneonex Software), die beim Weitergeben erhalten bleiben müssen.
 */
private const val ASSET_APP = "licenses/MIT-cryptochecker.txt"
private const val ASSET_FONT = "licenses/Rubik-OFL.txt"
private const val ASSET_APACHE = "licenses/Apache-2.0.txt"
private const val APACHE_NAME = "Apache License 2.0"

/** Lizenz von SQLCipher (BSD-Stil, Zetetic LLC) — Text unverändert aus dem SQLCipher-Quellcode. */
private const val ASSET_SQLCIPHER = "licenses/SQLCipher-BSD.txt"
private const val SQLCIPHER_NAME = "SQLCipher for Android (net.zetetic:sqlcipher-android)"
private const val SQLCIPHER_LICENSE_NAME = "BSD-style license, Zetetic LLC"

/** In SQLCipher (libsqlcipher.so) enthalten: SQLite (gemeinfrei); libcrypto von OpenSSL steht unter Apache 2.0. */
private const val SQLITE_LINE = "SQLite — Public Domain"

/**
 * Quellenangabe für den Ersatz-Wechselkurs (open.er-api.com, [com.cryptochecker.app.data.portfolio.FxRateSource]):
 * von ExchangeRate-API verlangte Nennung, nicht übersetzt (wie iOS `LicenseNotices.ratesByExchangeRateApi`).
 */
private const val RATES_BY_EXCHANGERATE_API = "Rates By Exchange Rate API (https://www.exchangerate-api.com)"

/** Fremdbibliotheken aus `app/build.gradle`, die in der App ausgeliefert werden (alle unter der Apache License 2.0). */
private val ANDROID_LIBRARIES = listOf(
    "AndroidX Core, Core SplashScreen, AppCompat, Activity, Lifecycle, Navigation",
    "Jetpack Compose (UI, UI Util, Material 3, ConstraintLayout)",
    "AndroidX Room",
    "AndroidX WorkManager",
    "AndroidX DataStore",
    "AndroidX Biometric",
    "AndroidX ProfileInstaller",
    "AndroidX Concurrent Futures",
    "Kotlin, kotlinx.coroutines",
    "Dagger / Hilt (Hilt Navigation Compose, Hilt Work)",
    // Logging Interceptor nur im Debug-Build (debugImplementation), nicht in der ausgelieferten App
    "OkHttp",
    "Okio",
    "OpenSSL libcrypto (in SQLCipher for Android)",
    "Timber"
)

/**
 * Lizenzhinweise: die App selbst (MIT), Schrift Rubik (OFL), Ersatz-Icons (CC0),
 * Android-Bibliotheken (Apache 2.0) und SQLCipher (BSD-Stil, mit SQLite gemeinfrei). Die Lizenztexte liegen unübersetzt in
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
    val app by produceState("") { value = readAsset(context, ASSET_APP) }
    val font by produceState("") { value = readAsset(context, ASSET_FONT) }
    val apache by produceState("") { value = readAsset(context, ASSET_APACHE) }
    val sqlcipher by produceState("") { value = readAsset(context, ASSET_SQLCIPHER) }
    SelectionContainer {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Die App selbst ist quelloffen (MIT); der ganze Lizenztext wie in `LICENSE`
            BodyText(stringResource(R.string.licenses_app_intro))
            LicenseText(app)
            // Pflichtangabe der CoinGecko-API-Bedingungen (Markenname, nicht übersetzt)
            BodyText(DataFreshness.POWERED_BY_COINGECKO)
            // Ersatz-Wechselkurse von open.er-api.com: verlangte Quellenangabe (nicht übersetzt)
            BodyText(RATES_BY_EXCHANGERATE_API)

            SectionDivider()
            SectionTitle(stringResource(R.string.licenses_font_title))
            LicenseText(font)

            // Ersatz-Logos (Build 46): freies Icon-Paket, CC0 1.0
            SectionDivider()
            SectionTitle(stringResource(R.string.licenses_icons_title))
            BodyText(stringResource(R.string.licenses_icons_bundled_text))

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

            // Verschlüsselte Datenbanken: SQLCipher (BSD-Stil, Zetetic) mit SQLite (gemeinfrei);
            // Bibliotheksnamen und Lizenztext nicht übersetzt
            SectionDivider()
            SectionTitle(SQLCIPHER_NAME)
            Text(
                "• $SQLCIPHER_NAME — $SQLCIPHER_LICENSE_NAME",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp)
            )
            Text(
                "• $SQLITE_LINE",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp)
            )
            LicenseText(sqlcipher)
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
            .sectionTitleMarker()
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
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(top = 16.dp)
    )
}

/** Liest einen Lizenztext aus `assets/` (leer, falls die Datei fehlt). */
private suspend fun readAsset(context: Context, path: String): String = withContext(Dispatchers.IO) {
    runCatching { context.assets.open(path).bufferedReader().use { it.readText() } }.getOrDefault("")
}
