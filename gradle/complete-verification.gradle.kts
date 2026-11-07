import org.gradle.api.tasks.testing.Test
import org.gradle.api.internal.tasks.testing.filter.DefaultTestFilter
import groovy.json.JsonSlurper
import javax.xml.parsers.DocumentBuilderFactory

// Existing tasks retain scenario, resource-lifecycle and verdict ownership.
val completeInvocation = gradle.startParameter.taskNames.any {
    it.substringAfterLast(':') in setOf("verifyAll", "verifyAllScope")
}
if (completeInvocation) {
    require(gradle.startParameter.excludedTaskNames.isEmpty()) {
        "Complete verification does not permit excluded tasks."
    }
}
val scope = tasks.register("verifyAllScope") {
    group = "verification"
    description = "Rejects incomplete source, profile, and legacy-retirement prerequisites."
    doLast {
        check(providers.gradleProperty("nativePreset").orElse("full-native-dev").get()
            == "full-native-dev") { "Complete verification requires full-native-dev." }
        check(providers.gradleProperty("kubernetesScenario").orElse("resting-buy").get()
            == "resting-buy") { "Complete verification requires the resting-buy Kubernetes scenario." }
        check(providers.gradleProperty("kubernetesRetain").orElse("false").get()
            .equals("false", ignoreCase = true)) {
            "Complete verification requires Kubernetes cleanup; kubernetesRetain must be false."
        }
        // Gradle applies --tests filters after task configuration, so check the
        // selected task graph at execution time, before any test can run.
        for (testTask in gradle.taskGraph.allTasks.filterIsInstance<Test>()) {
            val filter = testTask.filter as? DefaultTestFilter
                ?: error("Complete verification cannot inspect ${testTask.path} test filters.")
            check(filter.commandLineIncludePatterns.isEmpty()) {
                "Complete verification does not permit --tests on ${testTask.path}."
            }
        }
        val dirtyTrackedSource = ProcessBuilder("git", "diff", "--quiet", "HEAD", "--")
            .directory(rootDir).start().waitFor()
        check(dirtyTrackedSource == 0) {
            "Complete verification requires a committed, unchanged tracked source tree."
        }
        // Ignore only the operator's known private, non-build inputs. Do not read them.
        val untrackedPaths = providers.exec {
            commandLine("git", "ls-files", "--others", "--exclude-standard", "-z")
        }.standardOutput.asText.get().split('\u0000').filter { it.isNotEmpty() }
        check(untrackedPaths.all { path ->
            path == ".env" || path == ".secret" || path == "secrets" ||
                path.startsWith("secrets/") || path.startsWith(".scratch/")
        }) {
            "Complete verification requires committed or ignored build inputs; " +
                "unexpected non-ignored untracked files exist."
        }
        val trackedShellPaths = providers.exec {
            commandLine("git", "ls-files", "-z", "--", "*.sh")
        }.standardOutput.asText.get().split('\u0000').filter { it.isNotEmpty() }
        val trackedShellFiles = trackedShellPaths.map(rootProject::file).filter { it.isFile }
        val shellLines = trackedShellFiles.sumOf { file ->
            val bytes = file.readBytes()
            bytes.count { it == '\n'.code.toByte() } +
                if (bytes.isNotEmpty() && bytes.last() != '\n'.code.toByte()) 1 else 0
        }
        check(trackedShellFiles.size <= 34 && shellLines <= 6984) {
            "S5 P5b Shell retirement is incomplete: ${trackedShellFiles.size} files / " +
                "$shellLines physical lines; 80% design target requires at most 34 files / 6984 lines."
        }
    }
}
val complete = tasks.register("verifyAll") {
    group = "verification"
    description = "Runs complete local verification and checks the final Kubernetes cleanup verdict."
    dependsOn(scope, "staticAnalysis", "nativeTest", "manifestCheck", "verifyDependencies",
        "verifyKubernetes", ":services:quickfix-gateway:certificationTest")
    doLast {
        // A successful test task with no discovered tests is not complete acceptance.
        // These owners are required by the S5 retained-scope matrix.
        val requiredJUnitOwners = listOf(
            ":services:account-service" to "test",
            ":services:risk-service" to "test",
            ":services:persistence" to "test",
            ":services:quickfix-gateway" to "test",
            ":services:quickfix-gateway" to "certificationTest",
            ":services:market-data-projection" to "test",
            ":services:marketdata-streamer" to "test",
            ":services:query-service" to "test",
            ":services:query-service" to "redisSecurityTest",
            ":tools:kubernetes-tests" to "test",
            ":tools:kubernetes-tests" to "manifestTest",
            ":tools:kubernetes-tests" to "kubernetesTest",
            ":tools:dependency-tests" to "test",
            ":tools:dependency-tests" to "postgresTest",
            ":tools:dependency-tests" to "cdcTest",
            ":tools:recovery-evidence-verifier" to "test",
            ":tools:query-evidence-verifier" to "test",
            ":tools:risk-matching-e2e-verifier" to "test"
        )
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        for ((projectPath, taskName) in requiredJUnitOwners) {
            val owner = project(projectPath)
            val testTask = owner.tasks.named<Test>(taskName).get()
            check(!testTask.state.skipped) {
                "Complete verification did not execute required owner $projectPath:$taskName."
            }
            val results = owner.file("build/test-results/$taskName")
                .listFiles { file -> file.isFile && file.name.startsWith("TEST-") &&
                    file.name.endsWith(".xml") }?.toList().orEmpty()
            check(results.isNotEmpty()) {
                "Complete verification has no JUnit XML for $projectPath:$taskName."
            }
            if (projectPath == ":tools:kubernetes-tests" && taskName == "test") {
                // These tests replace independent Shell/Ruby safety owners. Do not allow
                // an unrelated successful JUnit suite to stand in for their execution.
                for (name in listOf("HardResetSafetyTest", "MatchingFleetLiveSafetyTest")) {
                    val report = results.singleOrNull {
                        it.name == "TEST-com.simplematch.tools.kubernetes.$name.xml"
                    } ?: error("Complete verification requires the $name safety contract.")
                    val suite = factory.newDocumentBuilder().parse(report).documentElement
                    check((suite.getAttribute("tests").toIntOrNull() ?: 0) > 0) {
                        "Complete verification requires nonempty $name assertions."
                    }
                }
            }
            var testCases = 0
            for (result in results) {
                val suite = factory.newDocumentBuilder().parse(result).documentElement
                testCases += suite.getAttribute("tests").toIntOrNull()
                    ?: error("JUnit XML lacks a valid test count: $projectPath:$taskName")
                for (failure in listOf("failures", "errors", "skipped")) {
                    check(suite.getAttribute(failure) == "0") {
                        "Required JUnit owner $projectPath:$taskName contains $failure."
                    }
                }
            }
            check(testCases > 0) {
                "Complete verification discovered no tests in $projectPath:$taskName."
            }
        }
        val kubernetesTask = project(":tools:kubernetes-tests").tasks
            .named<Test>("kubernetesTest").get()
        val evidencePath = checkNotNull(kubernetesTask.systemProperties["kubernetesEvidence"]) {
            "Complete verification has no Kubernetes evidence path."
        }.toString()
        val report = rootProject.file(evidencePath).resolve("summary.json")
        check(report.isFile) {
            "Complete verification requires a current Kubernetes summary.json."
        }
        val summary = JsonSlurper().parse(report) as Map<*, *>
        for (field in listOf("status", "assertions", "diagnostics", "cleanup")) {
            check(summary[field] == "PASS") {
                "Complete verification requires Kubernetes $field=PASS."
            }
        }
    }
}
allprojects {
    plugins.withId("java") {
        complete.configure { dependsOn(tasks.named("test")) }
    }
    if (completeInvocation) {
        tasks.configureEach {
            if (path != scope.get().path) {
                dependsOn(scope)
            }
        }
        tasks.withType<Test>().configureEach {
            outputs.upToDateWhen { false }
        }
    }
}
