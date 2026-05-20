package space.kscience.krig.ksp

import com.google.devtools.ksp.processing.SymbolProcessorProvider
import java.util.ServiceLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SPI-level smoke test for the krig KSP processor.
 */
class TypedDeviceGeneratorSmokeTest {

    private val providerFqn = "space.kscience.krig.ksp.KrigSymbolProcessorProvider"

    @Test
    fun metaInfServicesFileResolvesToKrigProvider() {
        val resourceName = "META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider"
        val resource = javaClass.classLoader.getResource(resourceName)
        assertNotNull(
            resource,
            "META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider " +
                "must be present on the test classpath",
        )
        val content = resource.readText().lines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertTrue(
            providerFqn in content,
            "The services file must list $providerFqn, was: $content",
        )
    }

    @Test
    fun providerClassIsLoadableAndInstantiable() {
        val clazz = Class.forName(providerFqn)
        assertTrue(
            SymbolProcessorProvider::class.java.isAssignableFrom(clazz),
            "$providerFqn must implement SymbolProcessorProvider",
        )
        // Reflectively locate the public no-arg constructor — ServiceLoader needs exactly this.
        val ctor = clazz.getDeclaredConstructor()
        val instance = ctor.newInstance()
        assertNotNull(instance)
        assertTrue(instance is SymbolProcessorProvider)
    }

    @Test
    fun serviceLoaderResolvesProviderThroughStandardSpi() {
        // ServiceLoader is exactly the mechanism the real KSP runtime uses to pick up
        // processors. If this call returns an empty iterator, the processor is invisible
        // in production regardless of how its internal wiring looks.
        val providers = ServiceLoader.load(SymbolProcessorProvider::class.java, javaClass.classLoader)
            .iterator()
            .asSequence()
            .toList()
        val ours = providers.filter { it::class.java.name == providerFqn }
        assertEquals(1, ours.size, "Exactly one KrigSymbolProcessorProvider must be discovered via ServiceLoader")
    }

    @Test
    fun allDelegateGeneratorsAreLoadable() {
        // The dispatcher aggregates three generators. A deleted/renamed file would silently
        // drop its corresponding FeatureSpec — this assertion makes the drop loud.
        val expected = listOf(
            "space.kscience.krig.ksp.FeatureSpecContractValidator",
            "space.kscience.krig.ksp.ContributesAggregator",
            "space.kscience.krig.ksp.SerializersModuleGenerator",
        )
        for (fqn in expected) {
            val clazz = assertNotNull(
                runCatching { Class.forName(fqn) }.getOrNull(),
                "Delegate generator $fqn must be on the classpath",
            )
            assertNotNull(clazz)
        }
    }

    @Test
    fun krigSymbolProcessorDispatcherClassIsLoadable() {
        val clazz = Class.forName("space.kscience.krig.ksp.KrigSymbolProcessor")
        assertNotNull(clazz)
        val declaredCtors = clazz.declaredConstructors
        assertTrue(
            declaredCtors.any { it.parameterCount == 1 },
            "KrigSymbolProcessor must have a single-argument (SymbolProcessorEnvironment) constructor",
        )
    }
}
