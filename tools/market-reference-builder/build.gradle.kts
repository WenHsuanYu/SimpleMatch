plugins {
    application
    `java-library`
    id("simplematch.java-conventions")
    id("simplematch.java-quality")
}

dependencies {
    implementation(project(":shared-java:market-reference-contract"))
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.jackson.databind)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("com.simplematch.marketreference.builder.MarketReferenceBuilderMain")
}
