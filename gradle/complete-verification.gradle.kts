import org.gradle.api.tasks.testing.Test

// Existing tasks retain scenario, resource-lifecycle and verdict ownership.
val completeInvocation = gradle.startParameter.taskNames.any {
    it.substringAfterLast(':') in setOf("verifyAll", "verifyAllScope")
}
if (completeInvocation) {
    require(gradle.startParameter.excludedTaskNames.isEmpty()) {
        "Complete verification does not permit excluded tasks."
    }
}
val unresolvedRequirements = listOf(
    "R11-R15: deployed critical-consumer safety, public FIX and business recovery",
    "R16-R17: deployed Market Data rebuild and public-stream recovery",
    "R18-R19: deployed Query processing and policy lifecycle",
    "R20-R22: retained dependency, placement and Matching recovery dispositions"
)
val scope = tasks.register("verifyAllScope") {
    group = "verification"
    description = "Rejects complete verification while retained requirement owners are missing."
    doLast {
        check(providers.gradleProperty("nativePreset").orElse("full-native-dev").get()
            == "full-native-dev") { "Complete verification requires full-native-dev." }
        check(unresolvedRequirements.isEmpty()) {
            "Complete verification is not implemented for:\n" +
                unresolvedRequirements.joinToString("\n")
        }
    }
}
val complete = tasks.register("verifyAll") {
    group = "verification"
    description = "Runs complete local verification; currently blocked by missing P3/P4 owners."
    dependsOn(scope, "staticAnalysis", "nativeTest", "manifestCheck", "verifyDependencies",
        "verifyKubernetes", ":services:quickfix-gateway:certificationTest")
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
