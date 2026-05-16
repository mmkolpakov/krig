package space.kscience.magix.api

/**
 * Opt-in marker for implementing [MagixEndpoint].
 */
@RequiresOptIn(
    message = "Implementing MagixEndpoint requires explicit opt-in.",
    level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
public annotation class UnstableMagixEndpoint

/**
 * Opt-in marker for Magix server-side SPI. These declarations are transport-neutral,
 * but their lifecycle and embedding model can change as the Magix loop runtime settles.
 */
@RequiresOptIn(
    message = "This Magix server-side SPI is experimental and may change before stabilization.",
    level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
public annotation class ExperimentalMagixApi
