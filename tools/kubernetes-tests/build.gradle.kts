import org.springframework.boot.gradle.tasks.bundling.BootBuildImage
import org.springframework.boot.gradle.tasks.bundling.BootJar
import java.util.UUID
import org.gradle.process.CommandLineArgumentProvider

plugins {
    id("simplematch.java-conventions")
    id("simplematch.java-quality")
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.jackson.databind)
    implementation(libs.jackson.dataformat.yaml)
    implementation(project(":shared-java:market-reference-contract"))
    implementation(project(":shared-java:simplematch-contracts"))
    implementation(project(":tools:risk-matching-e2e-verifier"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}

val dockerContext = providers.gradleProperty("kubernetesDockerContext").orElse(providers.exec {
    commandLine("docker", "context", "show")
}.standardOutput.asText.map { it.trim() })

tasks.withType<Test>().configureEach {
    systemProperty("verificationRepoRoot", rootProject.projectDir.absolutePath)
}

tasks.test { useJUnitPlatform { excludeTags("kubernetes-live") } }

val kubernetesTest = tasks.register<Test>("kubernetesTest") {
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("kubernetes-live") }
    outputs.upToDateWhen { false }
    maxParallelForks = 1
    providers.gradlePropertiesPrefixedBy("kubernetes").get().forEach { (name, value) ->
        systemProperty(name, value)
    }
}

// Each invocation builds unique image references and executes live checks anew.
val imageTag = providers.gradleProperty("kubernetesImageTag")
    .orElse("k8s-" + UUID.randomUUID().toString().take(12)).get()
val receipt = rootProject.file(providers.gradleProperty("kubernetesBuildReceipt")
    .orElse("out/kubernetes-build/" + UUID.randomUUID() + "/inputs.json").get())
val runProperties = providers.gradlePropertiesPrefixedBy("kubernetes").get() + mapOf(
    "verificationRepoRoot" to rootProject.projectDir.absolutePath,
    "kubernetesImageTag" to imageTag,
    "kubernetesBuildReceipt" to receipt.absolutePath,
    "kubernetesEvidence" to providers.gradleProperty("kubernetesEvidence")
        .orElse("out/verification/kubernetes/" + UUID.randomUUID()).get())
fun registerBuildInputs(name: String, operation: String) = tasks.register<JavaExec>(name) {
    group = "verification"
    workingDir(rootProject.projectDir)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.simplematch.tools.kubernetes.KubernetesBuildInputs")
    systemProperties(runProperties)
    doFirst { systemProperty("kubernetesDockerContext", dockerContext.get()) }
    args(operation)
}
val preflight = registerBuildInputs("kubernetesBuildPreflight", "begin")
val platformImages = registerBuildInputs("acquireKubernetesPlatformImages", "pull")
platformImages.configure { dependsOn(preflight) }
var previous: TaskProvider<out Task> = platformImages
for (service in listOf("account-service", "risk-service")) {
    evaluationDependsOn(":services:$service")
    val owner = project(":services:$service")
    val predecessor = previous
    previous = owner.tasks.register<BootBuildImage>("bootBuildKubernetesImage") {
        dependsOn(predecessor)
        archiveFile.set(owner.tasks.named<BootJar>("bootJar").flatMap { it.archiveFile })
        imageName.set("simplematch/$service:$imageTag-boot")
        docker {
            host.set(dockerContext.map { selected ->
                providers.exec {
                    commandLine("docker", "context", "inspect", selected, "--format",
                        "{{.Endpoints.docker.Host}}")
                }.standardOutput.asText.get().trim()
            })
        }
    }
}
fun dockerBuild(name: String, arguments: List<String>): TaskProvider<Exec> {
    val predecessor = previous
    val task = tasks.register<Exec>(name) {
        dependsOn(predecessor)
        workingDir(rootProject.projectDir)
        executable = "docker"
        argumentProviders.add(CommandLineArgumentProvider {
            listOf("--context", dockerContext.get(), "build") + arguments + "."
        })
    }
    previous = task
    return task
}
dockerBuild("buildKubernetesBarrierImage", listOf("-f", "deploy/docker/Dockerfile.matching",
    "--build-arg", "SIMPLEMATCH_BUILD_FIXTURE_TOOLS=ON", "--target", "fixture-publisher",
    "-t", "simplematch/kafka-fixture-publisher:$imageTag"))
dockerBuild("buildKubernetesMatchingImage", listOf("-f", "deploy/docker/Dockerfile.matching",
    "--build-arg", "SIMPLEMATCH_BUILD_FIXTURE_TOOLS=ON", "--target", "matching",
    "-t", "simplematch-matching:$imageTag"))
for (image in listOf("flyway-runner", "risk-matching-e2e-verifier")) {
    dockerBuild("buildKubernetes" + image.split('-').joinToString("") {
        it.replaceFirstChar(Char::uppercaseChar)
    } + "Image", listOf("-f", "deploy/docker/Dockerfile.$image", "-t", "simplematch/$image:$imageTag"))
}
for (service in listOf("account-service", "risk-service")) {
    dockerBuild("normalizeKubernetes" + service.substringBefore('-').replaceFirstChar(Char::uppercaseChar),
        listOf("-f", "deploy/docker/Dockerfile.kind-normalized", "--build-arg",
            "SOURCE_IMAGE=simplematch/$service:$imageTag-boot", "-t", "simplematch/$service:$imageTag"))
}
val buildInputs = registerBuildInputs("kubernetesBuildInputs", "finish")
buildInputs.configure { dependsOn(previous) }
kubernetesTest.configure {
    systemProperties(runProperties)
    doFirst { systemProperty("kubernetesDockerContext", dockerContext.get()) }
}
rootProject.tasks.register("verifyKubernetes") {
    group = "verification"
    description = "Builds current RM-1 images and verifies deployed behavior with owned kind resources."
    dependsOn(buildInputs, kubernetesTest)
}
kubernetesTest.configure { mustRunAfter(buildInputs) }

val cleanup = tasks.register<JavaExec>("cleanupRetainedRun") {
    group = "verification"
    workingDir(rootProject.projectDir)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.simplematch.tools.kubernetes.KubernetesRetainedCleanup")
    systemProperties(runProperties + mapOf("kubernetesCleanupEvidence" to
        providers.gradleProperty("kubernetesCleanupEvidence")
            .orElse("out/verification/kubernetes-cleanup/" + UUID.randomUUID()).get()))
}
rootProject.tasks.register("cleanupKubernetesRun") {
    group = "verification"
    description = "Cleans an explicitly selected retained run after revalidating its exact ownership."
    dependsOn(cleanup)
}
