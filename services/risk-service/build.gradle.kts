plugins {
    id("simplematch.spring-service")
    id("simplematch.flyway-service")
}

dependencies {
    implementation(project(":shared-java:market-reference-contract"))
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation(libs.grpc.netty.shaded)
    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.stub)
    compileOnly(libs.errorprone.annotations)

    testImplementation(project(":services:account-service"))
    testImplementation(libs.h2)
    testImplementation(libs.flyway.core)
}

simpleMatchFlyway {
    serviceId.set("risk-service")
    schemaName.set("risk_service")
}
