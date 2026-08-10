cmake_minimum_required(VERSION 3.28)
project(SimpleMatch LANGUAGES CXX)

include(CTest)

option(SIMPLEMATCH_ENABLE_WARNINGS "Enable compiler warnings" ON)
option(SIMPLEMATCH_ENABLE_SANITIZERS "Enable AddressSanitizer + UndefinedBehaviorSanitizer" OFF)

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

set(SIMPLEMATCH_ROUTING_PROTO_FILES
    ${CMAKE_CURRENT_SOURCE_DIR}/proto/common_v2.proto
    ${CMAKE_CURRENT_SOURCE_DIR}/proto/orders_v2.proto
    ${CMAKE_CURRENT_SOURCE_DIR}/proto/routing_policy_v2.proto)

add_library(simplematch_matching_ingress STATIC
    matching-engine/src/critical_order_ingress.cpp
    matching-engine/src/routing_policy_ingress.cpp
    matching-engine/src/market_reference_artifact.cpp)
target_include_directories(simplematch_matching_ingress
    PUBLIC matching-engine/include
    PRIVATE ${CMAKE_CURRENT_BINARY_DIR})
target_link_libraries(simplematch_matching_ingress
    PUBLIC protobuf::libprotobuf
           nlohmann_json::nlohmann_json
           OpenSSL::Crypto)
protobuf_generate(
    TARGET simplematch_matching_ingress
    LANGUAGE cpp
    PROTOS ${SIMPLEMATCH_ROUTING_PROTO_FILES}
    IMPORT_DIRS ${CMAKE_CURRENT_SOURCE_DIR}/proto)
simplematch_enable_warnings(simplematch_matching_ingress)
simplematch_enable_sanitizers(simplematch_matching_ingress)

if(BUILD_TESTING)
  find_package(Threads REQUIRED)
  find_package(GTest CONFIG REQUIRED)

  add_executable(simplematch_matching_ingress_tests
      matching-engine/tests/critical_order_ingress_test.cpp
      matching-engine/tests/routing_policy_ingress_test.cpp
      matching-engine/tests/market_reference_artifact_test.cpp)
  target_link_libraries(simplematch_matching_ingress_tests
      PRIVATE simplematch_matching_ingress
              GTest::gtest
              GTest::gtest_main
              Threads::Threads)
  target_include_directories(simplematch_matching_ingress_tests
      PRIVATE ${CMAKE_CURRENT_BINARY_DIR})
  target_compile_definitions(simplematch_matching_ingress_tests
      PRIVATE SIMPLEMATCH_TEST_FIXTURE_DIR="${CMAKE_CURRENT_SOURCE_DIR}/shared-java/simplematch-contracts/src/test/resources/native-routing-fixtures"
              SIMPLEMATCH_MARKET_REFERENCE_FIXTURE_DIR="${CMAKE_CURRENT_SOURCE_DIR}/shared-java/market-reference-contract/src/test/resources/market-reference-fixtures")
  simplematch_enable_warnings(simplematch_matching_ingress_tests)
  simplematch_enable_sanitizers(simplematch_matching_ingress_tests)

  include(GoogleTest)
  gtest_discover_tests(simplematch_matching_ingress_tests)
endif()
