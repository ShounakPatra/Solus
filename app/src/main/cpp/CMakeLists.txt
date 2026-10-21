cmake_minimum_required(VERSION 3.22.1)

project("solus_llama" C CXX)

# Require 64-bit architecture - drop armeabi-v7a support
if(ANDROID_ABI STREQUAL "armeabi-v7a" OR ANDROID_ABI STREQUAL "armeabi")
    message(FATAL_ERROR "armeabi-v7a (32-bit ARM) is not supported in Solus. On-device LLM inference strictly requires 64-bit architectures (arm64-v8a or x86_64).")
endif()

# Restrict native compile concurrency to two jobs and linking to one using Ninja job pools
set_property(GLOBAL PROPERTY JOB_POOLS compile_pool=2 link_pool=1)
set(CMAKE_JOB_POOL_COMPILE compile_pool)
set(CMAKE_JOB_POOL_LINK link_pool)

# Set C and C++ standards
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

# Upstream llama.cpp pin definition
set(SOLUS_LLAMA_UPSTREAM_COMMIT "5cdadfc326163ed1fa66cf609fb1761f7928900c")
add_compile_definitions(LLAMA_UPSTREAM_REVISION="${SOLUS_LLAMA_UPSTREAM_COMMIT}")

# Enable position-independent code globally for static libraries linked into the JNI shared library
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Configure llama.cpp / ggml options before adding subdirectory
set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build static llama libs" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "Build common utils library" FORCE)
set(LLAMA_BUILD_MTMD ON CACHE BOOL "Build tools/mtmd library standalone" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "Build tests" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "Build tools" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "Build examples" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "Build server example" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "Build unified binary" FORCE)
set(LLAMA_BUILD_UI OFF CACHE BOOL "Build embedded Web UI" FORCE)
set(LLAMA_USE_PREBUILT_UI OFF CACHE BOOL "Use prebuilt UI" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "Use OpenSSL" FORCE)
set(LLAMA_SUBPROCESS OFF CACHE BOOL "Use subprocess" FORCE)
set(MTMD_VIDEO OFF CACHE BOOL "Enable video support in mtmd" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "Use OpenMP" FORCE)
set(GGML_VULKAN ON CACHE BOOL "Use Vulkan" FORCE)

# Vulkan host shader generator toolchain and dependencies
set(SPIRV-Headers_DIR "${CMAKE_CURRENT_SOURCE_DIR}/spirv-headers/cmake" CACHE PATH "SPIRV-Headers config directory" FORCE)
list(APPEND CMAKE_PREFIX_PATH "${CMAKE_CURRENT_SOURCE_DIR}/spirv-headers")

if (CMAKE_HOST_SYSTEM_NAME STREQUAL "Windows")
    set(GGML_VULKAN_SHADERS_GEN_TOOLCHAIN "${CMAKE_CURRENT_SOURCE_DIR}/cmake/windows-host-toolchain.cmake" CACHE FILEPATH "Host toolchain for shader gen" FORCE)
endif()

if (NOT Vulkan_GLSLC_EXECUTABLE)
    if (DEFINED ANDROID_NDK)
        if (CMAKE_HOST_SYSTEM_NAME STREQUAL "Windows" AND EXISTS "${ANDROID_NDK}/shader-tools/windows-x86_64/glslc.exe")
            set(Vulkan_GLSLC_EXECUTABLE "${ANDROID_NDK}/shader-tools/windows-x86_64/glslc.exe" CACHE FILEPATH "NDK glslc executable" FORCE)
        elseif (CMAKE_HOST_SYSTEM_NAME STREQUAL "Linux" AND EXISTS "${ANDROID_NDK}/shader-tools/linux-x86_64/glslc")
            set(Vulkan_GLSLC_EXECUTABLE "${ANDROID_NDK}/shader-tools/linux-x86_64/glslc" CACHE FILEPATH "NDK glslc executable" FORCE)
        elseif (CMAKE_HOST_SYSTEM_NAME STREQUAL "Darwin" AND EXISTS "${ANDROID_NDK}/shader-tools/darwin-x86_64/glslc")
            set(Vulkan_GLSLC_EXECUTABLE "${ANDROID_NDK}/shader-tools/darwin-x86_64/glslc" CACHE FILEPATH "NDK glslc executable" FORCE)
        endif()
    endif()
    if (NOT Vulkan_GLSLC_EXECUTABLE)
        find_program(HOST_GLSLC_EXE glslc)
        if (HOST_GLSLC_EXE)
            set(Vulkan_GLSLC_EXECUTABLE "${HOST_GLSLC_EXE}" CACHE FILEPATH "Host glslc executable" FORCE)
        endif()
    endif()
endif()

find_library(ANDROID_VULKAN_LIB vulkan)
if (ANDROID_VULKAN_LIB AND NOT Vulkan_LIBRARY)
    set(Vulkan_LIBRARY "${ANDROID_VULKAN_LIB}" CACHE FILEPATH "Vulkan library" FORCE)
endif()

set(Vulkan_INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/vulkan-headers/include" CACHE PATH "Vulkan include directory" FORCE)
set(Vulkan_INCLUDE_DIRS "${CMAKE_CURRENT_SOURCE_DIR}/vulkan-headers/include" CACHE PATH "Vulkan include directory" FORCE)
include_directories(SYSTEM "${CMAKE_CURRENT_SOURCE_DIR}/vulkan-headers/include")
include_directories(SYSTEM "${CMAKE_CURRENT_SOURCE_DIR}/spirv-headers/include")

# Add optimizations and release mode definitions for all targets including llama.cpp and ggml
add_compile_options(-O3 -DNDEBUG -ffunction-sections -fdata-sections)

# Interprocedural Optimization (LTO) for Release and RelWithDebInfo configurations
if(CMAKE_BUILD_TYPE STREQUAL "Release" OR CMAKE_BUILD_TYPE STREQUAL "RelWithDebInfo")
    list(INSERT CMAKE_MODULE_PATH 0 "${CMAKE_CURRENT_SOURCE_DIR}/cmake")
    set(CMAKE_ANDROID_NDK_VERSION "28.2" CACHE STRING "" FORCE)
    set(CMAKE_C_COMPILE_OPTIONS_IPO "-flto=thin")
    set(CMAKE_CXX_COMPILE_OPTIONS_IPO "-flto=thin")
    set(CMAKE_C_LINK_OPTIONS_IPO "-flto=thin;-fuse-ld=lld")
    set(CMAKE_CXX_LINK_OPTIONS_IPO "-flto=thin;-fuse-ld=lld")
    set(CMAKE_C_LINK_OPTIONS_IPO "-flto=thin;-fuse-ld=lld" CACHE STRING "" FORCE)
    set(CMAKE_CXX_LINK_OPTIONS_IPO "-flto=thin;-fuse-ld=lld" CACHE STRING "" FORCE)

    include(CheckIPOSupported)
    check_ipo_supported(RESULT ipo_supported LANGUAGES C CXX)
    if(ipo_supported)
        set(CMAKE_INTERPROCEDURAL_OPTIMIZATION ON)
        set(CMAKE_C_COMPILE_OPTIONS_IPO "-flto=thin")
        set(CMAKE_CXX_COMPILE_OPTIONS_IPO "-flto=thin")
        set(CMAKE_C_LINK_OPTIONS_IPO "-flto=thin;-fuse-ld=lld")
        set(CMAKE_CXX_LINK_OPTIONS_IPO "-flto=thin;-fuse-ld=lld")
        set(CMAKE_C_LINK_OPTIONS_IPO "-flto=thin;-fuse-ld=lld" CACHE STRING "" FORCE)
        set(CMAKE_CXX_LINK_OPTIONS_IPO "-flto=thin;-fuse-ld=lld" CACHE STRING "" FORCE)
        set(GGML_LTO ON CACHE BOOL "Enable LTO in ggml" FORCE)
    else()
        message(FATAL_ERROR "Interprocedural Optimization (IPO/LTO) is not supported by the toolchain for C/CXX in ${CMAKE_BUILD_TYPE} configuration.")
    endif()
endif()

# Ensure 16 KB page-size alignment and lld linker for Android 15+ devices
add_link_options("-Wl,-z,max-page-size=16384" "-fuse-ld=lld")

add_subdirectory(llama.cpp)

add_library(
    solus_llama
    SHARED
    llama-jni.cpp
    llama-multimodal.cpp
)

target_link_options(
    solus_llama
    PRIVATE
    "-Wl,--version-script,${CMAKE_CURRENT_SOURCE_DIR}/solus-llama.map"
    "-Wl,--no-undefined-version"
    "-Wl,--gc-sections"
)

set_target_properties(
    solus_llama
    PROPERTIES
    LINK_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/solus-llama.map"
)

find_library(
    vulkan-lib
    vulkan
)

target_include_directories(
    solus_llama
    PRIVATE
    llama.cpp/include
    llama.cpp/src
    llama.cpp/common
    llama.cpp/tools/mtmd
    llama.cpp/ggml/include
    llama.cpp/ggml/src
    llama.cpp/ggml/src/ggml-vulkan
    vulkan-headers/include
    spirv-headers/include
)

find_library(
    log-lib
    log
)

find_library(
    android-lib
    android
)

target_link_libraries(
    solus_llama
    PRIVATE
    llama
    llama-common
    mtmd
    ggml
    ${log-lib}
    ${vulkan-lib}
    ${android-lib}
)

# Native test harness executable for deterministic C++ verification (opt-in)
option(SOLUS_BUILD_TEST_RUNNER "Build native verification test runner" OFF)
if(SOLUS_BUILD_TEST_RUNNER)
    add_executable(
        llama_native_test_runner
        llama-native-test-runner.cpp
    )

    target_include_directories(
        llama_native_test_runner
        PRIVATE
        llama.cpp/include
        llama.cpp/src
        llama.cpp/common
        llama.cpp/tools/mtmd
        llama.cpp/ggml/include
        llama.cpp/ggml/src
    )

    target_link_libraries(
        llama_native_test_runner
        PRIVATE
        llama
        llama-common
        mtmd
        ggml
        ${log-lib}
        ${vulkan-lib}
    )
endif()
