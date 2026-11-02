plugins {
    base
}

apply(from = "gradle/native-verification.gradle.kts")

allprojects {
    group = "com.simplematch"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}
