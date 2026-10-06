package ai.unicto.unibot.ui.chat

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4.0 item 77 — ViewModelStore LRU eviction.
 *
 * Session-hopping must not pin an unbounded number of live ChatViewModels
 * (agent loop + message list each) and OOM a 4GB phone. These tests pin:
 *  - the cache never exceeds MAX_CACHED_STORES,
 *  - eviction is least-recently-used (a re-touched session survives),
 *  - the foregrounded session is never evicted,
 *  - pre-existing behavior still holds (release drops the store, rename
 *    keeps the live store under the new id).
 *
 * Each test uses a unique id prefix: ChatViewModelStore is a process-wide
 * object whose state persists across test methods in one JVM fork.
 */
class ChatViewModelStoreTest {

    private val created = mutableListOf<String>()
    private var seq = 0

    private fun freshId(prefix: String): String {
        seq++
        return "lru-test-$prefix-$seq-${System.nanoTime()}".also { created.add(it) }
    }

    @After
    fun tearDown() {
        ChatViewModelStore.setActiveSession(null)
        for (id in created) {
            runCatching { ChatViewModelStore.release(id) }
        }
        created.clear()
    }

    @Test
    fun `cache never exceeds the cap`() {
        val ids = (1..ChatViewModelStore.MAX_CACHED_STORES + 4).map { freshId("cap") }
        ids.forEach { ChatViewModelStore.ownerFor(it) }
        assertEquals(
            ChatViewModelStore.MAX_CACHED_STORES,
            ChatViewModelStore.cachedStoreCount(),
        )
    }

    @Test
    fun `eviction is least-recently-used`() {
        val ids = (1..ChatViewModelStore.MAX_CACHED_STORES).map { freshId("lru") }
        ids.forEach { ChatViewModelStore.ownerFor(it) }
        // Re-touch the oldest: it becomes most-recently-used.
        ChatViewModelStore.ownerFor(ids.first())
        // One more allocation forces exactly one eviction.
        val newcomer = freshId("lru-new")
        ChatViewModelStore.ownerFor(newcomer)

        val keys = ChatViewModelStore.cachedStoreKeys()
        assertEquals(ChatViewModelStore.MAX_CACHED_STORES, keys.size)
        assertTrue("re-touched oldest must survive", keys.contains(ids.first()))
        assertTrue("newcomer must be cached", keys.contains(newcomer))
        assertFalse(
            "second-oldest (never re-touched) must be evicted",
            keys.contains(ids[1]),
        )
    }

    @Test
    fun `foregrounded session is never evicted`() {
        val ids = (1..ChatViewModelStore.MAX_CACHED_STORES).map { freshId("active") }
        ids.forEach { ChatViewModelStore.ownerFor(it) }
        ChatViewModelStore.setActiveSession(ids.first())
        // Flood past the cap: the active (oldest) store must survive every round.
        repeat(ChatViewModelStore.MAX_CACHED_STORES + 2) {
            ChatViewModelStore.ownerFor(freshId("flood"))
        }
        assertTrue(
            "active session store must never be evicted",
            ChatViewModelStore.cachedStoreKeys().contains(ids.first()),
        )
        assertTrue(
            ChatViewModelStore.cachedStoreCount() <= ChatViewModelStore.MAX_CACHED_STORES,
        )
    }

    @Test
    fun `release drops the store and its lru slot`() {
        val id = freshId("release")
        val owner1 = ChatViewModelStore.ownerFor(id)
        ChatViewModelStore.release(id)
        assertFalse(ChatViewModelStore.cachedStoreKeys().contains(id))
        // A later lookup allocates a FRESH store, not the released one.
        val owner2 = ChatViewModelStore.ownerFor(id)
        assertTrue(owner1.viewModelStore !== owner2.viewModelStore)
    }

    @Test
    fun `rename keeps the live store under the new id`() {
        val from = freshId("rename-from")
        val to = freshId("rename-to")
        val ownerBefore = ChatViewModelStore.ownerFor(from)
        ChatViewModelStore.rename(from, to)
        val ownerAfter = ChatViewModelStore.ownerFor(to)
        assertTrue(ownerBefore.viewModelStore === ownerAfter.viewModelStore)
    }

    @Test
    fun `same session lookup returns the same store`() {
        val id = freshId("same")
        val a = ChatViewModelStore.ownerFor(id)
        val b = ChatViewModelStore.ownerFor(id)
        assertTrue(a.viewModelStore === b.viewModelStore)
    }
}
