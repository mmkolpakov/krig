package space.kscience.krig.jupyter

import space.kscience.krig.api.lifecycle.LifecycleState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Smoke coverage for the pure HTML-building helpers behind the notebook renderers. */
class RendererSmokeTest {

    @Test
    fun lifecycleBadgeRendersEveryState() {
        val states = listOf(
            LifecycleState.Running,
            LifecycleState.Failed(IllegalStateException("boom")),
            LifecycleState.Detached,
            LifecycleState.Starting,
            LifecycleState.Attaching,
            LifecycleState.Stopping,
            LifecycleState.Detaching,
            LifecycleState.Stopped,
        )
        for (state in states) {
            val badge = state.htmlBadge()
            assertTrue(badge.startsWith("<span"), "badge for $state must be a span")
            assertTrue(state::class.simpleName!! in badge, "badge for $state must carry its label")
        }
    }

    @Test
    fun escapeNeutralisesHtml() {
        assertEquals("&lt;script&gt;&amp;", "<script>&".escape())
        assertEquals("", (null as Any?).escape())
        assertFalse("<" in "a<b>c".escape())
    }
}
