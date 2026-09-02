plugins {
    id("simplematch.spring-service")
    id("simplematch.flyway-service")
}

dependencies {
    implementation(project(":shared-java:market-reference-contract"))
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-web")

    testImplementation(libs.h2)
    testImplementation(libs.flyway.core)
    testImplementation("org.springframework:spring-jdbc")
}

simpleMatchFlyway {
    serviceId.set("query-service")
    schemaName.set("query_service")
}
