package ai.unicto.unibot.ui.browser

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Composable wrapper around Android WebView, mirroring iOS BrowserWebView.
 *
 * [T-android-browser-blank] Hosts the WebView inside a FrameLayout container
 * and swaps it in `update` instead of returning the WebView from `factory`.
 * AndroidView's factory runs ONCE per node — with the old direct-return
 * approach, switching tabs (or the pool recreating a WebView after idle
 * eviction) recomposed with a NEW WebView instance but the screen kept
 * showing the stale one: the sheet header/URL bar (StateFlows from the
 * current tab manager) looked correct while the content area rendered a
 * dead/blank WebView — the reported black/white screen. iOS avoids this via
 * `.id(pool.selectedTabId)` remounting; the container+update pattern is the
 * Android equivalent.
 *
 * Ask all ancestor views (including the host ModalBottomSheet) to stop
 * intercepting touches as soon as the user puts a finger down on the
 * WebView, so page scroll gestures never drag the sheet.
 *
 * v1.2 pull-to-refresh: when [pullToRefreshEnabled], a downward drag that
 * starts with the page scrolled to the top reports progress through
 * [onPullProgress] (0..1+, 1 = threshold) and [onPullRelease] fires on
 * finger-up with whether the threshold was passed. The listener only
 * *observes* (never consumes), so page scrolling is unaffected. The caller
 * owns the indicator UI and the actual refresh action.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun BrowserWebView(
    webView: WebView,
    modifier: Modifier = Modifier,
    pullToRefreshEnabled: Boolean = false,
    onPullProgress: ((Float) -> Unit)? = null,
    onPullRelease: ((pastThreshold: Boolean) -> Unit)? = null,
) {
    val thresholdPx = with(LocalDensity.current) { PULL_THRESHOLD_DP.dp.toPx() }
    // rememberUpdatedState keeps the touch listener's callbacks fresh across
    // tab switches — the listener is installed once per mounted WebView but
    // the lambdas it calls always resolve to the latest composition.
    val currentPullEnabled by rememberUpdatedState(pullToRefreshEnabled)
    val currentOnPullProgress by rememberUpdatedState(onPullProgress)
    val currentOnPullRelease by rememberUpdatedState(onPullRelease)
    val pullTracker = remember { PullTracker() }

    AndroidView(
        factory = { context -> FrameLayout(context) },
        update = { container ->
            val mounted = container.getChildAt(0)
            if (mounted !== webView) {
                container.removeAllViews()
                // The WebView may still be parented to a previous container
                // (sheet re-open racing the old node's disposal) — detach
                // first or addView throws "child already has a parent".
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.setOnTouchListener { v, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN,
                        MotionEvent.ACTION_POINTER_DOWN -> {
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                            pullTracker.onDown(event.y)
                        }
                        MotionEvent.ACTION_MOVE -> {
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                            if (currentPullEnabled) {
                                pullTracker.onMove(
                                    y = event.y,
                                    scrollY = webView.scrollY,
                                    thresholdPx = thresholdPx,
                                    onProgress = currentOnPullProgress,
                                )
                            }
                        }
                        MotionEvent.ACTION_UP,
                        MotionEvent.ACTION_CANCEL -> {
                            v.parent?.requestDisallowInterceptTouchEvent(false)
                            if (currentPullEnabled) {
                                pullTracker.onUp(
                                    thresholdPx = thresholdPx,
                                    onProgress = currentOnPullProgress,
                                    onRelease = currentOnPullRelease,
                                )
                            }
                        }
                    }
                    false
                }
                container.addView(
                    webView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        },
        onRelease = { container ->
            // Free the WebView so a later mount (same or different sheet)
            // can re-parent it without hitting the has-a-parent guard.
            container.removeAllViews()
        },
        modifier = modifier,
    )
}

/** Pull-to-refresh trigger distance. */
private const val PULL_THRESHOLD_DP = 110

/**
 * Tracks a top-of-page pull gesture across touch events. Owned per
 * composition (remember) so a mid-gesture recomposition doesn't reset it.
 * All math is in raw pixels; progress is dy / threshold.
 */
private class PullTracker {
    private var startY: Float = -1f
    private var lastDy: Float = 0f

    fun onDown(y: Float) {
        startY = y
        lastDy = 0f
    }

    fun onMove(
        y: Float,
        scrollY: Int,
        thresholdPx: Float,
        onProgress: ((Float) -> Unit)?,
    ) {
        if (startY < 0f) {
            startY = y
            return
        }
        if (scrollY > 0) {
            // Page isn't at the top — this is a scroll, not a pull.
            // Re-anchor so gliding to the top mid-gesture can't trigger.
            startY = y
            if (lastDy != 0f) {
                lastDy = 0f
                onProgress?.invoke(0f)
            }
            return
        }
        val dy = y - startY
        lastDy = dy
        onProgress?.invoke(if (dy > 0f) (dy / thresholdPx).coerceAtMost(1.25f) else 0f)
    }

    fun onUp(
        thresholdPx: Float,
        onProgress: ((Float) -> Unit)?,
        onRelease: ((pastThreshold: Boolean) -> Unit)?,
    ) {
        val pastThreshold = lastDy >= thresholdPx
        startY = -1f
        lastDy = 0f
        onProgress?.invoke(0f)
        if (pastThreshold) onRelease?.invoke(true)
    }
}
