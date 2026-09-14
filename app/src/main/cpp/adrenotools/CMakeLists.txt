cmake_minimum_required(VERSION 3.14)

if(NOT ${CMAKE_ANDROID_ARCH_ABI} STREQUAL arm64-v8a)
	message(FATAL_ERROR "Unsupported target architecture: ${CMAKE_ANDROID_ARCH_ABI}. Please make an issue on the repo!")
endif()

project(adrenotools LANGUAGES CXX C)

# the static libs (adrenotools, linkernsbypass) are linked into the app's
# SHARED libkytyhost.so, so they must be built as position-independent code
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(GEN_INSTALL_TARGET OFF CACHE BOOL "")

add_subdirectory(lib/linkernsbypass)

# bcenabler.cpp is excluded: it needs gen/bcenabler_patch.h produced by
# build_asm.sh (BCn texture patching, unused by this app - we only need the
# custom driver loading path).
set(LIB_SOURCES src/driver.cpp
                include/adrenotools/driver.h
                include/adrenotools/priv.h)

add_library(adrenotools ${LIB_SOURCES})

target_include_directories(adrenotools PUBLIC include)
target_include_directories(adrenotools PRIVATE .)
target_compile_options(adrenotools PRIVATE -Wall -Wextra)
target_link_libraries(adrenotools android linkernsbypass)

add_subdirectory(src/hook)
if (${BUILD_SHARED_LIBS})
    target_link_options(adrenotools PRIVATE "-Wl,-s,--exclude-libs,liblinkernsbypass.a")
endif()
if (${GEN_INSTALL_TARGET})
  configure_file(adrenotools.pc.in adrenotools.pc @ONLY)
  install(FILES ${CMAKE_BINARY_DIR}/adrenotools.pc DESTINATION lib/pkgconfig)
  install(TARGETS adrenotools main_hook file_redirect_hook gsl_alloc_hook hook_impl)
  install(DIRECTORY include/
          DESTINATION include
          FILES_MATCHING PATTERN "*.h*")
endif()
