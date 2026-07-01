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

    @Test
    fun expressionBindingsReturnUniqueExternalDependencies() {
        val rpm = Binding("pump".asName(), "rpm".asName())
        val pressure = Binding("pump".asName(), "pressure".asName())
        val expression: NumericExpression = Binary(
            operation = "add",
            left = NAry("sum", listOf(rpm, pressure, rpm)),
            right = Constant(1.0),
        )

        assertEquals(setOf(rpm, pressure), expression.bindings())
    }

    @Test
    fun expressionTreeRoundTripsThroughMetaConverter() {
        val expression: NumericExpression = Binary(
            operation = "mul",
            left = Binding("pump".asName(), "rpm".asName()),
            right = Constant(0.5),
        )

        val meta = numericExpressionMetaConverter.convert(expression)
        val decoded = numericExpressionMetaConverter.read(meta)

        assertEquals(expression, decoded)
    }
}
