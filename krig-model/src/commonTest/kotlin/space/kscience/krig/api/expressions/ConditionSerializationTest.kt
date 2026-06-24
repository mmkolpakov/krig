package space.kscience.krig.api.expressions

import kotlinx.serialization.json.Json
import space.kscience.dataforge.names.asName
import kotlin.test.Test
import kotlin.test.assertEquals

class ConditionSerializationTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

    @Test
    fun conditionTreeRoundTripsThroughSealedSerializer() {
        val condition: Condition = And(
            listOf(
                Comparison(
                    operation = ComparisonOperation.GREATER,
                    left = Binding("reactor".asName(), "temperature".asName()),
                    right = Constant(100.0),
                ),
                Not(
                    Or(
                        listOf(
                            Comparison(
                                operation = ComparisonOperation.LESS,
                                left = Binding("reactor".asName(), "pressure".asName()),
                                right = Constant(2.0),
                            ),
                            BooleanConstant(false),
                        ),
                    ),
                ),
            ),
        )
        val serializer = Condition.serializer()

        val decoded = json.decodeFromString(serializer, json.encodeToString(serializer, condition))

        assertEquals(condition, decoded)
    }
}
