pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}


rootProject.name = "SimpleMatch"

include(
    ":shared-java:simplematch-config",
    ":shared-java:simplematch-contracts",
    ":services:account-service",
    ":services:marketdata-publisher",
    ":services:persistence",
    ":services:quickfix-gateway",
    ":services:risk-service",
)

project(":shared-java:simplematch-config").projectDir = file("shared-java/simplematch-config")
project(":shared-java:simplematch-contracts").projectDir = file("shared-java/simplematch-contracts")
project(":services:account-service").projectDir = file("services/account-service")
project(":services:marketdata-publisher").projectDir = file("services/marketdata-publisher")
project(":services:persistence").projectDir = file("services/persistence")
project(":services:quickfix-gateway").projectDir = file("services/quickfix-gateway")
project(":services:risk-service").projectDir = file("services/risk-service")
