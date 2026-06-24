package space.kscience.krig.api.expressions

import kotlinx.serialization.json.Json
import space.kscience.dataforge.names.asName
import kotlin.test.Test
import kotlin.test.assertEquals

class ExpressionSerializationTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

    @Test
    fun expressionTreeRoundTripsThroughSealedSerializer() {
        val expression: NumericExpression = Binary(
            operation = "add",
            left = Binding("pump".asName(), "rpm".asName()),
            right = Unary("neg", Binding("pump".asName(), "pressure".asName())),
        )
        val serializer = NumericExpression.serializer()

        val decoded = json.decodeFromString(serializer, json.encodeToString(serializer, expression))

        assertEquals(expression, decoded)
    }
}
