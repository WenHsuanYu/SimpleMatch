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
    exclude("**/QueryCollectorPostgresContractTest.class")
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

tasks.register<Test>("postgresJdbcTest") {
    group = "verification"
    description = "Runs Query JDBC, HTTP, and collector contracts against Testcontainers PostgreSQL."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("com.simplematch.queryservice.QueryServiceApplicationTest")
    filter.includeTestsMatching("com.simplematch.queryservice.runtime.QueryProjectionRebuildServiceTest")
    filter.includeTestsMatching("com.simplematch.queryservice.store.QueryFlywayMigrationTest")
    filter.includeTestsMatching("com.simplematch.queryservice.store.JdbcQueryProjectionStoreTest")
    filter.includeTestsMatching("com.simplematch.queryservice.store.QueryCollectorPostgresContractTest")
    filter.includeTestsMatching("com.simplematch.queryservice.security.QueryHttpSecurityIntegrationTest")
    filter.includeTestsMatching("com.simplematch.queryservice.security.QueryRebuildDisabledHttpTest")
    filter.includeTestsMatching("com.simplematch.queryservice.security.QueryDirectHttpTest")
    filter.includeTestsMatching("com.simplematch.queryservice.security.QueryAccountSummaryHttpTest")
    filter.includeTestsMatching("com.simplematch.queryservice.security.QueryAccountPolicySnapshotHttpTest")
    filter.includeTestsMatching("com.simplematch.queryservice.security.QueryOrderHttpTest")
    filter.includeTestsMatching("com.simplematch.queryservice.security.QueryExecutionsHttpTest")
    filter.includeTestsMatching("com.simplematch.queryservice.security.QueryExecutionsSnapshotHttpTest")
    filter.includeTestsMatching("com.simplematch.queryservice.security.QueryMarketHttpTest")
    filter.includeTestsMatching("com.simplematch.queryservice.security.QueryMarketSnapshotHttpTest")
    systemProperty("simplematch.repositoryRoot", rootProject.projectDir.absolutePath)
    maxParallelForks = 1
    outputs.upToDateWhen { false }
}

simpleMatchFlyway {
    serviceId.set("query-service")
    schemaName.set("query_service")
}
