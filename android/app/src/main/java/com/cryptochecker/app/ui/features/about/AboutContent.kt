package com.cryptochecker.app.ui.features.about

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.BuildConfig
import com.cryptochecker.app.R

/** Fragen und Wünsche: Issues auf GitHub (Vorlagen für Frage, Wunsch, Börse, Fehler). */
const val FEEDBACK_LABEL = "github.com/r1adBE/cryptoChecker"
const val FEEDBACK_URL = "https://github.com/r1adBE/cryptoChecker/issues/new/choose"

/** «Börse wünschen»: GitHub-Issue mit der Vorlage `.github/ISSUE_TEMPLATE/exchange_request.md`. */
const val EXCHANGE_REQUEST_URL = "https://github.com/r1adBE/cryptoChecker/issues/new?template=exchange_request.md"

/** Runde 15: Quellcode der App, öffentlich unter der MIT-Lizenz. */
const val SOURCE_CODE_URL = "https://github.com/r1adBE/cryptoChecker"

/** Datenschutzerklärung (GitHub Pages), gleich wie im Store-Eintrag und in iOS `AppLinks`. */
const val PRIVACY_POLICY_URL = "https://r1adbe.github.io/cryptoChecker/privacy/"

/**
 * Kurzinfo zur App. Wird dauerhaft in den Einstellungen unter «Über» gezeigt.
 */
@Composable
fun ColumnScope.AboutContent(showHeading: Boolean = true, onVersionTap: (() -> Unit)? = null) {
    if (showHeading) {
        Text(
            text = stringResource(R.string.settings_section_about),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp)
        )
    }

    // «Warum gibt es die App?» mit tippbarem Link zu den GitHub-Issues
    val why = stringResource(R.string.about_why, FEEDBACK_LABEL)
    val linkColor = MaterialTheme.colorScheme.primary
    // Ohne Browser wirft der Standard-Handler (ActivityNotFound → IllegalArgumentException)
    val uriHandler = LocalUriHandler.current
    val whyText = buildAnnotatedString {
        val start = why.indexOf(FEEDBACK_LABEL)
        if (start < 0) {
            append(why)
        } else {
            append(why.substring(0, start))
            withLink(
                LinkAnnotation.Url(
                    url = FEEDBACK_URL,
                    styles = TextLinkStyles(
                        style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
                    ),
                    linkInteractionListener = { runCatching { uriHandler.openUri(FEEDBACK_URL) } }
                )
            ) {
                append(FEEDBACK_LABEL)
            }
            append(why.substring(start + FEEDBACK_LABEL.length))
        }
    }
    Text(
        text = whyText,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(bottom = 12.dp)
    )

    Text(
        text = stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
        style = MaterialTheme.typography.bodyMedium,
        // Sieben Mal tippen schaltet die Entwickleroptionen frei.
        modifier = if (onVersionTap != null) Modifier.clickable(onClick = onVersionTap) else Modifier
    )
    Text(
        text = stringResource(R.string.about_license),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp)
    )
    // Google Play verlangt den Link zur Datenschutzerklärung auch in der App.
    Text(
        text = stringResource(R.string.about_privacy_policy),
        style = MaterialTheme.typography.bodyMedium,
        color = linkColor,
        textDecoration = TextDecoration.Underline,
        modifier = Modifier
            .padding(top = 8.dp)
            .clickable(role = Role.Button) { runCatching { uriHandler.openUri(PRIVACY_POLICY_URL) } }
            .padding(vertical = 4.dp)
    )
    // Runde 13b: fehlende Börse direkt mit der GitHub-Vorlage wünschen
    Text(
        text = stringResource(R.string.about_request_exchange),
        style = MaterialTheme.typography.bodyMedium,
        color = linkColor,
        textDecoration = TextDecoration.Underline,
        modifier = Modifier
            .padding(top = 4.dp)
            .clickable(role = Role.Button) { runCatching { uriHandler.openUri(EXCHANGE_REQUEST_URL) } }
            .padding(vertical = 4.dp)
    )
    // Runde 15: Quellcode öffentlich auf GitHub (MIT)
    Text(
        text = stringResource(R.string.about_source_code),
        style = MaterialTheme.typography.bodyMedium,
        color = linkColor,
        textDecoration = TextDecoration.Underline,
        modifier = Modifier
            .padding(top = 4.dp)
            .clickable(role = Role.Button) { runCatching { uriHandler.openUri(SOURCE_CODE_URL) } }
            .padding(vertical = 4.dp)
    )
    // Runde 14: Lizenzhinweise, bewusst unauffällig ganz unten
    var showLicenses by rememberSaveable { mutableStateOf(false) }
    Text(
        text = stringResource(R.string.about_licenses),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(top = 8.dp)
            .clickable(role = Role.Button) { showLicenses = true }
            .padding(vertical = 4.dp)
    )
    if (showLicenses) LicensesSheet(onDismiss = { showLicenses = false })
}
