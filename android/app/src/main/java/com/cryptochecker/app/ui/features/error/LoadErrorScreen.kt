package com.cryptochecker.app.ui.features.error

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.cryptochecker.app.R
import com.cryptochecker.app.ui.components.EmptyState
import com.cryptochecker.app.ui.theme.CryptoCheckerTheme
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Symbol, Titel und Satz zu einem Ladefehler: kein Netz, Zeitüberschreitung (Börse antwortet nicht) oder sonst
 * etwas. Einfarbige Symbole wie jeder leere Zustand ([EmptyState]) statt farbiger Illustrationen.
 */
class LoadErrorScreenViewState(private val throwable: Throwable) {

    private val isNetwork: Boolean get() = throwable is IOException

    // Zeitüberschreitung ist auch eine IOException, darum zuerst
    private val isTimeout: Boolean get() = throwable is SocketTimeoutException

    /** Netz bzw. Börse nicht erreichbar: Wolke durchgestrichen; sonst Fehler-Symbol. */
    @DrawableRes
    fun iconRes(): Int = if (isNetwork) R.drawable.ic_cloud_off else R.drawable.ic_error

    @StringRes
    fun titleRes(): Int = when {
        isTimeout -> R.string.error_exchange_unreachable_short
        isNetwork -> R.string.offline_status
        else -> R.string.something_went_wrong
    }

    /** Was jetzt hilft — kein Ausnahme- oder Stack-Text; null = der Titel sagt genug. */
    @StringRes
    fun messageRes(): Int? = when {
        isTimeout -> R.string.timeout_error_message
        isNetwork -> R.string.no_internet_connection
        else -> null
    }
}

/** Ganze Fläche im Aufbau aller leeren Zustände ([EmptyState]): Symbol, Titel, Satz und «Erneut versuchen». */
@Composable
fun LoadErrorScreen(
    modifier: Modifier = Modifier,
    errorScreenViewState: LoadErrorScreenViewState,
    onTryAgainButtonClick: () -> Unit,
) {
    val state = errorScreenViewState
    Surface(modifier = modifier) {
        EmptyState(
            iconRes = state.iconRes(),
            title = stringResource(state.titleRes()),
            message = state.messageRes()?.let { stringResource(it) },
            actionLabel = stringResource(R.string.try_again),
            onAction = onTryAgainButtonClick,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun LoadErrorScreenPreview() {
    CryptoCheckerTheme {
        LoadErrorScreen(errorScreenViewState = LoadErrorScreenViewState(IOException()), onTryAgainButtonClick = {})
    }
}
