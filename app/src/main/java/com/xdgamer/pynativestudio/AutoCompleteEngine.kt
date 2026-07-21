package com.xdgamer.pynativestudio

/** Small, offline Python completion engine. Suggestions are never inserted automatically. */
object AutoCompleteEngine {
    data class Suggestion(
        val label: String,
        val insertion: String,
        val replacePrefix: Boolean = true
    )

    private val words = listOf(
        "print", "input", "range", "len", "list", "dict", "set", "tuple",
        "str", "int", "float", "bool", "open", "enumerate", "zip", "sorted",
        "sum", "min", "max", "abs", "round", "type", "isinstance",
        "import", "from", "as", "def", "class", "return", "yield", "if",
        "elif", "else", "for", "while", "break", "continue", "pass", "try",
        "except", "finally", "with", "lambda", "True", "False", "None"
    )

    fun suggest(source: String, cursor: Int): Suggestion? {
        val safeCursor = cursor.coerceIn(0, source.length)
        val before = source.substring(0, safeCursor)
        val currentLine = before.substringAfterLast('\n')
        val trimmed = currentLine.trimStart()

        contextSuggestion(trimmed)?.let { return it }

        val prefix = before.takeLastWhile { it.isLetterOrDigit() || it == '_' }
        if (prefix.length < 2) return null

        val match = words.firstOrNull {
            it.startsWith(prefix, ignoreCase = true) && !it.equals(prefix, ignoreCase = true)
        } ?: return null

        val insertion = when (match) {
            "print", "input", "len", "range", "open", "enumerate", "zip",
            "sorted", "sum", "min", "max", "abs", "round", "type",
            "isinstance", "list", "dict", "set", "tuple", "str", "int",
            "float", "bool" -> "$match()"
            else -> match
        }

        return Suggestion(match, insertion)
    }

    private fun contextSuggestion(line: String): Suggestion? = when {
        line == "if" || line == "elif" || line == "while" ->
            Suggestion("condition:", " condition:", replacePrefix = false)

        line == "for" ->
            Suggestion("item in range():", " item in range():", replacePrefix = false)

        line == "def" ->
            Suggestion("function_name():", " function_name():", replacePrefix = false)

        line == "class" ->
            Suggestion("ClassName:", " ClassName:", replacePrefix = false)

        line == "try" -> Suggestion(":", ":", replacePrefix = false)
        line == "except" -> Suggestion("Exception:", " Exception:", replacePrefix = false)
        line.endsWith("import ") -> Suggestion("os", "os", replacePrefix = false)
        line.endsWith("from ") -> Suggestion("pathlib import Path", "pathlib import Path", replacePrefix = false)
        else -> null
    }
}
