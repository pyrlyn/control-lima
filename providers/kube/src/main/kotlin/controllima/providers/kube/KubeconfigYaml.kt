package controllima.providers.kube

/**
 * A YAML subset reader for kubeconfig files only: block mappings, block sequences (also at the key's
 * own indentation, as kubectl writes them), plain / single-quoted / double-quoted scalars, comments and
 * the empty flow collections `{}` and `[]`. It exists because no YAML library is approved for this
 * project (see AGENTS.md) and kubeconfigs written by k3s, Colima and kubectl stay inside this subset.
 * Anything outside it (anchors, block scalars, non-empty flow collections, tabs) is rejected with an
 * error instead of being guessed at.
 *
 * Mappings become `Map<String, Any?>`, sequences `List<Any?>`, scalars `String` (`~` / `null` → null).
 */
object KubeconfigYaml {
    class YamlException(message: String) : IllegalArgumentException(message)

    private class Line(val number: Int, val indent: Int, val text: String)

    fun parse(text: String): Any? {
        val lines = text.lines().mapIndexedNotNull { i, raw -> toLine(i + 1, raw) }
        if (lines.isEmpty()) return null
        val parser = Parser(lines)
        val value = parser.node(lines.first().indent)
        if (parser.pos < lines.size) fail(lines[parser.pos], "unexpected indentation")
        return value
    }

    private fun toLine(number: Int, raw: String): Line? {
        val indent = raw.indexOfFirst { it != ' ' }.let { if (it < 0) raw.length else it }
        if (raw.getOrNull(indent) == '\t') throw YamlException("line $number: tabs are not allowed for indentation")
        val text = stripComment(raw.substring(indent)).trimEnd()
        if (text.isEmpty() || text == "---" || text == "...") return null
        if (indent == 0 && text.startsWith("%")) return null
        return Line(number, indent, text)
    }

    private fun stripComment(s: String): String {
        var quote: Char? = null
        var i = -1
        while (++i < s.length) {
            val c = s[i]
            when {
                quote == '\'' -> if (c == '\'') { if (s.getOrNull(i + 1) == '\'') i++ else quote = null }
                quote == '"' -> if (c == '"' && !escaped(s, i)) quote = null
                c == '\'' || c == '"' -> if (i == 0 || s[i - 1] == ' ' || s[i - 1] == ':' || s[i - 1] == '-') quote = c
                c == '#' && (i == 0 || s[i - 1] == ' ') -> return s.substring(0, i)
            }
        }
        return s
    }

    private fun escaped(s: String, i: Int): Boolean {
        var n = 0
        var j = i - 1
        while (j >= 0 && s[j] == '\\') { n++; j-- }
        return n % 2 == 1
    }

    private fun isSeqItem(text: String) = text == "-" || text.startsWith("- ")

    private fun fail(line: Line, message: String): Nothing = throw YamlException("line ${line.number}: $message")

    private class Parser(val lines: List<Line>) {
        var pos = 0
        private val overrides = HashMap<Int, Line>()

        fun peek(): Line? = lines.getOrNull(pos)?.let { overrides[pos] ?: it }

        fun node(indent: Int): Any? {
            val line = peek() ?: return null
            return if (isSeqItem(line.text)) sequence(indent) else mapping(indent)
        }

        fun mapping(indent: Int): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            while (true) {
                val line = peek() ?: break
                if (line.indent < indent) break
                if (line.indent > indent) fail(line, "unexpected indentation")
                if (isSeqItem(line.text)) break
                val (key, rest) = splitKey(line)
                if (map.containsKey(key)) fail(line, "duplicate key '$key'")
                pos++
                map[key] = if (rest.isEmpty()) nested(indent) else scalar(line, rest)
            }
            return map
        }

        /** The value of `key:` with nothing after the colon. */
        private fun nested(indent: Int): Any? {
            val next = peek() ?: return null
            return when {
                next.indent > indent -> node(next.indent)
                // kubectl writes `contexts:` and its `- name:` items at the same indentation.
                next.indent == indent && isSeqItem(next.text) -> sequence(indent)
                else -> null
            }
        }

        fun sequence(indent: Int): List<Any?> {
            val list = ArrayList<Any?>()
            while (true) {
                val line = peek() ?: break
                if (line.indent != indent || !isSeqItem(line.text)) {
                    if (line.indent > indent) fail(line, "unexpected indentation")
                    break
                }
                val rest = line.text.substring(1).trimStart()
                if (rest.isEmpty()) {
                    pos++
                    val next = peek()
                    list += if (next != null && next.indent > indent) node(next.indent) else null
                } else if (isSeqItem(rest) || findKeyColon(rest) >= 0) {
                    // `- name: x` opens a mapping (or nested sequence) whose column is where `name` starts.
                    val column = line.indent + (line.text.length - rest.length)
                    overrides[pos] = Line(line.number, column, rest)
                    list += node(column)
                } else {
                    pos++
                    list += scalar(line, rest)
                }
            }
            return list
        }

        private fun splitKey(line: Line): Pair<String, String> {
            val colon = findKeyColon(line.text)
            if (colon < 0) fail(line, "expected 'key: value'")
            val rawKey = line.text.substring(0, colon).trim()
            val key = scalar(line, rawKey) ?: fail(line, "null key")
            return key to line.text.substring(colon + 1).trim()
        }

        private fun scalar(line: Line, s: String): String? = when {
            s.startsWith("'") -> singleQuoted(line, s)
            s.startsWith("\"") -> doubleQuoted(line, s)
            s == "{}" || s == "[]" -> null
            s.startsWith("{") || s.startsWith("[") -> fail(line, "flow collections are not supported")
            s.startsWith("|") || s.startsWith(">") -> fail(line, "block scalars are not supported")
            s.startsWith("&") || s.startsWith("*") || s.startsWith("!") -> fail(line, "anchors, aliases and tags are not supported")
            s == "~" || s == "null" -> null
            else -> s
        }

        private fun singleQuoted(line: Line, s: String): String {
            val out = StringBuilder()
            var i = 1
            while (i < s.length) {
                val c = s[i]
                if (c == '\'') {
                    if (s.getOrNull(i + 1) == '\'') { out.append('\''); i += 2; continue }
                    if (i != s.length - 1) fail(line, "text after closing quote")
                    return out.toString()
                }
                out.append(c); i++
            }
            fail(line, "unterminated single-quoted string")
        }

        private fun doubleQuoted(line: Line, s: String): String {
            val out = StringBuilder()
            var i = 1
            while (i < s.length) {
                val c = s[i]
                when (c) {
                    '"' -> {
                        if (i != s.length - 1) fail(line, "text after closing quote")
                        return out.toString()
                    }
                    '\\' -> {
                        val e = s.getOrNull(i + 1) ?: fail(line, "dangling escape")
                        when (e) {
                            'n' -> out.append('\n'); 't' -> out.append('\t'); 'r' -> out.append('\r')
                            '0' -> out.append('\u0000'); '"' -> out.append('"'); '\\' -> out.append('\\')
                            '/' -> out.append('/'); ' ' -> out.append(' ')
                            'u' -> {
                                val hex = s.substring(i + 2, minOf(i + 6, s.length))
                                val code = hex.takeIf { it.length == 4 }?.toIntOrNull(16) ?: fail(line, "bad \\u escape")
                                out.append(code.toChar()); i += 4
                            }
                            else -> fail(line, "unsupported escape \\$e")
                        }
                        i += 2
                    }
                    else -> { out.append(c); i++ }
                }
            }
            fail(line, "unterminated double-quoted string")
        }
    }

    /** Index of the `:` that ends a mapping key (followed by a space or the end of line), or -1. */
    private fun findKeyColon(s: String): Int {
        var i = 0
        if (s.startsWith("'") || s.startsWith("\"")) {
            val q = s[0]
            i = 1
            while (i < s.length) {
                if (s[i] == '\\' && q == '"') { i += 2; continue }
                if (s[i] == q) {
                    if (q == '\'' && s.getOrNull(i + 1) == '\'') { i += 2; continue }
                    break
                }
                i++
            }
            i++
        }
        while (i < s.length) {
            if (s[i] == ':' && (i + 1 == s.length || s[i + 1] == ' ')) return i
            i++
        }
        return -1
    }
}
