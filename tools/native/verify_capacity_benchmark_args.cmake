if(NOT DEFINED BENCHMARK_EXECUTABLE OR NOT EXISTS "${BENCHMARK_EXECUTABLE}")
  message(FATAL_ERROR "A built Matching capacity benchmark is required")
endif()

# The old strtoull parser accepted +1 and whitespace as valid numeric CLI
# tokens. Keep this an end-to-end CLI regression, not a test of a private parser.
foreach(invalid_value IN ITEMS "+1" " 1")
  execute_process(
      COMMAND "${BENCHMARK_EXECUTABLE}"
              --warmup "${invalid_value}"
              --iterations 1
              --maximum-resting-orders 8
      RESULT_VARIABLE result
      OUTPUT_VARIABLE output
      ERROR_VARIABLE error
      TIMEOUT 5)
  if(NOT "${result}" STREQUAL "2" OR
     NOT "${error}" MATCHES "benchmark setup failed: --warmup must be a positive integer")
    message(FATAL_ERROR
        "Rejected CLI token '${invalid_value}' must exit 2 with the argument error. "
        "Result=${result}; stderr=${error}; stdout=${output}")
  endif()
endforeach()
