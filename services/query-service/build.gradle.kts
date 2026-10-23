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
    testImplementation(libs.h2)
    testImplementation(libs.flyway.core)
    testImplementation("org.springframework:spring-jdbc")
    testRuntimeOnly(libs.flyway.database.postgresql)
}

sourceSets.test {
    resources.srcDir(rootProject.file("shared-java/market-reference-contract/src/test/resources"))
}

tasks.test {
    exclude("**/QueryRedisSecurityIntegrationTest.class")
}

tasks.register<Test>("redisSecurityTest") {
    group = "verification"
    description = "Verifies Query TLS and ACL behavior against runner-owned Redis fixtures."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("com.simplematch.queryservice.config.QueryRedisSecurityIntegrationTest")
    maxParallelForks = 1
    outputs.upToDateWhen { false }
}

tasks.register<Test>("postgresJdbcTest") {
    group = "verification"
    description = "Runs Query JDBC and HTTP security contracts against owned PostgreSQL."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("com.simplematch.queryservice.store.JdbcQueryProjectionStoreTest")
    filter.includeTestsMatching("com.simplematch.queryservice.security.QueryHttpSecurityIntegrationTest")
    filter.includeTestsMatching("com.simplematch.queryservice.security.QueryRebuildDisabledHttpTest")
    filter.includeTestsMatching("com.simplematch.queryservice.security.QueryDirectHttpTest")
    systemProperty("simplematch.query.jdbc.backend", "postgresql")
    maxParallelForks = 1
    outputs.upToDateWhen { false }
}

simpleMatchFlyway {
    serviceId.set("query-service")
    schemaName.set("query_service")
}
