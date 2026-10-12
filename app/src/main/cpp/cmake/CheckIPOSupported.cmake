# Custom CheckIPOSupported module for Android NDK toolchains in CMake 3.22
# Passes CMAKE_TOOLCHAIN_FILE and NDK platform variables to try_compile.

cmake_policy(PUSH)
cmake_policy(SET CMP0069 NEW)

macro(_ipo_not_supported output)
  if(NOT X_RESULT)
    message(FATAL_ERROR "IPO is not supported (${output}).")
  endif()

  set("${X_RESULT}" NO PARENT_SCOPE)
  if(X_OUTPUT)
    set("${X_OUTPUT}" "${output}" PARENT_SCOPE)
  endif()
endmacro()

macro(_ipo_run_language_check language)
  set(testdir "${CMAKE_CURRENT_BINARY_DIR}/CMakeFiles/_CMakeLTOTest-${language}")

  file(REMOVE_RECURSE "${testdir}")
  file(MAKE_DIRECTORY "${testdir}")

  set(bindir "${testdir}/bin")
  set(srcdir "${testdir}/src")

  file(MAKE_DIRECTORY "${bindir}")
  file(MAKE_DIRECTORY "${srcdir}")

  set(TRY_COMPILE_PROJECT_NAME "lto-test")
  set(try_compile_src "${CMAKE_ROOT}/Modules/CheckIPOSupported")

  configure_file(
      "${try_compile_src}/CMakeLists-${language}.txt.in"
      "${srcdir}/CMakeLists.txt"
      @ONLY
  )

  string(COMPARE EQUAL "${language}" "C" is_c)
  string(COMPARE EQUAL "${language}" "CXX" is_cxx)
  string(COMPARE EQUAL "${language}" "Fortran" is_fortran)

  if(is_c)
    set(copy_sources foo.c main.c)
  elseif(is_cxx)
    set(copy_sources foo.cpp main.cpp)
  elseif(is_fortran)
    set(copy_sources foo.f main.f)
  else()
    message(FATAL_ERROR "Language not supported")
  endif()

  foreach(x ${copy_sources})
    configure_file(
        "${try_compile_src}/${x}"
        "${srcdir}/${x}"
        COPYONLY
    )
  endforeach()

  set(_extra_flags "")
  if(CMAKE_TOOLCHAIN_FILE)
    list(APPEND _extra_flags "-DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}")
  endif()
  if(ANDROID_ABI)
    list(APPEND _extra_flags "-DANDROID_ABI=${ANDROID_ABI}")
  endif()
  if(ANDROID_PLATFORM)
    list(APPEND _extra_flags "-DANDROID_PLATFORM=${ANDROID_PLATFORM}")
  endif()
  if(CMAKE_MAKE_PROGRAM)
    list(APPEND _extra_flags "-DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}")
  endif()

  list(APPEND _extra_flags
      "-DCMAKE_ANDROID_NDK_VERSION=28.2"
      "-DCMAKE_C_LINK_OPTIONS_IPO=-fuse-ld=lld"
      "-DCMAKE_CXX_LINK_OPTIONS_IPO=-fuse-ld=lld"
  )

  try_compile(
      _IPO_LANGUAGE_CHECK_RESULT
      "${bindir}"
      "${srcdir}"
      "${TRY_COMPILE_PROJECT_NAME}"
      CMAKE_FLAGS
      "-DCMAKE_VERBOSE_MAKEFILE=ON"
      "-DCMAKE_INTERPROCEDURAL_OPTIMIZATION=ON"
      ${_extra_flags}
      OUTPUT_VARIABLE output
  )
  set(_IPO_LANGUAGE_CHECK_RESULT "${_IPO_LANGUAGE_CHECK_RESULT}")
  unset(_IPO_LANGUAGE_CHECK_RESULT CACHE)

  if(NOT _IPO_LANGUAGE_CHECK_RESULT)
    file(APPEND ${CMAKE_BINARY_DIR}${CMAKE_FILES_DIRECTORY}/CMakeError.log
      "${language} compiler IPO check failed with the following output:\n"
      "${output}\n")
    _ipo_not_supported("check failed to compile")
    if(X_OUTPUT)
      set("${X_OUTPUT}" "${output}" PARENT_SCOPE)
    endif()
    return()
  endif()
endmacro()

function(check_ipo_supported)
  set(optional)
  set(one RESULT OUTPUT)
  set(multiple LANGUAGES)

  cmake_parse_arguments(X "${optional}" "${one}" "${multiple}" "${ARGV}")

  string(COMPARE NOTEQUAL "${X_UNPARSED_ARGUMENTS}" "" has_unparsed)
  if(has_unparsed)
    message(FATAL_ERROR "Unparsed arguments: ${X_UNPARSED_ARGUMENTS}")
  endif()

  string(COMPARE EQUAL "${X_LANGUAGES}" "" no_languages)
  if(no_languages)
    get_property(enabled_languages GLOBAL PROPERTY ENABLED_LANGUAGES)
    string(COMPARE EQUAL "${enabled_languages}" "" no_languages)
    if(no_languages)
      _ipo_not_supported("no languages found in ENABLED_LANGUAGES global property")
      return()
    endif()

    set(languages "")
    list(FIND enabled_languages "CXX" result)
    if(NOT result EQUAL -1)
      list(APPEND languages "CXX")
    endif()

    list(FIND enabled_languages "C" result)
    if(NOT result EQUAL -1)
      list(APPEND languages "C")
    endif()

    string(COMPARE EQUAL "${languages}" "" no_languages)
    if(no_languages)
      _ipo_not_supported("no C/CXX languages found in ENABLED_LANGUAGES global property")
      return()
    endif()
  else()
    set(languages "${X_LANGUAGES}")
    set(unsupported_languages "${languages}")
    list(REMOVE_ITEM unsupported_languages "C" "CXX")
    string(COMPARE NOTEQUAL "${unsupported_languages}" "" has_unsupported)
    if(has_unsupported)
      _ipo_not_supported("language(s) '${unsupported_languages}' not supported")
      return()
    endif()
  endif()

  foreach(lang ${languages})
    if(NOT _CMAKE_${lang}_IPO_SUPPORTED_BY_CMAKE)
      _ipo_not_supported("CMake doesn't support IPO for current ${lang} compiler")
      return()
    endif()

    if(NOT _CMAKE_${lang}_IPO_MAY_BE_SUPPORTED_BY_COMPILER)
      _ipo_not_supported("${lang} compiler doesn't support IPO")
      return()
    endif()
  endforeach()

  foreach(x ${languages})
    _ipo_run_language_check(${x})
  endforeach()

  if(X_RESULT)
    set("${X_RESULT}" YES PARENT_SCOPE)
  endif()
endfunction()

cmake_policy(POP)
