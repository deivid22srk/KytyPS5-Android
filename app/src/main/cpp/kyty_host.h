/*
 * KytyPS5 Android port — ARM64 host library (runs in the app process).
 *
 * Owns the ANativeWindow, the shared-memory bridge, the box64 child process,
 * AAudio playback and input injection for the x86_64 emulator that runs
 * under box64 translation.
 */

#ifndef KYTY_ANDROID_HOST_H_
#define KYTY_ANDROID_HOST_H_

#include "kyty_bridge.h"

#include <aaudio/AAudio.h>
#include <android/native_window.h>
#include <jni.h>
#include <vulkan/vulkan.h>

#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>
#include <utility>
#include <vector>

namespace KytyHost {

struct HostState {
        bool initialized = false;

        std::string files_root;      /* <filesDir>/kyty */
        std::string native_lib_dir;  /* applicationInfo.nativeLibraryDir */

        /* bridge */
        int shm_fd = -1;
        std::string shm_path;
        KytyBridgeShm *shm = nullptr;

        /* child process */
        std::mutex proc_mutex;
        pid_t child_pid = -1;
        std::atomic<bool> running{false};
        std::atomic<int> exit_code{-1};
        int stdout_pipe = -1;
        int stderr_pipe = -1;
        std::thread reader_thread;
        std::thread waiter_thread;
        std::thread rumble_thread;

        /* log ring */
        std::mutex log_mutex;
        std::string log_buffer; /* ring, capped */
        std::string log_file_path;
        FILE *log_file = nullptr;

        /* surface */
        std::mutex surface_mutex;
        ANativeWindow *window = nullptr;
        uint32_t surface_seq_val = 0;

        /* audio */
        std::mutex audio_mutex;
        struct AudioSlot {
                std::atomic<bool> active{false};
                std::atomic<bool> stop{false};
                std::thread thread;
                AAudioStream *stream = nullptr;
                int shm_slot = -1;
        } audio[KYTY_BRIDGE_MAX_AUDIO_DEVS];
        std::thread audio_monitor;

        /* jvm for callbacks */
        JavaVM *vm = nullptr;
        jobject java_callback = nullptr; /* global ref to EmuCallbacks */

        std::atomic<bool> shutdown{false};
};

HostState &Host();

/* lifecycle */
bool HostInit(const std::string &files_root, const std::string &native_lib_dir);
void HostShutdown();

/* process */
bool HostStart(const std::string &binary, const std::string &workdir,
               const std::vector<std::string> &args,
               const std::vector<std::pair<std::string, std::string>> &env);
void HostRequestQuit();
void HostKill();

/* surface */
void HostSetSurface(ANativeWindow *window, uint32_t w, uint32_t h);
void HostClearSurface();

/* input */
void HostSendKey(int32_t keycode, bool down, uint32_t meta);
void HostSendText(const std::string &utf8);
void HostSendMouse(int32_t x, int32_t y, int32_t dx, int32_t dy, int32_t buttons,
                   int32_t wheel_dx, int32_t wheel_dy);
void HostSendFinger(int32_t finger, int32_t phase, float x, float y);
int HostPadConnect(uint32_t type, const std::string &name); /* returns instance id */
void HostPadDisconnect(int32_t instance);
void HostPadAxis(int32_t instance, int32_t axis, int32_t value);
void HostPadButton(int32_t instance, int32_t button, bool down);
void HostPadTouchpad(int32_t instance, int32_t finger, int32_t phase, float x, float y);
void HostSendWindowResized(uint32_t w, uint32_t h);
void HostSendLifecycle(int32_t ev); /* KYTY_EV_APP_* */
void HostSendOrientation(int32_t orientation);

/* internal */
void HostPushEvent(const KytyBridgeEvent &ev);
void HostAudioMonitorMain();
std::string HostEnumerateVulkanDevices();

} // namespace KytyHost

#endif /* KYTY_ANDROID_HOST_H_ */
