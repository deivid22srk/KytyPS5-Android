# KytyPS5 Android port — ARM64 host library (loaded by the app process)

cmake_minimum_required(VERSION 3.22)
project(kytyhost LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# bridge protocol header lives in the emulator tree: <repo>/android/bridge
get_filename_component(KYTY_REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)
set(KYTY_BRIDGE_DIR "${KYTY_REPO_ROOT}/android/bridge")

add_library(kytyhost SHARED
	kyty_host_jni.cpp
	kyty_host_bridge.cpp
	kyty_host_audio.cpp
)

target_include_directories(kytyhost PRIVATE
	"${KYTY_BRIDGE_DIR}"
	"${CMAKE_CURRENT_SOURCE_DIR}"
)

# --- adrenotools: rootless custom Vulkan driver loading (e.g. Turnip) ---
# Builds libadrenotools.a (linked into kytyhost) plus the hook shared
# libraries (libmain_hook.so, libhook_impl.so, libfile_redirect_hook.so,
# libgsl_alloc_hook.so) that must be packaged inside the APK - they are
# dlopen()ed from nativeLibraryDir inside an isolated linker namespace.
# Vendored from https://github.com/bylaws/libadrenotools (BSD-2-Clause),
# linkernsbypass from https://github.com/bylaws/liblinkernsbypass.
set(CMAKE_ANDROID_ARCH_ABI arm64-v8a) # defensive: vendored CMake checks it
set(GEN_INSTALL_TARGET OFF)
add_subdirectory(adrenotools)

find_library(log-lib log)
find_library(android-lib android)
find_library(aaudio-lib aaudio)
find_library(vulkan-lib vulkan)

target_link_libraries(kytyhost
	${log-lib}
	${android-lib}
	${aaudio-lib}
	${vulkan-lib}
	adrenotools
)
