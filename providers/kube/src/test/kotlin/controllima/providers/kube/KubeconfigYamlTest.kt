package controllima.providers.kube

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class KubeconfigYamlTest {
    @Test
    fun `reads nested mappings and sequences at the key's indentation`() {
        val doc = KubeconfigYaml.parse(
            """
            a:
              b: c
            list:
            - name: x
              value:
                deep: 1
            - name: y
            other: z
            """.trimIndent(),
        )
        assertEquals(
            mapOf(
                "a" to mapOf("b" to "c"),
                "list" to listOf(mapOf("name" to "x", "value" to mapOf("deep" to "1")), mapOf("name" to "y")),
                "other" to "z",
            ),
            doc,
        )
    }

    @Test
    fun `reads indented sequences, scalars items and nested sequences`() {
        val doc = KubeconfigYaml.parse(
            """
            items:
              - one
              - 'two'
              -
                k: v
              - - inner
                - inner2
            """.trimIndent(),
        )
        assertEquals(mapOf("items" to listOf("one", "two", mapOf("k" to "v"), listOf("inner", "inner2"))), doc)
    }

    @Test
    fun `handles quotes, comments, nulls and empty flow collections`() {
        val doc = KubeconfigYaml.parse(
            """
            # header
            ---
            plain: https://127.0.0.1:6443   # trailing comment
            single: 'it''s # not a comment'
            double: "tab\there \"q\" \u0041"
            "quoted key": v
            hash: a#b
            empty:
            tilde: ~
            prefs: {}
            list: []
            """.trimIndent(),
        ) as Map<*, *>
        assertEquals("https://127.0.0.1:6443", doc["plain"])
        assertEquals("it's # not a comment", doc["single"])
        assertEquals("tab\there \"q\" A", doc["double"])
        assertEquals("v", doc["quoted key"])
        assertEquals("a#b", doc["hash"])
        assertNull(doc["empty"])
        assertNull(doc["tilde"])
        assertNull(doc["prefs"])
        assertNull(doc["list"])
    }

    @Test
    fun `rejects what it does not understand`() {
        listOf(
            "a:\n\tb: c",
            "a: [1, 2]",
            "a: |\n  text",
            "a: &anchor x",
            "a: b\na: c",
            "a: 'open",
            "a: b\n    c: d",
            "just text",
        ).forEach { assertFailsWith<KubeconfigYaml.YamlException>(it) { KubeconfigYaml.parse(it) } }
    }

    @Test
    fun `parses the k3s fixture`() {
        val doc = KubeconfigYaml.parse(fixture("k3s.yaml")) as Map<*, *>
        assertEquals("default", doc["current-context"])
        val cluster = ((doc["clusters"] as List<*>).single() as Map<*, *>)["cluster"] as Map<*, *>
        assertEquals("https://127.0.0.1:6443", cluster["server"])
    }
}
