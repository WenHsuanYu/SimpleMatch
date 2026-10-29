plugins {
    application
    id("simplematch.java-conventions")
    id("simplematch.java-quality")
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.jackson.databind)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("com.simplematch.tools.recovery.RecoveryEvidenceVerifierMain")
}

tasks.test {
    systemProperty("recoveryRepoRoot", rootProject.projectDir.absolutePath)
    systemProperty("dependencyVerifierClasspath", sourceSets.main.get().runtimeClasspath.asPath)
}
