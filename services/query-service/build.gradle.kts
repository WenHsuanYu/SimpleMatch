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
    implementation("org.springframework.boot:spring-boot-starter-security")

    testImplementation("org.springframework.security:spring-security-test")
    testImplementation(libs.flyway.core)
    testImplementation("org.springframework:spring-jdbc")
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.flyway.database.postgresql)
}

sourceSets.test {
    resources.srcDir(rootProject.file("shared-java/market-reference-contract/src/test/resources"))
}

tasks.processTestResources {
    listOf("query-service", "quickfix-gateway").forEach { service ->
        from(rootProject.file("services/$service/src/main/resources/application.yaml")) {
            into("kubernetes-secret-contract/$service")
        }
    }
}

tasks.test {
    maxParallelForks = 1
    outputs.upToDateWhen { false }
    exclude("**/QueryRedisSecurityIntegrationTest.class")
}

tasks.register<Test>("redisSecurityTest") {
    group = "verification"
    description = "Verifies Query TLS and ACL behavior against Testcontainers Redis fixtures."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("com.simplematch.queryservice.config.QueryRedisSecurityIntegrationTest")
    maxParallelForks = 1
    outputs.upToDateWhen { false }
}

tasks.register("postgresJdbcTest") {
    group = "verification"
    description = "Runs the canonical Query JDBC and HTTP tests once, including PostgreSQL fixtures."
    dependsOn(tasks.test)
}

simpleMatchFlyway {
    serviceId.set("query-service")
    schemaName.set("query_service")
}
