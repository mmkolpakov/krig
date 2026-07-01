package space.kscience.krig.core.contracts

import space.kscience.dataforge.names.Name
import space.kscience.krig.api.descriptors.PropertyDescriptor

/** Policy for properties absent from a device's static manifest/typed contract. */
public enum class DynamicDiscoveryPolicy {
    /** Unknown properties fail with `UnknownProperty`. */
    Strict,

    /** Unknown properties are served through transient synthetic `Meta` descriptors. */
    AdHoc,

    /** Unknown properties are served and remembered in [DynamicDescriptorOverlay.discoveredPropertyDescriptors]. */
    Learn,

    /** Only descriptors preloaded into [DynamicDescriptorOverlay.discoveredPropertyDescriptors] are accepted. */
    Catalog,
}

/** Runtime-discovered descriptors kept separate from the static manifest contract. */
public interface DynamicDescriptorOverlay {
    public val dynamicDiscoveryPolicy: DynamicDiscoveryPolicy
    public val discoveredPropertyDescriptors: Map<Name, PropertyDescriptor>
}
