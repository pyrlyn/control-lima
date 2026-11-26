package controllima.server.security

/**
 * Finds and masks secrets in configs and logs (docs/plan/backup-restore.md, logs.md). Built-in patterns
 * keep the key and mask the value; user patterns from settings mask the whole match.
 */
class Redactor(userPatterns: List<String>) {
    private class Pattern(val label: String, val regex: Regex, val valueGroup: Int?)

    private val patterns: List<Pattern> = BUILT_IN + userPatterns.mapNotNull { p ->
        runCatching { Pattern("custom pattern", Regex(p), null) }.getOrNull()
    }

    /** Findings as "line N: label"; the secret itself is never part of a finding. */
    fun scan(text: String): List<String> {
        val out = mutableListOf<String>()
        text.lineSequence().forEachIndexed { i, line ->
            patterns.filter { it.regex.containsMatchIn(line) }.map { it.label }.distinct().forEach { out += "line ${i + 1}: $it" }
        }
        return out
    }

    fun redact(text: String): String = patterns.fold(text) { acc, p ->
        p.regex.replace(acc) { m ->
            val group = p.valueGroup?.let { m.groups[it] }
            if (group == null) MASK
            else m.value.substring(0, group.range.first - m.range.first) + MASK + m.value.substring(group.range.last + 1 - m.range.first)
        }
    }

    companion object {
        const val MASK = "[REDACTED]"

        private val BUILT_IN = listOf(
            Pattern("key=value secret", Regex("(?i)\\b(password|passwd|secret|token|api[_-]?key|access[_-]?key|private[_-]?key)\\b\\s*[:=]\\s*\"?([^\\s\"']{4,})"), 2),
            Pattern("private key block", Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----"), null),
            Pattern("GitHub token", Regex("\\bgh[pousr]_[A-Za-z0-9]{30,}"), null),
            Pattern("AWS access key", Regex("\\bAKIA[0-9A-Z]{16}\\b"), null),
            Pattern("Slack token", Regex("\\bxox[baprs]-[A-Za-z0-9-]{10,}"), null),
            Pattern("bearer token", Regex("(?i)\\bbearer\\s+([A-Za-z0-9._~+/-]{16,}=*)"), 1),
        )
    }
}
