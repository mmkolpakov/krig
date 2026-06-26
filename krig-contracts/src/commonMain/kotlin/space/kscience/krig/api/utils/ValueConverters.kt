package space.kscience.krig.api.utils

import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaConverter

private object UnitMetaConverter : MetaConverter<Unit> {
    override fun readOrNull(source: Meta): Unit = Unit
    override fun convert(obj: Unit): Meta = Meta.EMPTY
}

/** Converter for actions that have no meaningful input or output. */
public val MetaConverter.Companion.unit: MetaConverter<Unit> get() = UnitMetaConverter
