package space.kscience.magix.api

import kotlinx.serialization.json.JsonNull
import space.kscience.dataforge.names.asName
import space.kscience.dataforge.names.parseAsName
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MagixMessageFilterTest {

    private fun message(topic: String): MagixMessage = MagixMessage(
        format = "krig",
        payload = JsonNull,
        sourceEndpoint = "source".asName(),
        topic = topic.parseAsName(),
    )

    @Test
    fun doubleStarMatchesNestedTopics() {
        val filter = MagixMessageFilter(topicPattern = "actions.**".parseAsName())
        assertTrue(filter.accepts(message("actions")))
        assertTrue(filter.accepts(message("actions.car")))
        assertTrue(filter.accepts(message("actions.car.speed")))
        assertFalse(filter.accepts(message("responses")))
    }

    @Test
    fun singleStarMatchesExactlyOneToken() {
        val filter = MagixMessageFilter(topicPattern = "actions.*".parseAsName())
        assertTrue(filter.accepts(message("actions.car")))
        assertFalse(filter.accepts(message("actions.car.speed")))
        assertFalse(filter.accepts(message("actions")))
    }

    @Test
    fun literalDottedNameIsNotTreatedAsPatternPath() {
        val filter = MagixMessageFilter(topicPattern = "actions..*".asName())
        assertFalse(filter.accepts(message("actions.car")))
        assertFalse(filter.accepts(message("actions.car.speed")))
    }

    @Test
    fun doubleStarMatchesMiddleSegments() {
        val filter = MagixMessageFilter(topicPattern = "actions.**.speed".parseAsName())
        assertTrue(filter.accepts(message("actions.speed")))
        assertTrue(filter.accepts(message("actions.car.speed")))
        assertTrue(filter.accepts(message("actions.car.motor.speed")))
        assertFalse(filter.accepts(message("actions.car.motor.rpm")))
    }
}
