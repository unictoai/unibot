package ai.unicto.unibot.ui.home

import ai.unicto.unibot.ui.navigation.Routes
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1.3.5: the drawer's Swarm entry must open the same dedicated swarm space
 * as the chat top-bar pill — [Routes.SWARM]. The destination is pinned behind
 * [swarmEntryRoute] so the two entries can never drift apart silently.
 *
 * (Drawer position — directly below Devices — and the row's rendering are
 * not unit-testable: this module has no Compose UI test harness. They are
 * verified by inspection of SideChatDrawer.kt, where SwarmDrawerRow sits
 * between the Devices block and the Coding / "Side chats" section.)
 */
class SwarmDrawerEntryTest {

    @Test
    fun `drawer swarm entry opens the swarm space`() {
        assertEquals(Routes.SWARM, swarmEntryRoute())
    }
}
