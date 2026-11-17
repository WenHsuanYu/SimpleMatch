package com.simplematch.gradle

import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class PmdPolicyTest {
    @Test
    fun `structural policy rejects foreign-namespace rules`(@TempDir directory: Path) {
        val fixture = directory.resolve("foreign-rule.xml")
        Files.writeString(
            fixture,
            """
            <ruleset xmlns="http://pmd.sourceforge.net/ruleset/2.0.0" xmlns:foreign="urn:untrusted">
              <rule ref="category/java/design.xml/ExcessiveParameterList"/>
              <foreign:rule ref="category/java/design.xml/MutableStaticState"/>
            </ruleset>
            """.trimIndent()
        )
        assertFailsWith<AssertionError> { assertCanonicalStructure(fixture) }
    }

    @Test
    fun `canonical ruleset contains unique Java rule references without a fixed count`() {
        assertCanonicalStructure(Path.of("../config/pmd/simplematch-design.xml"))
    }

    private fun assertCanonicalStructure(ruleset: Path) {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val document = factory.newDocumentBuilder().parse(ruleset.toFile())
        val namespace = "http://pmd.sourceforge.net/ruleset/2.0.0"
        val root = document.documentElement
        assertEquals("ruleset", root.localName)
        assertEquals(namespace, root.namespaceURI)
        val rules = root.getElementsByTagNameNS(namespace, "rule")
        val descendants = root.getElementsByTagName("*")
        val everyRule = (0 until descendants.length).count { descendants.item(it).localName == "rule" }
        assertEquals(everyRule, rules.length, "all rule elements must use the canonical PMD namespace")
        val references = (0 until rules.length).map { index -> rules.item(index).attributes.getNamedItem("ref")?.nodeValue }
        val explicitJavaRule = Regex("""category/java/[^/]+\.xml/[^/]+""")

        assertTrue(references.isNotEmpty())
        assertEquals(references.size, references.toSet().size)
        assertTrue(references.all { it != null && explicitJavaRule.matches(it) })
        assertTrue(references.contains("category/java/design.xml/ExcessiveParameterList"))
    }

}
