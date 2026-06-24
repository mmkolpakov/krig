package space.kscience.krig.api.services

import space.kscience.dataforge.context.AbstractPlugin
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.Plugin
import space.kscience.dataforge.context.PluginFactory
import space.kscience.dataforge.context.PluginTag
import space.kscience.dataforge.meta.Meta
import space.kscience.krig.api.context.AnonymousPrincipal
import space.kscience.krig.api.context.Principal
import space.kscience.krig.api.context.SimplePrincipal

/**
 * Resolves a transport-level caller identity into a local [Principal].
 *
 * Wire messages carry stable identity strings, not role sets. The receiving process decides what
 * those strings mean: a production provider can consult an ACL store, certificate cache or session
 * registry before [AuthorizationService] checks permissions.
 */
public interface IdentityProvider : Plugin {
    override val tag: PluginTag get() = Companion.tag

    public suspend fun resolve(identity: String?): Principal

    public companion object : PluginFactory<IdentityProvider> {
        override val tag: PluginTag = PluginTag("device.identity", group = PluginTag.DATAFORGE_GROUP)

        override fun build(context: Context, meta: Meta): IdentityProvider = DefaultIdentityProvider(meta)
    }
}

/**
 * Reference [IdentityProvider]: blank or absent identity resolves to [AnonymousPrincipal]; any other
 * string becomes a named [SimplePrincipal] without roles. Install a custom [IdentityProvider] when
 * roles must be attached by local policy before authorization.
 */
public class DefaultIdentityProvider(meta: Meta) : AbstractPlugin(meta), IdentityProvider {
    override val tag: PluginTag get() = IdentityProvider.tag

    override suspend fun resolve(identity: String?): Principal {
        val name = identity?.takeIf { it.isNotBlank() } ?: return AnonymousPrincipal
        return SimplePrincipal(name = name)
    }
}

private val defaultIdentityProviderFallback: IdentityProvider = DefaultIdentityProvider(Meta.EMPTY)

/** Identity resolver installed in [Context], or a shared [DefaultIdentityProvider] when none is configured. */
public val Context.identityProvider: IdentityProvider
    get() = plugins.find(true) { it is IdentityProvider } as? IdentityProvider
        ?: defaultIdentityProviderFallback
