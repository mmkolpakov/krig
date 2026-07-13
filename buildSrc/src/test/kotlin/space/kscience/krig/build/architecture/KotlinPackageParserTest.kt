package space.kscience.krig.build.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KotlinPackageParserTest {
    @Test
    fun parsesPackageAfterFileAnnotationsAndNestedComments() {
        val source = """
            @file:Suppress("package in a string")
            /* outer /* package false.positive */ comment */
            package sample.`when`.model

            public class Value
        """.trimIndent()

        assertEquals("sample.when.model", KotlinPackageParser.parse(source))
    }

    @Test
    fun ignoresPackageTokensInStringsAndComments() {
        val template = "$" + "{\"package false.three\"}"
        val source = "// package false.one\n" +
            "@file:Suppress(\"\"\"package false.two\"\"\")\n" +
            "@file:Suppress(\"$template\")\n" +
            "`package`\n" +
            "package actual.value\n"

        assertEquals("actual.value", KotlinPackageParser.parse(source))
    }

    @Test
    fun returnsNullForDefaultPackage() {
        assertNull(KotlinPackageParser.parse("public class Value"))
    }
}
