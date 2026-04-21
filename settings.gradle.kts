pluginManagement {
	includeBuild("build-logic")
	repositories {
		gradlePluginPortal()
		mavenCentral()
	}
	plugins {
		id("org.springframework.boot") version "3.5.13"
		id("io.spring.dependency-management") version "1.1.7"
		id("com.google.protobuf") version "0.9.5"
	}
}

rootProject.name = "SimpleMatch"

include(":java-libs:simplematch-config")
include(":java-libs:simplematch-contracts")
include(":services:account-service")
include(":services:quickfix-gateway")
include(":services:risk-service")

project(":java-libs:simplematch-config").projectDir = file("java-libs/simplematch-config")
project(":java-libs:simplematch-contracts").projectDir = file("java-libs/simplematch-contracts")
project(":services:account-service").projectDir = file("services/account-service")
project(":services:quickfix-gateway").projectDir = file("services/quickfix-gateway")
project(":services:risk-service").projectDir = file("services/risk-service")