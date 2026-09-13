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

find_library(log-lib log)
find_library(android-lib android)
find_library(aaudio-lib aaudio)
find_library(vulkan-lib vulkan)

target_link_libraries(kytyhost
	${log-lib}
	${android-lib}
	${aaudio-lib}
	${vulkan-lib}
)
