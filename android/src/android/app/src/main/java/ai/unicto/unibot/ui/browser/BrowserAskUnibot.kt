package ai.unicto.unibot.ui.browser

import android.webkit.WebView
import ai.unicto.unibot.ui.home.HomeBus

/**
 * "Ask unibot about this page": grabs the current page's title + main
 * article text ([BrowserPageText]) and prefills the main chat's composer
 * through [HomeBus] — the same `unibot://ask?text=…` path the home-screen
 * widget and Tasker broadcast use. The text is prefilled, never auto-sent:
 * the user reviews it in the composer first.
 *
 * [onDone] fires on the main thread once the prefill is queued, so the
 * caller can dismiss the browser sheet. Even when extraction fails the
 * button never dead-ends: title + URL still go to the composer.
 */
object BrowserAskUnibot {

    /** Characters of page text included ahead of the user's question. */
    private const val TEXT_BUDGET = 8000

    fun askAboutPage(
        webView: WebView,
        fallbackTitle: String,
        pageUrl: String,
        onDone: () -> Unit,
    ) {
        BrowserPageText.extract(webView) { content ->
            val title = content?.title?.ifBlank { null } ?: fallbackTitle.ifBlank { null }
            val text = content?.text?.take(TEXT_BUDGET)
            val prompt = buildString {
                appendLine("About this page:")
                if (!title.isNullOrBlank()) appendLine("Title: $title")
                if (pageUrl.isNotBlank()) appendLine("URL: $pageUrl")
                if (!text.isNullOrBlank()) {
                    appendLine()
                    appendLine(text)
                }
                appendLine()
                append("My question: ")
            }
            HomeBus.prefillComposer(prompt)
            onDone()
        }
    }
}
