package space.kscience.controls.api.meta

import space.kscience.dataforge.meta.descriptors.MetaDescriptor
import space.kscience.dataforge.meta.number
import space.kscience.dataforge.meta.string
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName

/**
 * A registry of standard attribute keys for [MetaDescriptor].
 *
 * Using these standardized keys ensures interoperability between different layers of the system:
 * - **Drivers** use limits for validation.
 * - **UI (Observers)** use units, precision, and widget hints to generate controls.
 * - **Analytics** use limits and units for data normalization.
 */
public object StandardAttributes {
    // --- Physics & Measurement ---

    /**
     * The physical unit of the property value.
     * Examples: "V", "A", "degC", "m/s", "rpm".
     * Type: String.
     */
    public val UNIT: Name = "unit".asName()

    /**
     * The display precision (number of decimal places) or significant digits.
     * Type: Number (Int).
     */
    public val PRECISION: Name = "precision".asName()

    // --- Hard Constraints (Driver Validation) ---

    /**
     * The absolute minimum value accepted by the device hardware.
     * Attempts to write values below this will cause a validation error.
     * Type: Number.
     */
    public val LIMIT_MIN: Name = "min".asName()

    /**
     * The absolute maximum value accepted by the device hardware.
     * Attempts to write values above this will cause a validation error.
     * Type: Number.
     */
    public val LIMIT_MAX: Name = "max".asName()

    // --- Soft Constraints (Alarms / Monitoring) ---

    /**
     * The lower threshold for a warning state.
     * If the value drops below this, the data quality may transition to WARNING.
     * Type: Number.
     */
    public val ALARM_LOW: Name = "alarm.low".asName()

    /**
     * The upper threshold for a warning state.
     * If the value rises above this, the data quality may transition to WARNING.
     * Type: Number.
     */
    public val ALARM_HIGH: Name = "alarm.high".asName()

    // --- UI Hints ---

    /**
     * A hint for the UI system on which widget to use for this property.
     * Examples: "slider", "toggle", "gauge", "text", "chart".
     * Type: String.
     */
    public val WIDGET_TYPE: Name = "ui.widget".asName()

    /**
     * A semantic group name for organizing properties in the UI.
     * Properties with the same group will be rendered together.
     * Type: String.
     */
    public val GROUP: Name = "ui.group".asName()
}

// --- Extensions for convenient access ---

/**
 * Retrieves the unit string from the descriptor attributes.
 */
public val MetaDescriptor.unit: String?
    get() = attributes[StandardAttributes.UNIT]?.string

/**
 * Retrieves the minimum limit from the descriptor attributes.
 */
public val MetaDescriptor.min: Double?
    get() = attributes[StandardAttributes.LIMIT_MIN]?.number?.toDouble()

/**
 * Retrieves the maximum limit from the descriptor attributes.
 */
public val MetaDescriptor.max: Double?
    get() = attributes[StandardAttributes.LIMIT_MAX]?.number?.toDouble()