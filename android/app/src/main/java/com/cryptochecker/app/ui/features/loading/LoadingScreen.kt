package com.cryptochecker.app.ui.features.loading

import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.cryptochecker.app.ui.components.SkeletonList
import com.cryptochecker.app.ui.theme.CryptoCheckerTheme

/** Ladeanzeige über die ganze Fläche: Platzhalter-Zeilen statt eines einzelnen Kreisels. */
@Composable
fun LoadingScreen(modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize()) {
        SkeletonList()
    }
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun LoadingScreenPreview() {
    CryptoCheckerTheme { LoadingScreen() }
}
