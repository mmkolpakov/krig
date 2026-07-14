@file:OptIn(
    kotlin.ExperimentalContextParameters::class,
    kotlin.contracts.ExperimentalContracts::class,
    kotlin.metadata.ExperimentalAnnotationsInMetadata::class,
)

package space.kscience.krig.build.architecture

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.metadata.ClassKind
import kotlin.metadata.KmAnnotation
import kotlin.metadata.KmAnnotationArgument
import kotlin.metadata.KmClass
import kotlin.metadata.KmClassifier
import kotlin.metadata.KmConstructor
import kotlin.metadata.KmContract
import kotlin.metadata.KmEffect
import kotlin.metadata.KmEffectExpression
import kotlin.metadata.KmEffectType
import kotlin.metadata.KmEnumEntry
import kotlin.metadata.KmFunction
import kotlin.metadata.KmPackage
import kotlin.metadata.KmType
import kotlin.metadata.KmTypeParameter
import kotlin.metadata.KmValueParameter
import kotlin.metadata.KmVariance
import kotlin.metadata.Modality
import kotlin.metadata.Visibility
import kotlin.metadata.declaresDefaultValue
import kotlin.metadata.isInline
import kotlin.metadata.isSuspend
import kotlin.metadata.isValue
import kotlin.metadata.kind
import kotlin.metadata.modality
import kotlin.metadata.visibility
import kotlin.metadata.jvm.JvmMetadataVersion
import kotlin.metadata.jvm.JvmMethodSignature
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.jvm.signature
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.TypeReference
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode

class JvmPublicSurfaceScannerTest {
    @Test
    fun respectsEffectiveEnclosingVisibilityAndNormalizesNestedNames() {
        val snapshot = JvmPublicSurfaceScanner.scanClassBytes(kotlinFixtureInputs())
        val names = snapshot.reachableClasses.map { it.fqName }.toSet()

        assertTrue("$API_PACKAGE.PublicOuter" in names)
        assertTrue("$API_PACKAGE.PublicOuter.Nested" in names)
        assertFalse("$API_PACKAGE.PrivateOuter" in names)
        assertFalse("$API_PACKAGE.PrivateOuter.Nested" in names)
    }

    @Test
    fun requiresOuterInputForPublicOrProtectedNestedClass() {
        val outer = kotlinInput("PublicOuter")
        val nested = kotlinInput("PublicOuter\$Nested").copy(
            origin = "fixture-api!/$API_INTERNAL_NAME/PublicOuter\$Nested.class",
        )
        val complete = JvmPublicSurfaceScanner.scanClassBytes(listOf(outer, nested))

        assertTrue(complete.reachableClasses.any { it.fqName == "$API_PACKAGE.PublicOuter.Nested" })

        val failure = assertFailsWith<IllegalArgumentException> {
            JvmPublicSurfaceScanner.scanClassBytes(listOf(nested))
        }
        assertTrue("$MODULE:${nested.origin}" in failure.message.orEmpty())
        assertTrue("$API_INTERNAL_NAME/PublicOuter" in failure.message.orEmpty())
    }

    @Test
    fun failsClosedForCyclicPublicNestedOwnership() {
        val failure = assertFailsWith<IllegalArgumentException> {
            JvmPublicSurfaceScanner.scanClassBytes(
                listOf(
                    JvmClassBytes(
                        MODULE,
                        "cycle/CycleA.class",
                        publicNestedClass("p/CycleA", "p/CycleB", "CycleA"),
                    ),
                    JvmClassBytes(
                        MODULE,
                        "cycle/CycleB.class",
                        publicNestedClass("p/CycleB", "p/CycleA", "CycleB"),
                    ),
                ),
            )
        }

        assertTrue("Cyclic public/protected nested JVM ownership" in failure.message.orEmpty())
        assertTrue("$MODULE:cycle/" in failure.message.orEmpty())
    }

    @Test
    fun countsOnlyTheAuthoredDataClassWhenSerializerBytecodeIsPresent() {
        val data = kotlinInput("DataFixture")
        val serializer = JvmClassBytes(
            moduleName = MODULE,
            origin = "generated/DataFixture-serializer.class",
            bytes = emptyJavaClass("${API_INTERNAL_NAME}/DataFixture\$\$serializer"),
        )

        val snapshot = JvmPublicSurfaceScanner.scanClassBytes(listOf(data, serializer))

        assertEquals(listOf("$API_PACKAGE.DataFixture"), snapshot.authoredPublicDataClasses.map { it.fqName })
    }

    @Test
    fun expandsTypeAliasesAndTraversesCompleteKotlinSignaturesAndInlineBodies() {
        val snapshot = JvmPublicSurfaceScanner.scanClassBytes(kotlinFixtureInputs())
        val external = snapshot.referenceView

        assertEquals(listOf("$API_PACKAGE.ExternalAlias"), snapshot.typeAliases.map { it.fqName })
        assertTrue("$EXTERNAL_PACKAGE.ExternalType" in external)
        assertTrue("$EXTERNAL_PACKAGE.ExternalBound" in external)
        assertTrue("$EXTERNAL_PACKAGE.ExternalSupertype" in external)
        assertTrue("$EXTERNAL_PACKAGE.ExternalReceiver" in external)
        assertTrue("$EXTERNAL_PACKAGE.ExternalSuspendResult" in external)
        assertTrue("$EXTERNAL_PACKAGE.ExternalApiMarker" in external)
        assertTrue(
            external.getValue("$EXTERNAL_PACKAGE.ExternalApiMarker").any { it.location == "class annotation" },
        )
        assertTrue("$EXTERNAL_PACKAGE.InlineOnly" in external)
        assertTrue(
            external.getValue("$EXTERNAL_PACKAGE.InlineOnly").any { "function inlineLeak" in it.location },
        )
        assertTrue(snapshot.publishedApiInternals.any { "publishedHelper" in it.declaration })
        assertTrue(snapshot.publishedApiInternals.any {
            it.ownerFqName == "$API_PACKAGE.PublishedInternal" && it.declaration == "class"
        })
    }

    @Test
    fun scansInlineDefaultBodyButNotNonInlineDefaultImplementation() {
        val facade = kotlinInput("PublicSurfaceFixturesKt")
        val defaultLambda = kotlinInput("PublicSurfaceFixturesKt\$inlineDefaultLeak\$1")
        val external = JvmPublicSurfaceScanner.scanClassBytes(listOf(facade, defaultLambda)).referenceView

        assertTrue(external.getValue("$EXTERNAL_PACKAGE.InlineDefaultOnly").any {
            it.location.startsWith("function inlineDefaultLeak")
        })
        assertTrue(external.getValue("$EXTERNAL_PACKAGE.InlineDefaultLambdaOnly").any {
            it.location.startsWith("function inlineDefaultLeak")
        })
        assertFalse("$EXTERNAL_PACKAGE.NonInlineDefaultOnly" in external)
    }

    @Test
    fun scansRealMemberAndSuspendInlineDefaultBridges() {
        val snapshot = JvmPublicSurfaceScanner.scanClassBytes(
            listOf(
                kotlinInput("PublicSurfaceFixturesKt"),
                kotlinInput("PublicSurfaceFixturesKt\$inlineDefaultLeak\$1"),
                kotlinInput("InlineDefaultMember"),
            ),
        )

        assertTrue("$EXTERNAL_PACKAGE.InlineMemberDefaultOnly" in snapshot.referenceView)
        assertTrue("$EXTERNAL_PACKAGE.InlineSuspendDefaultOnly" in snapshot.referenceView)
    }

    @Test
    fun preservesJavaVisibleBridgesAndInlineCodeAnnotationArguments() {
        val snapshot = JvmPublicSurfaceScanner.scanClassBytes(
            listOf(
                kotlinInput("PublicSurfaceFixturesKt"),
                kotlinInput("PublicSurfaceFixturesKt\$inlineDefaultLeak\$1"),
                kotlinInput("JavaBridgeSurface"),
                JvmClassBytes(
                    MODULE,
                    "generated/InlineCodeAnnotationApi.class",
                    inlineCodeAnnotationApiClass(),
                ),
            ),
        )

        assertTrue("$EXTERNAL_PACKAGE.ExternalType" in snapshot.jvmBinaryClassifierReferences)
        assertTrue("$EXTERNAL_PACKAGE.InlineLocalAnnotationOnly" in snapshot.referenceView)
    }

    @Test
    fun followsSyntheticHelpersFromInlinePropertyAccessorsOnly() {
        val facade = kotlinInput("PublicSurfaceFixturesKt")
        val inlineCarrier = kotlinInput("PublicSurfaceFixturesKt\$inlinePropertyLeak\$1")
        val external = JvmPublicSurfaceScanner.scanClassBytes(listOf(facade, inlineCarrier)).referenceView

        assertTrue(external.getValue("$EXTERNAL_PACKAGE.InlinePropertyOnly").any {
            it.location == "property inlinePropertyLeak"
        })
        assertFalse("$EXTERNAL_PACKAGE.NonInlinePropertyOnly" in external)
    }

    @Test
    fun traversesCopiedAnonymousObjectSurfaceButNotNamedImplementationBody() {
        val snapshot = JvmPublicSurfaceScanner.scanClassBytes(
            listOf(
                kotlinInput("PublicSurfaceFixturesKt"),
                kotlinInput("PublicSurfaceFixturesKt\$inlineAnonymousObjectLeak\$1"),
                externalInput("NamedInlineHelper"),
            ),
        )
        val references = snapshot.referenceView

        assertTrue("$EXTERNAL_PACKAGE.InlineAnonymousExternalMarker" in references)
        assertTrue("$EXTERNAL_PACKAGE.InlineAnonymousGenericOnly" in references)
        assertTrue("$EXTERNAL_PACKAGE.InlineAnonymousFieldOnly" in references)
        assertTrue("$EXTERNAL_PACKAGE.InlineAnonymousMethodOnly" in references)
        assertTrue("$EXTERNAL_PACKAGE.NamedInlineHelper" in references)
        assertFalse("$EXTERNAL_PACKAGE.NamedInlineImplementationOnly" in references)
    }

    @Test
    fun recursivelyTraversesNestedSyntheticCarrierFromRealInlineBytecode() {
        val snapshot = JvmPublicSurfaceScanner.scanClassBytes(
            listOf(
                kotlinInput("PublicSurfaceFixturesKt"),
                kotlinInput("PublicSurfaceFixturesKt\$inlineNestedCarrierLeak\$1"),
                kotlinInput("PublicSurfaceFixturesKt\$inlineNestedCarrierLeak\$1\$1"),
                externalInput("NamedInlineHelper"),
            ),
        )

        assertTrue(snapshot.referenceView.getValue("$EXTERNAL_PACKAGE.InlineNestedCarrierOnly").any {
            it.location.startsWith("function inlineNestedCarrierLeak")
        })
        assertFalse("$EXTERNAL_PACKAGE.NamedInlineImplementationOnly" in snapshot.referenceView)
    }

    @Test
    fun doesNotTraverseNonInlineDefaultHelperCalledFromInlineFunction() {
        val snapshot = JvmPublicSurfaceScanner.scanClassBytes(listOf(kotlinInput("PublicSurfaceFixturesKt")))

        assertFalse("$EXTERNAL_PACKAGE.NonInlineHelperDefaultOnly" in snapshot.referenceView)
    }

    @Test
    fun respectsAccessorAndFieldVisibilityBoundaries() {
        val snapshot = JvmPublicSurfaceScanner.scanClassBytes(listOf(kotlinInput("PublicSurfaceFixturesKt")))
        val references = snapshot.referenceView

        assertFalse("$EXTERNAL_PACKAGE.PrivateSetterImplementationMarker" in references)
        assertFalse("$EXTERNAL_PACKAGE.PrivateFieldImplementationMarker" in references)
        assertTrue("$EXTERNAL_PACKAGE.PublicSetterSurfaceMarker" in references)
        assertTrue("$EXTERNAL_PACKAGE.PublicFieldSurfaceMarker" in references)
        assertFalse("$EXTERNAL_PACKAGE.PublishedFieldSurfaceMarker" in references)
    }

    @Test
    fun failsClosedWhenMetadataAndBytecodeAccessorVisibilityDisagree() {
        val original = kotlinInput("PublicSurfaceFixturesKt")
        val mutated = original.copy(
            origin = "mutated/accessor-visibility.class",
            bytes = rewriteMethodAccess(
                original.bytes,
                "getNonInlinePropertyLeak",
                "()Ljava/lang/String;",
                Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            JvmPublicSurfaceScanner.scanClassBytes(listOf(mutated))
        }
    }

    @Test
    fun rejectsInvalidInlineDefaultCompilerCarriers() {
        val snapshot = JvmPublicSurfaceScanner.scanClassBytes(
            listOf(
                JvmClassBytes(
                    MODULE,
                    "generated/InlineDefaultCarrierVariantsKt.class",
                    inlineDefaultCarrierVariantsApiClass(),
                ),
            ),
        )

        val invalidCarrierClassifiers = listOf(
            "WrongMaskCarrierLeak",
            "WrongMarkerCarrierLeak",
            "NonSyntheticCarrierLeak",
            "WrongReceiverCarrierLeak",
            "WrongReturnCarrierLeak",
            "OverloadCarrierLeak",
        ).map { "$EXTERNAL_PACKAGE.$it" }
        assertTrue(invalidCarrierClassifiers.none { it in snapshot.referenceView })
    }

    @Test
    fun failsClosedWhenInlineDefaultBridgeCardinalityIsNotExactlyOne() {
        listOf(0, 2).forEach { validBridgeCount ->
            assertFailsWith<IllegalArgumentException> {
                JvmPublicSurfaceScanner.scanClassBytes(
                    listOf(
                        JvmClassBytes(
                            MODULE,
                            "generated/InlineDefaultCarrierVariants-$validBridgeCount.class",
                            inlineDefaultCarrierVariantsApiClass(validBridgeCount),
                        ),
                    ),
                )
            }
        }
    }

    @Test
    fun failsClosedForMissingPrivateDuplicateAndAbstractInlineMethods() {
        val original = kotlinInput("PublicSurfaceFixturesKt")
        val name = "inlineLeak"
        val descriptor = "(Lkotlin/jvm/functions/Function0;)V"
        val mutations = listOf(
            rewriteMethod(original.bytes, name, descriptor) { node, method -> node.methods.remove(method) },
            rewriteMethod(original.bytes, name, descriptor) { _, method ->
                method.access = Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL
            },
            rewriteMethod(original.bytes, name, descriptor) { node, method ->
                val duplicate = MethodNode(
                    Opcodes.ASM9,
                    method.access,
                    method.name,
                    method.desc,
                    method.signature,
                    method.exceptions?.toTypedArray(),
                )
                method.accept(duplicate)
                node.methods.add(duplicate)
            },
            rewriteMethod(original.bytes, name, descriptor) { _, method ->
                method.access = method.access or Opcodes.ACC_ABSTRACT
                method.instructions.clear()
                method.tryCatchBlocks.clear()
                method.localVariables?.clear()
            },
        )

        mutations.forEachIndexed { index, bytes ->
            assertFailsWith<IllegalArgumentException> {
                JvmPublicSurfaceScanner.scanClassBytes(
                    listOf(original.copy(origin = "mutated/inline-$index.class", bytes = bytes)),
                )
            }
        }
    }

    @Test
    fun readsModernContextParametersAlongsideReceiverBoundsAndSuspendTypes() {
        val snapshot = JvmPublicSurfaceScanner.scanClassBytes(
            listOf(JvmClassBytes(MODULE, "generated/ContextApi.class", contextApiClass())),
        )

        assertTrue("$EXTERNAL_PACKAGE.ExternalContext" in snapshot.referenceView)
        assertTrue("$EXTERNAL_PACKAGE.ExternalReceiver" in snapshot.referenceView)
        assertTrue("$EXTERNAL_PACKAGE.ExternalBound" in snapshot.referenceView)
        assertTrue("$EXTERNAL_PACKAGE.ExternalType" in snapshot.referenceView)
        assertTrue("$EXTERNAL_PACKAGE.ExternalSuspendResult" in snapshot.referenceView)
        assertTrue("$EXTERNAL_PACKAGE.AnnotationOnlyType" in snapshot.referenceView)
    }

    @Test
    fun supportsJavaClassesInterfacesRecordsAndProtectedNestedTypes() {
        val snapshot = JvmPublicSurfaceScanner.scanClassBytes(javaFixtureInputs())
        val byName = snapshot.reachableClasses.associateBy { it.fqName }

        assertEquals(SurfaceClassKind.JavaClass, byName.getValue("$JAVA_PACKAGE.JavaFixtures").kind)
        assertEquals(SurfaceClassKind.JavaClass, byName.getValue("$JAVA_PACKAGE.JavaFixtures.PublicNested").kind)
        assertEquals(SurfaceClassKind.JavaClass, byName.getValue("$JAVA_PACKAGE.JavaFixtures.ProtectedNested").kind)
        assertEquals(SurfaceClassKind.JavaInterface, byName.getValue("$JAVA_PACKAGE.JavaFixtures.Contract").kind)
        assertEquals(SurfaceClassKind.JavaRecord, byName.getValue("$JAVA_PACKAGE.JavaFixtures.SampleRecord").kind)
        assertTrue("$EXTERNAL_PACKAGE.ExternalType" in snapshot.referenceView)
    }

    @Test
    fun preservesNestedClassifierFoundOnlyInAnErasedJavaGenericArgument() {
        val snapshot = JvmPublicSurfaceScanner.scanClassBytes(
            listOf(JvmClassBytes(MODULE, "generated/NestedGenericApi.class", nestedGenericApiClass())),
        )

        val origins = snapshot.referenceView.getValue("$EXTERNAL_PACKAGE.ExternalOuter.Inner")
        assertTrue(origins.any { it.location == "JVM method nested()Ljava/util/List;" })
    }

    @Test
    fun preservesAllClassifierReferencesWithoutHostPlatformFiltering() {
        val snapshot = JvmPublicSurfaceScanner.scanClassBytes(
            listOf(JvmClassBytes(MODULE, "generated/PlatformBoundaryApi.class", platformBoundaryApiClass())),
        )

        assertTrue(
            snapshot.referenceView.keys.containsAll(
                setOf(
                    "javax.inject.Provider",
                    "javax.crypto.Cipher",
                    "java.util.List",
                    "java.lang.String",
                    "jdk.internal.misc.Unsafe",
                    "kotlin.jvm.functions.Function0",
                ),
            ),
        )
    }

    @Test
    fun collectsMetadataOnlyClassifiersAndJavaPermittedSubclasses() {
        val snapshot = JvmPublicSurfaceScanner.scanClassBytes(metadataCompletenessInputs())
        val expected = setOf(
            "SealedMetadataOnly",
            "ValueUnderlyingOnly",
            "EnumEntryAnnotationOnly",
            "ContractConstructorOnly",
            "ContractAndOnly",
            "ContractOrOnly",
            "AnnotationDefaultAnnotationOnly",
            "AnnotationDefaultEnumOnly",
            "AnnotationDefaultKClassOnly",
            "JavaPermittedOnly",
        ).mapTo(sortedSetOf()) { "$EXTERNAL_PACKAGE.$it" }

        assertTrue(snapshot.referenceView.keys.containsAll(expected))
    }

    @Test
    fun recognizesFileFacadeAndMultifileCarriersWithoutInventingDataClasses() {
        val snapshot = JvmPublicSurfaceScanner.scanClassBytes(multifileInputs())

        assertEquals(
            setOf(FileFacadeKind.MultiFileFacade, FileFacadeKind.MultiFilePart),
            snapshot.fileFacades.mapTo(linkedSetOf()) { it.kind },
        )
        assertEquals(3, snapshot.fileFacades.size)
        assertTrue(snapshot.authoredPublicDataClasses.isEmpty())
        assertTrue(snapshot.reachableClasses.isEmpty())
    }

    @Test
    fun failsClosedForUnsupportedVersionAndUnknownMetadataKind() {
        val original = kotlinInput("PublicOuter")
        val unsupportedVersion = original.copy(
            origin = "mutated/unsupported-version.class",
            bytes = rewriteMetadataValue(original.bytes, "mv", listOf(999, 0, 0)),
        )
        val unknownKind = original.copy(
            origin = "mutated/unknown-kind.class",
            bytes = rewriteMetadataValue(original.bytes, "k", 99),
        )

        assertFailsWith<UnsupportedKotlinMetadataException> {
            JvmPublicSurfaceScanner.scanClassBytes(listOf(unsupportedVersion))
        }
        assertFailsWith<UnsupportedKotlinMetadataException> {
            JvmPublicSurfaceScanner.scanClassBytes(listOf(unknownKind))
        }
    }

    @Test
    fun failsClosedForMalformedMetadataAnnotationShape() {
        val original = kotlinInput("PublicOuter")
        val malformedInputs = listOf(
            original.copy(
                origin = "mutated/non-integer-kind.class",
                bytes = rewriteMetadataValue(original.bytes, "k", "bad"),
            ),
            original.copy(
                origin = "mutated/non-string-extra.class",
                bytes = rewriteMetadataValue(original.bytes, "xs", 1),
            ),
            original.copy(
                origin = "mutated/mixed-version-array.class",
                bytes = rewriteMetadataValue(original.bytes, "mv", listOf(2, "bad")),
            ),
            original.copy(
                origin = "mutated/mixed-data-array.class",
                bytes = rewriteMetadataValue(original.bytes, "d1", listOf("valid", 1)),
            ),
            original.copy(
                origin = "mutated/duplicate-kind.class",
                bytes = appendMetadataValue(original.bytes, "k", 1),
            ),
        )

        malformedInputs.forEach { input ->
            assertFailsWith<UnsupportedKotlinMetadataException>(input.origin) {
                JvmPublicSurfaceScanner.scanClassBytes(listOf(input))
            }
        }
    }

    @Test
    fun reportsDuplicateFqcnAcrossModulesWithBothOrigins() {
        val bytes = resourceBytes("$API_INTERNAL_NAME/PublicOuter.class")
        val snapshot = JvmPublicSurfaceScanner.scanClassBytes(
            listOf(
                JvmClassBytes("alpha", "alpha/PublicOuter.class", bytes),
                JvmClassBytes("beta", "beta/PublicOuter.class", bytes),
            ),
        )

        val origins = snapshot.binaryNameCollisions.getValue("$API_PACKAGE.PublicOuter")
        assertEquals(listOf("alpha", "beta"), origins.map { it.moduleName })
        assertEquals(origins, snapshot.fqcnIndex.getValue("$API_PACKAGE.PublicOuter"))
        assertEquals(origins, snapshot.binaryNameIndex.getValue("$API_PACKAGE.PublicOuter"))
        assertEquals(origins, snapshot.sourceNameCollisions.getValue("$API_PACKAGE.PublicOuter"))
    }

    @Test
    fun reportsBinaryCollisionEvenWhenSourceNamesDiffer() {
        val snapshot = JvmPublicSurfaceScanner.scanClassBytes(
            listOf(
                JvmClassBytes("alpha", "alpha/A-dollar-B.class", binaryIdentityClass(nested = false)),
                JvmClassBytes("beta", "beta/A.class", emptyJavaClass("p/A")),
                JvmClassBytes("beta", "beta/A-nested-B.class", binaryIdentityClass(nested = true)),
            ),
        )

        assertTrue("p.A\$B" in snapshot.fqcnIndex)
        assertTrue("p.A.B" in snapshot.fqcnIndex)
        val binaryOrigins = snapshot.binaryNameIndex.getValue("p.A\$B")
        assertEquals(listOf("alpha", "beta"), binaryOrigins.map { it.moduleName })
        assertEquals(binaryOrigins, snapshot.binaryNameCollisions.getValue("p.A\$B"))
        assertTrue(snapshot.sourceNameCollisions.isEmpty())
    }

    @Test
    fun reportsSourceNameCollisionWithoutInventingBinaryCollision() {
        val snapshot = JvmPublicSurfaceScanner.scanClassBytes(
            listOf(
                JvmClassBytes("alpha", "alpha/A.class", emptyJavaClass("p/A")),
                JvmClassBytes("alpha", "alpha/A-nested-B.class", binaryIdentityClass(nested = true)),
                JvmClassBytes("beta", "beta/package-A-B.class", emptyJavaClass("p/A/B")),
            ),
        )

        val sourceOrigins = snapshot.sourceNameCollisions.getValue("p.A.B")
        assertEquals(listOf("alpha", "beta"), sourceOrigins.map { it.moduleName })
        assertTrue(snapshot.binaryNameCollisions.isEmpty())
    }

    @Test
    fun resolvesSourceDisplayWithinReferencingModuleBeforeUsingFallback() {
        val binaryInternalName = "p/A\$B"
        val binaryName = "p.A\$B"
        val snapshot = JvmPublicSurfaceScanner.scanClassBytes(
            listOf(
                JvmClassBytes("alpha-other", "other/A-dollar-B.class", binaryIdentityClass(nested = false)),
                JvmClassBytes(
                    "middle-fallback",
                    "fallback/FallbackConsumer.class",
                    javaApiReturning("q/FallbackConsumer", binaryInternalName),
                ),
                JvmClassBytes("zeta-consumer", "consumer/A.class", emptyJavaClass("p/A")),
                JvmClassBytes("zeta-consumer", "consumer/A-nested-B.class", binaryIdentityClass(nested = true)),
                JvmClassBytes(
                    "zeta-consumer",
                    "consumer/LocalConsumer.class",
                    javaApiReturning("q/LocalConsumer", binaryInternalName),
                ),
            ),
        )
        val origins = snapshot.jvmBinaryClassifierReferences.getValue(binaryName)
        val local = origins.single { it.ownerFqName == "q.LocalConsumer" }
        val fallback = origins.single { it.ownerFqName == "q.FallbackConsumer" }

        assertEquals("p.A.B", local.sourceDisplayName)
        assertEquals("p.A.B", fallback.sourceDisplayName)
        assertTrue(binaryName in snapshot.jvmBinaryClassifierReferences)
        assertFalse("p.A.B" in snapshot.jvmBinaryClassifierReferences)
    }

    @Test
    fun preservesClassifierReferenceToTypeDeclaredInAnotherModule() {
        val referencedType = "$GENERATED_INTERNAL_NAME/moduleb/PublicContract"
        val snapshot = JvmPublicSurfaceScanner.scanClassBytes(
            listOf(
                JvmClassBytes(
                    "module-a",
                    "module-a/PublicApi.class",
                    javaApiReturning("$GENERATED_INTERNAL_NAME/modulea/PublicApi", referencedType),
                ),
                JvmClassBytes(
                    "module-b",
                    "module-b/PublicContract.class",
                    emptyJavaClass(referencedType),
                ),
            ),
        )
        val classifier = referencedType.replace('/', '.')

        assertTrue(snapshot.referenceView.getValue(classifier).any {
            it.ownerFqName == "$GENERATED_PACKAGE.modulea.PublicApi" && it.origin.moduleName == "module-a"
        })
        assertEquals(snapshot.jvmBinaryClassifierReferences.keys.sorted(), snapshot.jvmBinaryClassifierReferences.keys.toList())
    }

    @Test
    fun producesTheSameSnapshotForShuffledClassInputs() {
        val inputs = kotlinFixtureInputs() + javaFixtureInputs()
        val shuffled = inputs.shuffled(Random(731))

        assertEquals(
            JvmPublicSurfaceScanner.scanClassBytes(inputs),
            JvmPublicSurfaceScanner.scanClassBytes(shuffled),
        )
    }

    @Test
    fun jarSnapshotUsesLogicalIdentityAndSurvivesFilesystemRelocation() {
        val firstRoot = Files.createTempDirectory("public-surface-first-")
        val secondRoot = Files.createTempDirectory("public-surface-second-")
        val firstJar = firstRoot.resolve("renamed-a.jar")
        val secondJar = secondRoot.resolve("renamed-b.jar")
        val bytes = jarBytes(
            "$API_INTERNAL_NAME/DataFixture.class",
            resourceBytes("$API_INTERNAL_NAME/DataFixture.class"),
        )
        try {
            Files.write(firstJar, bytes)
            Files.write(secondJar, bytes)

            val first = JvmPublicSurfaceScanner.scanJars(
                listOf(JvmJarArtifact(MODULE, "fixture-api", firstJar)),
            )
            val second = JvmPublicSurfaceScanner.scanJars(
                listOf(JvmJarArtifact(MODULE, "fixture-api", secondJar)),
            )

            assertEquals(first, second)
            assertEquals(listOf("$API_PACKAGE.DataFixture"), first.authoredPublicDataClasses.map { it.fqName })
            assertTrue(first.fqcnIndex.values.flatten().all { it.entryName.startsWith("fixture-api!/") })
            assertFalse(firstRoot.toString() in first.toString())
            assertFalse(secondRoot.toString() in second.toString())
        } finally {
            Files.deleteIfExists(firstJar)
            Files.deleteIfExists(secondJar)
            Files.deleteIfExists(firstRoot)
            Files.deleteIfExists(secondRoot)
        }
    }

    @Test
    fun ignoresDormantVersionedEntriesInNonMultiReleaseJar() {
        val root = Files.createTempDirectory("public-surface-non-mr-")
        val baseOnlyJar = root.resolve("base-only.jar")
        val dormantJar = root.resolve("with-dormant-version.jar")
        val baseClass = javaApiReturning("p/Api", "$EXTERNAL_INTERNAL_NAME/BaseOnly")
        try {
            Files.write(baseOnlyJar, jarBytes(linkedMapOf("p/Api.class" to baseClass)))
            Files.write(
                dormantJar,
                jarBytes(
                    linkedMapOf(
                        "p/Api.class" to baseClass,
                        "META-INF/versions/21/p/Api.class" to
                                javaApiReturning("p/Api", "$EXTERNAL_INTERNAL_NAME/DormantOnly"),
                    ),
                ),
            )

            val base = JvmPublicSurfaceScanner.scanJars(listOf(JvmJarArtifact(MODULE, "api", baseOnlyJar)))
            val withDormant = JvmPublicSurfaceScanner.scanJars(listOf(JvmJarArtifact(MODULE, "api", dormantJar)))

            assertEquals(base, withDormant)
            assertFalse("$EXTERNAL_PACKAGE.DormantOnly" in withDormant.referenceView)
            assertTrue(withDormant.binaryNameCollisions.isEmpty())
        } finally {
            Files.deleteIfExists(baseOnlyJar)
            Files.deleteIfExists(dormantJar)
            Files.deleteIfExists(root)
        }
    }

    @Test
    fun selectsEffectiveMultiReleaseEntryForTargetRuntime() {
        val root = Files.createTempDirectory("public-surface-mr-")
        val jar = root.resolve("multi-release.jar")
        try {
            Files.write(
                jar,
                jarBytes(
                    linkedMapOf(
                        "p/Api.class" to javaApiReturning("p/Api", "$EXTERNAL_INTERNAL_NAME/BaseOnly"),
                        "META-INF/versions/17/p/Api.class" to
                                javaApiReturning("p/Api", "$EXTERNAL_INTERNAL_NAME/Version17Only"),
                        "META-INF/versions/21/p/Api.class" to
                                javaApiReturning("p/Api", "$EXTERNAL_INTERNAL_NAME/Version21Only"),
                    ),
                    multiRelease = true,
                ),
            )

            val target17 = JvmPublicSurfaceScanner.scanJars(
                listOf(JvmJarArtifact(MODULE, "api", jar)),
                Runtime.Version.parse("17"),
            )
            val target21 = JvmPublicSurfaceScanner.scanJars(
                listOf(JvmJarArtifact(MODULE, "api", jar)),
                Runtime.Version.parse("21"),
            )

            assertTrue("$EXTERNAL_PACKAGE.Version17Only" in target17.referenceView)
            assertFalse("$EXTERNAL_PACKAGE.Version21Only" in target17.referenceView)
            assertTrue("$EXTERNAL_PACKAGE.Version21Only" in target21.referenceView)
            assertFalse("$EXTERNAL_PACKAGE.Version17Only" in target21.referenceView)
            assertTrue(target17.binaryNameCollisions.isEmpty())
            assertTrue(target21.binaryNameCollisions.isEmpty())
        } finally {
            Files.deleteIfExists(jar)
            Files.deleteIfExists(root)
        }
    }

    private fun kotlinFixtureInputs(): List<JvmClassBytes> = listOf(
        kotlinInput("PublicOuter"),
        kotlinInput("PublicOuter\$Nested"),
        kotlinInput("PrivateOuter"),
        kotlinInput("PrivateOuter\$Nested"),
        kotlinInput("DataFixture"),
        kotlinInput("GenericSurface"),
        kotlinInput("InlineDefaultMember"),
        kotlinInput("JavaBridgeSurface"),
        kotlinInput("PublishedInternal"),
        kotlinInput("PublicSurfaceFixturesKt"),
        kotlinInput("PublicSurfaceFixturesKt\$inlineDefaultLeak\$1"),
        kotlinInput("PublicSurfaceFixturesKt\$inlinePropertyLeak\$1"),
        kotlinInput("PublicSurfaceFixturesKt\$inlineAnonymousObjectLeak\$1"),
        kotlinInput("PublicSurfaceFixturesKt\$inlineNestedCarrierLeak\$1"),
        kotlinInput("PublicSurfaceFixturesKt\$inlineNestedCarrierLeak\$1\$1"),
        externalInput("NamedInlineHelper"),
    )

    private fun javaFixtureInputs(): List<JvmClassBytes> = listOf(
        javaInput("JavaFixtures"),
        javaInput("JavaFixtures\$PublicNested"),
        javaInput("JavaFixtures\$ProtectedNested"),
        javaInput("JavaFixtures\$Contract"),
        javaInput("JavaFixtures\$SampleRecord"),
    )

    private fun multifileInputs(): List<JvmClassBytes> = listOf(
        multifileInput("MultiApi"),
        multifileInput("MultiApi__MultifileOneKt"),
        multifileInput("MultiApi__MultifileTwoKt"),
    )

    private fun metadataCompletenessInputs(): List<JvmClassBytes> {
        val sealedClass = publicKmClass("SealedMetadataApi").apply {
            modality = Modality.SEALED
            sealedSubclasses += "$EXTERNAL_INTERNAL_NAME/SealedMetadataOnly"
        }
        val valueClass = publicKmClass("ValueMetadataApi").apply {
            isValue = true
            inlineClassUnderlyingPropertyName = "value"
            inlineClassUnderlyingType = kmType("$EXTERNAL_INTERNAL_NAME/ValueUnderlyingOnly")
        }
        val enumClass = publicKmClass("EnumMetadataApi", ClassKind.ENUM_CLASS).apply {
            kmEnumEntries += KmEnumEntry("ENTRY").apply {
                annotations += KmAnnotation(
                    "$EXTERNAL_INTERNAL_NAME/EnumEntryAnnotationOnly",
                    emptyMap(),
                )
            }
        }
        val contractClass = publicKmClass("ContractMetadataApi").apply {
            functions += KmFunction("contractMetadata").apply {
                visibility = Visibility.PUBLIC
                returnType = kmType("kotlin/Boolean")
                signature = JvmMethodSignature("contractMetadata", "()Z")
                contract = KmContract().apply {
                    effects += KmEffect(KmEffectType.RETURNS_NOT_NULL, null).apply {
                        constructorArguments += instanceEffect("$EXTERNAL_INTERNAL_NAME/ContractConstructorOnly")
                        conclusion = KmEffectExpression().apply {
                            andArguments += instanceEffect("$EXTERNAL_INTERNAL_NAME/ContractAndOnly")
                            orArguments += KmEffectExpression().apply {
                                orArguments += instanceEffect("$EXTERNAL_INTERNAL_NAME/ContractOrOnly")
                            }
                        }
                    }
                }
            }
        }
        val annotationClass = publicKmClass("AnnotationMetadataApi", ClassKind.ANNOTATION_CLASS).apply {
            constructors += KmConstructor().apply {
                visibility = Visibility.PUBLIC
                valueParameters += KmValueParameter("value").apply {
                    type = kmType("kotlin/String")
                    annotationParameterDefaultValue = KmAnnotationArgument.AnnotationValue(
                        KmAnnotation(
                            "$EXTERNAL_INTERNAL_NAME/AnnotationDefaultAnnotationOnly",
                            mapOf(
                                "enum" to KmAnnotationArgument.EnumValue(
                                    "$EXTERNAL_INTERNAL_NAME/AnnotationDefaultEnumOnly",
                                    "ENTRY",
                                ),
                                "type" to KmAnnotationArgument.KClassValue(
                                    "$EXTERNAL_INTERNAL_NAME/AnnotationDefaultKClassOnly",
                                ),
                            ),
                        ),
                    )
                }
            }
        }

        return listOf(sealedClass, valueClass, enumClass, contractClass, annotationClass).map { kmClass ->
            JvmClassBytes(
                MODULE,
                "generated/${kmClass.name.substringAfterLast('/')}.class",
                kotlinMetadataClass(kmClass),
            )
        } + JvmClassBytes(MODULE, "generated/JavaSealedApi.class", javaSealedApiClass())
    }

    private fun kotlinInput(simpleName: String): JvmClassBytes = JvmClassBytes(
        moduleName = MODULE,
        origin = "kotlin/$simpleName.class",
        bytes = resourceBytes("$API_INTERNAL_NAME/$simpleName.class"),
    )

    private fun javaInput(simpleName: String): JvmClassBytes = JvmClassBytes(
        moduleName = MODULE,
        origin = "java/$simpleName.class",
        bytes = resourceBytes("$JAVA_INTERNAL_NAME/$simpleName.class"),
    )

    private fun externalInput(simpleName: String): JvmClassBytes = JvmClassBytes(
        moduleName = MODULE,
        origin = "external/$simpleName.class",
        bytes = resourceBytes("$EXTERNAL_INTERNAL_NAME/$simpleName.class"),
    )

    private fun multifileInput(simpleName: String): JvmClassBytes = JvmClassBytes(
        moduleName = MODULE,
        origin = "multifile/$simpleName.class",
        bytes = resourceBytes("$MULTIFILE_INTERNAL_NAME/$simpleName.class"),
    )

    private fun resourceBytes(name: String): ByteArray = requireNotNull(javaClass.classLoader.getResourceAsStream(name)) {
        "Missing compiled test fixture $name"
    }.use { it.readBytes() }

    private val PublicSurfaceSnapshot.referenceView: Map<String, List<ClassifierReferenceOrigin>>
        get() {
            val references = linkedMapOf<String, MutableSet<ClassifierReferenceOrigin>>()
            metadataClassifierReferences.forEach { (classifier, origins) ->
                references.getOrPut(classifier) { linkedSetOf() }.addAll(origins)
            }
            jvmBinaryClassifierReferences.forEach { (binaryName, origins) ->
                references.getOrPut(binaryName) { linkedSetOf() }.addAll(origins)
                origins.forEach { origin ->
                    origin.sourceDisplayName?.let { sourceName ->
                        references.getOrPut(sourceName) { linkedSetOf() } += origin
                    }
                }
            }
            return references.toSortedMap().mapValuesTo(linkedMapOf()) { (_, origins) -> origins.sorted() }
        }

    private fun emptyJavaClass(internalName: String): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V21, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, internalName, null, "java/lang/Object", null)
        visitEnd()
    }.toByteArray()

    private fun binaryIdentityClass(nested: Boolean): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "p/A\$B", null, "java/lang/Object", null)
        if (nested) {
            visitInnerClass("p/A\$B", "p/A", "B", Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC)
        }
        visitEnd()
    }.toByteArray()

    private fun publicNestedClass(internalName: String, outerInternalName: String, innerName: String): ByteArray =
        ClassWriter(0).apply {
            visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
            visitInnerClass(
                internalName,
                outerInternalName,
                innerName,
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            )
            visitEnd()
        }.toByteArray()

    private fun publicKmClass(simpleName: String, classKind: ClassKind = ClassKind.CLASS): KmClass = KmClass().apply {
        name = "$GENERATED_INTERNAL_NAME/$simpleName"
        kind = classKind
        visibility = Visibility.PUBLIC
    }

    private fun instanceEffect(className: String): KmEffectExpression = KmEffectExpression().apply {
        isInstanceType = kmType(className)
    }

    private fun kotlinMetadataClass(kmClass: KmClass): ByteArray {
        val annotation = KotlinClassMetadata.Class(
            kmClass,
            JvmMetadataVersion.LATEST_STABLE_SUPPORTED,
            0,
        ).write()
        return ClassWriter(ClassWriter.COMPUTE_MAXS).apply {
            visit(Opcodes.V21, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, kmClass.name, null, "java/lang/Object", null)
            kmClass.functions.mapNotNull { it.signature }.forEach { signature ->
                visitMethod(
                    Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
                    signature.name,
                    signature.descriptor,
                    null,
                    null,
                ).apply {
                    visitCode()
                    when (Type.getReturnType(signature.descriptor).sort) {
                        Type.VOID -> visitInsn(Opcodes.RETURN)
                        Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> {
                            visitInsn(Opcodes.ICONST_0)
                            visitInsn(Opcodes.IRETURN)
                        }
                        Type.LONG -> {
                            visitInsn(Opcodes.LCONST_0)
                            visitInsn(Opcodes.LRETURN)
                        }
                        Type.FLOAT -> {
                            visitInsn(Opcodes.FCONST_0)
                            visitInsn(Opcodes.FRETURN)
                        }
                        Type.DOUBLE -> {
                            visitInsn(Opcodes.DCONST_0)
                            visitInsn(Opcodes.DRETURN)
                        }
                        else -> {
                            visitInsn(Opcodes.ACONST_NULL)
                            visitInsn(Opcodes.ARETURN)
                        }
                    }
                    visitMaxs(0, 0)
                    visitEnd()
                }
            }
            writeMetadata(annotation)
            visitEnd()
        }.toByteArray()
    }

    private fun javaSealedApiClass(): ByteArray = ClassWriter(0).apply {
        visit(
            Opcodes.V21,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT,
            "$GENERATED_INTERNAL_NAME/JavaSealedApi",
            null,
            "java/lang/Object",
            null,
        )
        visitPermittedSubclass("$EXTERNAL_INTERNAL_NAME/JavaPermittedOnly")
        visitEnd()
    }.toByteArray()

    private fun javaApiReturning(ownerInternalName: String, returnTypeInternalName: String): ByteArray =
        ClassWriter(0).apply {
            visit(
                Opcodes.V21,
                Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT,
                ownerInternalName,
                null,
                "java/lang/Object",
                null,
            )
            visitMethod(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT,
                "contract",
                "()L$returnTypeInternalName;",
                null,
                null,
            ).visitEnd()
            visitEnd()
        }.toByteArray()

    private fun contextApiClass(): ByteArray {
        val methodDescriptor = "(L$EXTERNAL_INTERNAL_NAME/ExternalContext;" +
                "L$EXTERNAL_INTERNAL_NAME/ExternalReceiver;" +
                "L$EXTERNAL_INTERNAL_NAME/ExternalType;" +
                "Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"
        val function = KmFunction("contextual").apply {
            visibility = Visibility.PUBLIC
            isSuspend = true
            receiverParameterType = kmType("$EXTERNAL_INTERNAL_NAME/ExternalReceiver")
            contextParameters += KmValueParameter("context").apply {
                type = kmType("$EXTERNAL_INTERNAL_NAME/ExternalContext")
            }
            typeParameters += KmTypeParameter("T", 0, KmVariance.INVARIANT).apply {
                upperBounds += kmType("$EXTERNAL_INTERNAL_NAME/ExternalBound")
            }
            valueParameters += KmValueParameter("value").apply {
                type = kmType("$EXTERNAL_INTERNAL_NAME/ExternalType")
            }
            returnType = kmType("$EXTERNAL_INTERNAL_NAME/ExternalSuspendResult")
            signature = JvmMethodSignature("contextual", methodDescriptor)
        }
        val kmClass = KmClass().apply {
            name = "$GENERATED_INTERNAL_NAME/ContextApi"
            kind = ClassKind.CLASS
            visibility = Visibility.PUBLIC
            functions += function
            annotations += KmAnnotation(
                "$EXTERNAL_INTERNAL_NAME/ExternalApiMarker",
                mapOf(
                    "type" to KmAnnotationArgument.KClassValue(
                        "$EXTERNAL_INTERNAL_NAME/AnnotationOnlyType",
                    ),
                ),
            )
        }
        val annotation = KotlinClassMetadata.Class(
            kmClass,
            JvmMetadataVersion.LATEST_STABLE_SUPPORTED,
            0,
        ).write()

        return ClassWriter(0).apply {
            visit(
                Opcodes.V21,
                Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT,
                "$GENERATED_INTERNAL_NAME/ContextApi",
                null,
                "java/lang/Object",
                null,
            )
            visitMethod(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT,
                "contextual",
                methodDescriptor,
                null,
                null,
            ).visitEnd()
            writeMetadata(annotation)
            visitEnd()
        }.toByteArray()
    }

    private fun nestedGenericApiClass(): ByteArray = ClassWriter(0).apply {
        visit(
            Opcodes.V21,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT,
            "$GENERATED_INTERNAL_NAME/NestedGenericApi",
            null,
            "java/lang/Object",
            null,
        )
        visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT,
            "nested",
            "()Ljava/util/List;",
            "()Ljava/util/List<L$EXTERNAL_INTERNAL_NAME/ExternalOuter.Inner;>;",
            null,
        ).visitEnd()
        visitEnd()
    }.toByteArray()

    private fun inlineDefaultCarrierVariantsApiClass(validBridgeCount: Int = 1): ByteArray {
        require(validBridgeCount >= 0)
        val owner = "$GENERATED_INTERNAL_NAME/InlineDefaultCarrierVariantsKt"
        val mainDescriptor = "(Lkotlin/jvm/functions/Function0;)Ljava/lang/String;"
        val carrierAccess = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC
        val candidates = listOf(
            Triple(
                "wrongMask",
                "(Lkotlin/jvm/functions/Function0;IILjava/lang/Object;)Ljava/lang/String;",
                carrierAccess,
            ),
            Triple(
                "wrongMarker",
                "(Lkotlin/jvm/functions/Function0;ILjava/lang/String;)Ljava/lang/String;",
                carrierAccess,
            ),
            Triple(
                "nonSynthetic",
                "(Lkotlin/jvm/functions/Function0;ILjava/lang/Object;)Ljava/lang/String;",
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            ),
            Triple(
                "wrongReceiver",
                "(L$owner;Lkotlin/jvm/functions/Function0;ILjava/lang/Object;)Ljava/lang/String;",
                carrierAccess,
            ),
            Triple(
                "wrongReturn",
                "(Lkotlin/jvm/functions/Function0;ILjava/lang/Object;)Ljava/lang/Object;",
                carrierAccess,
            ),
            Triple(
                "overload",
                "(Lkotlin/jvm/functions/Function1;ILjava/lang/Object;)Ljava/lang/String;",
                carrierAccess,
            ),
        )
        val annotation = KotlinClassMetadata.FileFacade(
            KmPackage().apply {
                candidates.forEach { (name) ->
                    functions += KmFunction(name).apply {
                        visibility = Visibility.PUBLIC
                        isInline = true
                        valueParameters += KmValueParameter("value").apply {
                            type = kmType("kotlin/Function0")
                            declaresDefaultValue = true
                        }
                        returnType = kmType("kotlin/String")
                        signature = JvmMethodSignature(name, mainDescriptor)
                    }
                }
            },
            JvmMetadataVersion.LATEST_STABLE_SUPPORTED,
            0,
        ).write()

        return ClassWriter(ClassWriter.COMPUTE_MAXS).apply {
            visit(Opcodes.V21, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, owner, null, "java/lang/Object", null)
            candidates.forEach { (name, descriptor, access) ->
                visitMethod(
                    Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
                    name,
                    mainDescriptor,
                    null,
                    null,
                ).apply {
                    visitCode()
                    visitInsn(Opcodes.ACONST_NULL)
                    visitInsn(Opcodes.ARETURN)
                    visitMaxs(0, 0)
                    visitEnd()
                }
                visitMethod(access, "$name\$default", descriptor, null, null).apply {
                    visitCode()
                    visitLdcInsn(
                        Type.getObjectType(
                            "$EXTERNAL_INTERNAL_NAME/${name.replaceFirstChar { it.uppercaseChar() }}CarrierLeak",
                        ),
                    )
                    visitInsn(Opcodes.POP)
                    visitInsn(Opcodes.ACONST_NULL)
                    visitInsn(Opcodes.ARETURN)
                    visitMaxs(0, 0)
                    visitEnd()
                }
                repeat(validBridgeCount) {
                    visitMethod(
                        carrierAccess,
                        "$name\$default",
                        "(Lkotlin/jvm/functions/Function0;ILjava/lang/Object;)Ljava/lang/String;",
                        null,
                        null,
                    ).apply {
                        visitCode()
                        visitInsn(Opcodes.ACONST_NULL)
                        visitInsn(Opcodes.ARETURN)
                        visitMaxs(0, 0)
                        visitEnd()
                    }
                }
            }
            writeMetadata(annotation)
            visitEnd()
        }.toByteArray()
    }

    private fun platformBoundaryApiClass(): ByteArray = ClassWriter(0).apply {
        visit(
            Opcodes.V21,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT,
            "$GENERATED_INTERNAL_NAME/PlatformBoundaryApi",
            null,
            "java/lang/Object",
            null,
        )
        listOf(
            "external" to "()Ljavax/inject/Provider;",
            "jdkJavax" to "()Ljavax/crypto/Cipher;",
            "jdkJava" to "()Ljava/util/List;",
            "jdkJavaLang" to "()Ljava/lang/String;",
            "jdkInternal" to "()Ljdk/internal/misc/Unsafe;",
            "kotlin" to "()Lkotlin/jvm/functions/Function0;",
        ).forEach { (name, descriptor) ->
            visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, name, descriptor, null, null).visitEnd()
        }
        visitEnd()
    }.toByteArray()

    private fun inlineCodeAnnotationApiClass(): ByteArray {
        val owner = "$GENERATED_INTERNAL_NAME/InlineCodeAnnotationApi"
        val descriptor = "()Ljava/lang/String;"
        val function = KmFunction("annotatedLocal").apply {
            visibility = Visibility.PUBLIC
            isInline = true
            returnType = kmType("kotlin/String")
            signature = JvmMethodSignature("annotatedLocal", descriptor)
        }
        val metadata = KotlinClassMetadata.FileFacade(
            KmPackage().apply { functions += function },
            JvmMetadataVersion.LATEST_STABLE_SUPPORTED,
            0,
        ).write()

        return ClassWriter(ClassWriter.COMPUTE_MAXS).apply {
            visit(Opcodes.V21, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, owner, null, "java/lang/Object", null)
            visitMethod(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
                "annotatedLocal",
                descriptor,
                null,
                null,
            ).apply {
                val start = Label()
                val end = Label()
                visitCode()
                visitLabel(start)
                visitLdcInsn("annotated")
                visitVarInsn(Opcodes.ASTORE, 0)
                visitVarInsn(Opcodes.ALOAD, 0)
                visitLabel(end)
                visitInsn(Opcodes.ARETURN)
                visitLocalVariable("value", "Ljava/lang/String;", null, start, end, 0)
                visitLocalVariableAnnotation(
                    TypeReference.newTypeReference(TypeReference.LOCAL_VARIABLE).value,
                    null,
                    arrayOf(start),
                    arrayOf(end),
                    intArrayOf(0),
                    "L$EXTERNAL_INTERNAL_NAME/ExternalApiMarker;",
                    false,
                ).apply {
                    visit("type", Type.getObjectType("$EXTERNAL_INTERNAL_NAME/InlineLocalAnnotationOnly"))
                    visitEnd()
                }
                visitMaxs(0, 0)
                visitEnd()
            }
            writeMetadata(metadata)
            visitEnd()
        }.toByteArray()
    }

    private fun jarBytes(entryName: String, bytes: ByteArray): ByteArray =
        jarBytes(linkedMapOf(entryName to bytes))

    private fun jarBytes(
        entries: Map<String, ByteArray>,
        multiRelease: Boolean = false,
    ): ByteArray = ByteArrayOutputStream().use { buffer ->
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            if (multiRelease) mainAttributes.putValue("Multi-Release", "true")
        }
        JarOutputStream(buffer, manifest).use { output ->
            entries.forEach { (entryName, bytes) ->
                output.putNextEntry(JarEntry(entryName).apply { time = 0L })
                output.write(bytes)
                output.closeEntry()
            }
        }
        buffer.toByteArray()
    }

    private fun ClassWriter.writeMetadata(metadata: Metadata) {
        visitAnnotation("Lkotlin/Metadata;", true).apply {
            visit("mv", metadata.metadataVersion)
            visit("k", metadata.kind)
            visitArray("d1").apply {
                metadata.data1.forEach { visit(null, it) }
                visitEnd()
            }
            visitArray("d2").apply {
                metadata.data2.forEach { visit(null, it) }
                visitEnd()
            }
            visit("xs", metadata.extraString)
            visit("pn", metadata.packageName)
            visit("xi", metadata.extraInt)
            visitEnd()
        }
    }

    private fun kmType(className: String): KmType = KmType().apply {
        classifier = KmClassifier.Class(className)
    }

    private fun rewriteMetadataValue(bytes: ByteArray, key: String, replacement: Any): ByteArray {
        val node = ClassNode()
        ClassReader(bytes).accept(node, 0)
        val annotation = (node.visibleAnnotations.orEmpty() + node.invisibleAnnotations.orEmpty())
            .filterIsInstance<AnnotationNode>()
            .single { it.desc == "Lkotlin/Metadata;" }
        val values = annotation.values
        val keyIndex = values.indices.firstOrNull { values[it] == key }
        if (keyIndex == null) {
            values.add(key)
            values.add(replacement)
        } else {
            values[keyIndex + 1] = replacement
        }
        return ClassWriter(0).also(node::accept).toByteArray()
    }

    private fun appendMetadataValue(bytes: ByteArray, key: String, value: Any): ByteArray {
        val node = ClassNode()
        ClassReader(bytes).accept(node, 0)
        val annotation = (node.visibleAnnotations.orEmpty() + node.invisibleAnnotations.orEmpty())
            .filterIsInstance<AnnotationNode>()
            .single { it.desc == "Lkotlin/Metadata;" }
        annotation.values.add(key)
        annotation.values.add(value)
        return ClassWriter(0).also(node::accept).toByteArray()
    }

    private fun rewriteMethodAccess(
        bytes: ByteArray,
        name: String,
        descriptor: String,
        access: Int,
    ): ByteArray {
        val node = ClassNode()
        ClassReader(bytes).accept(node, 0)
        node.methods.single { it.name == name && it.desc == descriptor }.access = access
        return ClassWriter(0).also(node::accept).toByteArray()
    }

    private fun rewriteMethod(
        bytes: ByteArray,
        name: String,
        descriptor: String,
        mutation: (ClassNode, MethodNode) -> Unit,
    ): ByteArray {
        val node = ClassNode()
        ClassReader(bytes).accept(node, 0)
        mutation(node, node.methods.single { it.name == name && it.desc == descriptor })
        return ClassWriter(0).also(node::accept).toByteArray()
    }

    private companion object {
        const val MODULE = "fixture"
        const val API_PACKAGE = "space.kscience.krig.build.architecture.fixtures.api"
        const val API_INTERNAL_NAME = "space/kscience/krig/build/architecture/fixtures/api"
        const val EXTERNAL_PACKAGE = "space.kscience.krig.build.architecture.fixtures.external"
        const val EXTERNAL_INTERNAL_NAME = "space/kscience/krig/build/architecture/fixtures/external"
        const val JAVA_PACKAGE = "space.kscience.krig.build.architecture.fixtures.javaapi"
        const val JAVA_INTERNAL_NAME = "space/kscience/krig/build/architecture/fixtures/javaapi"
        const val MULTIFILE_INTERNAL_NAME = "space/kscience/krig/build/architecture/fixtures/multifile"
        const val GENERATED_INTERNAL_NAME = "space/kscience/krig/build/architecture/fixtures/generated"
        const val GENERATED_PACKAGE = "space.kscience.krig.build.architecture.fixtures.generated"
    }
}
