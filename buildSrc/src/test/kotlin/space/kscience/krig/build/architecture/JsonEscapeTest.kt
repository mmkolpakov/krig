package space.kscience.krig.build.architecture

import kotlin.test.Test
import kotlin.test.assertEquals

class JsonEscapeTest {
    @Test
    fun escapesEveryJsonControlCharacter() {
        val controls = (0 until ' '.code).map(Int::toChar).joinToString("")
        val escaped = escapeJsonString("\\\"$controls")

        assertEquals(
            "\\\\\\\"" +
                "\\u0000\\u0001\\u0002\\u0003\\u0004\\u0005\\u0006\\u0007" +
                "\\b\\t\\n\\u000b\\f\\r\\u000e\\u000f" +
                "\\u0010\\u0011\\u0012\\u0013\\u0014\\u0015\\u0016\\u0017" +
                "\\u0018\\u0019\\u001a\\u001b\\u001c\\u001d\\u001e\\u001f",
            escaped,
        )
    }
}
