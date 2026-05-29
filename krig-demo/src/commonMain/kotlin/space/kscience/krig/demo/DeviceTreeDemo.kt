package space.kscience.krig.demo

import space.kscience.dataforge.names.asName
import space.kscience.dataforge.names.parseAsName
import space.kscience.krig.api.hub.resolveDevice
import space.kscience.krig.api.hub.resolveNode
import space.kscience.krig.core.contracts.asNode
import space.kscience.krig.core.contracts.deviceTree
import space.kscience.krig.core.contracts.read
import space.kscience.krig.core.contracts.write
import space.kscience.krig.dsl.device

/** Read-only topology views, separate from device ownership. */
suspend fun deviceTreeDemo() {
    val ctx = demoContext("device-tree-demo")
    val mainPump = device("mainPump", pumpBackend(), ctx) {
        manifest(PumpManifest)
    }
    val reservePump = device("reservePump", pumpBackend(), ctx) {
        manifest(PumpManifest)
    }

    mainPump.write(PumpSpec.rpm, 1_100.0)
    reservePump.write(PumpSpec.rpm, 650.0)

    val processView = deviceTree(
        children = mapOf(
            "plant".asName() to deviceTree(
                children = mapOf(
                    "lineA".asName() to deviceTree(
                        children = mapOf("main".asName() to mainPump.asNode()),
                    ),
                    "lineB".asName() to deviceTree(
                        children = mapOf("reserve".asName() to reservePump.asNode()),
                    ),
                ),
            ),
        ),
    )

    val maintenanceView = deviceTree(
        children = mapOf(
            "rack42".asName() to deviceTree(
                children = mapOf(
                    "slot1".asName() to mainPump.asNode(),
                    "slot2".asName() to reservePump.asNode(),
                ),
            ),
        ),
    )

    val lineA = checkNotNull(processView.resolveNode("plant.lineA".parseAsName()))
    val processMain = checkNotNull(processView.resolveDevice("plant.lineA.main".parseAsName()))
    val processReserve = checkNotNull(processView.resolveDevice("plant.lineB.reserve".parseAsName()))
    val rackMain = checkNotNull(maintenanceView.resolveDevice("rack42.slot1".parseAsName()))

    println("=== Device tree ===")
    println("  lineA is folder: ${lineA.device == null}, children: ${lineA.children.keys}")
    println("  plant.lineA.main rpm: ${processMain.read(PumpSpec.rpm)}")
    println("  plant.lineB.reserve rpm: ${processReserve.read(PumpSpec.rpm)}")
    println("  rack42.slot1 is same device: ${rackMain === mainPump}")

    mainPump.close()
    reservePump.close()
    ctx.close()
    println("\nDone - device tree demo complete.")
}
