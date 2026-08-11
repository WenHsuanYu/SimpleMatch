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
    ":shared-java:market-reference-contract",
    ":services:account-service",
    ":services:market-data-projection",
    ":services:query-service",
    ":services:marketdata-publisher",
    ":services:persistence",
    ":services:quickfix-gateway",
    ":services:risk-service",
    ":tools:market-reference-builder",
)

project(":shared-java:simplematch-config").projectDir = file("shared-java/simplematch-config")
project(":shared-java:simplematch-contracts").projectDir = file("shared-java/simplematch-contracts")
project(":shared-java:market-reference-contract").projectDir =
    file("shared-java/market-reference-contract")
project(":services:account-service").projectDir = file("services/account-service")
project(":services:market-data-projection").projectDir = file("services/market-data-projection")
project(":services:query-service").projectDir = file("services/query-service")
project(":services:marketdata-publisher").projectDir = file("services/marketdata-publisher")
project(":services:persistence").projectDir = file("services/persistence")
project(":services:quickfix-gateway").projectDir = file("services/quickfix-gateway")
project(":services:risk-service").projectDir = file("services/risk-service")
project(":tools:market-reference-builder").projectDir = file("tools/market-reference-builder")
