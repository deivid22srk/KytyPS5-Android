# SDL2 Android-bridge shim (x86_64 side)
#
# Drop-in replacement for SDL2-static inside kyty_emulator when built with
# KYTY_ANDROID_BRIDGE=ON. Implements the SDL2 API surface used by the
# emulator on top of the shared-memory host bridge.

add_library(sdl2-android-shim STATIC
	sdl2_shim.cpp
	sdl2_shim_vulkan.cpp
	sdl2_shim_audio.cpp
	sdl2_shim_gamecontroller.cpp
	sdl2_shim_keys.cpp
)

target_include_directories(sdl2-android-shim PUBLIC
	"${KYTY_THIRD_PARTY_DIR}/SDL2/include"
	"${CMAKE_CURRENT_SOURCE_DIR}/.."
	"${KYTY_THIRD_PARTY_DIR}/Vulkan-Headers/include"
)

target_compile_definitions(sdl2-android-shim PUBLIC
	VK_USE_PLATFORM_ANDROID_KHR=1
)

set_target_properties(sdl2-android-shim PROPERTIES
	CXX_STANDARD 20
	CXX_STANDARD_REQUIRED ON
	POSITION_INDEPENDENT_CODE ON
)

if(NOT MSVC)
	target_compile_options(sdl2-android-shim PRIVATE -Wno-unused-parameter)
endif()
