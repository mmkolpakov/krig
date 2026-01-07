package space.kscience.controls.core.deployment

import space.kscience.controls.core.legacy_alpha_2.contracts.Device
import space.kscience.dataforge.meta.Meta

/**
 * Defines a contract for migrating the state from an old device instance to a new one
 * during a "hot swap" operation. This is crucial for updating device logic without losing
 * its current state.
 *
 * @param Old The type of the old device from which the state is being migrated.
 * @param NewState The type of the state object, which must be [space.kscience.dataforge.meta.Meta]. This state will be
 *                 passed to the `restore()` method of the new device instance.
 */
public interface DeviceMigrator<in Old : Device, out NewState : Meta> {
    /**
     * Extracts the state from the `oldDevice` and transforms it into a [Meta] object
     * suitable for the new device version.
     *
     * If the migration is not possible or fails, this method should throw an exception.
     * This will cause the `hotSwap` transaction to be rolled back, ensuring the
     * system returns to its previous stable state with the old device.
     *
     * @param oldDevice The instance of the old device that is being replaced.
     * @return A [Meta] object representing the state to be restored on the new device.
     */
    public suspend fun migrate(oldDevice: Old): NewState
}