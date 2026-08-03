package com.simplematch.gradle

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PmdPolicyTest {
    @Test
    fun `loads a unique set of Java rule references from the checked in ruleset`() {
        val ruleset = Path.of("../config/pmd/simplematch-design.xml")
        val contents = Files.readString(ruleset)
        val references = PmdPolicy.ruleReferences(ruleset)

        assertTrue(contents.contains("handwritten"))
        assertEquals(PmdPolicy.EXPECTED_RULE_COUNT, references.size)
        assertEquals(references.size, references.toSet().size)
        assertTrue(references.all { it.startsWith("category/java/") })
        assertTrue(references.contains("category/java/design.xml/ExcessiveParameterList"))
    }

}
