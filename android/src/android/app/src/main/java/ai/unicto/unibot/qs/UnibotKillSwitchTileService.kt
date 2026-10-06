package ai.unicto.unibot.qs

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import ai.unicto.unibot.privacy.PrivacyPrefs

/**
 * Privacy backlog item 58 — kill-switch Quick Settings tile. One tap severs
 * all network app-wide: [PrivacyPrefs.setKillSwitch] flips the flag that
 * [ai.unicto.unibot.privacy.PrivacyNetworkGate.checkAllowed] reads on every
 * request, so in-flight and future requests fail fast with a clear
 * "network killed" state. Deliberately NOT a VPN — it is an app-level gate
 * over this app's own HTTP clients, and the tile + Settings row + banner
 * always show the live state so the phone can never look "mysteriously
 * offline".
 *
 * The switch is persisted: a kill the user engaged survives a process
 * restart. Tapping the tile again (or the Settings → Privacy row) turns it
 * back off.
 */
@RequiresApi(Build.VERSION_CODES.N)
class UnibotKillSwitchTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val context = applicationContext
        // init is idempotent — safe when the tile runs before any activity.
        PrivacyPrefs.init(context)
        val next = !PrivacyPrefs.killSwitch.value
        PrivacyPrefs.setKillSwitch(next)
        refreshTile()
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val engaged = try {
            PrivacyPrefs.init(applicationContext)
            PrivacyPrefs.killSwitch.value
        } catch (t: Throwable) {
            false
        }
        tile.state = if (engaged) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (engaged) "Network killed" else "Network on"
        }
        tile.updateTile()
    }
}
