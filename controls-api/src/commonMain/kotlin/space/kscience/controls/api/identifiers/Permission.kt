package space.kscience.controls.api.identifiers

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Represents a permission required to execute an action.
 * @param id A unique identifier for the permission, e.g., "device.control.motor".
 */
@JvmInline
@Serializable
public value class Permission(public val id: String)