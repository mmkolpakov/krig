package space.kscience.controls.core.meta

import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaConverter

/**
 * A [MetaConverter] for the [Unit] type.
 * It reads any [Meta] as a [Unit] value and converts [Unit] to an empty [Meta].
 * This is useful for actions that have no meaningful input or output.
 */
public object UnitMetaConverter : MetaConverter<Unit> {
    override fun readOrNull(source: Meta): Unit = Unit
    override fun convert(obj: Unit): Meta = Meta.EMPTY
}

/**
 * Provides a singleton instance of [UnitMetaConverter].
 */
public val MetaConverter.Companion.unit: MetaConverter<Unit> get() = UnitMetaConverter