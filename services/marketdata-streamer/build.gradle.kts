plugins {
    id("simplematch.spring-service")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation(libs.grpc.netty.shaded)
}

tasks.withType<Test>().configureEach {
    jvmArgs(
        "--enable-native-access=ALL-UNNAMED",
        "--sun-misc-unsafe-memory-access=allow"
    )
}
