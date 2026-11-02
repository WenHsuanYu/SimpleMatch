import java.time.Duration
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory

// CMake and CTest remain the only owners of native builds and assertions.
val completeInvocation = gradle.startParameter.taskNames.any {
    it.substringAfterLast(':') == "verifyAll"
}
val nativePreset = providers.gradleProperty("nativePreset")
    .orElse(if (completeInvocation) "full-native-dev" else "dev-debug").get()
require(nativePreset in setOf("dev-debug", "full-native-dev", "ci-fast", "ci-sanitize")) {
    "nativePreset must name a supported CMake preset"
}
val nativeParallelism = providers.gradleProperty("nativeParallelism").orElse("2").get()
require(nativeParallelism.toIntOrNull()?.let { it in 1..8 } == true) {
    "nativeParallelism must be between 1 and 8"
}
val nativeEvidence = file(providers.gradleProperty("nativeEvidence")
    .orElse("out/verification/native/" + UUID.randomUUID()).get())

val nativeConfigure = tasks.register<Exec>("nativeConfigure") {
    group = "verification"
    description = "Configures the existing native preset with normal dependency caches."
    workingDir(rootDir)
    environment("VCPKG_MAX_CONCURRENCY", nativeParallelism)
    environment("VCPKG_DISABLE_METRICS", "1")
    commandLine("cmake", "--preset", nativePreset)
    timeout.set(Duration.ofMinutes(15))
    doFirst {
        check(!nativeEvidence.exists()) { "nativeEvidence must name a new directory" }
        check(nativeEvidence.mkdirs()) { "Cannot create native evidence directory" }
    }
}
val nativeBuild = tasks.register<Exec>("nativeBuild") {
    group = "verification"
    dependsOn(nativeConfigure)
    workingDir(rootDir)
    environment("VCPKG_MAX_CONCURRENCY", nativeParallelism)
    environment("VCPKG_DISABLE_METRICS", "1")
    commandLine("cmake", "--build", "--preset", nativePreset, "--parallel", nativeParallelism)
    timeout.set(Duration.ofMinutes(30))
}
tasks.register<Exec>("nativeTest") {
    group = "verification"
    description = "Builds and executes every CTest in the selected native preset anew."
    dependsOn(nativeBuild)
    workingDir(rootDir)
    commandLine("ctest", "--preset", nativePreset, "--parallel", nativeParallelism,
        "--no-tests=error", "--output-junit", nativeEvidence.resolve("ctest.xml").absolutePath)
    timeout.set(Duration.ofMinutes(10))
    doLast {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val report = factory.newDocumentBuilder().parse(nativeEvidence.resolve("ctest.xml"))
        check(report.getElementsByTagName("testcase").length > 0) { "CTest discovered no tests" }
        for (tag in listOf("skipped", "failure", "error")) {
            check(report.getElementsByTagName(tag).length == 0) { "CTest reported $tag results" }
        }
        val suite = report.documentElement
        for (attribute in listOf("disabled", "skipped", "failures", "errors")) {
            check(suite.getAttribute(attribute).let { it.isEmpty() || it == "0" }) {
                "CTest reported $attribute results"
            }
        }
        logger.lifecycle("Native JUnit evidence: {}", nativeEvidence.resolve("ctest.xml"))
    }
}
