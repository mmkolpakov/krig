package space.kscience.krig.benchmarks.storage

internal class StorageBenchPlan(private val config: BenchConfig) {
    private val exposed = mutableListOf<BenchResult>()
    private val jdbc = mutableListOf<BenchResult>()
    private val dense = mutableListOf<BenchResult>()
    private val architecture = mutableListOf<BenchResult>()

    fun h2ExposedJournal() {
        exposed += runH2ExposedJournal(config)
    }

    fun h2JdbcJournal() {
        jdbc += runH2JdbcJournal(config)
    }

    fun h2Matrix(workload: MatrixWorkload) {
        architecture += runH2Matrix(config, workload)
    }

    fun compatibleRows(workload: MatrixWorkload) {
        dense += runChunkScenarios(
            config = config,
            workload = workload,
            prefix = "krig.reference-rows",
            deadband = config.referenceDelta,
        )
    }

    fun denseRows(workload: MatrixWorkload) {
        architecture += runChunkScenarios(
            config = config,
            workload = workload,
            prefix = "krig.${workload.id}",
            deadband = config.matrixDeadband,
        )
    }

    fun optionalTimescaleExposedJournal() {
        if (config.runTimescale) exposed += runTimescaleExposedJournal(config)
    }

    fun optionalTimescaleJdbcJournal() {
        if (config.runTimescale) jdbc += runTimescaleJdbcJournal(config)
    }

    fun optionalExternalJdbc(workload: MatrixWorkload) {
        externalJdbcTarget()?.let { architecture += runExternalJdbc(config, workload, it) }
    }

    fun build(): StorageBenchResults = StorageBenchResults(
        exposed = exposed.toList(),
        jdbc = jdbc.toList(),
        dense = dense.toList(),
        architecture = architecture.toList(),
    )
}

internal data class StorageBenchResults(
    val exposed: List<BenchResult>,
    val jdbc: List<BenchResult>,
    val dense: List<BenchResult>,
    val architecture: List<BenchResult>,
)

internal fun storageBench(
    config: BenchConfig,
    block: StorageBenchPlan.() -> Unit,
): StorageBenchResults = StorageBenchPlan(config).apply(block).build()
