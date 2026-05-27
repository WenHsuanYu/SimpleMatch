pluginManagement {
	includeBuild("build-logic")
	repositories {
		gradlePluginPortal()
		mavenCentral()
	}
	plugins {
		id("org.springframework.boot") version "3.5.14"
		id("io.spring.dependency-management") version "1.1.7"
		id("com.google.protobuf") version "0.9.5"
	}
}

rootProject.name = "SimpleMatch"

include(":shared-java:simplematch-config")
include(":shared-java:simplematch-contracts")
include(":services:account-service")
include(":services:persistence")
include(":services:quickfix-gateway")
include(":services:risk-service")

project(":shared-java:simplematch-config").projectDir = file("shared-java/simplematch-config")
project(":shared-java:simplematch-contracts").projectDir = file("shared-java/simplematch-contracts")
project(":services:account-service").projectDir = file("services/account-service")
project(":services:persistence").projectDir = file("services/persistence")
project(":services:quickfix-gateway").projectDir = file("services/quickfix-gateway")
project(":services:risk-service").projectDir = file("services/risk-service")