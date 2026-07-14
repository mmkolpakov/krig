package space.kscience.krig.build.bom

import space.kscience.krig.build.publication.PublicationCoordinate
import space.kscience.krig.build.publication.PublicationKind

internal data class BomConstraint(
    val group: String,
    val artifact: String,
    val version: String,
    val type: String = DEFAULT_MAVEN_TYPE,
    val classifier: String? = null,
    val scope: String = DEFAULT_MAVEN_SCOPE,
    val optional: Boolean = false,
) : Comparable<BomConstraint> {
    override fun compareTo(other: BomConstraint): Int =
        compareValuesBy(
            this,
            other,
            BomConstraint::group,
            BomConstraint::artifact,
            BomConstraint::version,
            BomConstraint::type,
            { it.classifier.orEmpty() },
            BomConstraint::scope,
            BomConstraint::optional,
        )

    override fun toString(): String = "$group:$artifact:$version"
}

internal data class BomIdentity(
    val modelVersion: String,
    val group: String,
    val artifact: String,
    val version: String,
    val packaging: String,
)

internal data class BomSnapshot(
    val identity: BomIdentity,
    val constraints: List<BomConstraint>,
)

internal data class BomPolicy(
    val expectedBomCoordinate: PublicationCoordinate,
    val expectedVersion: String,
    val expectedPublications: List<BomExpectedPublication>,
) {
    init {
        require(expectedBomCoordinate.group.isNotBlank()) { "Expected BOM group must not be blank" }
        require(expectedBomCoordinate.artifact.isNotBlank()) { "Expected BOM artifact id must not be blank" }
        require(expectedVersion.isNotBlank()) { "Expected BOM version must not be blank" }
        require(expectedPublications.isNotEmpty()) { "Expected BOM publication set must not be empty" }
        require(expectedPublications.all { publication ->
            publication.coordinate.group.isNotBlank() && publication.coordinate.artifact.isNotBlank()
        }) {
            "Expected BOM publication coordinates must not contain blank parts"
        }
        val coordinateCount = expectedPublications.map(BomExpectedPublication::coordinate).distinct().size
        require(coordinateCount == expectedPublications.size) {
            "Expected BOM publication coordinates must be unique"
        }
        require(expectedPublications.none { it.kind == PublicationKind.PLATFORM_BOM }) {
            "Expected library publication kinds must not contain PLATFORM_BOM"
        }
    }

    val expectedConstraints: List<BomConstraint> = expectedPublications
        .map { publication ->
            BomConstraint(
                group = publication.coordinate.group,
                artifact = publication.coordinate.artifact,
                version = expectedVersion,
            )
        }
        .sorted()
}

internal data class BomExpectedPublication(
    val coordinate: PublicationCoordinate,
    val kind: PublicationKind,
) : Comparable<BomExpectedPublication> {
    init {
        require(kind != PublicationKind.PLATFORM_BOM) {
            "Expected library publication must not have PLATFORM_BOM kind"
        }
    }

    override fun compareTo(other: BomExpectedPublication): Int = compareValuesBy(
        this,
        other,
        BomExpectedPublication::coordinate,
        { it.kind.name },
    )
}

internal data class BomVerification(
    val errors: List<String>,
) {
    val isSuccessful: Boolean get() = errors.isEmpty()
}

internal data class RenderedBomReport(
    val json: String,
    val markdown: String,
)

internal const val MAVEN_MODEL_VERSION: String = "4.0.0"
internal const val MAVEN_POM_NAMESPACE: String = "http://maven.apache.org/POM/4.0.0"
internal const val MAVEN_POM_PACKAGING: String = "pom"
internal const val DEFAULT_MAVEN_TYPE: String = "jar"
internal const val DEFAULT_MAVEN_SCOPE: String = "compile"
internal const val BOM_COORDINATE_SCOPE: String = "PUBLIC_ROOT"
internal const val BOM_EVIDENCE_LEVEL: String = "GENERATED_POM_ONLY"
internal const val BOM_PARSE_ERROR_CODE: String = "BOM_XML_INVALID"
