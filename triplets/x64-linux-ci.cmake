set(VCPKG_TARGET_ARCHITECTURE x64)
set(VCPKG_CRT_LINKAGE dynamic)
set(VCPKG_LIBRARY_LINKAGE static)
set(VCPKG_CMAKE_SYSTEM_NAME Linux)

# CI validates SimpleMatch itself as a Debug build, but third-party vcpkg
# dependencies only need their Release variants. This reduces cold-cache
# dependency build work without changing local development triplet semantics.
set(VCPKG_BUILD_TYPE release)
