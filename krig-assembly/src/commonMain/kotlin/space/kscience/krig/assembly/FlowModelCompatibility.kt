package space.kscience.krig.assembly

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import space.kscience.dataforge.meta.Meta

@Serializable
internal data class FlowModelConfiguration(
    val type: String,
    val name: String,
    val parameters: FlowModelParameters = FlowModelParameters(),
) {
    companion object {
        fun fromJsonString(json: String): FlowModelConfiguration =
            StrictConfigurationJson.decodeFromString(json)

        fun fromMeta(meta: Meta, lenient: Boolean = false): FlowModelConfiguration =
            decodeConfigurationMeta(serializer(), meta, lenient)
    }
}

@Serializable
internal data class FlowModelParameters(
    val models: Map<String, FlowModelNodeSpec> = emptyMap(),
    val flowBindings: List<FlowBindingSpec> = emptyList(),
)

@Serializable
internal data class FlowModelNodeSpec(
    val type: String,
    val parameters: JsonObject = JsonObject(emptyMap()),
)

@Serializable
internal data class FlowBindingSpec(
    val producer: String,
    val consumer: String,
)

internal data class FlowEndpoint(
    val model: String,
    val port: String?,
) {
    companion object {
        fun parse(raw: String): FlowEndpoint {
            val separator = raw.lastIndexOf('.')
            return if (separator < 0) {
                FlowEndpoint(raw, null)
            } else {
                FlowEndpoint(raw.substring(0, separator), raw.substring(separator + 1))
            }
        }
    }
}

internal enum class FlowModelDiagnosticSeverity {
    Error,
    Warning,
}

internal data class FlowModelDiagnostic(
    val code: String,
    val path: String,
    val message: String,
    val severity: FlowModelDiagnosticSeverity = FlowModelDiagnosticSeverity.Error,
)

internal fun FlowModelConfiguration.validateCompatibilityTarget(): List<FlowModelDiagnostic> = buildList {
    if (type != FLOW_MODEL_TYPE) {
        add(error("flow.type", "type", "Expected type '$FLOW_MODEL_TYPE', got '$type'."))
    }
    if (name.isBlank()) {
        add(error("flow.name.blank", "name", "Flow model name must not be blank."))
    }
    if (parameters.models.isEmpty()) {
        add(error("flow.models.empty", "parameters.models", "Flow model must declare at least one model."))
    }

    parameters.models.forEach { (id, model) ->
        validateModel(id, model)
    }
    parameters.flowBindings.forEachIndexed { index, binding ->
        validateBinding(index, binding, parameters.models)
    }
}

private fun MutableList<FlowModelDiagnostic>.validateModel(id: String, model: FlowModelNodeSpec) {
    val path = "parameters.models.$id"
    if (id.isBlank()) {
        add(error("flow.model.id.blank", path, "Flow model id must not be blank."))
    }
    val target = FlowModelTargets[model.type]
    if (target == null) {
        add(error("flow.model.type.unsupported", "$path.type", "Unsupported flow model type '${model.type}'."))
        return
    }
    model.parameters.keys
        .filterNot { it in target.parameterKeys }
        .sorted()
        .forEach { key ->
            add(
                warning(
                    "flow.parameter.unsupported",
                    "$path.parameters.$key",
                    "Parameter '$key' is not part of the compatibility target for '${model.type}'.",
                ),
            )
        }
    if (model.type == "consumer" && CONSUMATION_CAPACITY_KEY in model.parameters) {
        add(
            warning(
                "flow.parameter.legacy-spelling",
                "$path.parameters.$CONSUMATION_CAPACITY_KEY",
                "The source fixture uses '$CONSUMATION_CAPACITY_KEY'; KRig-native APIs should prefer '$CONSUMPTION_CAPACITY_KEY'.",
            ),
        )
    }
}

private fun MutableList<FlowModelDiagnostic>.validateBinding(
    index: Int,
    binding: FlowBindingSpec,
    models: Map<String, FlowModelNodeSpec>,
) {
    validateEndpoint(index, role = "producer", raw = binding.producer, models)
    validateEndpoint(index, role = "consumer", raw = binding.consumer, models)
}

private fun MutableList<FlowModelDiagnostic>.validateEndpoint(
    index: Int,
    role: String,
    raw: String,
    models: Map<String, FlowModelNodeSpec>,
) {
    val path = "parameters.flowBindings[$index].$role"
    if (raw.isBlank()) {
        add(error("flow.endpoint.blank", path, "Flow binding $role endpoint must not be blank."))
        return
    }
    if (raw.count { it == '.' } > 1) {
        add(
            error(
                "flow.endpoint.ambiguous",
                path,
                "Endpoint '$raw' is ambiguous: dotted model ids and dotted ports need an explicit future syntax.",
            ),
        )
    }

    val endpoint = FlowEndpoint.parse(raw)
    val model = models[endpoint.model]
    if (model == null) {
        add(error("flow.endpoint.unknown-model", path, "Endpoint '$raw' references unknown model '${endpoint.model}'."))
        return
    }
    val target = FlowModelTargets[model.type] ?: return
    val allowedPorts = target.portsFor(role, model.parameters)
    val requiresPort = target.requiresPort(role)

    when {
        endpoint.port == null && requiresPort -> add(
            error(
                "flow.endpoint.port-required",
                path,
                "Endpoint '$raw' must name one of ${allowedPorts.sorted()} ports for ${model.type} $role role.",
            ),
        )

        endpoint.port != null && allowedPorts.isNotEmpty() && endpoint.port !in allowedPorts -> add(
            error(
                "flow.endpoint.unknown-port",
                path,
                "Endpoint '$raw' uses unknown ${model.type} port '${endpoint.port}'. Known ports: ${allowedPorts.sorted()}.",
            ),
        )

        endpoint.port != null && allowedPorts.isEmpty() && !requiresPort -> add(
            warning(
                "flow.endpoint.port-ignored",
                path,
                "Endpoint '$raw' names a port, but ${model.type} $role role is a single-port compatibility target.",
            ),
        )
    }
}

private data class FlowModelTarget(
    val type: String,
    val parameterKeys: Set<String>,
    val consumerPorts: (JsonObject) -> Set<String> = { emptySet() },
    val producerPorts: (JsonObject) -> Set<String> = { emptySet() },
    val consumerPortRequired: Boolean = false,
    val producerPortRequired: Boolean = false,
) {
    fun portsFor(role: String, parameters: JsonObject): Set<String> =
        if (role == "consumer") consumerPorts(parameters) else producerPorts(parameters)

    fun requiresPort(role: String): Boolean =
        if (role == "consumer") consumerPortRequired else producerPortRequired
}

private val FlowModelTargets: Map<String, FlowModelTarget> = listOf(
    FlowModelTarget(
        type = "producer",
        parameterKeys = setOf("productionCapacity"),
    ),
    FlowModelTarget(
        type = "consumer",
        parameterKeys = setOf(CONSUMATION_CAPACITY_KEY, CONSUMPTION_CAPACITY_KEY),
    ),
    FlowModelTarget(
        type = "buffer",
        parameterKeys = setOf("capacity"),
    ),
    FlowModelTarget(
        type = "mix",
        parameterKeys = setOf("supplyKeys"),
        consumerPorts = { it.stringArray("supplyKeys").toSet() },
        consumerPortRequired = true,
    ),
    FlowModelTarget(
        type = "reaction",
        parameterKeys = setOf("formula", "productionCapacity"),
        consumerPorts = { it.objectKeys("formula") },
        consumerPortRequired = true,
    ),
    FlowModelTarget(
        type = "separate",
        parameterKeys = setOf("formula", "supplyKeys"),
        producerPorts = { it.objectKeys("formula") + it.stringArray("supplyKeys") },
        producerPortRequired = true,
    ),
    FlowModelTarget(
        type = "limited",
        parameterKeys = setOf("source", "limit"),
    ),
    FlowModelTarget(
        type = "delayed",
        parameterKeys = setOf("source", "delayMs"),
    ),
).associateBy { it.type }

private fun JsonObject.stringArray(key: String): List<String> =
    (this[key] as? JsonArray)
        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
        .orEmpty()

private fun JsonObject.objectKeys(key: String): Set<String> =
    (this[key] as? JsonObject)?.keys.orEmpty()

private fun error(code: String, path: String, message: String): FlowModelDiagnostic =
    FlowModelDiagnostic(code, path, message)

private fun warning(code: String, path: String, message: String): FlowModelDiagnostic =
    FlowModelDiagnostic(code, path, message, FlowModelDiagnosticSeverity.Warning)

private const val FLOW_MODEL_TYPE = "flowModel"
private const val CONSUMATION_CAPACITY_KEY = "consumationCapacity"
private const val CONSUMPTION_CAPACITY_KEY = "consumptionCapacity"
