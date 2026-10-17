package com.cryptochecker.app.ui.features.error

import android.content.Context
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.ui.theme.CryptoCheckerTheme
import java.io.IOException
import java.net.SocketTimeoutException

/** Bild und Satz zu einem Ladefehler: Netzfehler, Zeitüberschreitung oder sonst etwas. */
class ErrorScreenViewState(private val throwable: Throwable) {

    private val isNetwork: Boolean get() = throwable is IOException

    @DrawableRes
    fun getErrorImage(): Int = if (isNetwork) R.drawable.ic_no_connection else R.drawable.ic_error

    /** Was schiefging und was jetzt hilft — kein Ausnahme- oder Stack-Text. */
    fun getErrorMessage(context: Context): String = context.getString(messageRes())

    // Zeitüberschreitung ist auch eine IOException, darum zuerst
    @StringRes
    private fun messageRes(): Int = when {
        throwable is SocketTimeoutException -> R.string.timeout_error_message
        isNetwork -> R.string.no_internet_connection
        else -> R.string.something_went_wrong
    }
}

/** Ganze Fläche: Symbol, Satz und «Erneut versuchen». */
@Composable
fun ErrorScreen(
    modifier: Modifier = Modifier,
    errorScreenViewState: ErrorScreenViewState,
    onTryAgainButtonClick: () -> Unit,
) {
    val state = errorScreenViewState
    Surface(modifier = modifier) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize(),
        ) {
            Image(
                painter = painterResource(id = state.getErrorImage()),
                contentDescription = null,
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = Modifier.size(56.dp),
            )
            Text(
                text = state.getErrorMessage(LocalContext.current),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = 16.dp, start = 32.dp, end = 32.dp)
                    .alpha(0.8f),
            )
            Button(
                onClick = onTryAgainButtonClick,
                // Mindestens 250 dp, mit langer Übersetzung bzw. grosser Schrift breiter (Rand 32 dp wie der Satz)
                modifier = Modifier
                    .padding(top = 16.dp, start = 32.dp, end = 32.dp)
                    .widthIn(min = 250.dp),
            ) {
                Text(text = stringResource(id = R.string.try_again))
            }
        }
    }
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun ErrorScreenPreview() {
    CryptoCheckerTheme {
        ErrorScreen(errorScreenViewState = ErrorScreenViewState(IOException()), onTryAgainButtonClick = {})
    }
}
