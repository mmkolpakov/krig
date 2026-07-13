package space.kscience.krig.build.architecture

internal object KotlinPackageParser {
    fun parse(source: String): String? {
        val scanner = Scanner(source)
        while (true) {
            val token = scanner.nextToken() ?: return null
            if (!token.escaped && token.value == "package") return scanner.readQualifiedName()
        }
    }

    private class Scanner(private val source: String) {
        private var index: Int = if (source.firstOrNull() == '\uFEFF') 1 else 0

        fun nextToken(): Token? {
            while (index < source.length) {
                skipWhitespaceAndComments()
                if (index >= source.length) return null
                when (source[index]) {
                    '"' -> skipString()
                    '\'' -> skipCharacter()
                    '`' -> return Token(readEscapedIdentifier(), escaped = true)
                    else -> {
                        if (isIdentifierStart(source[index])) return Token(readIdentifier(), escaped = false)
                        index++
                    }
                }
            }
            return null
        }

        fun readQualifiedName(): String {
            val segments = mutableListOf<String>()
            while (true) {
                skipWhitespaceAndComments()
                require(index < source.length) { "Incomplete Kotlin package directive" }
                val segment = when {
                    source[index] == '`' -> readEscapedIdentifier()
                    isIdentifierStart(source[index]) -> readIdentifier()
                    else -> error("Invalid Kotlin package directive at offset $index")
                }
                segments += segment
                skipWhitespaceAndComments()
                if (index >= source.length || source[index] != '.') break
                index++
            }
            return segments.joinToString(".")
        }

        private fun skipWhitespaceAndComments() {
            while (index < source.length) {
                when {
                    source[index].isWhitespace() -> index++
                    source.startsWith("//", index) -> {
                        index += 2
                        while (index < source.length && source[index] != '\n') index++
                    }
                    source.startsWith("/*", index) -> skipBlockComment()
                    else -> return
                }
            }
        }

        private fun skipBlockComment() {
            var depth = 0
            while (index < source.length) {
                when {
                    source.startsWith("/*", index) -> {
                        depth++
                        index += 2
                    }
                    source.startsWith("*/", index) -> {
                        depth--
                        index += 2
                        if (depth == 0) return
                    }
                    else -> index++
                }
            }
            error("Unterminated Kotlin block comment")
        }

        private fun skipString() {
            if (source.startsWith("\"\"\"", index)) {
                index += 3
                val end = source.indexOf("\"\"\"", index)
                require(end >= 0) { "Unterminated Kotlin raw string" }
                index = end + 3
                return
            }
            index++
            while (index < source.length) {
                when (source[index]) {
                    '\\' -> index += 2
                    '$' -> {
                        if (source.getOrNull(index + 1) == '{') {
                            index += 2
                            skipTemplateExpression()
                        } else {
                            index++
                        }
                    }
                    '"' -> {
                        index++
                        return
                    }
                    else -> index++
                }
            }
            error("Unterminated Kotlin string")
        }

        private fun skipTemplateExpression() {
            var depth = 1
            while (index < source.length) {
                when {
                    source.startsWith("//", index) -> {
                        index += 2
                        while (index < source.length && source[index] != '\n') index++
                    }
                    source.startsWith("/*", index) -> skipBlockComment()
                    source[index] == '"' -> skipString()
                    source[index] == '\'' -> skipCharacter()
                    source[index] == '{' -> {
                        depth++
                        index++
                    }
                    source[index] == '}' -> {
                        depth--
                        index++
                        if (depth == 0) return
                    }
                    else -> index++
                }
            }
            error("Unterminated Kotlin string template expression")
        }

        private fun skipCharacter() {
            index++
            while (index < source.length) {
                when (source[index]) {
                    '\\' -> index += 2
                    '\'' -> {
                        index++
                        return
                    }
                    else -> index++
                }
            }
            error("Unterminated Kotlin character literal")
        }

        private fun readIdentifier(): String {
            val start = index++
            while (index < source.length && isIdentifierPart(source[index])) index++
            return source.substring(start, index)
        }

        private fun readEscapedIdentifier(): String {
            val start = ++index
            while (index < source.length && source[index] != '`') index++
            require(index < source.length) { "Unterminated escaped Kotlin identifier" }
            val value = source.substring(start, index)
            index++
            require(value.isNotEmpty()) { "Empty escaped Kotlin identifier" }
            return value
        }

        private fun isIdentifierStart(char: Char): Boolean = char == '_' || char.isLetter()
        private fun isIdentifierPart(char: Char): Boolean = char == '_' || char.isLetterOrDigit()

        data class Token(val value: String, val escaped: Boolean)
    }
}
