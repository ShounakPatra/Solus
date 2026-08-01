cmake_minimum_required(VERSION 3.22.1)

project("solus_llama")

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Compile options for ARM64 performance
if(CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
    add_compile_options(-O3 -flto -ffast-math)
endif()

add_library(
    solus_llama
    SHARED
    llama-jni.cpp
)

find_library(
    log-lib
    log
)

target_link_libraries(
    solus_llama
    ${log-lib}
)
