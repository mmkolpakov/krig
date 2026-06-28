package space.kscience.krig.jupyter

import space.kscience.dataforge.names.asName
import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.descriptors.PropertyKind
import space.kscience.krig.api.descriptors.TypeIds
import space.kscience.krig.api.lifecycle.LifecycleState
import space.kscience.krig.api.services.AllowAllAuthorizationService
import space.kscience.krig.api.services.auditService
import space.kscience.krig.api.services.authorizationService
import space.kscience.krig.assembly.DeviceCatalog
import space.kscience.krig.core.contracts.manifestOf
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

    @Test
    fun contractHtmlShowsDescriptorsAndCatalogEntries() {
        val property = PropertyDescriptor(
            name = "temperature".asName(),
            kind = PropertyKind.PHYSICAL,
            valueTypeId = TypeIds.DOUBLE,
        )
        val action = ActionDescriptor(name = "reset".asName())
        val manifest = manifestOf(
            id = "thermo".asName(),
            properties = mapOf(property.name to property),
            actions = mapOf(action.name to action),
        )
        val catalog = DeviceCatalog(mapOf(manifest.id to manifest))

        val manifestHtml = manifest.htmlSummary()
        assertTrue("DeviceManifest" in manifestHtml)
        assertTrue("temperature" in manifestHtml)
        assertTrue("kotlin.Double" in manifestHtml)
        assertTrue("reset" in manifestHtml)

        val catalogHtml = catalog.htmlSummary()
        assertTrue("DeviceCatalog" in catalogHtml)
        assertTrue("thermo" in catalogHtml)
    }

    @Test
    fun notebookContextUsesPermissiveLabServices() {
        val context = krigNotebookContext("renderer-smoke")
        try {
            assertTrue(context.authorizationService is AllowAllAuthorizationService)
            assertFalse(context.auditService.isActive)
        } finally {
            context.close()
        }
    }
}
