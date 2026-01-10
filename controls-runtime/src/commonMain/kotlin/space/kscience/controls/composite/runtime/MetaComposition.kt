package space.kscience.controls.composite.runtime

import space.kscience.dataforge.meta.Laminate
import space.kscience.dataforge.meta.Meta

/**
 * Composes multiple layers of metadata into a single [Meta] object, with a defined order of precedence.
 * Layers are applied from bottom to top, meaning values in later layers override values in earlier ones.
 * The order is: blueprint -> child -> attachment.
 *
 * This function ensures a consistent layering policy across the framework.
 *
 * @param blueprintMeta The base metadata from the [space.kscience.controls.core.contracts.DeviceBlueprint].
 * @param childMeta Optional metadata from a [space.kscience.controls.core.composition.LocalChildComponentConfig].
 * @param attachmentMeta Optional metadata from a runtime [space.kscience.controls.composite.dsl.children.AttachmentConfiguration].
 * @return A new, immutable [Meta] object representing the combined configuration.
 */
internal fun composeMeta(
    blueprintMeta: Meta,
    childMeta: Meta?,
    attachmentMeta: Meta?
): Meta = Laminate(attachmentMeta, childMeta, blueprintMeta)