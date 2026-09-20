cmake_minimum_required(VERSION 3.22.1)

project("solus_llama" C CXX)

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

# Add optimizations and release mode definitions for all targets including llama.cpp and ggml
add_compile_options(-O3 -DNDEBUG)

add_subdirectory(llama.cpp)

add_library(
    solus_llama
    SHARED
    llama-jni.cpp
)

target_include_directories(
    solus_llama
    PRIVATE
    llama.cpp/include
    llama.cpp/src
    llama.cpp/ggml/include
)

find_library(
    log-lib
    log
)

target_link_libraries(
    solus_llama
    PRIVATE
    llama
    ggml
    ${log-lib}
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
)

