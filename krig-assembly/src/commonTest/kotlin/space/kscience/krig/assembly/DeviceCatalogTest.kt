package space.kscience.krig.assembly

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import space.kscience.dataforge.names.asName
import space.kscience.krig.core.contracts.manifestOf

class DeviceCatalogTest {
    @Test
    fun registerRejectsDuplicateManifestIds() {
        val id = "demo.device".asName()
        val catalog = DeviceCatalog()
        val first = manifestOf(id, properties = emptyMap())
        val second = manifestOf(id, properties = emptyMap(), version = "0.2.0")

        catalog.register(first)

        assertFailsWith<IllegalArgumentException> {
            catalog.register(second)
        }
        assertEquals("0.1.0", catalog[id]?.version)
    }

    @Test
    fun replaceUpdatesManifestExplicitly() {
        val id = "demo.device".asName()
        val catalog = DeviceCatalog()
        catalog.register(manifestOf(id, properties = emptyMap()))

        catalog.replace(manifestOf(id, properties = emptyMap(), version = "0.2.0"))

        assertEquals("0.2.0", catalog[id]?.version)
    }
}
