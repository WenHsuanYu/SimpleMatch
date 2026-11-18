cmake_minimum_required(VERSION 3.28)
project(SimpleMatch LANGUAGES CXX)

include(CTest)

option(SIMPLEMATCH_ENABLE_WARNINGS "Enable compiler warnings" ON)
option(SIMPLEMATCH_ENABLE_SANITIZERS "Enable AddressSanitizer + UndefinedBehaviorSanitizer" OFF)
option(SIMPLEMATCH_ENABLE_MESSAGING "Build native Kafka runtime adapters" OFF)
option(SIMPLEMATCH_BUILD_FIXTURE_TOOLS "Build Kafka deployment fixture tools" OFF)
option(SIMPLEMATCH_ENABLE_CLANG_TIDY "Add an opt-in report-only clang-tidy target for native C++" OFF)

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
    matching-engine/src/matching_runtime_observation.cpp
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

  if(BUILD_TESTING OR SIMPLEMATCH_BUILD_FIXTURE_TOOLS)
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
  endif()

  if(BUILD_TESTING)
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

  # Full 150-book fill and sequential replay are intentionally opt-in. Do not
  # add this executable to ALL or CTest: capacity runs are resource-intensive.
  add_executable(simplematch_matching_capacity_envelope_test EXCLUDE_FROM_ALL
      matching-engine/tests/matching_capacity_envelope_test.cpp)
  target_link_libraries(simplematch_matching_capacity_envelope_test
      PRIVATE simplematch_matching_ingress GTest::gtest GTest::gtest_main Threads::Threads)
  target_include_directories(simplematch_matching_capacity_envelope_test
      PRIVATE ${CMAKE_CURRENT_BINARY_DIR})
  simplematch_enable_warnings(simplematch_matching_capacity_envelope_test)
  simplematch_enable_sanitizers(simplematch_matching_capacity_envelope_test)

  # Offline memory profiles are opt-in and may allocate large rings.
  add_executable(simplematch_matching_memory_envelope_test EXCLUDE_FROM_ALL
      matching-engine/tests/matching_memory_envelope_test.cpp)
  target_link_libraries(simplematch_matching_memory_envelope_test
      PRIVATE simplematch_matching_ingress GTest::gtest GTest::gtest_main Threads::Threads)
  simplematch_enable_warnings(simplematch_matching_memory_envelope_test)
  simplematch_enable_sanitizers(simplematch_matching_memory_envelope_test)

  add_executable(simplematch_matching_ingress_tests
      matching-engine/tests/market_reference_artifact_test.cpp
      matching-engine/tests/deterministic_matching_core_test.cpp
      matching-engine/tests/matching_command_decoder_test.cpp
      matching-engine/tests/matching_event_encoder_test.cpp
      matching-engine/tests/partition_ownership_permit_test.cpp
      matching-engine/tests/partition_replay_coordinator_test.cpp
      matching-engine/tests/input_offset_ledger_test.cpp
      matching-engine/tests/matching_runtime_test.cpp
      matching-engine/tests/matching_runtime_observation_test.cpp
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

# Analyze only translation units in matching-engine, never generated protobuf or vcpkg sources.
# Keep this separate from ALL, CTest and the normal Native CI build until findings are triaged.
if(SIMPLEMATCH_ENABLE_CLANG_TIDY)
  find_program(SIMPLEMATCH_CLANG_TIDY_EXECUTABLE
      NAMES clang-tidy clang-tidy-21 clang-tidy-20 clang-tidy-19 clang-tidy-18 REQUIRED)
  find_program(SIMPLEMATCH_RUN_CLANG_TIDY_EXECUTABLE
      NAMES run-clang-tidy run-clang-tidy-21 run-clang-tidy-20 run-clang-tidy-19 run-clang-tidy-18 REQUIRED)

  # CI pins both the analyzer version and the exact matching LLVM runner script.
  # Keep the expectations opt-in so other local LLVM releases remain usable.
  set(SIMPLEMATCH_EXPECTED_CLANG_TIDY_VERSION "" CACHE STRING
      "Required clang-tidy version for reproducible analyzer runs")
  set(SIMPLEMATCH_EXPECTED_RUN_CLANG_TIDY_SHA256 "" CACHE STRING
      "Required SHA-256 of the run-clang-tidy script")
  foreach(tool_path IN ITEMS
      "${SIMPLEMATCH_CLANG_TIDY_EXECUTABLE}"
      "${SIMPLEMATCH_RUN_CLANG_TIDY_EXECUTABLE}")
    if(NOT EXISTS "${tool_path}")
      message(FATAL_ERROR "Native clang-tidy executable does not exist: ${tool_path}")
    endif()
  endforeach()
  if(SIMPLEMATCH_EXPECTED_CLANG_TIDY_VERSION)
    execute_process(
        COMMAND "${SIMPLEMATCH_CLANG_TIDY_EXECUTABLE}" --version
        RESULT_VARIABLE tidy_version_exit
        OUTPUT_VARIABLE tidy_version_output
        ERROR_VARIABLE tidy_version_error)
    if(NOT tidy_version_exit EQUAL 0)
      message(FATAL_ERROR "Native clang-tidy version query failed: ${tidy_version_error}")
    endif()
    string(REGEX MATCH "[0-9]+[.][0-9]+[.][0-9]+"
        tidy_version_actual "${tidy_version_output}")
    if(NOT "${tidy_version_actual}" STREQUAL "${SIMPLEMATCH_EXPECTED_CLANG_TIDY_VERSION}")
      message(FATAL_ERROR
          "Native clang-tidy version mismatch: expected ${SIMPLEMATCH_EXPECTED_CLANG_TIDY_VERSION}, got ${tidy_version_actual}")
    endif()
  endif()
  if(SIMPLEMATCH_EXPECTED_RUN_CLANG_TIDY_SHA256)
    file(SHA256 "${SIMPLEMATCH_RUN_CLANG_TIDY_EXECUTABLE}" tidy_runner_sha256)
    if(NOT "${tidy_runner_sha256}" STREQUAL "${SIMPLEMATCH_EXPECTED_RUN_CLANG_TIDY_SHA256}")
      message(FATAL_ERROR
          "Native run-clang-tidy SHA-256 mismatch: expected ${SIMPLEMATCH_EXPECTED_RUN_CLANG_TIDY_SHA256}, got ${tidy_runner_sha256}")
    endif()
  endif()

  add_custom_target(simplematch_clang_tidy_report
      COMMAND "${SIMPLEMATCH_RUN_CLANG_TIDY_EXECUTABLE}"
              "-p=${CMAKE_CURRENT_BINARY_DIR}"
              "-clang-tidy-binary=${SIMPLEMATCH_CLANG_TIDY_EXECUTABLE}"
              "-j=2"
              "-source-filter=^${CMAKE_CURRENT_SOURCE_DIR}/matching-engine/(src|tests)/.*[.]cpp$"
              "-header-filter=^${CMAKE_CURRENT_SOURCE_DIR}/matching-engine/(include|src|tests)/"
              "${CMAKE_CURRENT_SOURCE_DIR}/matching-engine/(src|tests)/.*[.]cpp$"
      WORKING_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}"
      COMMENT "Run report-only clang-tidy on handwritten Native C++ sources"
      VERBATIM)

  add_dependencies(simplematch_clang_tidy_report simplematch_matching_ingress)
  if(TARGET simplematch_matching_ingress_tests)
    add_dependencies(simplematch_clang_tidy_report simplematch_matching_ingress_tests)
  endif()
  if(TARGET simplematch_matching_core_allocation_tests)
    add_dependencies(simplematch_clang_tidy_report simplematch_matching_core_allocation_tests)
  endif()
  if(TARGET simplematch-matching-capacity-benchmark)
    add_dependencies(simplematch_clang_tidy_report simplematch-matching-capacity-benchmark)
  endif()
  if(TARGET simplematch_matching_messaging)
    add_dependencies(simplematch_clang_tidy_report simplematch_matching_messaging)
  endif()
  if(TARGET simplematch-matching)
    add_dependencies(simplematch_clang_tidy_report simplematch-matching)
  endif()
endif()
