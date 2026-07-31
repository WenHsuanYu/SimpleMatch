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
    }

    @Test
    fun `maps only the completed account and admission slices to the seven parameter gate`() {
        assertEquals(
            listOf(
                "src/main/java/com/simplematch/accountservice/authority",
                "src/main/java/com/simplematch/accountservice/reservation"
            ),
            PmdPolicy.completedParameterSafetySourceDirectories(":services:account-service")
        )
        assertEquals(
            listOf("src/main/java/com/simplematch/riskservice/admission"),
            PmdPolicy.completedParameterSafetySourceDirectories(":services:risk-service")
        )
        assertTrue(PmdPolicy.completedParameterSafetySourceDirectories(":services:quickfix-gateway").isEmpty())
    }

    @Test
    fun `completed parameter safety ruleset fails at eight parameters`() {
        val ruleset = Path.of("../config/pmd/completed-parameter-safety.xml")
        val contents = Files.readString(ruleset)

        assertTrue(contents.contains("ExcessiveParameterList"))
        assertTrue(contents.contains("name=\"minimum\" value=\"8\""))
    }
}
