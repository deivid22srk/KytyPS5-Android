/*
 * SDL2 Android-bridge shim — Vulkan loader and surface creation.
 *
 * The instance is created by the emulator through the loader returned here.
 * Under box64, dlopen("libvulkan.so.1") is intercepted by box64's wrapped
 * libvulkan and forwarded to the ARM64 host Vulkan loader (system driver,
 * e.g. Adreno/Mali). The ANativeWindow pointer published by the host in the
 * bridge state is a real host pointer; it is handed to
 * vkCreateAndroidSurfaceKHR unchanged and remains valid on the host side.
 */

#include "sdl2_shim_internal.h"

#include "SDL_vulkan.h"

#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>

#ifndef VK_USE_PLATFORM_ANDROID_KHR
#define VK_USE_PLATFORM_ANDROID_KHR 1
#endif
#include <vulkan/vulkan.h>

extern "C" {

static void *g_vulkan_lib = nullptr;
static void *g_gipa = nullptr; /* vkGetInstanceProcAddr */

static void *ShimLoadVulkan(void) {
        if (g_vulkan_lib != nullptr) {
                return g_vulkan_lib;
        }
        static const char *kCandidates[] = {
                "libvulkan.so.1",
                "libvulkan.so",
        };
        for (const char *name: kCandidates) {
                void *lib = dlopen(name, RTLD_NOW | RTLD_LOCAL);
                if (lib != nullptr) {
                        g_vulkan_lib = lib;
                        return lib;
                }
        }
        ShimSetError("bridge: cannot load a Vulkan loader (libvulkan.so.1)");
        return nullptr;
}

int SDL_Vulkan_LoadLibrary(const char *path) {
        (void)path;
        return (ShimLoadVulkan() != nullptr) ? 0 : -1;
}

void SDL_Vulkan_UnloadLibrary(void) {
        /* keep the loader resident; the process exits when emulation ends */
}

void *SDL_Vulkan_GetVkGetInstanceProcAddr(void) {
        void *lib = ShimLoadVulkan();
        if (lib == nullptr) {
                return nullptr;
        }
        if (g_gipa == nullptr) {
                g_gipa = dlsym(lib, "vkGetInstanceProcAddr");
        }
        return g_gipa;
}

SDL_bool SDL_Vulkan_GetInstanceExtensions(SDL_Window *window, unsigned int *pCount,
                                           const char **pNames) {
        if (window == nullptr || pCount == nullptr) {
                return SDL_FALSE;
        }
        static const char *kExtensions[] = {
                "VK_KHR_surface",
                "VK_KHR_android_surface",
        };
        const unsigned int count = 2;
        if (pNames == nullptr) {
                *pCount = count;
                return SDL_TRUE;
        }
        if (*pCount < count) {
                *pCount = count;
                return SDL_FALSE;
        }
        pNames[0] = kExtensions[0];
        pNames[1] = kExtensions[1];
        *pCount = count;
        return SDL_TRUE;
}

SDL_bool SDL_Vulkan_CreateSurface(SDL_Window *window, VkInstance instance,
                                   VkSurfaceKHR *surface) {
        if (window == nullptr || instance == VK_NULL_HANDLE || surface == nullptr) {
                return SDL_FALSE;
        }
        if (!ShimBridgeAttach()) {
                return SDL_FALSE;
        }

        /* wait (bounded) for the host to publish a live surface */
        uint64_t native_window = 0;
        uint32_t seq = 0;
        for (int i = 0; i < 500; ++i) { /* up to 5 s */
                seq = g_shim.shm->state.surface_seq;
                native_window = g_shim.shm->state.native_window;
                if (native_window != 0 && (seq & 1u) != 0u) {
                        break;
                }
                struct timespec ts {0, 10 * 1000000};
                nanosleep(&ts, nullptr);
        }
        if (native_window == 0 || (seq & 1u) == 0u) {
                ShimSetError("bridge: host surface is not ready");
                return SDL_FALSE;
        }

        void *gipa_raw = SDL_Vulkan_GetVkGetInstanceProcAddr();
        if (gipa_raw == nullptr) {
                return SDL_FALSE;
        }
        auto gipa = (PFN_vkGetInstanceProcAddr)gipa_raw;

        PFN_vkCreateAndroidSurfaceKHR create_android_surface =
            (PFN_vkCreateAndroidSurfaceKHR)gipa(instance, "vkCreateAndroidSurfaceKHR");
        if (create_android_surface == nullptr) {
                ShimSetError("bridge: vkCreateAndroidSurfaceKHR not available");
                return SDL_FALSE;
        }

        VkAndroidSurfaceCreateInfoKHR info {};
        info.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
        info.pNext = nullptr;
        info.flags = 0;
        info.window = (struct ANativeWindow *)(uintptr_t)native_window;

        VkSurfaceKHR out = VK_NULL_HANDLE;
        VkResult res = create_android_surface(instance, &info, nullptr, &out);
        if (res != VK_SUCCESS) {
                ShimSetError("bridge: vkCreateAndroidSurfaceKHR failed (%d)", (int)res);
                return SDL_FALSE;
        }
        *surface = out;
        return SDL_TRUE;
}

} /* extern "C" */
