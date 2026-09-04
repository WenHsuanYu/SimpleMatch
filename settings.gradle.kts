pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

//dependencyResolutionManagement {
//    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
//
//    repositories {
//        mavenCentral()
//    }
//}

rootProject.name = "SimpleMatch"

include(
    ":shared-java:simplematch-config",
    ":shared-java:simplematch-contracts",
    ":shared-java:market-reference-contract",
    ":services:account-service",
    ":services:market-data-projection",
    ":services:marketdata-streamer",
    ":services:query-service",
    ":services:persistence",
    ":services:quickfix-gateway",
    ":services:risk-service",
    ":tools:market-reference-builder",
    ":tools:risk-matching-e2e-verifier",
)

project(":shared-java:simplematch-config").projectDir = file("shared-java/simplematch-config")
project(":shared-java:simplematch-contracts").projectDir = file("shared-java/simplematch-contracts")
project(":shared-java:market-reference-contract").projectDir = file("shared-java/market-reference-contract")
project(":services:account-service").projectDir = file("services/account-service")
project(":services:market-data-projection").projectDir = file("services/market-data-projection")
project(":services:marketdata-streamer").projectDir = file("services/marketdata-streamer")
project(":services:query-service").projectDir = file("services/query-service")
project(":services:persistence").projectDir = file("services/persistence")
project(":services:quickfix-gateway").projectDir = file("services/quickfix-gateway")
project(":services:risk-service").projectDir = file("services/risk-service")
project(":tools:market-reference-builder").projectDir = file("tools/market-reference-builder")
project(":tools:risk-matching-e2e-verifier").projectDir = file("tools/risk-matching-e2e-verifier")
