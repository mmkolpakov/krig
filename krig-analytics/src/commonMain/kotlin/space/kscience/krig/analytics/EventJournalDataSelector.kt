package space.kscience.krig.analytics

import kotlinx.coroutines.flow.toList
import space.kscience.dataforge.data.DataTree
import space.kscience.dataforge.data.putValue
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.workspace.DataSelector
import space.kscience.dataforge.workspace.Workspace
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.storage.journal.EventJournal

/**
 * Exposes the numeric content of an [EventJournal] as workspace [DataTree] data: each stored message
 * for which [extract] yields a non-null value becomes one `sample.<index>` datum, in journal order.
 *
 * The journal is the single source of truth for replay/audit; this selector is the read bridge that
 * lets DataForge Workspace tasks (reductions, fits, exports) consume that history without coupling the
 * analytics layer to a specific message schema — the schema lives entirely in [extract].
 *
 * Snapshot semantics: every [select] materialises the **whole** journal (`readAll().toList()`) into
 * memory at call time — consistent with the other read bridges, and sized for bounded analytical
 * windows, not unbounded production logs. Keys are positional (`sample.<index>`); time- or
 * tag-addressed selection is a future extension.
 */
public class EventJournalDataSelector(
    private val journal: EventJournal,
    private val extract: (DeviceMessage) -> Double?,
) : DataSelector<Double> {

    override suspend fun select(workspace: Workspace, meta: Meta): DataTree<Double> {
        val samples = journal.readAll().toList().mapNotNull { extract(it.payload) }
        return DataTree {
            samples.forEachIndexed { index, sample -> putValue("sample.$index", sample) }
        }
    }
}
