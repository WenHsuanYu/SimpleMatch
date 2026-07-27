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
  implementation(libs.flyway.gradle.plugin)
  implementation(libs.flyway.database.postgresql)
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
