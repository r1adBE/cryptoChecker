package com.cryptochecker.app.ui.features.error

import android.content.Context
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.ui.theme.CryptoCheckerTheme
import java.io.IOException
import java.net.SocketTimeoutException

class ErrorScreenViewState(private val throwable: Throwable) {

    @DrawableRes
    fun getErrorImage(): Int = when (throwable) {
        is IOException -> R.drawable.ic_no_connection
        else -> R.drawable.ic_error
    }

    fun getErrorMessage(context: Context): String {
        val message = when (throwable) {
//            is HttpException -> throwable.message()
            is SocketTimeoutException -> context.getString(R.string.timeout_error_message)
            is IOException -> context.getString(R.string.no_internet_connection)
            else -> context.getString(R.string.something_went_wrong)
        }
        // Kein Stack- oder Exception-Text: nur ein Satz, was schiefging.
        return message
    }
}

@Composable
fun ErrorScreen(
    modifier: Modifier = Modifier,
    errorScreenViewState: ErrorScreenViewState,
    onTryAgainButtonClick: () -> Unit
) {
    Surface(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(id = errorScreenViewState.getErrorImage()),
                contentDescription = null,
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = Modifier.size(56.dp)
            )

            Text(
                text = errorScreenViewState.getErrorMessage(LocalContext.current),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp, start = 32.dp, end = 32.dp).alpha(0.8f)
            )
            Button(
                onClick = onTryAgainButtonClick,
                modifier = Modifier
                    .width(250.dp)
                    .padding(top = 16.dp)
//                    .background(Gray900, RoundedCornerShape(4.dp))
            ) {
                Text(
                    text = stringResource(id = R.string.try_again),
                    //style = Typography.labelLarge,
                    //color = White
                )
            }
        }
    }

}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun ErrorScreenPreview() {
    CryptoCheckerTheme {
        ErrorScreen(errorScreenViewState = ErrorScreenViewState(IOException())) {}
    }
}