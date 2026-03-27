cmake_minimum_required(VERSION 3.21)

find_package(GTest CONFIG QUIET)

if(NOT GTest_FOUND)
  message(WARNING "GTest not found (vcpkg port: gtest). Skipping tests.")
  return()
endif()

add_executable(simplematch_unit_tests
  fix_gateway_execution_report_test.cpp
  fix_gateway_config_test.cpp
)

target_link_libraries(simplematch_unit_tests PRIVATE
  GTest::gtest_main
  fix_gateway_core
)

simplematch_enable_warnings(simplematch_unit_tests)
simplematch_enable_sanitizers(simplematch_unit_tests)

include(GoogleTest)

gtest_discover_tests(simplematch_unit_tests)
