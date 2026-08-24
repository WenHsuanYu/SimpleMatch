plugins {
    id("simplematch.spring-service")
    id("simplematch.flyway-service")
}

the<JavaPluginExtension>()
    .sourceSets
    .getByName("test")
    .resources
    .srcDir(rootProject.file("shared-java/simplematch-contracts/src/test/resources"))

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.boot:spring-boot-starter-web")

    testImplementation(libs.h2)
    testImplementation(libs.flyway.core)
    testImplementation("org.springframework:spring-jdbc")
    testImplementation("org.springframework.kafka:spring-kafka-test")
}

simpleMatchFlyway {
    serviceId.set("persistence")
    schemaName.set("persistence")
}
