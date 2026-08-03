package com.simplematch.gradle

/** Defines the narrow seven-parameter ratchet for completed refactoring slices. */
internal object ParameterCountPolicy {
    const val MAX_PARAMETERS = 7

    /** Returns the handwritten production source directories covered by the ratchet. */
    fun sourceDirectories(projectPath: String): List<String> =
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

    /** Returns an in-memory Checkstyle configuration so no second checked-in ruleset is needed. */
    fun checkstyleConfiguration(): String =
        """
        <?xml version="1.0"?>
        <!DOCTYPE module PUBLIC
          "-//Checkstyle//DTD Checkstyle Configuration 1.3//EN"
          "https://checkstyle.org/dtds/configuration_1_3.dtd">
        <module name="Checker">
          <module name="TreeWalker">
            <module name="ParameterNumber">
              <property name="max" value="$MAX_PARAMETERS"/>
              <property name="tokens" value="METHOD_DEF,CTOR_DEF"/>
            </module>
          </module>
        </module>
        """.trimIndent()
}
