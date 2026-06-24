package space.kscience.krig.api.services

import kotlinx.coroutines.test.runTest
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta
import space.kscience.krig.api.context.AnonymousPrincipal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** Resolution semantics of the default identity provider and the context accessor fallback. */
class IdentityProviderTest {

    @Test
    fun defaultProviderMapsBlankToAnonymousAndNameToPrincipal() = runTest {
        val provider = DefaultIdentityProvider(Meta.EMPTY)

        assertSame(AnonymousPrincipal, provider.resolve(null))
        assertSame(AnonymousPrincipal, provider.resolve("   "))
        assertEquals("alice", provider.resolve("alice").name)
    }

    @Test
    fun contextAccessorFallsBackWhenNoPluginInstalled() = runTest {
        val context = Context("identity-fallback")

        // Fallback resolves like the default provider rather than throwing.
        assertEquals("bob", context.identityProvider.resolve("bob").name)
    }
}
