package space.kscience.krig.build.publication

internal enum class PublicationKind {
    KMP_ROOT,
    JVM_DIRECT,
    PLATFORM_BOM,
}

internal data class PublicationCoordinate(
    val group: String,
    val artifact: String,
) : Comparable<PublicationCoordinate> {
    override fun compareTo(other: PublicationCoordinate): Int = compareValuesBy(
        this,
        other,
        PublicationCoordinate::group,
        PublicationCoordinate::artifact,
    )

    override fun toString(): String = "$group:$artifact"
}

internal data class PublicationSurfaceEntry(
    val projectPath: String,
    val kind: PublicationKind,
    val publicationName: String,
    val coordinate: PublicationCoordinate,
    val jvmPublicationName: String?,
    val jvmCoordinate: PublicationCoordinate?,
) : Comparable<PublicationSurfaceEntry> {
    override fun compareTo(other: PublicationSurfaceEntry): Int = compareValuesBy(
        this,
        other,
        PublicationSurfaceEntry::projectPath,
        { it.coordinate.group },
        { it.coordinate.artifact },
        { it.kind.name },
        PublicationSurfaceEntry::publicationName,
    )
}

internal data class PublicationSurface(
    val entries: List<PublicationSurfaceEntry>,
) {
    val bom: PublicationSurfaceEntry = entries.single { it.kind == PublicationKind.PLATFORM_BOM }
    val libraries: List<PublicationSurfaceEntry> = entries.filter { it.kind != PublicationKind.PLATFORM_BOM }
}
