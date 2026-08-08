plugins {
    `java-library`
    id("simplematch.java-conventions")
    id("simplematch.java-quality")
}

dependencies {
    api(platform(libs.spring.boot.bom))
    api("org.springframework.boot:spring-boot")
    api("org.springframework.boot:spring-boot-autoconfigure")
    api("io.micrometer:micrometer-core")
    implementation(libs.uuid.creator)
    compileOnly(libs.errorprone.annotations)
    //testImplementation("org.springframework.boot:spring-boot")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}
