/*
 * KytyPS5 Android Host Bridge — shared protocol
 *
 * This header defines the neutral binary protocol spoken between:
 *   - the ARM64 host library (android/host), which owns the ANativeWindow,
 *     the audio output (AAudio), the input sources and the in-process
 *     box64 session, and
 *   - the SDL2 compatibility shim (android/bridge/sdl2_shim) that is linked
 *     into the x86_64 kyty_emulator build and runs under box64 translation.
 *
 * Both sides map the same shared-memory file (created by the host) and
 * communicate through lock-free single-producer/single-consumer rings plus a
 * small state area. The layout is identical on x86_64 and aarch64 (explicit
 * fixed-width fields, natural alignment, little-endian on both targets).
 *
 * Environment variables understood by the shim:
 *   KYTY_BRIDGE_SHM   (required) path to the shared memory file
 *   KYTY_BASE_PATH    (optional) directory of the emulator binary
 */

#ifndef KYTY_ANDROID_BRIDGE_H_
#define KYTY_ANDROID_BRIDGE_H_

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define KYTY_BRIDGE_MAGIC UINT64_C(0x4B59545942524944) /* "KYTYBRID" */
#define KYTY_BRIDGE_VERSION 1u

/* ---- limits ------------------------------------------------------------ */

#define KYTY_BRIDGE_INPUT_SLOTS 512u
#define KYTY_BRIDGE_MAX_PADS 8u
#define KYTY_BRIDGE_MAX_AUDIO_DEVS 4u
#define KYTY_BRIDGE_AUDIO_RING_BYTES (512u * 1024u)
#define KYTY_BRIDGE_RUMBLE_SLOTS 32u
#define KYTY_BRIDGE_MAX_TEXT 24u

/* ---- audio formats (neutral; SDL_AudioFormat mapped by the shim) ------- */

enum {
        KYTY_AUDIO_FMT_S16 = 0,
        KYTY_AUDIO_FMT_S32 = 1,
        KYTY_AUDIO_FMT_F32 = 2,
        KYTY_AUDIO_FMT_INVALID = 0xFFu,
};

/* ---- neutral event types ------------------------------------------------ */

enum {
        /* keyboard / mouse */
        KYTY_EV_KEY_DOWN = 1,
        KYTY_EV_KEY_UP = 2,
        KYTY_EV_TEXT_INPUT = 3, /* bytes[] = utf-8 fragment, p1 = length */
        KYTY_EV_MOUSE_MOVE = 4, /* p1 = x, p2 = y, p3 = relative dx, p4 = relative dy */
        KYTY_EV_MOUSE_BUTTON = 5, /* p1 = button(1..5), p2 = down(0/1) */
        KYTY_EV_MOUSE_WHEEL = 6, /* f1 = dx, f2 = dy */
        KYTY_EV_FINGER_DOWN = 7, /* f1 = x, f2 = y (normalized), p1 = finger id */
        KYTY_EV_FINGER_MOTION = 8,
        KYTY_EV_FINGER_UP = 9,

        /* game controllers */
        KYTY_EV_PAD_ADD = 16, /* p1 = device index (pad slot), p2 = instance id */
        KYTY_EV_PAD_REMOVE = 17, /* p1 = instance id */
        KYTY_EV_PAD_AXIS = 18, /* p1 = instance, p2 = axis, p3 = value (int16) */
        KYTY_EV_PAD_BUTTON = 19, /* p1 = instance, p2 = button, p3 = down(0/1) */
        KYTY_EV_PAD_TOUCHPAD = 20, /* p1 = instance, p2 = finger, p3 = phase(0=down,1=motion,2=up), f1 = x, f2 = y */

        /* window / lifecycle */
        KYTY_EV_WINDOW_RESIZED = 32, /* p1 = w, p2 = h */
        KYTY_EV_WINDOW_FOCUS = 33, /* p1 = gained(1)/lost(0) */
        KYTY_EV_WINDOW_CLOSE = 34,
        KYTY_EV_APP_ENTER_BG = 35,
        KYTY_EV_APP_EXIT_BG = 36,
        KYTY_EV_APP_TERMINATING = 37,
        KYTY_EV_APP_LOW_MEMORY = 38,
        KYTY_EV_ORIENTATION = 39, /* p1 = neutral orientation */

        KYTY_EV_QUIT = 64,
};

/* neutral pad types */
enum {
        KYTY_PAD_TYPE_OTHER = 0,
        KYTY_PAD_TYPE_PS5 = 1,
        KYTY_PAD_TYPE_PS4 = 2,
        KYTY_PAD_TYPE_XBOX = 3,
};

/* neutral orientations */
enum {
        KYTY_ORIENT_UNKNOWN = 0,
        KYTY_ORIENT_LANDSCAPE = 1,
        KYTY_ORIENT_LANDSCAPE_FLIPPED = 2,
        KYTY_ORIENT_PORTRAIT = 3,
        KYTY_ORIENT_PORTRAIT_FLIPPED = 4,
};

/* one neutral event; 36 bytes, pad-free */
typedef struct KytyBridgeEvent {
        uint32_t type;
        int32_t p1;
        int32_t p2;
        int32_t p3;
        int32_t p4;
        float f1;
        float f2;
        uint8_t bytes[8]; /* small payloads (utf-8 fragments) */
} KytyBridgeEvent;

#if defined(__cplusplus)
static_assert(sizeof(KytyBridgeEvent) == 36, "KytyBridgeEvent size");
#else
_Static_assert(sizeof(KytyBridgeEvent) == 36, "KytyBridgeEvent size");
#endif

/* ---- generic SPSC byte/record ring ------------------------------------- */

typedef struct KytyRingHeader {
        volatile uint32_t head; /* writer increments after commit */
        volatile uint32_t tail; /* reader increments after consume */
        uint32_t capacity; /* element count */
        uint32_t pad0;
} KytyRingHeader;

/* ---- pad descriptor table (host writes, shim reads) --------------------- */

typedef struct KytyBridgePad {
        volatile uint32_t in_use;
        uint32_t instance_id;
        uint32_t type; /* KYTY_PAD_TYPE_* */
        uint32_t connected;
        char name[64];
} KytyBridgePad;

/* ---- audio slot (shim writes PCM, host drains into AAudio) -------------- */

typedef struct KytyBridgeAudio {
        volatile uint32_t state; /* 0=free 1=open 2=closed */
        uint32_t device_id; /* SDL device id assigned by shim */
        volatile int32_t freq;
        volatile uint32_t channels;
        volatile uint32_t format; /* KYTY_AUDIO_FMT_* */
        volatile uint32_t paused;
        volatile uint64_t queued_total; /* bytes ever queued */
        volatile uint64_t dropped_total; /* bytes dropped on overflow */
        KytyRingHeader ring; /* byte ring, capacity in bytes */
        uint8_t data[KYTY_BRIDGE_AUDIO_RING_BYTES];
} KytyBridgeAudio;

/* ---- rumble command (shim writes, host executes) ------------------------ */

typedef struct KytyBridgeRumble {
        uint32_t instance_id;
        uint32_t duration_ms;
        uint16_t low; /* 0..65535 */
        uint16_t high; /* 0..65535 */
        uint32_t pad0;
} KytyBridgeRumble;

/* ---- shared state -------------------------------------------------------- */

typedef struct KytyBridgeState {
        /* written by host before exec; read by shim */
        volatile uint64_t native_window; /* ANativeWindow* (host pointer, as integer) */
        volatile uint32_t surface_seq; /* bumped when surface (re)created; odd = ready */
        volatile uint32_t window_w;
        volatile uint32_t window_h;
        volatile uint32_t display_dpi_x;
        volatile uint32_t display_dpi_y;
} KytyBridgeState;

/* ---- top-level shared memory layout -------------------------------------- */

typedef struct KytyBridgeShm {
        volatile uint64_t magic;
        volatile uint32_t version;
        volatile uint32_t pid_host; /* host lib writes */
        volatile uint32_t pid_guest; /* shim writes after attach */

        KytyBridgeState state;

        /* input: host writes events, shim reads */
        KytyRingHeader input_ring;
        KytyBridgeEvent input_events[KYTY_BRIDGE_INPUT_SLOTS];

        /* pads table */
        KytyBridgePad pads[KYTY_BRIDGE_MAX_PADS];

        /* audio slots */
        KytyBridgeAudio audio[KYTY_BRIDGE_MAX_AUDIO_DEVS];

        /* rumble: shim writes, host reads */
        KytyRingHeader rumble_ring; /* element = KytyBridgeRumble */
        KytyBridgeRumble rumbles[KYTY_BRIDGE_RUMBLE_SLOTS];

        volatile uint32_t guest_ready; /* shim sets 1 when attached */
        volatile uint32_t host_alive; /* host sets 1, cleared on exit */
} KytyBridgeShm;

#ifdef __cplusplus
} /* extern "C" */
#endif

#endif /* KYTY_ANDROID_BRIDGE_H_ */
