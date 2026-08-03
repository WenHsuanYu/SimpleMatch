package com.simplematch.gradle

import com.puppycrawl.tools.checkstyle.Checker
import com.puppycrawl.tools.checkstyle.ConfigurationLoader
import com.puppycrawl.tools.checkstyle.PropertiesExpander
import com.puppycrawl.tools.checkstyle.api.AuditEvent
import com.puppycrawl.tools.checkstyle.api.AuditListener
import java.io.StringReader
import java.nio.file.Files
import java.util.Properties
import org.xml.sax.InputSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParameterCountPolicyTest {
    @Test
    fun `allows exactly seven parameters`() {
        assertTrue(audit(javaSourceWithParameters(7)).isEmpty())
    }

    @Test
    fun `rejects eight parameters`() {
        val violations = audit(javaSourceWithParameters(8))

        assertEquals(1, violations.size)
        assertTrue(violations.single().message.contains("7"))
    }

    @Test
    fun `rejects a constructor with eight parameters`() {
        val violations = audit(javaSourceWithConstructorParameters(8))

        assertEquals(1, violations.size)
        assertTrue(violations.single().message.contains("7"))
    }

    @Test
    fun `keeps parameter policy separate from canonical PMD rules`() {
        val configuration = ParameterCountPolicy.checkstyleConfiguration()

        assertEquals(7, ParameterCountPolicy.MAX_PARAMETERS)
        assertTrue(configuration.contains("<module name=\"ParameterNumber\">"))
        assertTrue(configuration.contains("<property name=\"max\" value=\"7\"/>"))
        assertTrue(!configuration.contains("ExcessiveParameterList"))
        assertEquals(
            listOf(
                "src/main/java/com/simplematch/accountservice/authority",
                "src/main/java/com/simplematch/accountservice/reservation"
            ),
            ParameterCountPolicy.sourceDirectories(":services:account-service")
        )
        assertEquals(
            listOf(
                "src/main/java/com/simplematch/riskservice/admission",
                "src/main/java/com/simplematch/riskservice/outbox"
            ),
            ParameterCountPolicy.sourceDirectories(":services:risk-service")
        )
        assertTrue(ParameterCountPolicy.sourceDirectories(":services:quickfix-gateway").isEmpty())
    }

    private fun audit(source: String): List<AuditEvent> {
        val directory = Files.createTempDirectory("simplematch-parameter-count")
        val sourceFile = directory.resolve("Boundary.java")
        Files.writeString(sourceFile, source)
        val violations = mutableListOf<AuditEvent>()
        val checker = Checker()
        checker.setModuleClassLoader(javaClass.classLoader)
        checker.addListener(recordingListener(violations))

        try {
            val configuration = ConfigurationLoader.loadConfiguration(
                InputSource(StringReader(ParameterCountPolicy.checkstyleConfiguration())),
                PropertiesExpander(Properties()),
                ConfigurationLoader.IgnoredModulesOptions.OMIT
            )
            checker.configure(configuration)
            checker.process(listOf(sourceFile.toFile()))
        } finally {
            checker.destroy()
            Files.deleteIfExists(sourceFile)
            Files.deleteIfExists(directory)
        }
        return violations
    }

    private fun recordingListener(violations: MutableList<AuditEvent>) =
        object : AuditListener {
            override fun auditStarted(event: AuditEvent) = Unit

            override fun auditFinished(event: AuditEvent) = Unit

            override fun fileStarted(event: AuditEvent) = Unit

            override fun fileFinished(event: AuditEvent) = Unit

            override fun addError(event: AuditEvent) {
                violations += event
            }

            override fun addException(event: AuditEvent, throwable: Throwable) {
                throw AssertionError("Checkstyle failed to inspect the fixture", throwable)
            }
        }

    private fun javaSourceWithParameters(count: Int): String {
        val parameters = parameterList(count)
        return """
            class Boundary {
                void method($parameters) {}
            }
        """.trimIndent()
    }

    private fun javaSourceWithConstructorParameters(count: Int): String {
        return """
            class Boundary {
                Boundary(${parameterList(count)}) {}
            }
        """.trimIndent()
    }

    private fun parameterList(count: Int): String =
        (1..count).joinToString(", ") { "int value$it" }
}
