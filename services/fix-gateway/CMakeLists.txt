add_library(fix_gateway_core
  src/FixGatewayApp.cpp
  src/FixGatewayConfig.cpp
  src/ExecutionReportBuilder.cpp
  src/wal/WalAppender.cpp
)

simplematch_enable_warnings(fix_gateway_core)
simplematch_enable_sanitizers(fix_gateway_core)

find_package(nlohmann_json CONFIG QUIET)

target_include_directories(fix_gateway_core PUBLIC
  ${CMAKE_CURRENT_SOURCE_DIR}/src
  ${CMAKE_SOURCE_DIR}/libs/fix/include
)

target_link_libraries(fix_gateway_core PUBLIC
  QuickFIX::quickfix
  Threads::Threads
)

if (nlohmann_json_FOUND)
  target_link_libraries(fix_gateway_core PUBLIC nlohmann_json::nlohmann_json)
  target_compile_definitions(fix_gateway_core PUBLIC SIMPLEMATCH_HAS_NLOHMANN_JSON=1)
endif()

add_executable(fix-gateway
  src/main.cpp
)

target_link_libraries(fix-gateway PRIVATE
  fix_gateway_core
)

simplematch_enable_warnings(fix-gateway)
simplematch_enable_sanitizers(fix-gateway)

# Ensure the vendored shared library can be found at runtime.
set_target_properties(fix-gateway PROPERTIES
  BUILD_RPATH "${CMAKE_SOURCE_DIR}/libs/fix/lib"
)
