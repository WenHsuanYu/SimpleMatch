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
    implementation("org.springframework.cloud:spring-cloud-starter")
    implementation(libs.grpc.netty.shaded)
    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.stub)

    testImplementation(libs.h2)
    testImplementation(libs.flyway.core)
    testImplementation("org.springframework:spring-jdbc")
}

simpleMatchFlyway {
    serviceId.set("account-service")
    schemaName.set("account_service")
}
