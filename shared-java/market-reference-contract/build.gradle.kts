plugins {
    `java-library`
    id("simplematch.java-conventions")
    id("simplematch.java-quality")
}

dependencies {
    api(platform(libs.spring.boot.bom))
    api(libs.jackson.databind)

    compileOnly(libs.errorprone.annotations)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}
