plugins {
    id("simplematch.spring-service")
    id("simplematch.flyway-service")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-web")

    testImplementation(libs.h2)
    testImplementation(libs.flyway.core)
    testImplementation("org.springframework:spring-jdbc")
    testImplementation("org.springframework.kafka:spring-kafka-test")
}

simpleMatchFlyway {
    serviceId.set("market-data-projection")
    schemaName.set("market_data_projection")
}
