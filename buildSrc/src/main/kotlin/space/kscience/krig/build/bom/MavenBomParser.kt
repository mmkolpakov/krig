package space.kscience.krig.build.bom

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.ErrorHandler
import org.xml.sax.SAXParseException

internal object MavenBomParser {
    fun parse(file: File): BomSnapshot = file.inputStream().buffered().use(::parse)

    fun parse(input: InputStream): BomSnapshot {
        val bytes = input.readNBytes(MAX_POM_BYTES + 1)
        require(bytes.size <= MAX_POM_BYTES) { "Maven POM exceeds the $MAX_POM_BYTES byte safety limit" }
        require(!bytes.toString(StandardCharsets.UTF_8).contains("<!DOCTYPE", ignoreCase = true)) {
            "Maven POM must not contain a DOCTYPE declaration"
        }

        val document = try {
            secureDocumentBuilderFactory().newDocumentBuilder().apply {
                setErrorHandler(ThrowingErrorHandler)
            }.parse(ByteArrayInputStream(bytes))
        } catch (failure: Exception) {
            throw IllegalArgumentException("Could not parse Maven POM as secure XML", failure)
        }

        val root = document.documentElement
        require(root.localTagName == "project") { "Maven POM root element must be <project>" }
        require(root.namespaceURI == MAVEN_POM_NAMESPACE) {
            "Maven POM root element must use namespace '$MAVEN_POM_NAMESPACE'"
        }
        val identity = BomIdentity(
            modelVersion = root.requiredChildText("modelVersion"),
            group = root.requiredChildText("groupId"),
            artifact = root.requiredChildText("artifactId"),
            version = root.requiredChildText("version"),
            packaging = root.requiredChildText("packaging"),
        )
        val dependencyManagement = root.directChildren("dependencyManagement")
        require(dependencyManagement.size <= 1) { "Maven POM contains multiple <dependencyManagement> elements" }
        val management = dependencyManagement.singleOrNull() ?: return BomSnapshot(identity, emptyList())
        management.requireOnlyDirectElements(setOf("dependencies"))
        val dependencies = management.directChildren("dependencies")
        require(dependencies.size <= 1) {
            "Maven POM dependencyManagement contains multiple <dependencies> elements"
        }
        val container = dependencies.singleOrNull() ?: return BomSnapshot(identity, emptyList())
        container.requireOnlyDirectElements(setOf("dependency"))

        return BomSnapshot(
            identity = identity,
            constraints = container.directChildren("dependency")
                .map { dependency ->
                    dependency.requireOnlyDirectElements(DEPENDENCY_CHILD_ELEMENTS)
                    BomConstraint(
                        group = dependency.requiredChildText("groupId"),
                        artifact = dependency.requiredChildText("artifactId"),
                        version = dependency.requiredChildText("version"),
                        type = dependency.optionalChildText("type") ?: DEFAULT_MAVEN_TYPE,
                        classifier = dependency.optionalChildText("classifier"),
                        scope = dependency.optionalChildText("scope") ?: DEFAULT_MAVEN_SCOPE,
                        optional = dependency.optionalBooleanChild("optional") ?: false,
                    )
                }
                .sorted(),
        )
    }

    private fun secureDocumentBuilderFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isXIncludeAware = false
            isExpandEntityReferences = false
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }

    private fun Element.requiredChildText(name: String): String {
        val children = directChildren(name)
        require(children.size == 1) {
            "Maven <${localTagName}> must contain exactly one <$name> element"
        }
        return children.single().requiredText(name)
    }

    private fun Element.optionalChildText(name: String): String? {
        val children = directChildren(name)
        require(children.size <= 1) {
            "Maven <${localTagName}> must contain at most one <$name> element"
        }
        return children.singleOrNull()?.requiredText(name)
    }

    private fun Element.optionalBooleanChild(name: String): Boolean? = optionalChildText(name)?.let { value ->
        when (value) {
            "true" -> true
            "false" -> false
            else -> throw IllegalArgumentException("Maven <$name> must be 'true' or 'false'")
        }
    }

    private fun Element.requiredText(name: String): String = textContent.trim().also { value ->
        require(directElementChildren().isEmpty()) {
            "Maven <$name> must contain text only"
        }
        require(value.isNotEmpty()) { "Maven <$name> value must not be blank" }
        require(value.none(Char::isWhitespace)) { "Maven <$name> value must not contain whitespace" }
    }

    private fun Element.requireOnlyDirectElements(allowedNames: Set<String>) {
        directElementChildren().forEach { child ->
            require(child.namespaceURI == MAVEN_POM_NAMESPACE) {
                "Maven <${child.localTagName}> element must use namespace '$MAVEN_POM_NAMESPACE'"
            }
            require(child.localTagName in allowedNames) {
                "Unsupported Maven <${localTagName}> child <${child.localTagName}>"
            }
        }
    }

    private fun Element.directElementChildren(): List<Element> = buildList {
        val children = childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child.nodeType == Node.ELEMENT_NODE) add(child as Element)
        }
    }

    private fun Element.directChildren(name: String): List<Element> = buildList {
        val children = childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child.nodeType == Node.ELEMENT_NODE && (child as Element).localTagName == name) {
                require(child.namespaceURI == MAVEN_POM_NAMESPACE) {
                    "Maven <$name> element must use namespace '$MAVEN_POM_NAMESPACE'"
                }
                add(child)
            }
        }
    }

    private val Element.localTagName: String
        get() = localName ?: tagName.substringAfter(':')

    private object ThrowingErrorHandler : ErrorHandler {
        override fun warning(exception: SAXParseException): Unit = throw exception
        override fun error(exception: SAXParseException): Unit = throw exception
        override fun fatalError(exception: SAXParseException): Unit = throw exception
    }

    private val DEPENDENCY_CHILD_ELEMENTS: Set<String> = setOf(
        "groupId",
        "artifactId",
        "version",
        "type",
        "classifier",
        "scope",
        "optional",
    )
    private const val MAX_POM_BYTES: Int = 1024 * 1024
}
