cmake_minimum_required(VERSION 3.22.1)

project("solus_llama" C CXX)

# Require 64-bit architecture - drop armeabi-v7a support
if(ANDROID_ABI STREQUAL "armeabi-v7a" OR ANDROID_ABI STREQUAL "armeabi")
    message(FATAL_ERROR "armeabi-v7a (32-bit ARM) is not supported in Solus. On-device LLM inference strictly requires 64-bit architectures (arm64-v8a or x86_64).")
endif()

# Set C and C++ standards
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

# Configure llama.cpp / ggml options before adding subdirectory
set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build static llama libs" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "Build common utils library" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "Build tests" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "Build examples" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "Build server example" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "Use libcurl" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "Use OpenMP" FORCE)
set(GGML_VULKAN ON CACHE BOOL "Use Vulkan" FORCE)
set(Vulkan_INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/vulkan-headers/include" CACHE PATH "Vulkan include directory" FORCE)
set(Vulkan_INCLUDE_DIRS "${CMAKE_CURRENT_SOURCE_DIR}/vulkan-headers/include" CACHE PATH "Vulkan include directory" FORCE)
include_directories(SYSTEM "${CMAKE_CURRENT_SOURCE_DIR}/vulkan-headers/include")

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
    llama.cpp/ggml/include
    llama.cpp/ggml/src/ggml-vulkan
    vulkan-headers/include
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
    ggml
    ${log-lib}
    ${vulkan-lib}
    ${android-lib}
)

# Native test harness executable for deterministic C++ verification
add_executable(
    llama_native_test_runner
    llama-native-test-runner.cpp
)

target_include_directories(
    llama_native_test_runner
    PRIVATE
    llama.cpp/include
    llama.cpp/src
    llama.cpp/ggml/include
    llama.cpp/ggml/src
)

target_link_libraries(
    llama_native_test_runner
    PRIVATE
    llama
    ggml
    ${log-lib}
    ${vulkan-lib}
)

