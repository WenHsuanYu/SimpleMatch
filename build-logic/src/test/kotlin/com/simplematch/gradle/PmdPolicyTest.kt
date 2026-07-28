package com.simplematch.gradle

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PmdPolicyTest {
    @Test
    fun `uses the approved five-rule production design policy`() {
        assertEquals(
            listOf(
                "category/java/design.xml/ExcessiveParameterList",
                "category/java/design.xml/CyclomaticComplexity",
                "category/java/design.xml/NcssCount",
                "category/java/design.xml/ExcessivePublicCount",
                "category/java/design.xml/TooManyMethods"
            ),
            PmdPolicy.ruleReferences
        )
    }

    @Test
    fun `checked in ruleset contains exactly the approved references`() {
        val ruleset = Path.of("../config/pmd/simplematch-design.xml")
        val contents = Files.readString(ruleset)
        val references = Regex("rule ref=\"([^\"]+)\"").findAll(contents).map { it.groupValues[1] }.toList()

        assertEquals(PmdPolicy.ruleReferences, references)
        assertTrue(contents.contains("handwritten"))
    }
}
