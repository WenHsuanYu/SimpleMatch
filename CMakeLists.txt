cmake_minimum_required(VERSION 3.28)
project(SimpleMatch LANGUAGES CXX)

# Testing (default ON via CTest). If GTest isn't available, tests/ will gracefully skip.
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

find_package(Threads REQUIRED)

# QuickFIX: this repo vendors headers + shared libs under libs/fix.
add_library(QuickFIX::quickfix UNKNOWN IMPORTED)
set_target_properties(QuickFIX::quickfix PROPERTIES
  IMPORTED_LOCATION "${CMAKE_SOURCE_DIR}/libs/fix/lib/libquickfix.so"
  INTERFACE_INCLUDE_DIRECTORIES "${CMAKE_SOURCE_DIR}/libs/fix/include"
)

add_subdirectory(services/fix-gateway)

if(BUILD_TESTING)
  add_subdirectory(tests)
endif()
