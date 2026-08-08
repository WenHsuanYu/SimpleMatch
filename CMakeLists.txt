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

  # Apply both compile + link flags.
  target_compile_options(${target_name} PRIVATE -fsanitize=address,undefined -fno-omit-frame-pointer)
  target_link_options(${target_name} PRIVATE -fsanitize=address,undefined -fno-omit-frame-pointer)
endfunction()

# For clangd/VS Code IntelliSense
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

find_package(Protobuf MODULE REQUIRED)

# vcpkg's Linux triplet supplies Protobuf as a static archive.  The module
# package exposes the archive but not its Abseil dependency graph, so prefer
# the package's generated pkg-config metadata when it is available.  This
# keeps the target portable to complete vcpkg installations while allowing a
# release-only local installation to be used when its debug artifacts are
# absent.
find_package(PkgConfig QUIET)
if(PkgConfig_FOUND AND Protobuf_LIBRARY_RELEASE MATCHES "\\.a$")
  get_filename_component(SIMPLEMATCH_PROTOBUF_PREFIX
      "${Protobuf_LIBRARY_RELEASE}" DIRECTORY)
  get_filename_component(SIMPLEMATCH_PROTOBUF_PREFIX
      "${SIMPLEMATCH_PROTOBUF_PREFIX}" DIRECTORY)
  set(SIMPLEMATCH_PREVIOUS_PKG_CONFIG_PATH "$ENV{PKG_CONFIG_PATH}")
  set(ENV{PKG_CONFIG_PATH}
      "${SIMPLEMATCH_PROTOBUF_PREFIX}/lib/pkgconfig")
  set(PKG_CONFIG_USE_STATIC_LIBS ON)
  pkg_check_modules(SIMPLEMATCH_PROTOBUF_PC QUIET IMPORTED_TARGET protobuf)
  if(SIMPLEMATCH_PREVIOUS_PKG_CONFIG_PATH)
    set(ENV{PKG_CONFIG_PATH} "${SIMPLEMATCH_PREVIOUS_PKG_CONFIG_PATH}")
  else()
    unset(ENV{PKG_CONFIG_PATH})
  endif()
endif()

set(SIMPLEMATCH_ROUTING_PROTO_FILES
    ${CMAKE_CURRENT_SOURCE_DIR}/proto/common_v2.proto
    ${CMAKE_CURRENT_SOURCE_DIR}/proto/orders_v2.proto
    ${CMAKE_CURRENT_SOURCE_DIR}/proto/routing_policy_v2.proto)

add_library(simplematch_matching_ingress STATIC
    matching-engine/src/critical_order_ingress.cpp
    matching-engine/src/routing_policy_ingress.cpp)
target_include_directories(simplematch_matching_ingress
    PUBLIC matching-engine/include
    PRIVATE ${CMAKE_CURRENT_BINARY_DIR}
            ${Protobuf_INCLUDE_DIRS})
if(TARGET PkgConfig::SIMPLEMATCH_PROTOBUF_PC)
  target_link_libraries(simplematch_matching_ingress
      PUBLIC PkgConfig::SIMPLEMATCH_PROTOBUF_PC)
else()
  target_link_libraries(simplematch_matching_ingress PUBLIC protobuf::libprotobuf)
endif()
set(PROTOBUF_IMPORT_DIRS ${CMAKE_CURRENT_SOURCE_DIR}/proto)
protobuf_generate_cpp(
    SIMPLEMATCH_ROUTING_PROTO_SRCS
    SIMPLEMATCH_ROUTING_PROTO_HDRS
    ${SIMPLEMATCH_ROUTING_PROTO_FILES})
target_sources(simplematch_matching_ingress
    PRIVATE ${SIMPLEMATCH_ROUTING_PROTO_SRCS} ${SIMPLEMATCH_ROUTING_PROTO_HDRS})
simplematch_enable_warnings(simplematch_matching_ingress)
simplematch_enable_sanitizers(simplematch_matching_ingress)

if(BUILD_TESTING)
  find_package(Threads REQUIRED)
  find_path(SIMPLEMATCH_GTEST_INCLUDE_DIR
      NAMES gtest/gtest.h
      REQUIRED)
  find_library(SIMPLEMATCH_GTEST_LIBRARY
      NAMES gtest
      REQUIRED)
  find_library(SIMPLEMATCH_GTEST_MAIN_LIBRARY
      NAMES gtest_main
      HINTS ${CMAKE_PREFIX_PATH}/lib/manual-link
      REQUIRED)

  add_executable(simplematch_matching_ingress_tests
      matching-engine/tests/critical_order_ingress_test.cpp
      matching-engine/tests/routing_policy_ingress_test.cpp)
  target_link_libraries(simplematch_matching_ingress_tests
      PRIVATE simplematch_matching_ingress
              ${SIMPLEMATCH_GTEST_MAIN_LIBRARY}
              ${SIMPLEMATCH_GTEST_LIBRARY}
              Threads::Threads)
  target_include_directories(simplematch_matching_ingress_tests
      PRIVATE ${SIMPLEMATCH_GTEST_INCLUDE_DIR}
              ${CMAKE_CURRENT_BINARY_DIR})
  target_compile_definitions(simplematch_matching_ingress_tests
      PRIVATE SIMPLEMATCH_TEST_FIXTURE_DIR="${CMAKE_CURRENT_SOURCE_DIR}/shared-java/simplematch-contracts/src/test/resources/native-routing-fixtures")
  simplematch_enable_warnings(simplematch_matching_ingress_tests)
  simplematch_enable_sanitizers(simplematch_matching_ingress_tests)

  include(GoogleTest)
  gtest_discover_tests(simplematch_matching_ingress_tests)
endif()
