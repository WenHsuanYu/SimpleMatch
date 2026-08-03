import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
}

java {
    sourceCompatibility = JavaVersion.VERSION_23
    targetCompatibility = JavaVersion.VERSION_23
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_23)
    }
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencyLocking {
    lockAllConfigurations()
}

dependencies {
    implementation(gradleApi())
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.flyway.gradle.plugin)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.errorprone.gradle.plugin)
    implementation(libs.protobuf.gradle.plugin)
    implementation(libs.spring.boot.gradle.plugin)
    implementation(libs.spotbugs.gradle.plugin)
    compileOnly("javax.inject:javax.inject:1")
    testImplementation("com.puppycrawl.tools:checkstyle:${libs.versions.checkstyle.get()}")
    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

gradlePlugin {
    plugins {
        register("simpleMatchFlywayService") {
            id = "simplematch.flyway-service"
            implementationClass = "com.simplematch.gradle.SimpleMatchFlywayServicePlugin"
        }
        register("simpleMatchSpringService") {
            id = "simplematch.spring-service"
            implementationClass = "com.simplematch.gradle.SimpleMatchSpringServicePlugin"
        }
        register("simpleMatchProtobufContracts") {
            id = "simplematch.protobuf-contracts"
            implementationClass = "com.simplematch.gradle.SimpleMatchProtobufContractsPlugin"
        }
        register("simpleMatchJavaConventions") {
            id = "simplematch.java-conventions"
            implementationClass = "com.simplematch.gradle.SimpleMatchJavaConventionsPlugin"
        }
        register("simpleMatchJavaQuality") {
            id = "simplematch.java-quality"
            implementationClass = "com.simplematch.gradle.SimpleMatchJavaQualityPlugin"
        }
    }
}
