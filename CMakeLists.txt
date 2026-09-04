cmake_minimum_required(VERSION 3.28)
project(SimpleMatch LANGUAGES CXX)

include(CTest)

option(SIMPLEMATCH_ENABLE_WARNINGS "Enable compiler warnings" ON)
option(SIMPLEMATCH_ENABLE_SANITIZERS "Enable AddressSanitizer + UndefinedBehaviorSanitizer" OFF)
option(SIMPLEMATCH_ENABLE_MESSAGING "Build native Kafka runtime adapters" OFF)

function(simplematch_enable_warnings target_name)
  if(NOT SIMPLEMATCH_ENABLE_WARNINGS)
    return()
  endif()

  if(MSVC)
    target_compile_options(${target_name} PRIVATE /W4)
  else()
    target_compile_options(${target_name} PRIVATE -Wall -Wextra -Wpedantic)
  endif()
endfunction()

function(simplematch_enable_sanitizers target_name)
  if(NOT SIMPLEMATCH_ENABLE_SANITIZERS)
    return()
  endif()
  if(MSVC)
    message(WARNING "Sanitizers are not configured for MSVC")
    return()
  endif()

  target_compile_options(${target_name} PRIVATE -fsanitize=address,undefined -fno-omit-frame-pointer)
  target_link_options(${target_name} PRIVATE -fsanitize=address,undefined -fno-omit-frame-pointer)
endfunction()

set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

find_package(protobuf CONFIG REQUIRED)
find_package(nlohmann_json CONFIG REQUIRED)
find_package(OpenSSL REQUIRED)
find_package(spdlog CONFIG REQUIRED)

set(SIMPLEMATCH_ROUTING_PROTO_FILES
    ${CMAKE_CURRENT_SOURCE_DIR}/proto/common_v2.proto
    ${CMAKE_CURRENT_SOURCE_DIR}/proto/orders_v2.proto
    ${CMAKE_CURRENT_SOURCE_DIR}/proto/matching_runtime_v1.proto)

add_library(simplematch_matching_ingress STATIC
    matching-engine/src/market_reference_artifact.cpp
    matching-engine/src/deterministic_matching_core.cpp
    matching-engine/src/matching_command_decoder.cpp
    matching-engine/src/matching_event_encoder.cpp
    matching-engine/src/matching_partition_runtime_driver.cpp
    matching-engine/src/partition_ownership_permit.cpp
    matching-engine/src/partition_replay_coordinator.cpp
    matching-engine/src/input_offset_ledger.cpp
    matching-engine/src/matching_runtime.cpp
    matching-engine/src/matching_runtime_supervisor.cpp)
target_include_directories(simplematch_matching_ingress
    PUBLIC matching-engine/include
    PRIVATE ${CMAKE_CURRENT_BINARY_DIR})
target_link_libraries(simplematch_matching_ingress
    PUBLIC protobuf::libprotobuf
           nlohmann_json::nlohmann_json
           OpenSSL::Crypto
           spdlog::spdlog_header_only)
protobuf_generate(
    TARGET simplematch_matching_ingress
    LANGUAGE cpp
    PROTOS ${SIMPLEMATCH_ROUTING_PROTO_FILES}
    IMPORT_DIRS ${CMAKE_CURRENT_SOURCE_DIR}/proto)
simplematch_enable_warnings(simplematch_matching_ingress)
simplematch_enable_sanitizers(simplematch_matching_ingress)

if(SIMPLEMATCH_ENABLE_MESSAGING)
  find_package(RdKafka CONFIG REQUIRED)
  find_package(CURL CONFIG REQUIRED)

  add_library(simplematch_matching_messaging STATIC
      matching-engine/src/kubernetes_lease_ownership_adapter.cpp
      matching-engine/src/rdkafka_runtime_adapter.cpp)
  target_include_directories(simplematch_matching_messaging
      PUBLIC matching-engine/include
      PRIVATE ${CMAKE_CURRENT_BINARY_DIR})
  target_link_libraries(simplematch_matching_messaging
      PUBLIC simplematch_matching_ingress
             RdKafka::rdkafka
             CURL::libcurl)
  simplematch_enable_warnings(simplematch_matching_messaging)
  simplematch_enable_sanitizers(simplematch_matching_messaging)

  add_executable(simplematch-matching matching-engine/src/matching_main.cpp)
  target_include_directories(simplematch-matching PRIVATE ${CMAKE_CURRENT_BINARY_DIR})
  target_link_libraries(simplematch-matching PRIVATE simplematch_matching_messaging)
  simplematch_enable_warnings(simplematch-matching)
  simplematch_enable_sanitizers(simplematch-matching)

  if(BUILD_TESTING)
    add_executable(
        simplematch-matching-kafka-fixture-publisher
        matching-engine/tests/matching_kafka_fixture_publisher.cpp)
    target_include_directories(
        simplematch-matching-kafka-fixture-publisher PRIVATE ${CMAKE_CURRENT_BINARY_DIR})
    target_link_libraries(
        simplematch-matching-kafka-fixture-publisher
        PRIVATE simplematch_matching_ingress RdKafka::rdkafka)
    simplematch_enable_warnings(simplematch-matching-kafka-fixture-publisher)
    simplematch_enable_sanitizers(simplematch-matching-kafka-fixture-publisher)

    add_executable(
        simplematch-matching-e2e-certification
        matching-engine/tests/matching_e2e_certification.cpp)
    target_include_directories(
        simplematch-matching-e2e-certification PRIVATE ${CMAKE_CURRENT_BINARY_DIR})
    target_link_libraries(
        simplematch-matching-e2e-certification
        PRIVATE simplematch_matching_ingress RdKafka::rdkafka)
    simplematch_enable_warnings(simplematch-matching-e2e-certification)
    simplematch_enable_sanitizers(simplematch-matching-e2e-certification)

    add_test(
        NAME simplematch_matching_liveness_probe
        COMMAND bash
                "${CMAKE_CURRENT_SOURCE_DIR}/scripts/test-matching-liveness-probe.sh"
                "$<TARGET_FILE:simplematch-matching>")
  endif()
endif()

if(BUILD_TESTING)
  find_package(Threads REQUIRED)
  find_package(GTest CONFIG REQUIRED)

  add_executable(
      simplematch-matching-capacity-benchmark
      matching-engine/tests/matching_capacity_benchmark.cpp)
  target_link_libraries(simplematch-matching-capacity-benchmark PRIVATE simplematch_matching_ingress)
  target_include_directories(
      simplematch-matching-capacity-benchmark PRIVATE ${CMAKE_CURRENT_BINARY_DIR})
  simplematch_enable_warnings(simplematch-matching-capacity-benchmark)
  simplematch_enable_sanitizers(simplematch-matching-capacity-benchmark)

  add_executable(simplematch_matching_ingress_tests
      matching-engine/tests/market_reference_artifact_test.cpp
      matching-engine/tests/deterministic_matching_core_test.cpp
      matching-engine/tests/matching_command_decoder_test.cpp
      matching-engine/tests/matching_event_encoder_test.cpp
      matching-engine/tests/partition_ownership_permit_test.cpp
      matching-engine/tests/partition_replay_coordinator_test.cpp
      matching-engine/tests/input_offset_ledger_test.cpp
      matching-engine/tests/matching_runtime_test.cpp
      matching-engine/tests/matching_partition_runtime_driver_test.cpp)
  target_link_libraries(simplematch_matching_ingress_tests
      PRIVATE simplematch_matching_ingress
              GTest::gtest
              GTest::gtest_main
              Threads::Threads)
  if(SIMPLEMATCH_ENABLE_MESSAGING)
    target_link_libraries(simplematch_matching_ingress_tests
        PRIVATE simplematch_matching_messaging)
    target_sources(simplematch_matching_ingress_tests
        PRIVATE matching-engine/tests/matching_messaging_adapter_test.cpp)
  endif()
  target_include_directories(simplematch_matching_ingress_tests
      PRIVATE ${CMAKE_CURRENT_BINARY_DIR})
  target_compile_definitions(simplematch_matching_ingress_tests
      PRIVATE SIMPLEMATCH_TEST_FIXTURE_DIR="${CMAKE_CURRENT_SOURCE_DIR}/shared-java/simplematch-contracts/src/test/resources/native-routing-fixtures"
              SIMPLEMATCH_MARKET_REFERENCE_FIXTURE_DIR="${CMAKE_CURRENT_SOURCE_DIR}/shared-java/market-reference-contract/src/test/resources/market-reference-fixtures")
  simplematch_enable_warnings(simplematch_matching_ingress_tests)
  simplematch_enable_sanitizers(simplematch_matching_ingress_tests)

  add_executable(
      simplematch_matching_core_allocation_tests
      matching-engine/tests/matching_core_allocation_test.cpp)
  target_link_libraries(
      simplematch_matching_core_allocation_tests
      PRIVATE simplematch_matching_ingress
              GTest::gtest
              GTest::gtest_main
              Threads::Threads)
  target_include_directories(
      simplematch_matching_core_allocation_tests PRIVATE ${CMAKE_CURRENT_BINARY_DIR})
  simplematch_enable_warnings(simplematch_matching_core_allocation_tests)
  simplematch_enable_sanitizers(simplematch_matching_core_allocation_tests)

  include(GoogleTest)
  gtest_discover_tests(simplematch_matching_ingress_tests)
  gtest_discover_tests(simplematch_matching_core_allocation_tests)
  add_test(
      NAME simplematch_matching_capacity_benchmark
      COMMAND simplematch-matching-capacity-benchmark
              --warmup 1
              --iterations 5
              --maximum-resting-orders 32)
endif()
