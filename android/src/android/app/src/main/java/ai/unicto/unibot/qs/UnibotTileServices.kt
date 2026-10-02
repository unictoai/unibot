package ai.unicto.unibot.qs

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi

/**
 * [P1-qs-tile] Quick Settings tiles for unibot.
 *
 * Tapping a tile collapses the shade and fires the matching
 * `unibot://action/…` deep link at MainActivity (same routes the
 * launcher long-press shortcuts use — see `res/xml/shortcuts.xml` and
 * [ai.unicto.unibot.deeplink.DeepLinkHandler]):
 * - "Voice ask" → `unibot://action/voice_chat` (new chat, mic auto-opens)
 * - "New chat"  → `unibot://action/new_chat`
 *
 * TileService needs API 24+; minSdk is 26, so no runtime gating is needed.
 * The tiles are user-added from the QS editor; nothing is scheduled or
 * polled — [onClick] is the only override.
 */
private fun TileService.collapseToUnibotAction(action: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("unibot://action/$action")).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    // Collapses the Quick Settings shade as the app opens — the standard
    // TileService handoff (API 24+).
    startActivityAndCollapse(intent)
}

/** "Voice ask" tile — opens a new chat with voice input engaged. */
@RequiresApi(Build.VERSION_CODES.N)
class UnibotVoiceTileService : TileService() {
    override fun onClick() {
        super.onClick()
        collapseToUnibotAction("voice_chat")
    }
}

/** "New chat" tile — opens a fresh draft chat. */
@RequiresApi(Build.VERSION_CODES.N)
class UnibotNewChatTileService : TileService() {
    override fun onClick() {
        super.onClick()
        collapseToUnibotAction("new_chat")
    }
}
