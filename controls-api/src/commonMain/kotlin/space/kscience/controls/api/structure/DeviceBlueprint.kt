package space.kscience.controls.api.structure

import space.kscience.dataforge.meta.MetaRepr

/**
 * A marker interface for any description of a device structure, whether static (Manifest)
 * or dynamic (Definition).
 *
 * Represents the "Data Plane" view of a device.
 */
public interface DeviceBlueprint : MetaRepr