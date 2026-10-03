package ai.unicto.unibot.ui.browser

import ai.unicto.unibot.R
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Find-in-page bar driving [WebView.findAllAsync] with match navigation.
 *
 * Wired to a live WebView: typing searches, the chevrons step through
 * matches via [WebView.findNext], and closing (or emptying the query)
 * clears the highlight via [WebView.clearMatches]. All WebView calls happen
 * on the main thread — Compose event handlers already run there.
 */
@Composable
fun BrowserFindBar(
    webView: WebView?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var activeMatch by remember { mutableStateOf(0) }
    var matchCount by remember { mutableStateOf(0) }
    var countingDone by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    DisposableEffect(webView) {
        webView?.setFindListener { activeOrdinal, numberOfMatches, isDoneCounting ->
            // activeOrdinal is 0-based; display 1-based once counting settles.
            activeMatch = activeOrdinal + 1
            matchCount = numberOfMatches
            countingDone = isDoneCounting
        }
        onDispose {
            webView?.setFindListener(null)
            webView?.clearMatches()
        }
    }

    LaunchedEffect(query) {
        val wv = webView ?: return@LaunchedEffect
        if (query.isEmpty()) {
            wv.clearMatches()
            matchCount = 0
            countingDone = false
        } else {
            countingDone = false
            wv.findAllAsync(query)
        }
    }

    val barBg = MaterialTheme.colorScheme.surfaceContainerHigh

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(barBg)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = {
                webView?.findNext(true)
            }),
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text(
                        stringResource(R.string.browser_find_hint),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                inner()
            },
            modifier = Modifier.weight(1f),
        )
        if (query.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(
                if (matchCount > 0 && countingDone) "$activeMatch/$matchCount"
                else if (matchCount > 0) "$matchCount"
                else "0",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(
                onClick = { webView?.findNext(false) },
                enabled = matchCount > 0,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.Filled.ChevronLeft,
                    contentDescription = stringResource(R.string.browser_find_prev),
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(
                onClick = { webView?.findNext(true) },
                enabled = matchCount > 0,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = stringResource(R.string.browser_find_next),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        IconButton(
            onClick = {
                keyboardController?.hide()
                focusManager.clearFocus()
                onClose()
            },
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(R.string.browser_find_close),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
