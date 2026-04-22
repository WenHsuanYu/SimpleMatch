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

dependencies {
  implementation(gradleApi())
  implementation("org.flywaydb:flyway-gradle-plugin:12.3.0")
  implementation("org.flywaydb:flyway-database-postgresql:12.3.0")
  compileOnly("javax.inject:javax.inject:1")
}

gradlePlugin {
  plugins {
    register("simpleMatchFlywayService") {
      id = "simplematch.flyway-service"
      implementationClass = "com.simplematch.gradle.SimpleMatchFlywayServicePlugin"
    }
  }
}