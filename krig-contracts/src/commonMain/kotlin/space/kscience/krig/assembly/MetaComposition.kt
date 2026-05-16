package space.kscience.krig.assembly

import space.kscience.dataforge.meta.Laminate
import space.kscience.dataforge.meta.Meta

/**
 * Composes multiple layers of metadata into a single [Meta] object, with a defined order of precedence.
 * Layers are applied from bottom to top: blueprint → child → attachment.
 *
 * @param blueprintMeta The base metadata from the DeviceBlueprint.
 * @param childMeta Optional metadata from a child component configuration.
 * @param attachmentMeta Optional metadata from a runtime attachment.
 * @return A combined [Meta] with consistent layering.
 */
internal fun composeMeta(
    blueprintMeta: Meta,
    childMeta: Meta?,
    attachmentMeta: Meta?,
): Meta = Laminate(attachmentMeta, childMeta, blueprintMeta)
