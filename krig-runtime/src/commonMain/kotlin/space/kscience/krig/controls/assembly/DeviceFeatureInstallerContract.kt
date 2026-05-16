package space.kscience.krig.assembly

import space.kscience.krig.api.discovery.FeatureInstallerContributions
import space.kscience.krig.api.faults.ValidationFault
import space.kscience.krig.api.result.DeviceOutcome
import space.kscience.krig.api.result.okUnit
import space.kscience.krig.core.contracts.DeviceFeatureInstaller
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.gather
import space.kscience.dataforge.meta.Meta

/**
 * Lightweight runtime sanity check for contributed [DeviceFeatureInstaller] installers.
 *
 * The strict `@KrigFeatureSpec.id == @SerialName` invariant is enforced at compile time
 * by the KSP `DeviceFeatureSpecContractValidator`. Runtime code intentionally avoids reflective DTO
 * inspection so the SDK contract stays multiplatform.
 */
public object DeviceFeatureInstallerContract {

    /** Cheap non-reflective check: non-blank [DeviceFeatureInstaller.id] and a named [DeviceFeatureInstaller.featureClass]. */
    public fun validate(installer: DeviceFeatureInstaller<*, *>): DeviceOutcome<Unit> {
        if (installer.id.isBlank()) {
            return validationFailure("DeviceFeatureInstaller.id must be a non-blank stable identifier.")
        }
        if (installer.featureClass.simpleName == null) {
            return validationFailure(
                "DeviceFeatureInstaller.featureClass must expose a non-null simpleName for id \"${installer.id}\".",
            )
        }
        return okUnit()
    }

    private fun validationFailure(message: String): DeviceOutcome<Unit> =
        DeviceOutcome.Fail(ValidationFault(details = Meta { "message" put message }))
}

/** Discovers every [DeviceFeatureInstaller] contributed to [FeatureInstallerContributions.Target] and returns the valid ones. */
public fun Context.gatherValidFeatureInstallers(): Map<space.kscience.dataforge.names.Name, DeviceFeatureInstaller<*, *>> {
    val discovered = gather<DeviceFeatureInstaller<*, *>>(FeatureInstallerContributions.Target.id)
    return discovered.filter { (_, installer) ->
        DeviceFeatureInstallerContract.validate(installer) is DeviceOutcome.Ok
    }
}
