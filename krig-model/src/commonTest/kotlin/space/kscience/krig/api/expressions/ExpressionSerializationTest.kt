package space.kscience.krig.api.expressions

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import space.kscience.dataforge.names.asName
import kotlin.test.Test
import kotlin.test.assertEquals

class ExpressionSerializationTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
        serializersModule = SerializersModule {
            polymorphic(Expression::class) {
                subclass(Binary::class)
                subclass(Binding::class)
                subclass(NAry::class)
                subclass(Unary::class)
            }
        }
    }

    @Test
    fun expressionTreeRoundTripsThroughPolymorphicSerializer() {
        val expression: Expression<Double> = Binary(
            operation = "add",
            left = Binding("pump".asName(), "rpm".asName()),
            right = Unary("neg", Binding("pump".asName(), "pressure".asName())),
        )
        val serializer = Expression.serializer(Double.serializer())

        val decoded = json.decodeFromString(serializer, json.encodeToString(serializer, expression))

        assertEquals(expression, decoded)
    }
}
