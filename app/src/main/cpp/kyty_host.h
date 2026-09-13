/*
 * KytyPS5 Android port — host library (runs in the app process).
 *
 * Owns the ANativeWindow, the shared-memory bridge, in-process box64
 * execution of the x86_64 emulator, AAudio playback and input injection.
 *
 * Process model (in-process, Winlator-style):
 *   - libbox64.so is dlopen()ed here and box64_main() is called on a
 *     dedicated pthread with a large stack. The x86_64 emulator therefore
 *     runs INSIDE the app process, which keeps the ANativeWindow pointer
 *     (published through the bridge) valid for vkCreateAndroidSurfaceKHR.
 *   - The guest's exit() is bridged back by a box64 patch (longjmp), so a
 *     guest error path ends the session instead of killing the app.
 *   - Guest stdout/stderr are redirected to a real log file via freopen()
 *     and streamed to the UI.
 */

#ifndef KYTY_ANDROID_HOST_H_
#define KYTY_ANDROID_HOST_H_

#include "kyty_bridge.h"

#include <aaudio/AAudio.h>
#include <android/native_window.h>
#include <jni.h>
#include <setjmp.h>
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

	/* in-process box64 */
	void *box64_lib = nullptr; /* dlopen handle */
	int (*box64_main)(int argc, const char **argv, char **env) = nullptr;
	pthread_t emu_thread {};
	std::atomic<bool> emu_thread_running{false};
	bool emu_thread_started = false;

	/* session */
	std::atomic<bool> running{false};
	std::atomic<int> exit_code{-1};
	std::mutex start_mutex; /* one session at a time */

	/* log capture (guest stdout/stderr redirected into this file) */
	std::mutex log_mutex;
	FILE *log_file = nullptr;   /* our side, for tailing */
	uint64_t log_offset = 0;
	std::string log_tail_buffer;

	std::thread rumble_thread;

	/* surface */
	std::mutex surface_mutex;
	ANativeWindow *window = nullptr;
	uint32_t surface_seq_val = 0;

	/* audio */
	std::mutex audio_mutex;
	struct AudioSlot {
		std::atomic<bool> active{false};
		std::atomic<bool> stop{false};
		std::thread thread; /* detached lifecycle */
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

/* session: loads libbox64.so, applies env, redirects guest stdio and runs
 * argv (argv[0] = x86_64 emulator path, then its CLI flags). */
bool HostStart(const std::string &workdir, const std::vector<std::string> &args,
               const std::vector<std::pair<std::string, std::string>> &env);
void HostRequestQuit();

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
std::string HostReadLogTail(); /* thread-safe tail of the guest log file */

} // namespace KytyHost

#endif /* KYTY_ANDROID_HOST_H_ */
