package space.kscience.krig.api.expressions

import space.kscience.dataforge.meta.MetaConverter
import space.kscience.krig.api.meta.serializableMetaConverter

/** Meta converter for config-time storage of sealed numeric expression trees. */
public val numericExpressionMetaConverter: MetaConverter<NumericExpression> =
    serializableMetaConverter(NumericExpression.serializer())
