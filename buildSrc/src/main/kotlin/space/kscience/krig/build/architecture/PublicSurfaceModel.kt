package space.kscience.krig.build.architecture

import java.nio.file.Path

internal data class JvmClassBytes(
    val moduleName: String,
    val origin: String,
    val bytes: ByteArray,
)

internal data class JvmJarArtifact(
    val moduleName: String,
    val logicalArtifactId: String,
    val path: Path,
) {
    init {
        require(logicalArtifactId.isNotBlank()) { "JVM artifact logical id must not be blank" }
        require('!' !in logicalArtifactId) { "JVM artifact logical id must not contain '!'" }
    }
}

internal data class SurfaceOrigin(
    val moduleName: String,
    val entryName: String,
) : Comparable<SurfaceOrigin> {
    override fun compareTo(other: SurfaceOrigin): Int =
        compareValuesBy(this, other, SurfaceOrigin::moduleName, SurfaceOrigin::entryName)
}

internal enum class SurfaceClassKind {
    KotlinClass,
    KotlinInterface,
    KotlinEnum,
    KotlinAnnotation,
    KotlinObject,
    JavaClass,
    JavaInterface,
    JavaRecord,
    JavaEnum,
    JavaAnnotation,
}

internal data class ReachableClass(
    val fqName: String,
    val kind: SurfaceClassKind,
    val origin: SurfaceOrigin,
)

internal enum class FileFacadeKind {
    FileFacade,
    MultiFileFacade,
    MultiFilePart,
}

internal data class FileFacadeDeclaration(
    val fqName: String,
    val kind: FileFacadeKind,
    val origin: SurfaceOrigin,
)

internal data class TypeAliasDeclaration(
    val fqName: String,
    val origin: SurfaceOrigin,
)

internal data class PublishedApiDeclaration(
    val ownerFqName: String,
    val declaration: String,
    val origin: SurfaceOrigin,
)

internal data class ClassifierReferenceOrigin(
    val ownerFqName: String,
    val location: String,
    val origin: SurfaceOrigin,
    val sourceDisplayName: String? = null,
) : Comparable<ClassifierReferenceOrigin> {
    override fun compareTo(other: ClassifierReferenceOrigin): Int = compareValuesBy(
        this,
        other,
        ClassifierReferenceOrigin::ownerFqName,
        ClassifierReferenceOrigin::location,
        ClassifierReferenceOrigin::sourceDisplayName,
        { it.origin.moduleName },
        { it.origin.entryName },
    )
}

internal data class PublicSurfaceSnapshot(
    val reachableClasses: List<ReachableClass>,
    val authoredPublicDataClasses: List<ReachableClass>,
    val typeAliases: List<TypeAliasDeclaration>,
    val fileFacades: List<FileFacadeDeclaration>,
    val publishedApiInternals: List<PublishedApiDeclaration>,
    val fqcnIndex: Map<String, List<SurfaceOrigin>>,
    val binaryNameIndex: Map<String, List<SurfaceOrigin>>,
    val sourceNameCollisions: Map<String, List<SurfaceOrigin>>,
    val binaryNameCollisions: Map<String, List<SurfaceOrigin>>,
    val metadataClassifierReferences: Map<String, List<ClassifierReferenceOrigin>>,
    val jvmBinaryClassifierReferences: Map<String, List<ClassifierReferenceOrigin>>,
)

internal class UnsupportedKotlinMetadataException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)
