package space.kscience.krig.assembly

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import space.kscience.dataforge.meta.Meta

@Serializable
internal data class ExternalFlowModelDocument(
    val type: String,
    val name: String,
    val parameters: ExternalFlowModelParameters = ExternalFlowModelParameters(),
) {
    companion object {
        fun fromJsonString(json: String): ExternalFlowModelDocument =
            StrictConfigurationJson.decodeFromString(json)

        fun fromMeta(meta: Meta, lenient: Boolean = false): ExternalFlowModelDocument =
            decodeConfigurationMeta(serializer(), meta, lenient)
    }
}

@Serializable
internal data class ExternalFlowModelParameters(
    val models: Map<String, ExternalFlowNodeSpec> = emptyMap(),
    val flowBindings: List<ExternalFlowBindingSpec> = emptyList(),
)

@Serializable
internal data class ExternalFlowNodeSpec(
    val type: String,
    val parameters: JsonObject = JsonObject(emptyMap()),
)

@Serializable
internal data class ExternalFlowBindingSpec(
    val producer: String,
    val consumer: String,
)

internal data class ExternalFlowEndpoint(
    val model: String,
    val port: String?,
) {
    companion object {
        fun parse(raw: String): ExternalFlowEndpoint {
            val separator = raw.lastIndexOf('.')
            return if (separator < 0) {
                ExternalFlowEndpoint(raw, null)
            } else {
                ExternalFlowEndpoint(raw.substring(0, separator), raw.substring(separator + 1))
            }
        }
    }
}

internal enum class ProcessFlowDiagnosticSeverity {
    Error,
    Warning,
}

internal data class ProcessFlowDiagnostic(
    val code: String,
    val path: String,
    val message: String,
    val severity: ProcessFlowDiagnosticSeverity = ProcessFlowDiagnosticSeverity.Error,
)

internal fun ExternalFlowModelDocument.validateProcessFlowDialect(): List<ProcessFlowDiagnostic> = buildList {
    if (type != FLOW_MODEL_TYPE) {
        add(error("flow.type", "type", "Expected type '$FLOW_MODEL_TYPE', got '$type'."))
    }
    if (name.isBlank()) {
        add(error("flow.name.blank", "name", "Process-flow document name must not be blank."))
    }
    if (parameters.models.isEmpty()) {
        add(error("flow.models.empty", "parameters.models", "Process-flow document must declare at least one model."))
    }

    parameters.models.forEach { (id, model) ->
        validateModel(id, model)
    }
    parameters.flowBindings.forEachIndexed { index, binding ->
        validateBinding(index, binding, parameters.models)
    }
}

private fun MutableList<ProcessFlowDiagnostic>.validateModel(id: String, model: ExternalFlowNodeSpec) {
    val path = "parameters.models.$id"
    if (id.isBlank()) {
        add(error("flow.model.id.blank", path, "Flow model id must not be blank."))
    }
    val target = ExternalFlowNodeTargets[model.type]
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
                    "Parameter '$key' is not part of the external process-flow dialect for '${model.type}'.",
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

private fun MutableList<ProcessFlowDiagnostic>.validateBinding(
    index: Int,
    binding: ExternalFlowBindingSpec,
    models: Map<String, ExternalFlowNodeSpec>,
) {
    validateEndpoint(index, role = "producer", raw = binding.producer, models)
    validateEndpoint(index, role = "consumer", raw = binding.consumer, models)
}

private fun MutableList<ProcessFlowDiagnostic>.validateEndpoint(
    index: Int,
    role: String,
    raw: String,
    models: Map<String, ExternalFlowNodeSpec>,
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

    val endpoint = ExternalFlowEndpoint.parse(raw)
    val model = models[endpoint.model]
    if (model == null) {
        add(error("flow.endpoint.unknown-model", path, "Endpoint '$raw' references unknown model '${endpoint.model}'."))
        return
    }
    val target = ExternalFlowNodeTargets[model.type] ?: return
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
                "Endpoint '$raw' names a port, but ${model.type} $role role is a single-port external target.",
            ),
        )
    }
}

private data class ExternalFlowNodeTarget(
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

private val ExternalFlowNodeTargets: Map<String, ExternalFlowNodeTarget> = listOf(
    ExternalFlowNodeTarget(
        type = "producer",
        parameterKeys = setOf("productionCapacity", UNIT_KEY),
    ),
    ExternalFlowNodeTarget(
        type = "consumer",
        parameterKeys = setOf(CONSUMATION_CAPACITY_KEY, CONSUMPTION_CAPACITY_KEY, UNIT_KEY),
    ),
    ExternalFlowNodeTarget(
        type = "buffer",
        parameterKeys = setOf("capacity", UNIT_KEY),
    ),
    ExternalFlowNodeTarget(
        type = "mix",
        parameterKeys = setOf("supplyKeys", UNIT_KEY),
        consumerPorts = { it.stringArray("supplyKeys").toSet() },
        consumerPortRequired = true,
    ),
    ExternalFlowNodeTarget(
        type = "reaction",
        parameterKeys = setOf("formula", "productionCapacity", UNIT_KEY),
        consumerPorts = { it.objectKeys("formula") },
        consumerPortRequired = true,
    ),
    ExternalFlowNodeTarget(
        type = "separate",
        parameterKeys = setOf("formula", "supplyKeys", UNIT_KEY),
        producerPorts = { it.objectKeys("formula") + it.stringArray("supplyKeys") },
        producerPortRequired = true,
    ),
    ExternalFlowNodeTarget(
        type = "limited",
        parameterKeys = setOf("source", "limit", UNIT_KEY),
    ),
    ExternalFlowNodeTarget(
        type = "delayed",
        parameterKeys = setOf("source", "delayMs", UNIT_KEY),
    ),
).associateBy { it.type }

private fun JsonObject.stringArray(key: String): List<String> =
    (this[key] as? JsonArray)
        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
        .orEmpty()

private fun JsonObject.objectKeys(key: String): Set<String> =
    (this[key] as? JsonObject)?.keys.orEmpty()

private fun error(code: String, path: String, message: String): ProcessFlowDiagnostic =
    ProcessFlowDiagnostic(code, path, message)

private fun warning(code: String, path: String, message: String): ProcessFlowDiagnostic =
    ProcessFlowDiagnostic(code, path, message, ProcessFlowDiagnosticSeverity.Warning)

private const val FLOW_MODEL_TYPE = "flowModel"
private const val CONSUMATION_CAPACITY_KEY = "consumationCapacity"
private const val CONSUMPTION_CAPACITY_KEY = "consumptionCapacity"
private const val UNIT_KEY = "unit"
