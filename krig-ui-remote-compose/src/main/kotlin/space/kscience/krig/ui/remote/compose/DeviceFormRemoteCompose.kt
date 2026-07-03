package space.kscience.krig.ui.remote.compose

import androidx.compose.remote.creation.JvmRcPlatformServices
import androidx.compose.remote.creation.RemoteComposeWriter
import space.kscience.krig.ui.schema.DeviceFormAction
import space.kscience.krig.ui.schema.DeviceFormNodeId
import space.kscience.krig.ui.schema.DeviceFormProperty
import space.kscience.krig.ui.schema.DeviceFormSchema
import space.kscience.krig.ui.schema.DeviceFormSourceKind

private const val DEFAULT_WIDTH: Int = 480
private const val DEFAULT_HEIGHT: Int = 720
private const val DEFAULT_MAX_NODES: Int = 64
private const val DEFAULT_DENSITY: Float = 1.0f
private const val DOCUMENT_GENERATION_TIME: Long = 0L
private const val LEFT_PADDING: Float = 24f
private const val TOP_PADDING: Float = 32f
private const val TITLE_STEP: Float = 28f
private const val ROW_STEP: Float = 22f
private const val ANCHOR_PAN: Float = 0f
private const val TEXT_FLAGS: Int = 0

/** Rendering bounds for the optional Remote Compose projection. */
public data class DeviceFormRemoteComposeOptions(
    public val width: Int = DEFAULT_WIDTH,
    public val height: Int = DEFAULT_HEIGHT,
    public val contentDescription: String = "KRig device form",
    public val maxNodes: Int = DEFAULT_MAX_NODES,
) {
    init {
        require(width > 0) { "Remote Compose document width must be positive" }
        require(height > 0) { "Remote Compose document height must be positive" }
        require(contentDescription.isNotBlank()) { "Remote Compose content description must not be blank" }
        require(maxNodes > 0) { "Remote Compose maxNodes must be positive" }
    }
}

/** Trace entry that maps a rendered Remote Compose line back to a neutral KRig form node. */
public data class DeviceFormRemoteComposeTrace(
    public val nodeId: DeviceFormNodeId,
    public val sourceKind: DeviceFormSourceKind,
    public val label: String,
)

/**
 * Immutable Remote Compose document produced from a neutral KRig form schema.
 *
 * AndroidX Remote Compose types are deliberately absent from this public API. The renderer module owns
 * the dependency and exposes only transportable bytes plus KRig-level trace metadata.
 */
public class DeviceFormRemoteComposeDocument(
    bytes: ByteArray,
    public val schemaHash: String,
    public val contentDescription: String,
    public val trace: List<DeviceFormRemoteComposeTrace>,
) {
    private val payload: ByteArray = bytes.copyOf()

    public val byteSize: Int get() = payload.size

    public fun toByteArray(): ByteArray = payload.copyOf()
}

/** Optional JVM renderer from KRig neutral form schema to AndroidX Remote Compose bytes. */
public object DeviceFormRemoteComposeRenderer {
    public fun render(
        schema: DeviceFormSchema,
        options: DeviceFormRemoteComposeOptions = DeviceFormRemoteComposeOptions(),
    ): DeviceFormRemoteComposeDocument {
        val lines = schema.renderLines(options.maxNodes)
        val writer = RemoteComposeWriter(
            options.width,
            options.height,
            options.contentDescription,
            JvmRcPlatformServices(),
        )

        writer.header(
            options.width,
            options.height,
            options.contentDescription,
            DEFAULT_DENSITY,
            DOCUMENT_GENERATION_TIME,
        )
        writer.startRoot()
        writer.drawTextAnchored("KRig ${schema.manifestId}", LEFT_PADDING, TOP_PADDING, ANCHOR_PAN, ANCHOR_PAN, TEXT_FLAGS)
        writer.drawTextAnchored(
            "Version ${schema.manifestVersion}",
            LEFT_PADDING,
            TOP_PADDING + TITLE_STEP,
            ANCHOR_PAN,
            ANCHOR_PAN,
            TEXT_FLAGS,
        )

        var y = TOP_PADDING + TITLE_STEP + ROW_STEP
        for (line in lines) {
            writer.drawTextAnchored(line.text, LEFT_PADDING, y, ANCHOR_PAN, ANCHOR_PAN, TEXT_FLAGS)
            y += ROW_STEP
        }
        writer.endRoot()

        return DeviceFormRemoteComposeDocument(
            bytes = writer.encodeToByteArray(),
            schemaHash = schema.schemaHash,
            contentDescription = options.contentDescription,
            trace = lines.map { it.trace },
        )
    }
}

public fun DeviceFormSchema.toRemoteComposeDocument(
    options: DeviceFormRemoteComposeOptions = DeviceFormRemoteComposeOptions(),
): DeviceFormRemoteComposeDocument = DeviceFormRemoteComposeRenderer.render(this, options)

private data class RenderLine(
    val text: String,
    val trace: DeviceFormRemoteComposeTrace,
)

private fun DeviceFormSchema.renderLines(maxNodes: Int): List<RenderLine> {
    val propertyLines = properties.map(DeviceFormProperty::renderLine)
    val discoveredLines = discoveredProperties.map(DeviceFormProperty::renderLine)
    val actionLines = actions.map(DeviceFormAction::renderLine)
    return (propertyLines + discoveredLines + actionLines).take(maxNodes)
}

private fun DeviceFormProperty.renderLine(): RenderLine = RenderLine(
    text = "property ${name}: ${valueTypeId.id}",
    trace = DeviceFormRemoteComposeTrace(
        nodeId = id,
        sourceKind = source.kind,
        label = name.toString(),
    ),
)

private fun DeviceFormAction.renderLine(): RenderLine = RenderLine(
    text = "action ${name}",
    trace = DeviceFormRemoteComposeTrace(
        nodeId = id,
        sourceKind = source.kind,
        label = name.toString(),
    ),
)
