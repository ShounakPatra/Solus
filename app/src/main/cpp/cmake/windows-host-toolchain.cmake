# windows-host-toolchain.cmake
# Toolchain file for building host shader generator (vulkan-shaders-gen) on Windows

set(CMAKE_SYSTEM_NAME Windows)
set(CMAKE_SYSTEM_PROCESSOR AMD64)
set(CMAKE_EXE_LINKER_FLAGS "-static" CACHE STRING "Link host binaries statically" FORCE)

if (NOT CMAKE_MAKE_PROGRAM)
    get_filename_component(_CMAKE_BIN_DIR "${CMAKE_COMMAND}" DIRECTORY)
    if (EXISTS "${_CMAKE_BIN_DIR}/ninja.exe")
        set(CMAKE_MAKE_PROGRAM "${_CMAKE_BIN_DIR}/ninja.exe" CACHE FILEPATH "Ninja build program" FORCE)
    else()
        find_program(_HOST_NINJA_EXE NAMES ninja ninja.exe NO_CMAKE_FIND_ROOT_PATH)
        if (_HOST_NINJA_EXE)
            set(CMAKE_MAKE_PROGRAM "${_HOST_NINJA_EXE}" CACHE FILEPATH "Ninja build program" FORCE)
        endif()
    endif()
endif()

# Determine project root from this file location:
# app/src/main/cpp/cmake -> app/src/main/cpp -> app/src/main -> app/src -> app -> Solus root
get_filename_component(_SOLUS_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../.." ABSOLUTE)
set(_LLVM_MINGW_BIN "${_SOLUS_ROOT}/tools/llvm-mingw/bin")

if (EXISTS "${_LLVM_MINGW_BIN}/clang.exe" AND EXISTS "${_LLVM_MINGW_BIN}/clang++.exe")
    set(CMAKE_C_COMPILER "${_LLVM_MINGW_BIN}/clang.exe" CACHE FILEPATH "Host C compiler" FORCE)
    set(CMAKE_CXX_COMPILER "${_LLVM_MINGW_BIN}/clang++.exe" CACHE FILEPATH "Host C++ compiler" FORCE)
    set(CMAKE_AR "${_LLVM_MINGW_BIN}/llvm-ar.exe" CACHE FILEPATH "Host AR" FORCE)
    set(CMAKE_RANLIB "${_LLVM_MINGW_BIN}/llvm-ranlib.exe" CACHE FILEPATH "Host RANLIB" FORCE)
else()
    find_program(_HOST_C_COMPILER NAMES cl gcc clang NO_CMAKE_FIND_ROOT_PATH)
    find_program(_HOST_CXX_COMPILER NAMES cl g++ clang++ NO_CMAKE_FIND_ROOT_PATH)
    if (_HOST_C_COMPILER AND _HOST_CXX_COMPILER)
        set(CMAKE_C_COMPILER "${_HOST_C_COMPILER}" CACHE FILEPATH "Host C compiler" FORCE)
        set(CMAKE_CXX_COMPILER "${_HOST_CXX_COMPILER}" CACHE FILEPATH "Host C++ compiler" FORCE)
    else()
        message(FATAL_ERROR "No Windows host C++ compiler found. Please run tools/prepare-native-tools.ps1 to install portable LLVM-MinGW.")
    endif()
endif()
