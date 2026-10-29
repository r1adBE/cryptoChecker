package com.cryptochecker.app.ui.features.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import com.cryptochecker.app.settings.logoRes
import com.cryptochecker.app.ui.theme.LocalAccentColor
import com.cryptochecker.app.ui.theme.LocalDarkTheme
import com.cryptochecker.app.ui.theme.Spacing
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.BuildConfig
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.market.DataFreshness
import com.cryptochecker.app.domain.market.ExchangeVerification
import com.cryptochecker.app.util.LocaleNumbers
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Fragen und Wünsche: Issues auf GitHub (Vorlagen für Frage, Wunsch, Börse, Fehler). */
const val FEEDBACK_LABEL = "github.com/r1adBE/cryptoChecker"
const val FEEDBACK_URL = "https://github.com/r1adBE/cryptoChecker/issues/new/choose"

/** «Börse wünschen»: GitHub-Issue mit der Vorlage `.github/ISSUE_TEMPLATE/exchange_request.md`. */
const val EXCHANGE_REQUEST_URL = "https://github.com/r1adBE/cryptoChecker/issues/new?template=exchange_request.md"

/** Quellcode der App, öffentlich unter der MIT-Lizenz. */
const val SOURCE_CODE_URL = "https://github.com/r1adBE/cryptoChecker"

/** Datenschutzerklärung (GitHub Pages), gleich wie im Store-Eintrag und in iOS `AppLinks`. */
const val PRIVACY_POLICY_URL = "https://r1adbe.github.io/cryptoChecker/privacy/"

/**
 * Kurzinfo zur App — Reihenfolge wie iOS `AboutContent`: App-Symbol mit Version zuerst, dann die Einleitung.
 * In den Einstellungen auf der Seite «Über die App»; Datenschutz, Börse
 * wünschen, Quellcode und Lizenzen stehen als eigene Zeilen der Hauptseite (mit ↗ bzw. ›),
 * nicht hier — Verweise sehen so überall gleich aus.
 */
@Composable
fun ColumnScope.AboutContent(
    showHeading: Boolean = true,
    onVersionTap: (() -> Unit)? = null,
) {
    if (showHeading) {
        Text(
            text = stringResource(R.string.settings_section_about),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp)
        )
    }

    // Kopf wie iOS: App-Symbol, daneben Version, Lizenz, CoinGecko-Hinweis und Stand der Börsen-Leser;
    // darunter die Einleitung. Der ganze Kopf ist die Tippfläche für «Entwickler» (7 Tipps)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = (if (onVersionTap != null) Modifier.clickable(onClick = onVersionTap) else Modifier)
            .padding(vertical = Spacing.md)
    ) {
        Image(
            painter = painterResource(LocalAccentColor.current.logoRes(LocalDarkTheme.current)),
            contentDescription = null,
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(13.dp))
        )
        Column(modifier = Modifier.padding(start = Spacing.md)) {
            Text(
                text = stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.about_license),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp)
            )
            // Pflichtangabe der CoinGecko-API-Bedingungen (Markenname, nicht übersetzt; 12 sp)
            Text(
                text = DataFreshness.POWERED_BY_COINGECKO,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp)
            )
            // Stand der Börsen-Leser: Tag der letzten echten Aufzeichnung, Datum im Format des Geräts
            Text(
                text = stringResource(
                    R.string.about_exchanges_verified,
                    ExchangeVerification.LAST_VERIFIED.format(LocaleNumbers.dates(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
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
        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
    )
}
