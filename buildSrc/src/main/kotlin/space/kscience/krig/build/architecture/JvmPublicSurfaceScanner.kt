@file:OptIn(
    kotlin.ExperimentalContextParameters::class,
    kotlin.contracts.ExperimentalContracts::class,
    kotlin.metadata.ExperimentalAnnotationsInMetadata::class,
    kotlin.metadata.ExperimentalContextReceivers::class,
)
@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")

package space.kscience.krig.build.architecture

import java.util.ArrayDeque
import java.util.jar.JarFile
import java.util.zip.ZipFile
import kotlin.metadata.ClassKind
import kotlin.metadata.KmAnnotation
import kotlin.metadata.KmAnnotationArgument
import kotlin.metadata.KmClass
import kotlin.metadata.KmClassifier
import kotlin.metadata.KmConstructor
import kotlin.metadata.KmDeclarationContainer
import kotlin.metadata.KmEffectExpression
import kotlin.metadata.KmFunction
import kotlin.metadata.KmProperty
import kotlin.metadata.KmType
import kotlin.metadata.KmTypeAlias
import kotlin.metadata.KmTypeParameter
import kotlin.metadata.KmValueParameter
import kotlin.metadata.Visibility
import kotlin.metadata.declaresDefaultValue
import kotlin.metadata.isData
import kotlin.metadata.isInline
import kotlin.metadata.kind
import kotlin.metadata.visibility
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.jvm.anonymousObjectOriginName
import kotlin.metadata.jvm.annotations
import kotlin.metadata.jvm.fieldSignature
import kotlin.metadata.jvm.getterSignature
import kotlin.metadata.jvm.signature
import kotlin.metadata.jvm.setterSignature
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.signature.SignatureReader
import org.objectweb.asm.signature.SignatureVisitor
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.FrameNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.MultiANewArrayInsnNode
import org.objectweb.asm.tree.RecordComponentNode
import org.objectweb.asm.tree.TypeInsnNode

internal object JvmPublicSurfaceScanner {
    fun scanJars(
        artifacts: Iterable<JvmJarArtifact>,
        targetRuntimeVersion: Runtime.Version = Runtime.Version.parse("21"),
    ): PublicSurfaceSnapshot {
        val sortedArtifacts = artifacts.sortedWith(compareBy({ it.moduleName }, { it.logicalArtifactId }))
        require(sortedArtifacts.distinctBy { it.moduleName to it.logicalArtifactId }.size == sortedArtifacts.size) {
            "JVM artifact logical ids must be unique within each module"
        }
        val classes = sortedArtifacts
            .flatMap { artifact ->
                JarFile(
                    artifact.path.toFile(),
                    false,
                    ZipFile.OPEN_READ,
                    targetRuntimeVersion,
                ).use { jar ->
                    val entries = jar.versionedStream()
                        .filter { entry ->
                            !entry.isDirectory &&
                                    !entry.name.startsWith(MULTI_RELEASE_VERSION_PREFIX) &&
                                    entry.name.endsWith(".class") &&
                                    !entry.name.endsWith("module-info.class") &&
                                    !entry.name.endsWith("package-info.class")
                        }
                        .sorted(compareBy { it.name })
                        .toList()
                    require(entries.distinctBy { it.name }.size == entries.size) {
                        "Duplicate effective JVM class entry in ${artifact.moduleName}:${artifact.logicalArtifactId}"
                    }
                    entries.map { entry ->
                        val bytes = jar.getInputStream(entry).use { it.readBytes() }
                        val declaredInternalName = try {
                            ClassReader(bytes).className
                        } catch (failure: RuntimeException) {
                            throw IllegalArgumentException(
                                "Could not parse JVM class entry ${artifact.moduleName}:" +
                                        "${artifact.logicalArtifactId}!/${entry.name}",
                                failure,
                            )
                        }
                        require(entry.name == "$declaredInternalName.class") {
                            "JVM class entry ${artifact.moduleName}:${artifact.logicalArtifactId}!/${entry.name} " +
                                    "declares $declaredInternalName"
                        }
                            JvmClassBytes(
                                moduleName = artifact.moduleName,
                                origin = "${artifact.logicalArtifactId}!/${entry.name}",
                                bytes = bytes,
                            )
                        }
                }
            }
        return scanClassBytes(classes)
    }

    fun scanClassBytes(inputs: Iterable<JvmClassBytes>): PublicSurfaceSnapshot {
        val parsed = inputs
            .map(::parseClass)
            .sortedWith(compareBy({ it.origin.moduleName }, { it.origin.entryName }, { it.node.name }))
        val lookup = parsed.groupBy { it.origin.moduleName to it.node.name }
        val names = ClassNameResolver(lookup)

        val fqcnIndex = parsed
            .groupBy { names.fqName(it) }
            .toSortedMap()
            .mapValuesTo(linkedMapOf()) { (_, records) -> records.map { it.origin }.distinct().sorted() }
        val binaryNameIndex = parsed
            .groupBy { it.node.name.replace('/', '.') }
            .toSortedMap()
            .mapValuesTo(linkedMapOf()) { (_, records) -> records.map { it.origin }.distinct().sorted() }
        val sourceNameCollisions = fqcnIndex
            .filterValues { it.size > 1 }
            .toSortedMap()
            .mapValuesTo(linkedMapOf()) { it.value }
        val binaryNameCollisions = binaryNameIndex
            .filterValues { it.size > 1 }
            .toSortedMap()
            .mapValuesTo(linkedMapOf()) { it.value }

        val reachableRecords = parsed.filter { isReachable(it, lookup, mutableSetOf()) }
        val reachableClasses = reachableRecords.mapNotNull { record ->
            when (val metadata = record.metadata) {
                is KotlinClassMetadata.Class -> ReachableClass(
                    fqName = normalizeMetadataName(metadata.kmClass.name),
                    kind = metadata.kmClass.surfaceKind(),
                    origin = record.origin,
                )

                null -> ReachableClass(
                    fqName = names.fqName(record),
                    kind = record.node.javaSurfaceKind(),
                    origin = record.origin,
                )

                else -> null
            }
        }.sortedWith(reachableClassOrder)

        val dataClasses = reachableRecords.mapNotNull { record ->
            val metadata = record.metadata as? KotlinClassMetadata.Class ?: return@mapNotNull null
            if (!metadata.kmClass.isData) return@mapNotNull null
            ReachableClass(
                fqName = normalizeMetadataName(metadata.kmClass.name),
                kind = metadata.kmClass.surfaceKind(),
                origin = record.origin,
            )
        }.distinct().sortedWith(reachableClassOrder)

        val references = ReferenceCollector(names)
        val inlineClosures = InlineClosureIndex(parsed)
        val aliases = mutableListOf<TypeAliasDeclaration>()
        val facades = mutableListOf<FileFacadeDeclaration>()
        val publishedApi = mutableListOf<PublishedApiDeclaration>()

        parsed.forEach { record ->
            collectPublishedApi(record, names, publishedApi)
            when (val metadata = record.metadata) {
                is KotlinClassMetadata.Class -> {
                    if (record in reachableRecords) {
                        val owner = normalizeMetadataName(metadata.kmClass.name)
                        collectKotlinClass(metadata.kmClass, record, owner, references, aliases, inlineClosures)
                        collectJvmBinaryMembers(record, owner, references)
                    }
                }

                is KotlinClassMetadata.FileFacade -> {
                    val owner = names.fqName(record)
                    facades += FileFacadeDeclaration(owner, FileFacadeKind.FileFacade, record.origin)
                    collectFacadeAnnotations(record, owner, references)
                    collectJvmBinaryMembers(record, owner, references)
                    collectContainer(
                        container = metadata.kmPackage,
                        record = record,
                        owner = owner,
                        aliasPrefix = packageName(owner),
                        references = references,
                        aliases = aliases,
                        inlineClosures = inlineClosures,
                    )
                }

                is KotlinClassMetadata.MultiFileClassPart -> {
                    val owner = names.fqName(record)
                    facades += FileFacadeDeclaration(owner, FileFacadeKind.MultiFilePart, record.origin)
                    collectFacadeAnnotations(record, owner, references)
                    collectJvmBinaryMembers(record, owner, references)
                    collectContainer(
                        container = metadata.kmPackage,
                        record = record,
                        owner = owner,
                        aliasPrefix = packageName(owner),
                        references = references,
                        aliases = aliases,
                        inlineClosures = inlineClosures,
                    )
                }

                is KotlinClassMetadata.MultiFileClassFacade -> {
                    val owner = names.fqName(record)
                    facades += FileFacadeDeclaration(owner, FileFacadeKind.MultiFileFacade, record.origin)
                    collectFacadeAnnotations(record, owner, references)
                    collectJvmBinaryMembers(record, owner, references)
                }

                null -> if (record in reachableRecords) collectJavaClass(record, names.fqName(record), references)
                is KotlinClassMetadata.SyntheticClass -> Unit
                is KotlinClassMetadata.Unknown -> Unit
            }
        }

        val sortedAliases = aliases.distinct().sortedWith(compareBy({ it.fqName }, { it.origin }))
        return PublicSurfaceSnapshot(
            reachableClasses = reachableClasses,
            authoredPublicDataClasses = dataClasses,
            typeAliases = sortedAliases,
            fileFacades = facades.distinct().sortedWith(compareBy({ it.fqName }, { it.kind }, { it.origin })),
            publishedApiInternals = publishedApi.distinct().sortedWith(
                compareBy({ it.ownerFqName }, { it.declaration }, { it.origin }),
            ),
            fqcnIndex = fqcnIndex,
            binaryNameIndex = binaryNameIndex,
            sourceNameCollisions = sourceNameCollisions,
            binaryNameCollisions = binaryNameCollisions,
            metadataClassifierReferences = references.metadataSnapshot(),
            jvmBinaryClassifierReferences = references.jvmSnapshot(),
        )
    }

    private fun parseClass(input: JvmClassBytes): ParsedClass {
        val node = ClassNode()
        try {
            ClassReader(input.bytes).accept(node, 0)
        } catch (failure: RuntimeException) {
            throw IllegalArgumentException("Could not parse JVM class bytes from ${input.moduleName}:${input.origin}", failure)
        }
        require(node.name != "module-info" && !node.name.endsWith("/package-info")) {
            "Module and package descriptors are not class surface inputs: ${input.moduleName}:${input.origin}"
        }
        val origin = SurfaceOrigin(input.moduleName, input.origin)
        return ParsedClass(origin, node, readMetadata(node, origin))
    }

    private fun readMetadata(node: ClassNode, origin: SurfaceOrigin): KotlinClassMetadata? {
        val annotation = node.findAnnotation(KOTLIN_METADATA_DESCRIPTOR) ?: return null
        val values = annotation.strictMetadataValues(origin)
        val metadata = kotlin.metadata.jvm.Metadata(
            kind = values.metadataInt("k", origin),
            metadataVersion = values.metadataIntList("mv", origin)?.toIntArray(),
            data1 = values.metadataStringList("d1", origin)?.toTypedArray(),
            data2 = values.metadataStringList("d2", origin)?.toTypedArray(),
            extraString = values.metadataString("xs", origin),
            packageName = values.metadataString("pn", origin),
            extraInt = values.metadataInt("xi", origin),
        )
        val parsed = try {
            KotlinClassMetadata.readStrict(metadata)
        } catch (failure: IllegalArgumentException) {
            val version = metadata.metadataVersion.joinToString(".")
            throw UnsupportedKotlinMetadataException(
                "Unsupported or corrupt Kotlin metadata $version in ${origin.moduleName}:${origin.entryName}",
                failure,
            )
        }
        if (parsed is KotlinClassMetadata.Unknown) {
            throw UnsupportedKotlinMetadataException(
                "Unknown Kotlin metadata kind ${metadata.kind} in ${origin.moduleName}:${origin.entryName}",
            )
        }
        return parsed
    }

    private fun collectKotlinClass(
        kmClass: KmClass,
        record: ParsedClass,
        owner: String,
        references: ReferenceCollector,
        aliases: MutableList<TypeAliasDeclaration>,
        inlineClosures: InlineClosureIndex,
    ) {
        val annotationSink = references.at(record, owner, "class annotation")
        collectAnnotations(kmClass.annotations, annotationSink)
        collectAsmAnnotations(record.node.visibleAnnotations, annotationSink)
        collectAsmAnnotations(record.node.invisibleAnnotations, annotationSink)
        collectAsmAnnotations(record.node.visibleTypeAnnotations, annotationSink)
        collectAsmAnnotations(record.node.invisibleTypeAnnotations, annotationSink)
        kmClass.typeParameters.forEach { collectTypeParameter(it, references.at(record, owner, "class type parameter")) }
        kmClass.contextReceiverTypes.forEach { collectType(it, references.at(record, owner, "class context receiver")) }
        kmClass.supertypes.forEach { collectType(it, references.at(record, owner, "supertype")) }
        kmClass.sealedSubclasses.forEach {
            references.at(record, owner, "sealed subclass").metadataName(it)
        }
        kmClass.inlineClassUnderlyingType?.let {
            collectType(it, references.at(record, owner, "inline class underlying type"))
        }
        kmClass.kmEnumEntries.forEach { entry ->
            collectAnnotations(entry.annotations, references.at(record, owner, "enum entry ${entry.name}"))
        }
        collectContainer(kmClass, record, owner, owner, references, aliases, inlineClosures)

        kmClass.constructors.filter { it.visibility.isPublicApi() }.forEach { constructor ->
            val signature = constructor.signature
            val location = "constructor ${signature?.descriptor.orEmpty()}"
            val sink = references.at(record, owner, location)
            constructor.valueParameters.forEach { collectValueParameter(it, sink) }
            collectAnnotations(constructor.annotations, sink)
            signature?.takeUnless { kmClass.kind == ClassKind.ANNOTATION_CLASS }?.let { jvmSignature ->
                val method = record.node.requireUniqueMethod(
                    jvmSignature.name,
                    jvmSignature.descriptor,
                    record.origin,
                    location,
                )
                method.requirePublicApiVisibility(record.origin, location)
                collectMethodSurface(method, sink, includeBody = false)
            }
        }
    }

    private fun collectContainer(
        container: KmDeclarationContainer,
        record: ParsedClass,
        owner: String,
        aliasPrefix: String,
        references: ReferenceCollector,
        aliases: MutableList<TypeAliasDeclaration>,
        inlineClosures: InlineClosureIndex,
    ) {
        container.functions.filter { it.visibility.isPublicApi() }.forEach { function ->
            collectFunction(function, record, owner, references, inlineClosures)
        }
        container.properties.filter { it.visibility.isPublicApi() }.forEach { property ->
            collectProperty(property, record, owner, references, inlineClosures)
        }
        container.typeAliases.filter { it.visibility.isPublicApi() }.forEach { alias ->
            val aliasName = "$aliasPrefix.${alias.name}".trimStart('.')
            aliases += TypeAliasDeclaration(aliasName, record.origin)
            collectTypeAlias(alias, references.at(record, owner, "typealias $aliasName"))
        }
    }

    private fun collectFunction(
        function: KmFunction,
        record: ParsedClass,
        owner: String,
        references: ReferenceCollector,
        inlineClosures: InlineClosureIndex,
    ) {
        val signature = function.signature
        val location = "function ${function.name}${signature?.descriptor.orEmpty()}"
        val sink = references.at(record, owner, location)
        function.typeParameters.forEach { collectTypeParameter(it, sink) }
        function.receiverParameterType?.let { collectType(it, sink) }
        function.contextReceiverTypes.forEach { collectType(it, sink) }
        function.contextParameters.forEach { collectValueParameter(it, sink) }
        function.valueParameters.forEach { collectValueParameter(it, sink) }
        collectType(function.returnType, sink)
        collectAnnotations(function.annotations, sink)
        collectAnnotations(function.extensionReceiverParameterAnnotations, sink)
        function.contract?.effects.orEmpty().forEach { effect ->
            effect.constructorArguments.forEach { collectEffectExpression(it, sink) }
            effect.conclusion?.let { collectEffectExpression(it, sink) }
        }

        if (signature == null) {
            require(!function.isInline) {
                "Public inline Kotlin function '${function.name}' has no JVM signature in ${record.origin.describe()}"
            }
            return
        }

        val mainMethod = record.node.requireUniqueMethod(
            signature.name,
            signature.descriptor,
            record.origin,
            location,
        )
        mainMethod.requirePublicApiVisibility(record.origin, location)
        collectMethodSurface(mainMethod, sink, includeBody = false)
        if (function.isInline) {
            mainMethod.requireConcreteInlineBody(record.origin, location)
            val roots = buildList {
                add(mainMethod)
                if (function.valueParameters.any { it.declaresDefaultValue }) {
                    val maskCount = (function.valueParameters.size + DEFAULT_MASK_BITS - 1) / DEFAULT_MASK_BITS
                    val defaultMethods = findDefaultMethods(record.node, mainMethod, maskCount)
                    require(defaultMethods.size == 1) {
                        "Expected exactly one JVM default bridge for $location in ${record.origin.describe()}, " +
                            "found ${defaultMethods.size}"
                    }
                    val defaultMethod = defaultMethods.single()
                    defaultMethod.requirePublicApiVisibility(record.origin, "$location default bridge")
                    defaultMethod.requireConcreteInlineBody(record.origin, "$location default bridge")
                    add(defaultMethod)
                }
            }
            collectInlineClosure(record, roots, sink, inlineClosures)
        }
    }

    private fun findDefaultMethods(
        owner: ClassNode,
        mainMethod: MethodNode,
        maskCount: Int,
    ): List<MethodNode> = owner.methods
        .filter { candidate ->
            candidate.name == "${mainMethod.name}\$default" &&
                    candidate.isDefaultBridgeFor(mainMethod, owner.name, maskCount)
        }

    private fun MethodNode.isDefaultBridgeFor(
        mainMethod: MethodNode,
        ownerInternalName: String,
        maskCount: Int,
    ): Boolean {
        val requiredAccess = Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC
        if (access and requiredAccess != requiredAccess) return false
        val mainType = Type.getMethodType(mainMethod.desc)
        val bridgeType = Type.getMethodType(desc)
        if (bridgeType.returnType != mainType.returnType) return false

        val expectedPrefix = buildList {
            if (mainMethod.access and Opcodes.ACC_STATIC == 0) add(Type.getObjectType(ownerInternalName))
            addAll(mainType.argumentTypes)
        }
        val bridgeArguments = bridgeType.argumentTypes.toList()
        if (bridgeArguments.take(expectedPrefix.size) != expectedPrefix) return false

        val expectedSuffix = buildList {
            repeat(maskCount) { add(Type.INT_TYPE) }
            add(Type.getObjectType("java/lang/Object"))
        }
        return bridgeArguments.drop(expectedPrefix.size) == expectedSuffix
    }

    private fun collectProperty(
        property: KmProperty,
        record: ParsedClass,
        owner: String,
        references: ReferenceCollector,
        inlineClosures: InlineClosureIndex,
    ) {
        val location = "property ${property.name}"
        val sink = references.at(record, owner, location)
        val field = property.fieldSignature?.let { signature ->
            record.node.fields.firstOrNull { it.name == signature.name && it.desc == signature.descriptor }
        }
        val getter = property.getterSignature?.let { signature ->
            record.node.requireUniqueMethod(
                signature.name,
                signature.descriptor,
                record.origin,
                "property ${property.name} getter",
            )
        }
        val setter = property.setterSignature?.let { signature ->
            record.node.requireUniqueMethod(
                signature.name,
                signature.descriptor,
                record.origin,
                "property ${property.name} setter",
            )
        }
        val getterIsMetadataSurface = property.getter.visibility.isPublicApi()
        val setterIsMetadataSurface = property.setter?.visibility?.isPublicApi() == true
        if (getterIsMetadataSurface) {
            getter?.requirePublicApiVisibility(record.origin, "property ${property.name} getter")
        }
        if (setterIsMetadataSurface) {
            setter?.requirePublicApiVisibility(record.origin, "property ${property.name} setter")
        }
        val getterSurface = getter?.takeIf { getterIsMetadataSurface && it.access.isPublicApi() }
        val setterSurface = setter?.takeIf { setterIsMetadataSurface && it.access.isPublicApi() }
        val inlineAccessorMethods = buildList {
            if (property.getter.isInline) {
                val method = requireNotNull(getterSurface) {
                    "Inline getter for property '${property.name}' has no public JVM method in ${record.origin.describe()}"
                }
                method.requireConcreteInlineBody(record.origin, "property ${property.name} getter")
                add(method)
            }
            if (property.setter?.isInline == true) {
                val method = requireNotNull(setterSurface) {
                    "Inline setter for property '${property.name}' has no public JVM method in ${record.origin.describe()}"
                }
                method.requireConcreteInlineBody(record.origin, "property ${property.name} setter")
                add(method)
            }
        }
        val fieldIsInlineAbi = field?.hasAnnotation(PUBLISHED_API_DESCRIPTOR) == true &&
                inlineAccessorMethods.any { it.referencesField(record.node.name, field) }
        val fieldSurface = field?.takeIf { it.access.isPublicApi() || fieldIsInlineAbi }

        property.typeParameters.forEach { collectTypeParameter(it, sink) }
        property.receiverParameterType?.let { collectType(it, sink) }
        property.contextReceiverTypes.forEach { collectType(it, sink) }
        property.contextParameters.forEach { collectValueParameter(it, sink) }
        collectType(property.returnType, sink)
        if (setterSurface != null) property.setterParameter?.let { collectValueParameter(it, sink) }
        collectAnnotations(property.annotations, sink)
        if (fieldSurface != null) {
            collectAnnotations(property.backingFieldAnnotations, sink)
            collectAnnotations(property.delegateFieldAnnotations, sink)
        }
        collectAnnotations(property.extensionReceiverParameterAnnotations, sink)
        if (getterSurface != null) collectAnnotations(property.getter.annotations, sink)
        if (setterSurface != null) property.setter?.let { collectAnnotations(it.annotations, sink) }

        fieldSurface?.let { collectFieldSurface(it, sink) }
        getterSurface?.let { publicGetter ->
            collectMethodSurface(publicGetter, sink, includeBody = false)
            if (property.getter.isInline) {
                collectInlineClosure(record, listOf(publicGetter), sink, inlineClosures)
            }
        }
        setterSurface?.let { publicSetter ->
            collectMethodSurface(publicSetter, sink, includeBody = false)
            if (property.setter?.isInline == true) {
                collectInlineClosure(record, listOf(publicSetter), sink, inlineClosures)
            }
        }
    }

    private fun MethodNode.referencesField(ownerInternalName: String, field: FieldNode): Boolean =
        instructions.any { instruction ->
            instruction is FieldInsnNode && instruction.owner == ownerInternalName &&
                    instruction.name == field.name && instruction.desc == field.desc
        }

    private fun collectTypeAlias(alias: KmTypeAlias, sink: ReferenceSink) {
        alias.typeParameters.forEach { collectTypeParameter(it, sink) }
        collectType(alias.underlyingType, sink)
        collectType(alias.expandedType, sink)
        collectAnnotations(alias.annotations, sink)
    }

    private fun collectTypeParameter(parameter: KmTypeParameter, sink: ReferenceSink) {
        parameter.upperBounds.forEach { collectType(it, sink) }
        collectAnnotations(parameter.annotations, sink)
    }

    private fun collectValueParameter(parameter: KmValueParameter, sink: ReferenceSink) {
        collectType(parameter.type, sink)
        parameter.varargElementType?.let { collectType(it, sink) }
        parameter.annotationParameterDefaultValue?.let { collectAnnotationArgument(it, sink) }
        collectAnnotations(parameter.annotations, sink)
    }

    private fun collectEffectExpression(expression: KmEffectExpression, sink: ReferenceSink) {
        expression.isInstanceType?.let { collectType(it, sink) }
        expression.andArguments.forEach { collectEffectExpression(it, sink) }
        expression.orArguments.forEach { collectEffectExpression(it, sink) }
    }

    private fun collectType(type: KmType, sink: ReferenceSink) {
        when (val classifier = type.classifier) {
            is KmClassifier.Class -> sink.metadataName(classifier.name)
            is KmClassifier.TypeAlias -> sink.metadataName(classifier.name)
            is KmClassifier.TypeParameter -> Unit
        }
        type.arguments.mapNotNull { it.type }.forEach { collectType(it, sink) }
        type.abbreviatedType?.let { collectType(it, sink) }
        type.outerType?.let { collectType(it, sink) }
        type.flexibleTypeUpperBound?.type?.let { collectType(it, sink) }
        collectAnnotations(type.annotations, sink)
    }

    private fun collectAnnotations(annotations: Iterable<KmAnnotation>, sink: ReferenceSink) {
        annotations.forEach { annotation ->
            sink.metadataName(annotation.className)
            annotation.arguments.values.forEach { collectAnnotationArgument(it, sink) }
        }
    }

    private fun collectAnnotationArgument(argument: KmAnnotationArgument, sink: ReferenceSink) {
        when (argument) {
            is KmAnnotationArgument.KClassValue -> sink.metadataName(argument.className)
            is KmAnnotationArgument.ArrayKClassValue -> sink.metadataName(argument.className)
            is KmAnnotationArgument.EnumValue -> sink.metadataName(argument.enumClassName)
            is KmAnnotationArgument.AnnotationValue -> collectAnnotations(listOf(argument.annotation), sink)
            is KmAnnotationArgument.ArrayValue -> argument.elements.forEach { collectAnnotationArgument(it, sink) }
            else -> Unit
        }
    }

    private fun collectJavaClass(record: ParsedClass, owner: String, references: ReferenceCollector) {
        val classSink = references.at(record, owner, "class signature")
        record.node.superName?.let(classSink::jvmName)
        record.node.interfaces.forEach(classSink::jvmName)
        record.node.permittedSubclasses.orEmpty().forEach(classSink::jvmName)
        record.node.signature?.let(classSink::signature)
        collectAsmAnnotations(record.node.visibleAnnotations, classSink)
        collectAsmAnnotations(record.node.invisibleAnnotations, classSink)
        collectAsmAnnotations(record.node.visibleTypeAnnotations, classSink)
        collectAsmAnnotations(record.node.invisibleTypeAnnotations, classSink)

        collectJvmBinaryMembers(record, owner, references)
    }

    private fun collectJvmBinaryMembers(record: ParsedClass, owner: String, references: ReferenceCollector) {
        record.node.fields.filter { it.access.isPublicApi() }.forEach { field ->
            collectFieldSurface(field, references.at(record, owner, "JVM field ${field.name}"))
        }
        record.node.methods.filter { it.access.isPublicApi() }.forEach { method ->
            collectMethodSurface(
                method,
                references.at(record, owner, "JVM method ${method.name}${method.desc}"),
                includeBody = false,
            )
        }
        record.node.recordComponents.orEmpty().forEach { component ->
            collectRecordComponent(component, references.at(record, owner, "JVM record component ${component.name}"))
        }
    }

    private fun ClassNode.requireUniqueMethod(
        name: String,
        descriptor: String,
        origin: SurfaceOrigin,
        declaration: String,
    ): MethodNode {
        val matches = methods.filter { it.name == name && it.desc == descriptor }
        require(matches.size == 1) {
            "Expected exactly one JVM method $name$descriptor for $declaration in ${origin.describe()}, " +
                "found ${matches.size}"
        }
        return matches.single()
    }

    private fun MethodNode.requirePublicApiVisibility(origin: SurfaceOrigin, declaration: String) {
        require(access.isPublicApi()) {
            "JVM method $name$desc for $declaration is not public/protected in ${origin.describe()}"
        }
    }

    private fun MethodNode.requireConcreteInlineBody(origin: SurfaceOrigin, declaration: String) {
        val unsupportedAccess = Opcodes.ACC_ABSTRACT or Opcodes.ACC_NATIVE
        require(access and unsupportedAccess == 0 && instructions.size() > 0) {
            "Inline JVM method $name$desc for $declaration has no concrete body in ${origin.describe()}"
        }
    }

    private fun SurfaceOrigin.describe(): String = "$moduleName:$entryName"

    private fun collectFacadeAnnotations(record: ParsedClass, owner: String, references: ReferenceCollector) {
        val sink = references.at(record, owner, "file annotation")
        collectAsmAnnotations(record.node.visibleAnnotations, sink)
        collectAsmAnnotations(record.node.invisibleAnnotations, sink)
        collectAsmAnnotations(record.node.visibleTypeAnnotations, sink)
        collectAsmAnnotations(record.node.invisibleTypeAnnotations, sink)
    }

    private fun collectFieldSurface(field: FieldNode, sink: ReferenceSink) {
        sink.descriptor(field.desc)
        field.signature?.let(sink::signature)
        collectAsmAnnotations(field.visibleAnnotations, sink)
        collectAsmAnnotations(field.invisibleAnnotations, sink)
        collectAsmAnnotations(field.visibleTypeAnnotations, sink)
        collectAsmAnnotations(field.invisibleTypeAnnotations, sink)
        collectAsmValue(field.value, sink)
    }

    private fun collectMethodSurface(method: MethodNode, sink: ReferenceSink, includeBody: Boolean) {
        sink.methodDescriptor(method.desc)
        method.signature?.let(sink::signature)
        method.exceptions.orEmpty().forEach(sink::jvmName)
        collectAsmAnnotations(method.visibleAnnotations, sink)
        collectAsmAnnotations(method.invisibleAnnotations, sink)
        collectAsmAnnotations(method.visibleTypeAnnotations, sink)
        collectAsmAnnotations(method.invisibleTypeAnnotations, sink)
        method.visibleParameterAnnotations.orEmpty().filterNotNull().forEach { collectAsmAnnotations(it, sink) }
        method.invisibleParameterAnnotations.orEmpty().filterNotNull().forEach { collectAsmAnnotations(it, sink) }
        collectAsmValue(method.annotationDefault, sink)
        if (includeBody) collectInlineBody(method, sink)
    }

    private fun collectRecordComponent(component: RecordComponentNode, sink: ReferenceSink) {
        sink.descriptor(component.descriptor)
        component.signature?.let(sink::signature)
        collectAsmAnnotations(component.visibleAnnotations, sink)
        collectAsmAnnotations(component.invisibleAnnotations, sink)
        collectAsmAnnotations(component.visibleTypeAnnotations, sink)
        collectAsmAnnotations(component.invisibleTypeAnnotations, sink)
    }

    private fun collectInlineBody(method: MethodNode, sink: ReferenceSink) {
        method.instructions?.forEach { instruction -> collectInstruction(instruction, sink) }
        method.tryCatchBlocks.orEmpty().forEach { block ->
            block.type?.let(sink::jvmName)
            collectAsmAnnotations(block.visibleTypeAnnotations, sink)
            collectAsmAnnotations(block.invisibleTypeAnnotations, sink)
        }
        method.localVariables.orEmpty().forEach { local ->
            sink.descriptor(local.desc)
            local.signature?.let(sink::signature)
        }
        collectAsmAnnotations(method.visibleLocalVariableAnnotations, sink)
        collectAsmAnnotations(method.invisibleLocalVariableAnnotations, sink)
    }

    private fun collectInlineClosure(
        record: ParsedClass,
        roots: Iterable<MethodNode>,
        sink: ReferenceSink,
        inlineClosures: InlineClosureIndex,
    ) {
        val queue = ArrayDeque<InlineMethod>()
        roots.forEach { queue.addLast(InlineMethod(record, it)) }
        val visited = mutableSetOf<JvmOwnedMethodReference>()
        val surfacedCarriers = mutableSetOf<Pair<SurfaceOrigin, String>>()

        while (queue.isNotEmpty()) {
            val (currentRecord, method) = queue.removeFirst()
            val identity = JvmOwnedMethodReference(
                currentRecord.origin.moduleName,
                currentRecord.node.name,
                method.name,
                method.desc,
            )
            if (!visited.add(identity)) continue
            collectInlineBody(method, sink)
            collectReferencedPublishedApiFields(currentRecord, method, sink)
            inlineClosures.referencedCarriers(currentRecord, method).forEach { carrier ->
                if (surfacedCarriers.add(carrier.origin to carrier.node.name)) {
                    collectInlineCarrierSurface(carrier, sink)
                }
                carrier.node.methods.forEach { queue.addLast(InlineMethod(carrier, it)) }
            }
        }
    }

    private fun collectReferencedPublishedApiFields(
        record: ParsedClass,
        method: MethodNode,
        sink: ReferenceSink,
    ) {
        val fields = record.node.fields.associateBy { it.name to it.desc }
        method.instructions.asSequence()
            .filterIsInstance<FieldInsnNode>()
            .filter { it.owner == record.node.name }
            .mapNotNull { fields[it.name to it.desc] }
            .filter { it.hasAnnotation(PUBLISHED_API_DESCRIPTOR) }
            .distinctBy { it.name to it.desc }
            .forEach { collectFieldSurface(it, sink) }
    }

    private fun collectInlineCarrierSurface(record: ParsedClass, sink: ReferenceSink) {
        val node = record.node
        node.superName?.let(sink::jvmName)
        node.interfaces.forEach(sink::jvmName)
        node.signature?.let(sink::signature)
        node.permittedSubclasses.orEmpty().forEach(sink::jvmName)
        collectAsmAnnotations(node.visibleAnnotations, sink)
        collectAsmAnnotations(node.invisibleAnnotations, sink)
        collectAsmAnnotations(node.visibleTypeAnnotations, sink)
        collectAsmAnnotations(node.invisibleTypeAnnotations, sink)
        node.fields.forEach { collectFieldSurface(it, sink) }
        node.methods.forEach { collectMethodSurface(it, sink, includeBody = false) }
        node.recordComponents.orEmpty().forEach { collectRecordComponent(it, sink) }
    }

    private fun MethodNode.referencedOwnerNames(): Set<String> = buildSet {
        instructions?.forEach { instruction ->
            when (instruction) {
                is TypeInsnNode -> add(instruction.desc)
                is FieldInsnNode -> add(instruction.owner)
                is MethodInsnNode -> add(instruction.owner)
                is InvokeDynamicInsnNode -> {
                    addReferencedOwners(instruction.bsm)
                    instruction.bsmArgs.forEach { addReferencedOwners(it) }
                }

                is LdcInsnNode -> addReferencedOwners(instruction.cst)
            }
        }
    }

    private fun MutableSet<String>.addReferencedOwners(value: Any?) {
        when (value) {
            is Type -> when (value.sort) {
                Type.ARRAY -> addReferencedOwners(value.elementType)
                Type.OBJECT -> add(value.internalName)
                Type.METHOD -> {
                    value.argumentTypes.forEach { addReferencedOwners(it) }
                    addReferencedOwners(value.returnType)
                }
            }

            is Handle -> {
                add(value.owner)
                addReferencedOwners(Type.getType(value.desc))
            }

            is ConstantDynamic -> {
                addReferencedOwners(Type.getType(value.descriptor))
                addReferencedOwners(value.bootstrapMethod)
                repeat(value.bootstrapMethodArgumentCount) { index ->
                    addReferencedOwners(value.getBootstrapMethodArgument(index))
                }
            }

            is Iterable<*> -> value.forEach { addReferencedOwners(it) }
            is Array<*> -> value.forEach { addReferencedOwners(it) }
        }
    }

    private fun collectInstruction(instruction: AbstractInsnNode, sink: ReferenceSink) {
        when (instruction) {
            is TypeInsnNode -> sink.jvmName(instruction.desc)
            is FieldInsnNode -> {
                sink.jvmName(instruction.owner)
                sink.descriptor(instruction.desc)
            }
            is MethodInsnNode -> {
                sink.jvmName(instruction.owner)
                sink.methodDescriptor(instruction.desc)
            }
            is InvokeDynamicInsnNode -> {
                sink.methodDescriptor(instruction.desc)
                collectHandle(instruction.bsm, sink)
                instruction.bsmArgs.forEach { collectAsmValue(it, sink) }
            }
            is LdcInsnNode -> collectAsmValue(instruction.cst, sink)
            is MultiANewArrayInsnNode -> sink.descriptor(instruction.desc)
            is FrameNode -> (instruction.local.orEmpty() + instruction.stack.orEmpty())
                .filterIsInstance<String>()
                .forEach(sink::jvmName)
        }
        collectAsmAnnotations(instruction.visibleTypeAnnotations, sink)
        collectAsmAnnotations(instruction.invisibleTypeAnnotations, sink)
    }

    private fun collectAsmAnnotations(annotations: Iterable<AnnotationNode>?, sink: ReferenceSink) {
        annotations?.forEach { annotation ->
            sink.descriptor(annotation.desc)
            annotation.values.orEmpty().chunked(2).forEach { pair ->
                if (pair.size == 2) collectAsmValue(pair[1], sink)
            }
        }
    }

    private fun collectAsmValue(value: Any?, sink: ReferenceSink) {
        when (value) {
            is Type -> sink.type(value)
            is Handle -> collectHandle(value, sink)
            is ConstantDynamic -> {
                sink.descriptor(value.descriptor)
                collectHandle(value.bootstrapMethod, sink)
                repeat(value.bootstrapMethodArgumentCount) { index ->
                    collectAsmValue(value.getBootstrapMethodArgument(index), sink)
                }
            }
            is AnnotationNode -> collectAsmAnnotations(listOf(value), sink)
            is List<*> -> value.forEach { collectAsmValue(it, sink) }
            is Array<*> -> {
                (value.firstOrNull() as? String)
                    ?.takeIf { it.startsWith('L') && it.endsWith(';') }
                    ?.let(sink::descriptor)
                value.forEach { collectAsmValue(it, sink) }
            }
        }
    }

    private fun collectHandle(handle: Handle, sink: ReferenceSink) {
        sink.jvmName(handle.owner)
        if (handle.desc.startsWith("(")) sink.methodDescriptor(handle.desc) else sink.descriptor(handle.desc)
    }

    private fun collectPublishedApi(
        record: ParsedClass,
        names: ClassNameResolver,
        destination: MutableList<PublishedApiDeclaration>,
    ) {
        val owner = names.fqName(record)
        if (record.node.hasAnnotation(PUBLISHED_API_DESCRIPTOR)) {
            destination += PublishedApiDeclaration(owner, "class", record.origin)
        }
        record.node.fields.filter { it.hasAnnotation(PUBLISHED_API_DESCRIPTOR) }.forEach { field ->
            destination += PublishedApiDeclaration(owner, "field ${field.name}:${field.desc}", record.origin)
        }
        record.node.methods.filter { it.hasAnnotation(PUBLISHED_API_DESCRIPTOR) }.forEach { method ->
            destination += PublishedApiDeclaration(owner, "method ${method.name}${method.desc}", record.origin)
        }
    }

    private fun isReachable(
        record: ParsedClass,
        lookup: Map<Pair<String, String>, List<ParsedClass>>,
        visiting: MutableSet<Pair<String, String>>,
    ): Boolean {
        val metadata = record.metadata
        val selfVisible = when (metadata) {
            is KotlinClassMetadata.Class ->
                metadata.kmClass.visibility.isPublicApi() && record.declarationAccess.isPublicApi()
            null -> record.declarationAccess.isPublicApi()
            else -> false
        }
        if (!selfVisible) return false

        val outerName = record.outerInternalName ?: return true
        val key = record.origin.moduleName to outerName
        if (!visiting.add(key)) return false
        val outer = lookup[key]?.firstOrNull() ?: return false
        return isReachable(outer, lookup, visiting)
    }

    private fun Visibility.isPublicApi(): Boolean = this == Visibility.PUBLIC || this == Visibility.PROTECTED

    private fun Int.isPublicApi(): Boolean = this and (Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED) != 0

    private fun KmClass.surfaceKind(): SurfaceClassKind = when (kind) {
        ClassKind.CLASS -> SurfaceClassKind.KotlinClass
        ClassKind.INTERFACE -> SurfaceClassKind.KotlinInterface
        ClassKind.ENUM_CLASS, ClassKind.ENUM_ENTRY -> SurfaceClassKind.KotlinEnum
        ClassKind.ANNOTATION_CLASS -> SurfaceClassKind.KotlinAnnotation
        ClassKind.OBJECT, ClassKind.COMPANION_OBJECT -> SurfaceClassKind.KotlinObject
    }

    private fun ClassNode.javaSurfaceKind(): SurfaceClassKind = when {
        access and Opcodes.ACC_ANNOTATION != 0 -> SurfaceClassKind.JavaAnnotation
        access and Opcodes.ACC_RECORD != 0 -> SurfaceClassKind.JavaRecord
        access and Opcodes.ACC_ENUM != 0 -> SurfaceClassKind.JavaEnum
        access and Opcodes.ACC_INTERFACE != 0 -> SurfaceClassKind.JavaInterface
        else -> SurfaceClassKind.JavaClass
    }

    private fun normalizeMetadataName(name: String): String = name.removePrefix(".").replace('/', '.')

    private fun packageName(facadeFqName: String): String = facadeFqName.substringBeforeLast('.', "")

    private fun ClassNode.findAnnotation(descriptor: String): AnnotationNode? =
        (visibleAnnotations.orEmpty() + invisibleAnnotations.orEmpty()).firstOrNull { it.desc == descriptor }

    private fun ClassNode.hasAnnotation(descriptor: String): Boolean = findAnnotation(descriptor) != null

    private fun FieldNode.hasAnnotation(descriptor: String): Boolean =
        (visibleAnnotations.orEmpty() + invisibleAnnotations.orEmpty()).any { it.desc == descriptor }

    private fun MethodNode.hasAnnotation(descriptor: String): Boolean =
        (visibleAnnotations.orEmpty() + invisibleAnnotations.orEmpty()).any { it.desc == descriptor }

    private fun AnnotationNode.strictMetadataValues(origin: SurfaceOrigin): Map<String, Any> {
        val rawValues = values.orEmpty()
        if (rawValues.size % 2 != 0) invalidMetadataShape(origin, "odd element/value count")
        return buildMap {
            rawValues.chunked(2).forEach { pair ->
                val name = pair[0] as? String
                    ?: invalidMetadataShape(origin, "non-string element name")
                if (name !in KOTLIN_METADATA_ELEMENTS) {
                    invalidMetadataShape(origin, "unknown element '$name'")
                }
                if (containsKey(name)) invalidMetadataShape(origin, "duplicate element '$name'")
                val value = pair[1] ?: invalidMetadataShape(origin, "null value for '$name'")
                put(name, value)
            }
        }
    }

    private fun Map<String, Any>.metadataInt(name: String, origin: SurfaceOrigin): Int? {
        val value = this[name] ?: return null
        return value as? Int ?: invalidMetadataShape(origin, "element '$name' is not an integer")
    }

    private fun Map<String, Any>.metadataString(name: String, origin: SurfaceOrigin): String? {
        val value = this[name] ?: return null
        return value as? String ?: invalidMetadataShape(origin, "element '$name' is not a string")
    }

    private fun Map<String, Any>.metadataIntList(name: String, origin: SurfaceOrigin): List<Int>? {
        val value = this[name] ?: return null
        return when (value) {
            is IntArray -> value.toList()
            is List<*> -> value.mapIndexed { index, element ->
                element as? Int
                    ?: invalidMetadataShape(origin, "element '$name' has a non-integer item at index $index")
            }
            else -> invalidMetadataShape(origin, "element '$name' is not an integer array")
        }
    }

    private fun Map<String, Any>.metadataStringList(name: String, origin: SurfaceOrigin): List<String>? {
        val value = this[name] ?: return null
        return when (value) {
            is Array<*> -> value.mapIndexed { index, element ->
                element as? String
                    ?: invalidMetadataShape(origin, "element '$name' has a non-string item at index $index")
            }
            is List<*> -> value.mapIndexed { index, element ->
                element as? String
                    ?: invalidMetadataShape(origin, "element '$name' has a non-string item at index $index")
            }
            else -> invalidMetadataShape(origin, "element '$name' is not a string array")
        }
    }

    private fun invalidMetadataShape(origin: SurfaceOrigin, detail: String): Nothing =
        throw UnsupportedKotlinMetadataException(
            "Corrupt Kotlin metadata annotation in ${origin.moduleName}:${origin.entryName}: $detail",
        )

    private data class ParsedClass(
        val origin: SurfaceOrigin,
        val node: ClassNode,
        val metadata: KotlinClassMetadata?,
    ) {
        val outerInternalName: String?
            get() = node.innerClasses.firstOrNull { it.name == node.name }?.outerName

        val declarationAccess: Int
            get() = node.innerClasses.firstOrNull { it.name == node.name }?.access ?: node.access
    }

    private data class JvmOwnedMethodReference(
        val moduleName: String,
        val ownerInternalName: String,
        val name: String,
        val descriptor: String,
    )

    private data class InlineMethod(
        val record: ParsedClass,
        val method: MethodNode,
    )

    private data class EnclosingMethodReference(
        val moduleName: String,
        val ownerInternalName: String,
        val name: String,
        val descriptor: String,
    )

    private class InlineClosureIndex(records: Iterable<ParsedClass>) {
        private val carriersByEnclosingMethod: Map<EnclosingMethodReference, List<ParsedClass>> = records
            .mapNotNull { record ->
                if (!record.isKotlinInlineCarrier()) return@mapNotNull null
                val outerClass = record.node.outerClass ?: return@mapNotNull null
                val outerMethod = record.node.outerMethod ?: return@mapNotNull null
                val outerMethodDescriptor = record.node.outerMethodDesc ?: return@mapNotNull null
                EnclosingMethodReference(
                    record.origin.moduleName,
                    outerClass,
                    outerMethod,
                    outerMethodDescriptor,
                ) to record
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, carriers) ->
                carriers.sortedWith(compareBy({ it.node.name }, { it.origin }))
            }

        fun referencedCarriers(record: ParsedClass, method: MethodNode): List<ParsedClass> {
            val key = EnclosingMethodReference(
                record.origin.moduleName,
                record.node.name,
                method.name,
                method.desc,
            )
            val referencedOwners = method.referencedOwnerNames()
            return carriersByEnclosingMethod[key].orEmpty().filter { it.node.name in referencedOwners }
        }
    }

    private fun ParsedClass.isKotlinInlineCarrier(): Boolean = when (val kotlinMetadata = metadata) {
        is KotlinClassMetadata.SyntheticClass -> true
        is KotlinClassMetadata.Class -> {
            val selfEntry = node.innerClasses.firstOrNull { it.name == node.name }
            kotlinMetadata.kmClass.anonymousObjectOriginName != null ||
                    selfEntry != null && selfEntry.innerName == null
        }
        else -> false
    }

    private class ClassNameResolver(
        private val lookup: Map<Pair<String, String>, List<ParsedClass>>,
    ) {
        fun fqName(record: ParsedClass): String = fqName(record, mutableSetOf())

        fun jvmName(internalName: String): String {
            val known = lookup.entries.firstOrNull { it.key.second == internalName }?.value?.firstOrNull()
            return known?.let(::fqName) ?: internalName.replace('/', '.').replace('$', '.')
        }

        private fun fqName(record: ParsedClass, visiting: MutableSet<Pair<String, String>>): String {
            val key = record.origin.moduleName to record.node.name
            if (!visiting.add(key)) return record.node.name.replace('/', '.')
            val selfEntry = record.node.innerClasses.firstOrNull { it.name == record.node.name }
            val outerName = selfEntry?.outerName ?: return record.node.name.replace('/', '.')
            val outer = lookup[record.origin.moduleName to outerName]?.firstOrNull()
                ?: return record.node.name.replace('/', '.').replace('$', '.')
            val simpleName = selfEntry.innerName ?: record.node.name.substringAfterLast('$')
            return "${fqName(outer, visiting)}.$simpleName"
        }
    }

    private class ReferenceCollector(private val names: ClassNameResolver) {
        private val metadataReferences = linkedMapOf<String, MutableSet<ClassifierReferenceOrigin>>()
        private val jvmReferences = linkedMapOf<String, MutableSet<ClassifierReferenceOrigin>>()

        fun at(record: ParsedClass, owner: String, location: String): ReferenceSink = ReferenceSink(
            names = names,
            origin = ClassifierReferenceOrigin(owner, location, record.origin),
            addMetadata = { classifier, origin ->
                metadataReferences.getOrPut(classifier) { linkedSetOf() } += origin
            },
            addJvm = { classifier, origin ->
                jvmReferences.getOrPut(classifier) { linkedSetOf() } += origin
            },
        )

        fun metadataSnapshot(): Map<String, List<ClassifierReferenceOrigin>> = metadataReferences
            .toSortedMap()
            .mapValuesTo(linkedMapOf()) { (_, origins) -> origins.sorted() }

        fun jvmSnapshot(): Map<String, List<ClassifierReferenceOrigin>> = jvmReferences
            .toSortedMap()
            .mapValuesTo(linkedMapOf()) { (_, origins) -> origins.sorted() }
    }

    private class ReferenceSink(
        private val names: ClassNameResolver,
        private val origin: ClassifierReferenceOrigin,
        private val addMetadata: (String, ClassifierReferenceOrigin) -> Unit,
        private val addJvm: (String, ClassifierReferenceOrigin) -> Unit,
    ) {
        fun metadataName(name: String) {
            val normalized = normalizeMetadataName(name)
            if (normalized.isNotEmpty() && !name.startsWith('.')) addMetadata(normalized, origin)
        }

        fun jvmName(internalName: String) {
            if (internalName.isNotEmpty() && internalName[0] != '[') {
                val binaryName = internalName.replace('/', '.')
                addJvm(binaryName, origin.copy(sourceDisplayName = names.jvmName(internalName)))
            } else {
                descriptor(internalName)
            }
        }

        fun descriptor(descriptor: String) {
            if (descriptor.isEmpty()) return
            type(Type.getType(descriptor))
        }

        fun methodDescriptor(descriptor: String) {
            val type = Type.getMethodType(descriptor)
            type.argumentTypes.forEach(::type)
            type(type.returnType)
        }

        fun type(type: Type) {
            when (type.sort) {
                Type.ARRAY -> type(type.elementType)
                Type.OBJECT -> jvmName(type.internalName)
                Type.METHOD -> {
                    type.argumentTypes.forEach(::type)
                    type(type.returnType)
                }
            }
        }

        fun signature(signature: String) {
            SignatureReader(signature).accept(classifierSignatureVisitor())
        }

        private fun classifierSignatureVisitor(): SignatureVisitor = object : SignatureVisitor(Opcodes.ASM9) {
            private var currentClassName: String? = null

            override fun visitClassBound(): SignatureVisitor = classifierSignatureVisitor()

            override fun visitInterfaceBound(): SignatureVisitor = classifierSignatureVisitor()

            override fun visitSuperclass(): SignatureVisitor = classifierSignatureVisitor()

            override fun visitInterface(): SignatureVisitor = classifierSignatureVisitor()

            override fun visitParameterType(): SignatureVisitor = classifierSignatureVisitor()

            override fun visitReturnType(): SignatureVisitor = classifierSignatureVisitor()

            override fun visitExceptionType(): SignatureVisitor = classifierSignatureVisitor()

            override fun visitArrayType(): SignatureVisitor = classifierSignatureVisitor()

            override fun visitClassType(name: String) {
                currentClassName = name
                jvmName(name)
            }

            override fun visitInnerClassType(name: String) {
                val nestedName = "${requireNotNull(currentClassName)}\$$name"
                currentClassName = nestedName
                jvmName(nestedName)
            }

            override fun visitTypeArgument(wildcard: Char): SignatureVisitor = classifierSignatureVisitor()
        }
    }

    private val reachableClassOrder = compareBy<ReachableClass>({ it.fqName }, { it.kind }, { it.origin })
    private val KOTLIN_METADATA_ELEMENTS: Set<String> = setOf("k", "mv", "d1", "d2", "xs", "pn", "xi")

    private const val KOTLIN_METADATA_DESCRIPTOR = "Lkotlin/Metadata;"
    private const val PUBLISHED_API_DESCRIPTOR = "Lkotlin/PublishedApi;"
    private const val DEFAULT_MASK_BITS = 32
    private const val MULTI_RELEASE_VERSION_PREFIX = "META-INF/versions/"
}
