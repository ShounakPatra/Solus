cmake_minimum_required(VERSION 3.15)
project(solus_host_tests CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

enable_testing()

add_executable(utf_conversion_test
    utf-conversion-test.cpp
)

target_include_directories(utf_conversion_test
    PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/../../main/cpp"
)

add_test(NAME utf_conversion_test COMMAND utf_conversion_test)

add_executable(runtime_warmup_test runtime-warmup-test.cpp)
target_include_directories(runtime_warmup_test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/../../main/cpp")
add_test(NAME runtime_warmup_test COMMAND runtime_warmup_test)
