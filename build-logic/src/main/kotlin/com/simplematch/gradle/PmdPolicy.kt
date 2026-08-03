package com.simplematch.gradle

import java.nio.file.Files
import java.nio.file.Path

/** The PMD policy enforced for handwritten production Java. */
internal object PmdPolicy {
    const val RULESET_PATH = "config/pmd/simplematch-design.xml"
    const val COMPLETED_PARAMETER_SAFETY_RULESET_PATH =
        "config/pmd/completed-parameter-safety.xml"
    const val EXPECTED_RULE_COUNT = 47

    private val ruleReferencePattern = Regex("<rule\\s+ref=\"([^\"]+)\"")

    /** Reads the PMD rule references declared by [ruleset]. */
    fun ruleReferences(ruleset: Path): List<String> {
        val references =
            ruleReferencePattern
                .findAll(Files.readString(ruleset))
                .map { it.groupValues[1] }
                .toList()

        require(references.isNotEmpty()) { "PMD ruleset $ruleset does not declare any rules." }
        require(references.size == references.toSet().size) {
            "PMD ruleset $ruleset declares duplicate rules."
        }
        return references
    }

    /** Returns the handwritten production source directories covered by the completed-slice gate. */
    fun completedParameterSafetySourceDirectories(projectPath: String): List<String> =
        when (projectPath) {
            ":services:account-service" ->
                listOf(
                    "src/main/java/com/simplematch/accountservice/authority",
                    "src/main/java/com/simplematch/accountservice/reservation"
                )
            ":services:risk-service" ->
                listOf(
                    "src/main/java/com/simplematch/riskservice/admission",
                    "src/main/java/com/simplematch/riskservice/outbox"
                )
            else -> emptyList()
        }
}
