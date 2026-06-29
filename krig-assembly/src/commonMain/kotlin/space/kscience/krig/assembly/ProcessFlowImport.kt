package space.kscience.krig.assembly

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.flow.FlowAmount
import space.kscience.krig.flow.FlowGraph
import space.kscience.krig.flow.FlowGraphBuilder
import space.kscience.krig.flow.FlowRate
import space.kscience.krig.flow.FlowRatio
import space.kscience.krig.flow.FlowUnit
import space.kscience.krig.flow.FlowUnits
import space.kscience.krig.flow.flowGraph
import kotlin.math.ceil

/** Options for importing a KRig process-flow graph from an external flow document. */
public data class ProcessFlowImportOptions(
    public val defaultUnit: FlowUnit = FlowUnits.Generic,
    public val delayStepMillis: Long = 1_000,
) {
    init {
        require(delayStepMillis > 0) { "Delay step must be positive" }
    }
}

/** Imports a deterministic process-flow graph from an external JSON flow document. */
public fun importProcessFlowGraphFromJson(
    json: String,
    options: ProcessFlowImportOptions = ProcessFlowImportOptions(),
): FlowGraph = ExternalFlowModelDocument.fromJsonString(json).toFlowGraph(options)

/** Imports a deterministic process-flow graph from an external Meta flow document. */
public fun importProcessFlowGraphFromMeta(
    meta: Meta,
    options: ProcessFlowImportOptions = ProcessFlowImportOptions(),
    lenient: Boolean = false,
): FlowGraph = ExternalFlowModelDocument.fromMeta(meta, lenient).toFlowGraph(options)

private fun ExternalFlowModelDocument.toFlowGraph(options: ProcessFlowImportOptions): FlowGraph {
    val errors = validateProcessFlowDialect().filter { it.severity == ProcessFlowDiagnosticSeverity.Error }
    require(errors.isEmpty()) {
        errors.joinToString(separator = "; ") { diagnostic -> "${diagnostic.path}: ${diagnostic.message}" }
    }

    return flowGraph {
        parameters.models.forEach { (id, model) ->
            addProcessFlowBlock(id.asName(), model, options)
        }
        parameters.flowBindings.forEach { binding ->
            val source = ExternalFlowEndpoint.parse(binding.producer)
            val target = ExternalFlowEndpoint.parse(binding.consumer)
            connect(
                sourceBlock = source.model.asName(),
                targetBlock = target.model.asName(),
                sourcePort = (source.port ?: DEFAULT_OUTPUT_PORT).asName(),
                targetPort = (target.port ?: DEFAULT_INPUT_PORT).asName(),
            )
        }
    }
}

private fun FlowGraphBuilder.addProcessFlowBlock(
    id: Name,
    model: ExternalFlowNodeSpec,
    options: ProcessFlowImportOptions,
) {
    val parameters = model.parameters
    val unit = parameters.unitOrDefault(options.defaultUnit)
    val path = "parameters.models.$id.parameters"
    when (model.type) {
        "producer" -> producer(id, unit, FlowRate(parameters.requiredDouble("productionCapacity", path)))
        "consumer" -> consumer(id, unit, FlowRate(parameters.requiredConsumptionCapacity(path)))
        "buffer" -> buffer(id, unit, FlowAmount(parameters.requiredDouble("capacity", path)))
        "mix" -> mix(id, unit, parameters.requiredStringArray("supplyKeys", path).map { it.asName() })
        "reaction" -> conversion(
            id = id,
            unit = unit,
            inputCoefficients = parameters.requiredInputCoefficients("formula", path),
            productionLimit = parameters.optionalDouble("productionCapacity")?.let(::FlowRate),
        )
        "separate" -> separate(id, unit, parameters.requiredSeparationOutputs(path))
        "limited" -> limited(id, unit, outputLimit = FlowRate(parameters.requiredDouble("limit", path)))
        "delayed" -> delayed(id, unit, delaySteps = parameters.requiredDelaySteps(options, path))
        else -> invalidProcessFlow("Unsupported flow node type '${model.type}' at parameters.models.$id.type")
    }
}

private fun JsonObject.unitOrDefault(defaultUnit: FlowUnit): FlowUnit =
    optionalString("unit")?.let(::FlowUnit) ?: defaultUnit

private fun JsonObject.requiredConsumptionCapacity(path: String): Double =
    optionalDouble("consumptionCapacity")
        ?: optionalDouble("consumationCapacity")
        ?: invalidProcessFlow("$path must declare 'consumptionCapacity'")

private fun JsonObject.requiredInputCoefficients(key: String, path: String): Map<Name, FlowRatio> {
    val formula = this[key] as? JsonObject ?: invalidProcessFlow("$path.$key must be an object")
    require(formula.isNotEmpty()) { "$path.$key must not be empty" }
    return formula.entries.associate { (port, value) ->
        port.asName() to FlowRatio(
            value.jsonPrimitive.doubleOrNull ?: invalidProcessFlow("$path.$key.$port must be a number"),
        )
    }
}

private fun JsonObject.requiredSeparationOutputs(path: String): Map<Name, FlowRatio> {
    val formula = (this["formula"] as? JsonObject)?.entries.orEmpty()
    if (formula.isNotEmpty()) {
        return formula.associate { (port, value) ->
            port.asName() to FlowRatio(
                value.jsonPrimitive.doubleOrNull ?: invalidProcessFlow("$path.formula.$port must be a number"),
            )
        }
    }

    val supplyKeys = requiredStringArray("supplyKeys", path)
    val share = FlowRatio(1.0 / supplyKeys.size)
    return supplyKeys.associate { it.asName() to share }
}

private fun JsonObject.requiredDelaySteps(options: ProcessFlowImportOptions, path: String): Int {
    val delayMs = requiredDouble("delayMs", path)
    require(delayMs > 0.0) { "$path.delayMs must be positive" }
    return ceil(delayMs / options.delayStepMillis.toDouble()).toInt().coerceAtLeast(1)
}

private fun JsonObject.requiredStringArray(key: String, path: String): List<String> {
    val array = this[key] as? JsonArray ?: invalidProcessFlow("$path.$key must be an array")
    val values = array.mapIndexed { index, element ->
        element.jsonPrimitive.contentOrNull ?: invalidProcessFlow("$path.$key[$index] must be a string")
    }
    require(values.isNotEmpty()) { "$path.$key must not be empty" }
    return values
}

private fun JsonObject.requiredDouble(key: String, path: String): Double =
    optionalDouble(key) ?: invalidProcessFlow("$path.$key must be a number")

private fun JsonObject.optionalDouble(key: String): Double? = this[key]?.jsonPrimitive?.doubleOrNull

private fun JsonObject.optionalString(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

private fun invalidProcessFlow(message: String): Nothing = throw IllegalArgumentException(message)

private const val DEFAULT_INPUT_PORT = "in"
private const val DEFAULT_OUTPUT_PORT = "out"
