package space.kscience.krig.core.timetravel

import space.kscience.dataforge.meta.MetaTransformation
import space.kscience.dataforge.meta.MetaTransformationBuilder

/**
 * Builds a [JournalMigration] that rewrites the payload `Meta` of entries stored under the [from]
 * schema with a DataForge [MetaTransformation], re-stamping the result as [to]. Entries on any other
 * schema pass through untouched.
 *
 * This is the idiomatic way to handle **dictionary drift** — a device whose property names moved or
 * were renamed across manifest versions — without hand-rolled `Meta` parsing or premature
 * deserialization: the rewrite happens on the raw [JournalEntry.payload] tree, *before* it is decoded
 * into a current [space.kscience.krig.api.messages.DeviceMessage]. Compose several of these in a
 * [JournalMigrations] chain to walk a payload across multiple schema versions.
 *
 * Note on [MetaTransformation] semantics: `move`/`keep` rules applied via [MetaTransformation.apply]
 * rewrite matched nodes in place. To *drop* nodes, prefer a whitelist built with `generate`; see the
 * [MetaTransformation] docs.
 */
public fun metaJournalMigration(
    from: StorageSchema,
    to: StorageSchema,
    transformation: MetaTransformation,
): JournalMigration = JournalMigration { entry ->
    if (entry.schema == from) {
        entry.copy(schema = to, payload = transformation.apply(entry.payload))
    } else {
        entry
    }
}

/**
 * Convenience overload that builds the [MetaTransformation] inline.
 *
 * ```kotlin
 * val v1ToV2 = metaJournalMigration(StorageSchemas.deviceMessageV1, deviceMessageV2) {
 *     move("oldSensor".parseAsName(), "newSensor".parseAsName())
 * }
 * val codec = MessageJournalCodec(migrations = JournalMigrations(v1ToV2), schema = deviceMessageV2)
 * ```
 */
public fun metaJournalMigration(
    from: StorageSchema,
    to: StorageSchema,
    block: MetaTransformationBuilder.() -> Unit,
): JournalMigration = metaJournalMigration(from, to, MetaTransformation.make(block))
