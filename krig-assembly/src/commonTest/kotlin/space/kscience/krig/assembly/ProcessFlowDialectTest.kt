package space.kscience.krig.assembly

import kotlinx.coroutines.test.runTest
import space.kscience.dataforge.io.JsonMetaFormat
import space.kscience.dataforge.io.parse
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.result.getOrThrow
import space.kscience.krig.core.contracts.typed.readObservedOutcome
import space.kscience.krig.flow.FlowPropertyContracts
import space.kscience.krig.flow.toSteppedBackend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ProcessFlowDialectTest {

    @Test
    fun chemicalFactoryFixtureDefinesExternalDialectTarget() {
        val model = ExternalFlowModelDocument.fromJsonString(ChemicalFactoryJson)

        assertEquals("flowModel", model.type)
        assertEquals("ChemicalFactory", model.name)
        assertEquals(
            setOf("aProducer", "bProducer", "mixer", "abBuffer", "cProducer", "cBuffer", "reactor", "consumer"),
            model.parameters.models.keys,
        )
        assertEquals(7, model.parameters.flowBindings.size)

        val diagnostics = model.validateProcessFlowDialect()
        assertTrue(diagnostics.none { it.severity == ProcessFlowDiagnosticSeverity.Error }, diagnostics.toString())
        assertTrue(diagnostics.any { it.code == "flow.parameter.legacy-spelling" }, diagnostics.toString())
    }

    @Test
    fun diagnosticsCatchUnknownModelsPortsAndAmbiguousEndpoints() {
        val model = ExternalFlowModelDocument.fromJsonString(
            """
            {
              "type": "flowModel",
              "name": "BrokenFactory",
              "parameters": {
                "models": {
                  "p": {
                    "type": "producer",
                    "parameters": { "unexpected": 1.0 }
                  },
                  "mixer": {
                    "type": "mix",
                    "parameters": { "supplyKeys": ["a"] }
                  },
                  "consumer": {
                    "type": "consumer",
                    "parameters": { "consumptionCapacity": 1.0 }
                  }
                },
                "flowBindings": [
                  { "producer": "missing", "consumer": "mixer.b" },
                  { "producer": "lab.reactor.ab", "consumer": "consumer" },
                  { "producer": "p", "consumer": "mixer" }
                ]
              }
            }
            """.trimIndent(),
        )

        val diagnostics = model.validateProcessFlowDialect()
        val codes = diagnostics.map { it.code }.toSet()

        assertTrue("flow.parameter.unsupported" in codes, diagnostics.toString())
        assertTrue("flow.endpoint.unknown-model" in codes, diagnostics.toString())
        assertTrue("flow.endpoint.unknown-port" in codes, diagnostics.toString())
        assertTrue("flow.endpoint.ambiguous" in codes, diagnostics.toString())
        assertTrue("flow.endpoint.port-required" in codes, diagnostics.toString())
    }

    @Test
    fun modifierKindsAreRecognizedButNotExpandedIntoCoreApi() {
        val model = ExternalFlowModelDocument.fromJsonString(
            """
            {
              "type": "flowModel",
              "name": "Modifiers",
              "parameters": {
                "models": {
                  "limitedProducer": {
                    "type": "limited",
                    "parameters": { "source": "producer", "limit": 1.0 }
                  },
                  "delayedConsumer": {
                    "type": "delayed",
                    "parameters": { "source": "consumer", "delayMs": 100 }
                  }
                },
                "flowBindings": []
              }
            }
            """.trimIndent(),
        )

        val diagnostics = model.validateProcessFlowDialect()

        assertTrue(diagnostics.none { it.code == "flow.model.type.unsupported" }, diagnostics.toString())
    }

    @Test
    fun chemicalFactoryFixtureBuildsExecutableProcessFlowGraph() = runTest {
        val graph = importProcessFlowGraphFromJson(ChemicalFactoryJson)
        val backend = graph.toSteppedBackend()

        backend.step(1.seconds)

        val consumed = backend.readObservedOutcome(
            FlowPropertyContracts.totalConsumed("consumer".asName()),
        ).getOrThrow()
        val reactorOutput = backend.readObservedOutcome(
            FlowPropertyContracts.lastOutput("reactor".asName()),
        ).getOrThrow()

        assertEquals(1.0, consumed.value)
        assertEquals(1.0, reactorOutput.value)
    }

    @Test
    fun processFlowMetaInputBuildsSameGraphShape() {
        val graph = importProcessFlowGraphFromMeta(JsonMetaFormat().parse(ChemicalFactoryJson))

        assertEquals(
            setOf("aProducer", "bProducer", "mixer", "abBuffer", "cProducer", "cBuffer", "reactor", "consumer"),
            graph.blocks.keys.map { it.toString() }.toSet(),
        )
        assertEquals(7, graph.connections.size)
    }

    @Test
    fun adapterRejectsUnitMismatchDuringConfiguration() {
        assertFailsWith<IllegalArgumentException> {
            importProcessFlowGraphFromJson(
                """
                {
                  "type": "flowModel",
                  "name": "UnitMismatch",
                  "parameters": {
                    "models": {
                      "source": {
                        "type": "producer",
                        "parameters": { "productionCapacity": 1.0, "unit": "kg" }
                      },
                      "sink": {
                        "type": "consumer",
                        "parameters": { "consumptionCapacity": 1.0, "unit": "l" }
                      }
                    },
                    "flowBindings": [
                      { "producer": "source", "consumer": "sink" }
                    ]
                  }
                }
                """.trimIndent(),
            )
        }
    }
}

private val ChemicalFactoryJson: String = """
{
  "type": "flowModel",
  "name": "ChemicalFactory",
  "parameters": {
    "models": {
      "aProducer": {
        "type": "producer",
        "parameters": {
          "productionCapacity": 1.0
        }
      },
      "bProducer": {
        "type": "producer",
        "parameters": {
          "productionCapacity": 1.5
        }
      },
      "mixer": {
        "type": "mix",
        "parameters": {
          "supplyKeys": [
            "a",
            "b"
          ]
        }
      },
      "abBuffer": {
        "type": "buffer",
        "parameters": {
          "capacity": 10.0
        }
      },
      "cProducer": {
        "type": "producer",
        "parameters": {
          "productionCapacity": 10.0
        }
      },
      "cBuffer": {
        "type": "buffer",
        "parameters": {
          "capacity": 50.0
        }
      },
      "reactor": {
        "type": "reaction",
        "parameters": {
          "formula": {
            "ab": 1.0,
            "c": 1.0
          },
          "productionCapacity": 1.0
        }
      },
      "consumer": {
        "type": "consumer",
        "parameters": {
          "consumationCapacity": 2.0
        }
      }
    },
    "flowBindings": [
      {
        "producer": "aProducer",
        "consumer": "mixer.a"
      },
      {
        "producer": "bProducer",
        "consumer": "mixer.b"
      },
      {
        "producer": "mixer",
        "consumer": "abBuffer"
      },
      {
        "producer": "cProducer",
        "consumer": "cBuffer"
      },
      {
        "producer": "abBuffer",
        "consumer": "reactor.ab"
      },
      {
        "producer": "cBuffer",
        "consumer": "reactor.c"
      },
      {
        "producer": "reactor",
        "consumer": "consumer"
      }
    ]
  }
}
""".trimIndent()
