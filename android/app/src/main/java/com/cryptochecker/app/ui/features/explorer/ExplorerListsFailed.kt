// Satz zu Börsen, deren Paarliste nicht geladen werden konnte (Teil von `ListsOffer`, eigene Datei wegen der Länge).
package com.cryptochecker.app.ui.features.explorer

import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import com.cryptochecker.resources.R

/**
 * «Binance, Bybit, Coinbase, +47 weitere antworten zurzeit nicht …»: Börsen ohne Antwort alphabetisch, eingeklappt
 * höchstens [FAILED_NAMES_SHOWN] (ohne Netz wären es sonst rund 50 Namen); Tipp auf den Satz zeigt alle. Wie iOS.
 */
@Composable
internal fun FailedListsText(failed: List<String>, expanded: Boolean, onExpand: () -> Unit) {
    val (names, hidden) = failedNamesShown(failed, expanded)
    val more = if (hidden > 0) pluralStringResource(R.plurals.explorer_lists_failed_more, hidden, hidden) else null
    // Zwei Texte statt Plural: die Zahl steht nicht im Satz (Xcode verlangt sie in Plural-Varianten)
    val sentence = stringResource(
        if (failed.size == 1) R.string.explorer_lists_failed_one else R.string.explorer_lists_failed_many,
        (names + listOfNotNull(more)).joinToString(", ")
    )
    val accent = MaterialTheme.colorScheme.primary
    val text = remember(sentence, more, accent) {
        buildAnnotatedString {
            append(sentence)
            val start = more?.let { sentence.lastIndexOf(it) } ?: -1
            if (more != null && start >= 0) {
                addStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold), start, start + more.length)
            }
        }
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = if (more == null) {
            Modifier
        } else {
            Modifier.clickable(
                role = Role.Button,
                onClickLabel = stringResource(R.string.watchlist_show_all),
                onClick = onExpand
            )
        }
    )
}

/**
 * Viele Börsen ohne Antwort ([failed] > [FAILED_NAMES_SHOWN]) und noch nicht aufgeklappt: nur die Zahl der fehlenden
 * Listen, keine Namen. Wie iOS `ExplorerListsOffer.summarized`.
 */
internal fun failedSummarized(failed: Int, expanded: Boolean): Boolean = failed > FAILED_NAMES_SHOWN && !expanded

/** Eingeklappt so viele Börsennamen im Satz «… antworten zurzeit nicht». */
internal const val FAILED_NAMES_SHOWN = 3

/** Gezeigte Namen (alphabetisch) und wie viele dahinter als «+N weitere» stehen. Wie iOS `ExplorerListsOffer.shown`. */
internal fun failedNamesShown(failed: List<String>, expanded: Boolean): Pair<List<String>, Int> {
    val sorted = failed.sortedWith(String.CASE_INSENSITIVE_ORDER)
    if (expanded || sorted.size <= FAILED_NAMES_SHOWN) return sorted to 0
    return sorted.take(FAILED_NAMES_SHOWN) to sorted.size - FAILED_NAMES_SHOWN
}
