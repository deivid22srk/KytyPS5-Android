/*
 * SDL2 Android-bridge shim — core: init, errors, time, base path, events,
 * keyboard / mouse / touch, window management. (x86_64 side, under box64.)
 */

#include "sdl2_shim_internal.h"

#include "SDL_filesystem.h"
#include "SDL_hints.h"
#include "SDL_pixels.h"
#include "SDL_rwops.h"
#include "SDL_surface.h"
#include "SDL_touch.h"

#include <dlfcn.h>
#include <errno.h>
#include <sched.h>
#include <stdarg.h>
#include <stdatomic.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

extern "C" {

ShimState g_shim;

/* -------------------------------------------------------------------------- */
/* errors / misc                                                              */
/* -------------------------------------------------------------------------- */

void ShimSetError(const char *fmt, ...) {
        va_list ap;
        va_start(ap, fmt);
        vsnprintf(g_shim.error_buf, sizeof(g_shim.error_buf), fmt, ap);
        va_end(ap);
}

const char *SDL_GetError(void) {
        return g_shim.error_buf;
}

void SDL_ClearError(void) {
        g_shim.error_buf[0] = '\0';
}

int SDL_SetError(const char *fmt, ...) {
        va_list ap;
        va_start(ap, fmt);
        vsnprintf(g_shim.error_buf, sizeof(g_shim.error_buf), fmt, ap);
        va_end(ap);
        return -1;
}

uint64_t ShimTicks64Ms(void) {
        struct timespec ts {};
        clock_gettime(CLOCK_MONOTONIC, &ts);
        return (uint64_t)ts.tv_sec * 1000u + (uint64_t)ts.tv_nsec / 1000000u;
}

Uint64 SDL_GetTicks64(void) {
        return ShimTicks64Ms();
}

void SDL_Delay(Uint32 ms) {
        struct timespec ts {};
        ts.tv_sec = ms / 1000u;
        ts.tv_nsec = (long)(ms % 1000u) * 1000000l;
        while (nanosleep(&ts, &ts) == -1 && errno == EINTR) {
        }
}

SDL_threadID SDL_ThreadID(void) {
        return (SDL_threadID)pthread_self();
}

int SDL_setenv(const char *name, const char *value, int overwrite) {
        return setenv(name, value, overwrite);
}

void SDL_free(void *ptr) {
        free(ptr);
}

char *SDL_GetBasePath(void) {
        const char *env = getenv("KYTY_BASE_PATH");
        if (env != nullptr && env[0] != '\0') {
                snprintf(g_shim.base_path, sizeof(g_shim.base_path), "%s", env);
                return strdup(g_shim.base_path);
        }

        char self[4096] = {};
        ssize_t n = readlink("/proc/self/exe", self, sizeof(self) - 1);
        if (n <= 0) {
                return nullptr;
        }
        self[n] = '\0';
        char *slash = strrchr(self, '/');
        if (slash == nullptr) {
                return nullptr;
        }
        slash[1] = '\0';
        snprintf(g_shim.base_path, sizeof(g_shim.base_path), "%s", self);
        return strdup(g_shim.base_path);
}

/* -------------------------------------------------------------------------- */
/* hints                                                                      */
/* -------------------------------------------------------------------------- */

SDL_bool SDL_SetHint(const char *name, const char *value) {
        (void)name;
        (void)value;
        return SDL_TRUE;
}

SDL_bool SDL_SetHintWithPriority(const char *name, const char *value, SDL_HintPriority priority) {
        (void)name;
        (void)value;
        (void)priority;
        return SDL_TRUE;
}

/* -------------------------------------------------------------------------- */
/* bridge attach                                                              */
/* -------------------------------------------------------------------------- */

int ShimBridgeAttach(void) {
        if (g_shim.shm != nullptr) {
                return 1;
        }
        const char *path = getenv("KYTY_BRIDGE_SHM");
        if (path == nullptr || path[0] == '\0') {
                ShimSetError("bridge: KYTY_BRIDGE_SHM is not set");
                return 0;
        }

        FILE *f = fopen(path, "rb+"); /* host must have created it */
        if (f == nullptr) {
                ShimSetError("bridge: cannot open %s (%s)", path, strerror(errno));
                return 0;
        }

        if (fseek(f, 0, SEEK_END) != 0) {
                fclose(f);
                ShimSetError("bridge: seek failed");
                return 0;
        }
        long size = ftell(f);
        if (size < (long)sizeof(KytyBridgeShm)) {
                fclose(f);
                ShimSetError("bridge: shm too small (%ld < %zu)", size, sizeof(KytyBridgeShm));
                return 0;
        }
        rewind(f);

        void *mem = malloc((size_t)size);
        if (mem == nullptr || fread(mem, 1, (size_t)size, f) != (size_t)size) {
                fclose(f);
                free(mem);
                ShimSetError("bridge: read failed");
                return 0;
        }
        fclose(f);

        KytyBridgeShm *shm = (KytyBridgeShm *)mem;
        if (shm->magic != KYTY_BRIDGE_MAGIC) {
                free(mem);
                ShimSetError("bridge: bad magic");
                return 0;
        }
        if (shm->version != KYTY_BRIDGE_VERSION) {
                free(mem);
                ShimSetError("bridge: version mismatch (shm=%u, shim=%u)", shm->version,
                             KYTY_BRIDGE_VERSION);
                return 0;
        }

        g_shim.shm = shm;
        KYTY_ASTORE(&shm->pid_guest, (uint32_t)getpid());
        KYTY_ASTORE(&shm->guest_ready, 1u);
        return 1;
}

/* -------------------------------------------------------------------------- */
/* init                                                                       */
/* -------------------------------------------------------------------------- */

Uint32 SDL_WasInit(Uint32 flags) {
        return g_shim.init_flags & flags;
}

static void ShimEnsureCommonInit(void) {
        if (!g_shim.inited) {
                pthread_mutex_init(&g_shim.ev_mutex, nullptr);
                pthread_cond_init(&g_shim.ev_cond, nullptr);
                g_shim.ev_q_cap = 256;
                g_shim.ev_queue = (SDL_Event *)calloc(g_shim.ev_q_cap, sizeof(SDL_Event));
                g_shim.next_window_id = 1;
                g_shim.user_event_base = SDL_USEREVENT;
                g_shim.focused = 1; /* assume focused until told otherwise */
                g_shim.inited = 1;
        }
}

static void ShimMaybeStartDrainThread(void) {
        if (g_shim.drain_running || g_shim.shm == nullptr) {
                return;
        }
        /* drain thread started lazily by event functions; created in sdl2_shim.c below */
        g_shim.drain_running = 1;
}

int SDL_Init(Uint32 flags) {
        ShimEnsureCommonInit();
        g_shim.init_flags |= flags;
        return 0;
}

int SDL_InitSubSystem(Uint32 flags) {
        ShimEnsureCommonInit();

        if ((flags & SDL_INIT_VIDEO) != 0u) {
                if (!ShimBridgeAttach()) {
                        return -1;
                }
        }
        if ((flags & SDL_INIT_AUDIO) != 0u) {
                if (!ShimBridgeAttach()) {
                        return -1;
                }
        }
        if ((flags & SDL_INIT_GAMECONTROLLER) != 0u) {
                if (!ShimBridgeAttach()) {
                        return -1;
                }
                /* synthesize hotplug events for pads the host registered before attach */
                for (uint32_t i = 0; i < KYTY_BRIDGE_MAX_PADS; ++i) {
                        const KytyBridgePad *pad = &g_shim.shm->pads[i];
                        if (KYTY_ALOAD(&pad->in_use) != 0u &&
                            pad->connected != 0u) {
                                SDL_Event ev {};
                                ev.type = SDL_CONTROLLERDEVICEADDED;
                                ev.cdevice.timestamp = (Uint32)ShimTicks64Ms();
                                ev.cdevice.which = (Sint32)i; /* device index */
                                ShimLocalPush(&ev);
                        }
                }
        }

        g_shim.init_flags |= flags;
        SDL_ClearError();
        return 0;
}

void SDL_QuitSubSystem(Uint32 flags) {
        g_shim.init_flags &= ~flags;
}

void SDL_Quit(void) {
        g_shim.init_flags = 0;
        if (g_shim.shm != nullptr) {
                KYTY_ASTORE(&g_shim.shm->guest_ready, 0u);
        }
}

/* -------------------------------------------------------------------------- */
/* local event queue                                                          */
/* -------------------------------------------------------------------------- */

int ShimLocalPush(const SDL_Event *ev) {
        pthread_mutex_lock(&g_shim.ev_mutex);
        if (g_shim.ev_q_head - g_shim.ev_q_tail >= g_shim.ev_q_cap) {
                /* grow */
                size_t new_cap = g_shim.ev_q_cap * 2;
                SDL_Event *nq = (SDL_Event *)calloc(new_cap, sizeof(SDL_Event));
                for (size_t i = g_shim.ev_q_tail; i < g_shim.ev_q_head; ++i) {
                        nq[i - g_shim.ev_q_tail] = g_shim.ev_queue[i % g_shim.ev_q_cap];
                }
                free(g_shim.ev_queue);
                g_shim.ev_queue = nq;
                g_shim.ev_q_head -= g_shim.ev_q_tail;
                g_shim.ev_q_tail = 0;
                g_shim.ev_q_cap = new_cap;
        }
        g_shim.ev_queue[g_shim.ev_q_head % g_shim.ev_q_cap] = *ev;
        g_shim.ev_q_head++;
        pthread_cond_broadcast(&g_shim.ev_cond);
        pthread_mutex_unlock(&g_shim.ev_mutex);
        return 1;
}

int SDL_PushEvent(SDL_Event *event) {
        return ShimLocalPush(event);
}

static int ShimLocalPop(SDL_Event *out) {
        int got = 0;
        pthread_mutex_lock(&g_shim.ev_mutex);
        if (g_shim.ev_q_tail < g_shim.ev_q_head) {
                *out = g_shim.ev_queue[g_shim.ev_q_tail % g_shim.ev_q_cap];
                g_shim.ev_q_tail++;
                got = 1;
        }
        pthread_mutex_unlock(&g_shim.ev_mutex);
        return got;
}

static int ShimLocalPeek(SDL_Event *out) {
        int got = 0;
        pthread_mutex_lock(&g_shim.ev_mutex);
        if (g_shim.ev_q_tail < g_shim.ev_q_head) {
                *out = g_shim.ev_queue[g_shim.ev_q_tail % g_shim.ev_q_cap];
                got = 1;
        }
        pthread_mutex_unlock(&g_shim.ev_mutex);
        return got;
}

/* -------------------------------------------------------------------------- */
/* ring -> SDL_Event translation                                              */
/* -------------------------------------------------------------------------- */

static void ShimApplyNeutralEvent(const KytyBridgeEvent *e) {
        SDL_Event ev {};
        ev.common.timestamp = (Uint32)ShimTicks64Ms();

        switch (e->type) {
                case KYTY_EV_KEY_DOWN:
                case KYTY_EV_KEY_UP: {
                        ev.type = (e->type == KYTY_EV_KEY_DOWN) ? SDL_KEYDOWN : SDL_KEYUP;
                        ev.key.windowID = 1;
                        ev.key.state = (e->type == KYTY_EV_KEY_DOWN) ? SDL_PRESSED : SDL_RELEASED;
                        ev.key.repeat = 0;
                        ev.key.keysym.sym = ShimKeyFromAndroid(e->p1);
                        ev.key.keysym.scancode = SDL_SCANCODE_UNKNOWN;
                        ev.key.keysym.mod = ShimKeyModFromAndroid((uint32_t)e->p2);
                        break;
                }
                case KYTY_EV_TEXT_INPUT: {
                        ev.type = SDL_TEXTINPUT;
                        ev.text.windowID = 1;
                        size_t n = (size_t)(e->p1 < 8 ? e->p1 : 8);
                        memcpy(ev.text.text, e->bytes, n);
                        ev.text.text[n] = '\0';
                        break;
                }
                case KYTY_EV_MOUSE_MOVE: {
                        ev.type = SDL_MOUSEMOTION;
                        ev.motion.windowID = 1;
                        ev.motion.x = e->p1;
                        ev.motion.y = e->p2;
                        ev.motion.xrel = e->p3;
                        ev.motion.yrel = e->p4;
                        ev.motion.state = g_shim.mouse_buttons;
                        ev.motion.which = 0; /* real mouse */
                        g_shim.mouse_x = e->p1;
                        g_shim.mouse_y = e->p2;
                        g_shim.mouse_rel_x += e->p3;
                        g_shim.mouse_rel_y += e->p4;
                        break;
                }
                case KYTY_EV_MOUSE_BUTTON: {
                        int btn = e->p1;
                        int down = (e->p2 != 0);
                        uint32_t mask = 0;
                        switch (btn) {
                                case 1: mask = SDL_BUTTON_LMASK; break;
                                case 2: mask = SDL_BUTTON_MMASK; break;
                                case 3: mask = SDL_BUTTON_RMASK; break;
                                case 4: mask = SDL_BUTTON_X1MASK; break;
                                case 5: mask = SDL_BUTTON_X2MASK; break;
                                default: mask = 0; break;
                        }
                        if (down) {
                                g_shim.mouse_buttons |= mask;
                        } else {
                                g_shim.mouse_buttons &= ~mask;
                        }
                        ev.type = down ? SDL_MOUSEBUTTONDOWN : SDL_MOUSEBUTTONUP;
                        ev.button.windowID = 1;
                        ev.button.button = (Uint8)btn;
                        ev.button.state = down ? SDL_PRESSED : SDL_RELEASED;
                        ev.button.clicks = 1;
                        ev.button.x = g_shim.mouse_x;
                        ev.button.y = g_shim.mouse_y;
                        ev.button.which = 0;
                        break;
                }
                case KYTY_EV_MOUSE_WHEEL: {
                        ev.type = SDL_MOUSEWHEEL;
                        ev.wheel.windowID = 1;
                        ev.wheel.x = (int)(e->f1);
                        ev.wheel.y = (int)(e->f2);
                        ev.wheel.direction = SDL_MOUSEWHEEL_NORMAL;
                        break;
                }
                case KYTY_EV_FINGER_DOWN:
                case KYTY_EV_FINGER_MOTION:
                case KYTY_EV_FINGER_UP: {
                        ev.type = (e->type == KYTY_EV_FINGER_DOWN) ? SDL_FINGERDOWN
                                  : (e->type == KYTY_EV_FINGER_MOTION) ? SDL_FINGERMOTION
                                                                        : SDL_FINGERUP;
                        ev.tfinger.touchId = 1; /* our virtual touchscreen */
                        ev.tfinger.fingerId = (SDL_FingerID)e->p1;
                        ev.tfinger.x = e->f1;
                        ev.tfinger.y = e->f2;
                        ev.tfinger.dx = 0;
                        ev.tfinger.dy = 0;
                        ev.tfinger.pressure = (e->type == KYTY_EV_FINGER_UP) ? 0.0f : 1.0f;
                        ev.tfinger.windowID = 1;
                        break;
                }
                case KYTY_EV_PAD_ADD: {
                        ev.type = SDL_CONTROLLERDEVICEADDED;
                        ev.cdevice.timestamp = (Uint32)ShimTicks64Ms();
                        ev.cdevice.which = e->p1; /* device index */
                        break;
                }
                case KYTY_EV_PAD_REMOVE: {
                        ev.type = SDL_CONTROLLERDEVICEREMOVED;
                        ev.cdevice.timestamp = (Uint32)ShimTicks64Ms();
                        ev.cdevice.which = (Sint32)e->p1; /* instance id */
                        break;
                }
                case KYTY_EV_PAD_AXIS: {
                        ev.type = SDL_CONTROLLERAXISMOTION;
                        ev.caxis.timestamp = (Uint32)ShimTicks64Ms();
                        ev.caxis.which = (SDL_JoystickID)e->p1;
                        ev.caxis.axis = (Uint8)e->p2;
                        ev.caxis.value = (Sint16)e->p3;
                        break;
                }
                case KYTY_EV_PAD_BUTTON: {
                        ev.type = (e->p3 != 0) ? SDL_CONTROLLERBUTTONDOWN : SDL_CONTROLLERBUTTONUP;
                        ev.cbutton.timestamp = (Uint32)ShimTicks64Ms();
                        ev.cbutton.which = (SDL_JoystickID)e->p1;
                        ev.cbutton.button = (Uint8)e->p2;
                        ev.cbutton.state = (e->p3 != 0) ? SDL_PRESSED : SDL_RELEASED;
                        break;
                }
                case KYTY_EV_PAD_TOUCHPAD: {
                        switch (e->p3) {
                                case 0: ev.type = SDL_CONTROLLERTOUCHPADDOWN; break;
                                case 1: ev.type = SDL_CONTROLLERTOUCHPADMOTION; break;
                                default: ev.type = SDL_CONTROLLERTOUCHPADUP; break;
                        }
                        ev.ctouchpad.timestamp = (Uint32)ShimTicks64Ms();
                        ev.ctouchpad.which = (SDL_JoystickID)e->p1;
                        ev.ctouchpad.finger = (Sint32)e->p2;
                        ev.ctouchpad.x = e->f1;
                        ev.ctouchpad.y = e->f2;
                        ev.ctouchpad.pressure = (e->p3 == 2) ? 0.0f : 1.0f;
                        break;
                }
                case KYTY_EV_WINDOW_RESIZED: {
                        ev.type = SDL_WINDOWEVENT;
                        ev.window.windowID = 1;
                        ev.window.event = SDL_WINDOWEVENT_SIZE_CHANGED;
                        ev.window.data1 = e->p1;
                        ev.window.data2 = e->p2;
                        ShimLocalPush(&ev);
                        ev.window.event = SDL_WINDOWEVENT_RESIZED;
                        break;
                }
                case KYTY_EV_WINDOW_FOCUS: {
                        ev.type = SDL_WINDOWEVENT;
                        ev.window.windowID = 1;
                        ev.window.event = (e->p1 != 0) ? SDL_WINDOWEVENT_FOCUS_GAINED
                                                       : SDL_WINDOWEVENT_FOCUS_LOST;
                        g_shim.focused = (e->p1 != 0);
                        break;
                }
                case KYTY_EV_WINDOW_CLOSE: {
                        ev.type = SDL_WINDOWEVENT;
                        ev.window.windowID = 1;
                        ev.window.event = SDL_WINDOWEVENT_CLOSE;
                        break;
                }
                case KYTY_EV_APP_ENTER_BG: ev.type = SDL_APP_WILLENTERBACKGROUND; break;
                case KYTY_EV_APP_EXIT_BG: ev.type = SDL_APP_DIDENTERFOREGROUND; break;
                case KYTY_EV_APP_TERMINATING: ev.type = SDL_APP_TERMINATING; break;
                case KYTY_EV_APP_LOW_MEMORY: ev.type = SDL_APP_LOWMEMORY; break;
                case KYTY_EV_ORIENTATION: {
                        ev.type = SDL_DISPLAYEVENT;
                        ev.display.display = 0;
                        ev.display.event = SDL_DISPLAYEVENT_ORIENTATION;
                        switch (e->p1) {
                                case KYTY_ORIENT_LANDSCAPE: ev.display.data1 = SDL_ORIENTATION_LANDSCAPE; break;
                                case KYTY_ORIENT_LANDSCAPE_FLIPPED:
                                        ev.display.data1 = SDL_ORIENTATION_LANDSCAPE_FLIPPED;
                                        break;
                                case KYTY_ORIENT_PORTRAIT: ev.display.data1 = SDL_ORIENTATION_PORTRAIT; break;
                                case KYTY_ORIENT_PORTRAIT_FLIPPED:
                                        ev.display.data1 = SDL_ORIENTATION_PORTRAIT_FLIPPED;
                                        break;
                                default: ev.display.data1 = SDL_ORIENTATION_UNKNOWN; break;
                        }
                        break;
                }
                case KYTY_EV_QUIT: {
                        ev.type = SDL_QUIT;
                        break;
                }
                default:
                        return; /* unknown — ignore */
        }

        ShimLocalPush(&ev);
}

void ShimPumpRingEvents(void) {
        if (g_shim.shm == nullptr) {
                return;
        }
        KytyRingHeader *ring = &g_shim.shm->input_ring;
        for (;;) {
                uint32_t head = KYTY_ALOAD(&ring->head);
                uint32_t tail = ring->tail;
                if (tail == head) {
                        break;
                }
                const KytyBridgeEvent *e = &g_shim.shm->input_events[tail % KYTY_BRIDGE_INPUT_SLOTS];
                ShimApplyNeutralEvent(e);
                KYTY_ASTORE(&ring->tail, tail + 1);
        }
}

/* drain thread: bridges ring events into the local queue */
static void *ShimDrainMain(void *arg) {
        (void)arg;
        struct timespec ts {0, 2 * 1000000}; /* 2 ms */
        for (;;) {
                int stop;
                pthread_mutex_lock(&g_shim.ev_mutex);
                stop = g_shim.ev_quit;
                pthread_mutex_unlock(&g_shim.ev_mutex);
                if (stop) {
                        break;
                }
                ShimPumpRingEvents();
                nanosleep(&ts, nullptr);
        }
        return nullptr;
}

static void ShimEnsureDrainThread(void) {
        if (!g_shim.drain_running) {
                g_shim.drain_running = 1;
                pthread_create(&g_shim.drain_thread, nullptr, ShimDrainMain, nullptr);
        }
}

/* -------------------------------------------------------------------------- */
/* event pump API                                                             */
/* -------------------------------------------------------------------------- */

int SDL_PollEvent(SDL_Event *event) {
        ShimEnsureDrainThread();
        ShimPumpRingEvents();
        return ShimLocalPop(event);
}

int SDL_WaitEvent(SDL_Event *event) {
        ShimEnsureDrainThread();
        for (;;) {
                ShimPumpRingEvents();
                if (ShimLocalPop(event)) {
                        return 1;
                }
                pthread_mutex_lock(&g_shim.ev_mutex);
                if (g_shim.ev_q_tail == g_shim.ev_q_head) {
                        pthread_cond_wait(&g_shim.ev_cond, &g_shim.ev_mutex);
                }
                pthread_mutex_unlock(&g_shim.ev_mutex);
        }
}

int SDL_WaitEventTimeout(SDL_Event *event, int timeout) {
        ShimEnsureDrainThread();
        ShimPumpRingEvents();
        if (ShimLocalPop(event)) {
                return 1;
        }
        if (timeout == 0) {
                return 0;
        }

        uint64_t deadline = ShimTicks64Ms() + (uint64_t)(timeout < 0 ? 0 : timeout);
        for (;;) {
                pthread_mutex_lock(&g_shim.ev_mutex);
                if (g_shim.ev_q_tail == g_shim.ev_q_head) {
                        pthread_cond_wait(&g_shim.ev_cond, &g_shim.ev_mutex);
                }
                pthread_mutex_unlock(&g_shim.ev_mutex);
                ShimPumpRingEvents();
                if (ShimLocalPop(event)) {
                        return 1;
                }
                if (ShimTicks64Ms() >= deadline) {
                        return 0;
                }
                struct timespec sleep_ts {0, 1000000}; /* 1 ms */
                nanosleep(&sleep_ts, nullptr);
        }
}

Uint32 SDL_RegisterEvents(int numevents) {
        uint32_t base = g_shim.user_event_base;
        g_shim.user_event_base += (uint32_t)numevents;
        return base;
}

/* SDL_GetEventState is a macro -> SDL_EventState(type, SDL_QUERY) */
Uint8 SDL_EventState(Uint32 type, int state) {
        (void)type;
        if (state == SDL_QUERY) {
                return SDL_ENABLE; /* the bridge keeps every event type enabled */
        }
        return SDL_ENABLE;
}

/* -------------------------------------------------------------------------- */
/* keyboard / mouse / text input                                              */
/* -------------------------------------------------------------------------- */

SDL_Window *SDL_GetKeyboardFocus(void) {
        return g_shim.focused ? g_shim.focus_window : nullptr;
}

SDL_bool SDL_IsTextInputActive(void) {
        return g_shim.text_input_active ? SDL_TRUE : SDL_FALSE;
}

void SDL_StartTextInput(void) {
        g_shim.text_input_active = 1;
}

void SDL_StopTextInput(void) {
        g_shim.text_input_active = 0;
}

int SDL_SetRelativeMouseMode(SDL_bool enabled) {
        g_shim.relative_mouse_mode = (enabled != SDL_FALSE);
        return 0;
}

Uint32 SDL_GetRelativeMouseState(int *x, int *y) {
        if (x != nullptr) {
                *x = g_shim.mouse_rel_x;
        }
        if (y != nullptr) {
                *y = g_shim.mouse_rel_y;
        }
        g_shim.mouse_rel_x = 0;
        g_shim.mouse_rel_y = 0;
        return g_shim.mouse_buttons;
}

Uint32 SDL_GetMouseState(int *x, int *y) {
        if (x != nullptr) {
                *x = g_shim.mouse_x;
        }
        if (y != nullptr) {
                *y = g_shim.mouse_y;
        }
        return g_shim.mouse_buttons;
}

void SDL_WarpMouseInWindow(SDL_Window *window, int x, int y) {
        (void)window;
        (void)x;
        (void)y;
}

/* -------------------------------------------------------------------------- */
/* window                                                                     */
/* -------------------------------------------------------------------------- */

SDL_Window *SDL_CreateWindow(const char *title, int x, int y, int w, int h, Uint32 flags) {
        (void)title;
        (void)x;
        (void)y;

        if (g_shim.shm == nullptr && !ShimBridgeAttach()) {
                return nullptr;
        }

        auto *win = (struct SDL_Window *)calloc(1, sizeof(struct SDL_Window));
        win->w = w;
        win->h = h;
        win->flags = flags | SDL_WINDOW_VULKAN;
        win->id = g_shim.next_window_id++;
        win->fullscreen = ((flags & SDL_WINDOW_FULLSCREEN_DESKTOP) != 0u) ? 1 : 0;
        win->focused = g_shim.focused;
        win->shown = 1;
        g_shim.focus_window = win;

        /* remember requested size in the bridge state (informational) */
        KYTY_ASTORE(&g_shim.shm->state.window_w, (uint32_t)w);
        KYTY_ASTORE(&g_shim.shm->state.window_h, (uint32_t)h);
        return win;
}

void SDL_DestroyWindow(SDL_Window *window) {
        if (window == nullptr) {
                return;
        }
        if (g_shim.focus_window == window) {
                g_shim.focus_window = nullptr;
        }
        free(window);
}

Uint32 SDL_GetWindowFlags(SDL_Window *window) {
        if (window == nullptr) {
                return 0;
        }
        uint32_t f = window->flags;
        if (window->fullscreen) {
                f |= SDL_WINDOW_FULLSCREEN_DESKTOP;
        }
        if (window->focused) {
                f |= SDL_WINDOW_INPUT_FOCUS;
        }
        if (window->shown) {
                f |= SDL_WINDOW_SHOWN;
        }
        return f;
}

int SDL_SetWindowFullscreen(SDL_Window *window, Uint32 flags) {
        if (window == nullptr) {
                return -1;
        }
        window->fullscreen = ((flags & SDL_WINDOW_FULLSCREEN_DESKTOP) != 0u) ? 1 : 0;
        return 0;
}

void SDL_SetWindowTitle(SDL_Window *window, const char *title) {
        (void)window;
        (void)title;
}

void SDL_SetWindowIcon(SDL_Window *window, SDL_Surface *icon) {
        (void)window;
        (void)icon;
}

void SDL_SetWindowResizable(SDL_Window *window, SDL_bool resizable) {
        (void)window;
        (void)resizable;
}

void SDL_SetWindowPosition(SDL_Window *window, int x, int y) {
        (void)window;
        (void)x;
        (void)y;
}

void SDL_SetWindowSize(SDL_Window *window, int w, int h) {
        if (window == nullptr) {
                return;
        }
        window->w = w;
        window->h = h;
}

void SDL_GetWindowSize(SDL_Window *window, int *w, int *h) {
        if (w != nullptr) {
                *w = (window != nullptr) ? window->w : 0;
        }
        if (h != nullptr) {
                *h = (window != nullptr) ? window->h : 0;
        }
}

void SDL_ShowWindow(SDL_Window *window) {
        if (window != nullptr) {
                window->shown = 1;
        }
}

void SDL_HideWindow(SDL_Window *window) {
        if (window != nullptr) {
                window->shown = 0;
        }
}

void SDL_RaiseWindow(SDL_Window *window) {
        (void)window;
}

/* -------------------------------------------------------------------------- */
/* surfaces (icon path only)                                                  */
/* -------------------------------------------------------------------------- */

SDL_Surface *SDL_CreateRGBSurfaceWithFormatFrom(void *pixels, int width, int height, int depth,
                                                int pitch, Uint32 format) {
        (void)pixels;
        (void)depth;
        (void)format;
        auto *s = (SDL_Surface *)calloc(1, sizeof(SDL_Surface));
        s->flags = 0;
        s->w = width;
        s->h = height;
        s->pitch = pitch;
        s->pixels = pixels;
        s->format = nullptr; /* icon path only; the bridge has no window chrome */
        return s;
}

void SDL_FreeSurface(SDL_Surface *surface) {
        if (surface == nullptr) {
                return;
        }
        free(surface);
}

} /* extern "C" */
