package space.kscience.krig.api.annotations

/**
 * Marks a common [DeviceContractBuilder][space.kscience.krig.core.meta.DeviceContractBuilder]
 * declaration as a stable SDK contract that KSP can project into generated manifest/registry
 * artifacts.
 *
 * The annotation is KSP-only. It is retained in class metadata for the processor, but it is
 * not part of runtime discovery and does not imply DataForge/JVM auto-contribution.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class KrigDeviceContract(
    /** Stable contract id, usually reverse-DNS-like, stored as the generated manifest id. */
    public val id: String,
    /** Stable contract version used by generated manifests, registries and schema hashes. */
    public val version: String = "0.1.0",
)
