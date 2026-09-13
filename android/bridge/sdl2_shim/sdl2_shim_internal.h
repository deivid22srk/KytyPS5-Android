/*
 * SDL2 Android-bridge shim — internal header (x86_64 side, runs under box64).
 *
 * Implements the subset of the SDL2 API used by kyty_emulator on top of the
 * KytyPS5 Android host bridge (kyty_bridge.h): input events, game controllers,
 * queued audio and the Vulkan window/surface are provided by the ARM64 host.
 */

#ifndef KYTY_SDL2_SHIM_INTERNAL_H_
#define KYTY_SDL2_SHIM_INTERNAL_H_

#include "kyty_bridge.h"

#include "SDL.h"
#include "SDL_audio.h"
#include "SDL_error.h"
#include "SDL_events.h"
#include "SDL_gamecontroller.h"
#include "SDL_joystick.h"
#include "SDL_keyboard.h"
#include "SDL_mouse.h"
#include "SDL_stdinc.h"
#include "SDL_thread.h"
#include "SDL_timer.h"
#include "SDL_video.h"

#include <pthread.h>

/* GCC/Clang atomic builtins — identical semantics in C and C++ */
#define KYTY_ALOAD(p) __atomic_load_n((p), __ATOMIC_ACQUIRE)
#define KYTY_ASTORE(p, v) __atomic_store_n((p), (v), __ATOMIC_RELEASE)
#define KYTY_AFENCE() __atomic_thread_fence(__ATOMIC_SEQ_CST)

#ifdef __cplusplus
extern "C" {
#endif

/* ---- shim-side window ---------------------------------------------------- */

struct SDL_Window {
        int w;
        int h;
        uint32_t flags;
        uint32_t id;
        int fullscreen;
        int focused;
        int shown;
};

/* ---- shim-side game controller ------------------------------------------- */

typedef struct ShimGameController {
        int used;
        int slot; /* index into KytyBridgeShm::pads */
        SDL_JoystickID instance;
} ShimGameController;

/* ---- shim-side audio device ---------------------------------------------- */

typedef struct ShimAudioDev {
        int used;
        SDL_AudioDeviceID id;
        int shm_slot;
        SDL_AudioSpec spec;
        int is_open;
        int paused;
        int queue_bytes; /* bytes handed to the drain path but not yet in ring */
        pthread_mutex_t queue_mutex;
        SDL_AudioCallback callback;
        void *userdata;
        pthread_t cb_thread;
        int cb_thread_running;
} ShimAudioDev;

/* ---- shared state --------------------------------------------------------- */

typedef struct ShimState {
        int inited;
        uint32_t init_flags;
        KytyBridgeShm *shm;
        pthread_mutex_t ev_mutex;
        pthread_cond_t ev_cond;
        int ev_quit; /* drain thread should stop */
        pthread_t drain_thread;
        int drain_running;
        /* local event queue (multi-producer: drain thread + SDL_PushEvent) */
        SDL_Event *ev_queue;
        size_t ev_q_head, ev_q_tail, ev_q_cap;
        /* keyboard / mouse state */
        int text_input_active;
        int mouse_x, mouse_y;
        int mouse_rel_x, mouse_rel_y;
        uint32_t mouse_buttons; /* SDL_BUTTON_LMASK style */
        int relative_mouse_mode;
        int focused;
        /* game controllers */
        ShimGameController pads[KYTY_BRIDGE_MAX_PADS];
        int pad_count;
        uint32_t next_window_id;
        uint32_t user_event_base;
        char error_buf[256];
        char base_path[4096];
        struct SDL_Window *focus_window;
} ShimState;

extern ShimState g_shim;

/* helpers */
void ShimSetError(const char *fmt, ...);
int ShimBridgeAttach(void); /* returns 1 on success; lazy attach */
void ShimPumpRingEvents(void); /* move bridge events into the local queue */
int ShimLocalPush(const SDL_Event *ev); /* thread-safe local enqueue + signal */
uint64_t ShimTicks64Ms(void);

/* key mapping (android keycode -> SDL_Keycode) */
SDL_Keycode ShimKeyFromAndroid(int android_keycode);
uint16_t ShimKeyModFromAndroid(uint32_t android_meta);

/* game controller helpers */
ShimGameController *ShimPadByInstance(SDL_JoystickID instance);
ShimGameController *ShimPadByIndex(int slot);

/* audio helpers */
ShimAudioDev *ShimAudioDevById(SDL_AudioDeviceID id);

#ifdef __cplusplus
} /* extern "C" */
#endif

#endif /* KYTY_SDL2_SHIM_INTERNAL_H_ */
