package space.kscience.controls.automation

import space.kscience.dataforge.names.Name

/**
 * Defines the Meta structure for a transactional plan. This serves as a formal contract
 * for serialization and remote execution.
 * @see TransactionPlan for the type-safe old.
 */
public object TransactionPlanSpec {
    public val ACTIONS_KEY: Name = Name.of("actions")

    public object Action {
        public val TYPE_KEY: Name = Name.of("type")

        // Universal Keys
        public val IDEMPOTENCY_KEY: Name = Name.of("idempotencyKey")

        // For AttachAction
        public val DEVICE_ADDRESS_KEY: Name = Name.of("deviceAddress")
        public val BLUEPRINT_ID_KEY: Name = Name.of("blueprintId")
        public val ATTACH_CONFIG_KEY: Name = Name.of("config")

        // For WritePropertyAction
        // uses DEVICE_ADDRESS_KEY
        public val PROPERTY_NAME_KEY: Name = Name.of("property")
        public val PROPERTY_VALUE_KEY: Name = Name.of("value")
    }
}

/**
 * An enumeration of standard action types. The system is extensible via custom action types.
 */
public object ActionType {
    public const val ATTACH: String = "attach"
    public const val DETACH: String = "detach"
    public const val START: String = "start"
    public const val STOP: String = "stop"
    public const val WRITE_PROPERTY: String = "write"
}