/*
 * KytyPS5 Android port — host bridge core: shared memory, in-process box64
 * session management, input injection, rumble dispatch, log capture.
 */

#include "kyty_host.h"

#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

#include <algorithm>
#include <cerrno>
#include <cstring>
#include <cstdlib>

extern char **environ;

#define LOG_TAG "KytyHost"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace KytyHost {

HostState &Host() {
        static HostState state;
        return state;
}

/* -------------------------------------------------------------------------- */
/* events                                                                      */
/* -------------------------------------------------------------------------- */

void HostPushEvent(const KytyBridgeEvent &ev) {
        HostState &s = Host();
        if (s.shm == nullptr) {
                return;
        }
        KytyRingHeader *ring = &s.shm->input_ring;
        uint32_t head = __atomic_load_n(&ring->head, __ATOMIC_ACQUIRE);
        uint32_t tail = __atomic_load_n(&ring->tail, __ATOMIC_ACQUIRE);
        if (head - tail >= ring->capacity) {
                return; /* overflow: drop (the guest is not pumping) */
        }
        s.shm->input_events[head % KYTY_BRIDGE_INPUT_SLOTS] = ev;
        __atomic_thread_fence(__ATOMIC_SEQ_CST);
        __atomic_store_n(&ring->head, head + 1, __ATOMIC_RELEASE);
}

void HostSendKey(int32_t keycode, bool down, uint32_t meta) {
        KytyBridgeEvent ev {};
        ev.type = down ? KYTY_EV_KEY_DOWN : KYTY_EV_KEY_UP;
        ev.p1 = keycode;
        ev.p2 = (int32_t)meta;
        HostPushEvent(ev);
}

void HostSendText(const std::string &utf8) {
        size_t off = 0;
        while (off < utf8.size()) {
                size_t n = std::min<size_t>(utf8.size() - off, 8);
                KytyBridgeEvent ev {};
                ev.type = KYTY_EV_TEXT_INPUT;
                ev.p1 = (int32_t)n;
                memcpy(ev.bytes, utf8.data() + off, n);
                HostPushEvent(ev);
                off += n;
        }
}

void HostSendMouse(int32_t x, int32_t y, int32_t dx, int32_t dy, int32_t buttons,
                   int32_t wheel_dx, int32_t wheel_dy) {
        if (wheel_dx != 0 || wheel_dy != 0) {
                KytyBridgeEvent ev {};
                ev.type = KYTY_EV_MOUSE_WHEEL;
                ev.f1 = (float)wheel_dx;
                ev.f2 = (float)wheel_dy;
                HostPushEvent(ev);
                return;
        }
        if (buttons != 0) {
                KytyBridgeEvent ev {};
                ev.type = KYTY_EV_MOUSE_BUTTON;
                ev.p1 = buttons < 0 ? -buttons : buttons;
                ev.p2 = buttons > 0 ? 1 : 0;
                HostPushEvent(ev);
                return;
        }
        KytyBridgeEvent ev {};
        ev.type = KYTY_EV_MOUSE_MOVE;
        ev.p1 = x;
        ev.p2 = y;
        ev.p3 = dx;
        ev.p4 = dy;
        HostPushEvent(ev);
}

void HostSendFinger(int32_t finger, int32_t phase, float x, float y) {
        KytyBridgeEvent ev {};
        ev.type = phase == 0 ? KYTY_EV_FINGER_DOWN
                 : phase == 1 ? KYTY_EV_FINGER_MOTION
                              : KYTY_EV_FINGER_UP;
        ev.p1 = finger;
        ev.f1 = x;
        ev.f2 = y;
        HostPushEvent(ev);
}

/* pads table serialization: connect/disconnect must not race on in_use
 * (BT pad churn reconnects on the same slot) */
static std::mutex g_pad_table_mutex;

int HostPadConnect(uint32_t type, const std::string &name) {
        HostState &s = Host();
        if (s.shm == nullptr) {
                return -1;
        }
        static int next_instance = 1;
        std::lock_guard<std::mutex> lock(g_pad_table_mutex);
        for (uint32_t i = 0; i < KYTY_BRIDGE_MAX_PADS; ++i) {
                KytyBridgePad *pad = &s.shm->pads[i];
                uint32_t expected = 0;
                if (__atomic_load_n(&pad->in_use, __ATOMIC_ACQUIRE) == 0u &&
                    __atomic_compare_exchange_n(&pad->in_use, &expected, 1u, false, __ATOMIC_ACQ_REL,
                                                __ATOMIC_ACQUIRE)) {
                        int instance = next_instance++;
                        pad->instance_id = (uint32_t)instance;
                        pad->type = type;
                        pad->connected = 1;
                        snprintf(pad->name, sizeof(pad->name), "%s", name.c_str());
                        KytyBridgeEvent ev {};
                        ev.type = KYTY_EV_PAD_ADD;
                        ev.p1 = (int32_t)i; /* device index */
                        ev.p2 = instance;
                        HostPushEvent(ev);
                        return instance;
                }
        }
        return -1;
}

void HostPadDisconnect(int32_t instance) {
        HostState &s = Host();
        if (s.shm == nullptr) {
                return;
        }
        /* same mutex as HostPadConnect: a disconnect racing a reconnect on
         * the same slot could otherwise clear in_use on a freshly-claimed
         * pad (BT pad churn does exactly this) */
        std::lock_guard<std::mutex> lock(g_pad_table_mutex);
        for (uint32_t i = 0; i < KYTY_BRIDGE_MAX_PADS; ++i) {
                KytyBridgePad *pad = &s.shm->pads[i];
                if (pad->in_use != 0u && (int32_t)pad->instance_id == instance) {
                        pad->connected = 0;
                        KytyBridgeEvent ev {};
                        ev.type = KYTY_EV_PAD_REMOVE;
                        ev.p1 = instance;
                        HostPushEvent(ev);
                        __atomic_store_n(&pad->in_use, 0u, __ATOMIC_RELEASE);
                        return;
                }
        }
}

void HostPadAxis(int32_t instance, int32_t axis, int32_t value) {
        KytyBridgeEvent ev {};
        ev.type = KYTY_EV_PAD_AXIS;
        ev.p1 = instance;
        ev.p2 = axis;
        ev.p3 = value;
        HostPushEvent(ev);
}

void HostPadButton(int32_t instance, int32_t button, bool down) {
        KytyBridgeEvent ev {};
        ev.type = KYTY_EV_PAD_BUTTON;
        ev.p1 = instance;
        ev.p2 = button;
        ev.p3 = down ? 1 : 0;
        HostPushEvent(ev);
}

void HostPadTouchpad(int32_t instance, int32_t finger, int32_t phase, float x, float y) {
        KytyBridgeEvent ev {};
        ev.type = KYTY_EV_PAD_TOUCHPAD;
        ev.p1 = instance;
        ev.p2 = finger;
        ev.p3 = phase;
        ev.f1 = x;
        ev.f2 = y;
        HostPushEvent(ev);
}

void HostSendWindowResized(uint32_t w, uint32_t h) {
        KytyBridgeEvent ev {};
        ev.type = KYTY_EV_WINDOW_RESIZED;
        ev.p1 = (int32_t)w;
        ev.p2 = (int32_t)h;
        HostPushEvent(ev);
}

void HostSendLifecycle(int32_t ev_type) {
        KytyBridgeEvent ev {};
        ev.type = (uint32_t)ev_type;
        HostPushEvent(ev);
}

void HostSendOrientation(int32_t orientation) {
        KytyBridgeEvent ev {};
        ev.type = KYTY_EV_ORIENTATION;
        ev.p1 = orientation;
        HostPushEvent(ev);
}

/* -------------------------------------------------------------------------- */
/* surface                                                                     */
/* -------------------------------------------------------------------------- */

void HostSetSurface(ANativeWindow *window, uint32_t w, uint32_t h) {
        HostState &s = Host();
        std::lock_guard<std::mutex> lock(s.surface_mutex);

        if (s.window != nullptr) {
                ANativeWindow_release(s.window);
                s.window = nullptr;
        }
        s.window = window; /* takes the reference from fromSurface() */

        if (s.shm == nullptr) {
                return;
        }
        if (s.surface_seq_val % 2 == 1) {
                s.surface_seq_val++; /* invalidate */
                __atomic_store_n(&s.shm->state.surface_seq, s.surface_seq_val, __ATOMIC_RELEASE);
        }
        __atomic_store_n(&s.shm->state.native_window, (uint64_t)(uintptr_t)s.window,
                         __ATOMIC_RELEASE);
        __atomic_store_n(&s.shm->state.window_w, w, __ATOMIC_RELEASE);
        __atomic_store_n(&s.shm->state.window_h, h, __ATOMIC_RELEASE);
        s.surface_seq_val++;
        __atomic_store_n(&s.shm->state.surface_seq, s.surface_seq_val, __ATOMIC_RELEASE);

        HostSendWindowResized(w, h);
        ALOGI("surface published: %p (%ux%u)", (void *)s.window, w, h);
}

void HostClearSurface() {
        HostState &s = Host();
        std::lock_guard<std::mutex> lock(s.surface_mutex);
        if (s.shm != nullptr && s.surface_seq_val % 2 == 1) {
                s.surface_seq_val++;
                __atomic_store_n(&s.shm->state.surface_seq, s.surface_seq_val, __ATOMIC_RELEASE);
        }
        if (s.window != nullptr) {
                ANativeWindow_release(s.window);
                s.window = nullptr;
                __atomic_store_n(&s.shm->state.native_window, 0u, __ATOMIC_RELEASE);
        }
}

/* -------------------------------------------------------------------------- */
/* log capture (guest stdout/stderr -> file -> UI)                             */
/* -------------------------------------------------------------------------- */

/* keeps only byte sequences that are valid (or sanitized) UTF-8 so that
 * NewStringUTF can never fault on raw guest output */
static void SanitizeUtf8(const std::string &in, std::string &out) {
        out.clear();
        out.reserve(in.size());
        size_t i = 0;
        while (i < in.size()) {
                unsigned char c = (unsigned char)in[i];
                size_t len = 0;
                if (c < 0x80) {
                        len = 1;
                } else if ((c & 0xE0) == 0xC0) {
                        len = 2;
                } else if ((c & 0xF0) == 0xE0) {
                        len = 3;
                } else if ((c & 0xF8) == 0xF0) {
                        len = 4;
                } else {
                        out.push_back('?'); /* stray continuation / invalid lead */
                        i++;
                        continue;
                }
                if (i + len > in.size()) {
                        out.push_back('?');
                        i++;
                        continue;
                }
                bool ok = true;
                for (size_t k = 1; k < len; ++k) {
                        if (((unsigned char)in[i + k] & 0xC0) != 0x80) {
                                ok = false;
                                break;
                        }
                }
                if (!ok) {
                        out.push_back('?');
                        i++;
                        continue;
                }
                out.append(in, i, len);
                i += len;
        }
}

std::string HostReadLogTail() {
        HostState &s = Host();
        std::lock_guard<std::mutex> lock(s.log_mutex);
        if (s.log_file == nullptr) {
                return {};
        }
        if (fseek(s.log_file, (long)s.log_offset, SEEK_SET) != 0) {
                return {};
        }
        char buf[16384];
        size_t n = fread(buf, 1, sizeof(buf), s.log_file);
        s.log_offset += n;
        std::string clean;
        if (n > 0) {
                SanitizeUtf8(std::string(buf, n), clean);
        }
        return clean;
}

/* -------------------------------------------------------------------------- */
/* log drain: pipe -> emulator.log + logcat mirror                            */
/* -------------------------------------------------------------------------- */

/* box64 / guest stdout+stderr are redirected (dup2) into a pipe; this thread
 * drains it into emulator.log (for the in-app UI tail and the crash
 * black-box) AND mirrors every line into logcat with tag "KytyBox64" so a
 * plain `adb logcat` / bug-report capture carries the full box64 diagnostic
 * trace (library loads, symbol warnings, SIGSEGV report) without adb access
 * to the app's private files. */
static void HostLogMirrorLine(const char *line, size_t len) {
        /* logcat truncates very long lines; cap defensively */
        char tmp[3500];
        size_t n = len < sizeof(tmp) - 1 ? len : sizeof(tmp) - 1;
        memcpy(tmp, line, n);
        tmp[n] = '\0';
        __android_log_print(ANDROID_LOG_INFO, "KytyBox64", "%s", tmp);
}

static void HostLogDrainThread(int fd, FILE *lf) {
        char buf[4096];
        std::string acc; /* partial-line accumulator for the logcat mirror */
        for (;;) {
                ssize_t n = read(fd, buf, sizeof(buf));
                if (n < 0) {
                        if (errno == EINTR) {
                                continue;
                        }
                        break;
                }
                if (n == 0) {
                        break; /* EOF: both write ends closed */
                }
                {
                        HostState &s = Host();
                        std::lock_guard<std::mutex> lock(s.log_mutex);
                        fwrite(buf, 1, (size_t)n, lf);
                        fflush(lf);
                }
                acc.append(buf, (size_t)n);
                size_t nl;
                while ((nl = acc.find('\n')) != std::string::npos) {
                        if (nl > 0) {
                                HostLogMirrorLine(acc.data(), nl);
                        }
                        acc.erase(0, nl + 1);
                }
                if (acc.size() > 8192) {
                        /* pathological unterminated line: flush it */
                        HostLogMirrorLine(acc.data(), acc.size());
                        acc.clear();
                }
        }
        if (!acc.empty()) {
                HostLogMirrorLine(acc.data(), acc.size());
        }
}

/* -------------------------------------------------------------------------- */
/* exit notification                                                           */
/* -------------------------------------------------------------------------- */

static void HostNotifyExit(int code) {
        HostState &s = Host();
        /* once-only: HostKill notifies immediately, but the detached emulator
         * thread may deliver its own late code afterwards — the first code
         * wins so the UI is not overwritten after a forced stop */
        bool expected = false;
        if (!s.exit_notified.compare_exchange_strong(expected, true)) {
                return;
        }
        ALOGI("emulator session ended: code=%d", code);
        if (s.vm != nullptr && s.java_callback != nullptr) {
                JNIEnv *env = nullptr;
                bool attached = false;
                if (s.vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
                        s.vm->AttachCurrentThread(&env, nullptr);
                        attached = true;
                }
                if (env != nullptr) {
                        jclass cls = env->GetObjectClass(s.java_callback);
                        jmethodID mid = env->GetMethodID(cls, "onEmulatorExit", "(I)V");
                        if (mid != nullptr) {
                                env->CallVoidMethod(s.java_callback, mid, (jint)code);
                        }
                        if (env->ExceptionCheck()) {
                                env->ExceptionClear();
                        }
                }
                if (attached) {
                        s.vm->DetachCurrentThread();
                }
        }
}

/* -------------------------------------------------------------------------- */
/* in-process box64 session                                                    */
/* -------------------------------------------------------------------------- */

struct SessionArgs {
        std::vector<std::string> storage; /* keeps strings alive */
        std::vector<char *> argv;
};

static void *HostEmulatorMain(void *arg) {
        HostState &s = Host();
        auto *sa = (SessionArgs *)arg;

        int code = s.box64_main((int)sa->argv.size() - 1,
                                (const char **)sa->argv.data(), environ);

        delete sa;

        s.emu_thread_running.store(false);
        s.running.store(false);
        s.exit_code.store(code);
        HostNotifyExit(code);
        return nullptr;
}

static bool HostLoadBox64(HostState &s) {
        if (s.box64_main != nullptr) {
                return true;
        }
        std::lock_guard<std::mutex> lock(s.box64_mutex);
        if (s.box64_main != nullptr) { /* re-check under the lock */
                return true;
        }
        /* With useLegacyPackaging=false the library is NOT extracted to
         * nativeLibraryDir — it lives inside base.apk and is reachable
         * through the app class-loader namespace by its bare name (the
         * same mechanism that loaded libkytyhost.so). Try the bare name
         * first, then the extracted path for devices that do extract. */
        s.box64_lib = dlopen("libbox64.so", RTLD_NOW | RTLD_LOCAL);
        const char *err = s.box64_lib != nullptr ? nullptr : dlerror();
        if (s.box64_lib != nullptr) {
                ALOGI("libbox64.so loaded via class-loader namespace (APK-embedded)");
        } else {
                std::string path = s.native_lib_dir + "/libbox64.so";
                ALOGI("bare-name dlopen failed (%s); trying %s",
                      err != nullptr ? err : "unknown", path.c_str());
                s.box64_lib = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
                if (s.box64_lib == nullptr) {
                        ALOGE("dlopen(%s) failed: %s", path.c_str(), dlerror());
                        return false;
                }
        }
        s.box64_main = (int (*)(int, const char **, char **))dlsym(s.box64_lib, "box64_main");
        if (s.box64_main == nullptr) {
                ALOGE("dlsym(box64_main) failed: %s", dlerror());
                dlclose(s.box64_lib);
                s.box64_lib = nullptr;
                return false;
        }
        ALOGI("libbox64.so ready (box64_main resolved)");
        return true;
}

bool HostBox64Available() {
        /* real probe: dlopen + dlsym (cached after the first success) */
        return HostLoadBox64(Host());
}

bool HostStart(const std::string &workdir, const std::vector<std::string> &args,
               const std::vector<std::pair<std::string, std::string>> &env) {
        HostState &s = Host();
        if (!s.initialized) {
                return false;
        }
        std::lock_guard<std::mutex> lock(s.start_mutex);
        if (s.running.load() || s.emu_thread_started) {
                /* box64 library mode is single-session per process by design */
                ALOGE("a session already ran in this process; restart the app for a new one");
                return false;
        }
        if (args.empty()) {
                return false;
        }
        if (!HostLoadBox64(s)) {
                return false;
        }

        /* fresh log capture: redirect the process stdout/stderr into a pipe
         * drained by HostLogDrainThread, which persists to emulator.log (UI
         * tail + crash black-box) and mirrors every line to logcat (tag
         * "KytyBox64") so device-side bug reports carry the box64 trace. */
        {
                std::lock_guard<std::mutex> llock(s.log_mutex);
                if (s.log_file != nullptr) {
                        fclose(s.log_file);
                        s.log_file = nullptr;
                }
                std::string log_path = s.files_root + "/logs/emulator.log";
                remove(log_path.c_str());

                int fds[2];
                if (pipe(fds) != 0) {
                        ALOGE("pipe() for log capture failed: %s", strerror(errno));
                        return false;
                }
                fflush(stdout);
                fflush(stderr);
                if (dup2(fds[1], STDOUT_FILENO) < 0 || dup2(fds[1], STDERR_FILENO) < 0) {
                        ALOGE("dup2(log pipe) failed: %s", strerror(errno));
                        close(fds[0]);
                        close(fds[1]);
                        return false;
                }
                close(fds[1]); /* fds 1 & 2 are now the only write ends */
                setvbuf(stdout, nullptr, _IOLBF, 8192);

                FILE *lf = fopen(log_path.c_str(), "wb");
                if (lf == nullptr) {
                        ALOGE("fopen(emulator.log) failed: %s", strerror(errno));
                        close(fds[0]);
                        return false;
                }
                s.log_write = lf;
                if (!s.log_drain_started) {
                        s.log_drain_started = true;
                        s.log_drain_thread = std::thread(HostLogDrainThread, fds[0], lf);
                        s.log_drain_thread.detach(); /* daemon: parks on read after session end */
                }
                /* separate read handle + offset so the UI tails flushed bytes */
                s.log_file = fopen(log_path.c_str(), "rb");
                s.log_offset = 0;
                if (s.log_file == nullptr) {
                        ALOGE("fopen(emulator.log, rb) failed: %s", strerror(errno));
                }
        }

        /* working directory: the emulator mounts sandbox dirs relative to cwd.
         * HOME/TMPDIR point inside it, so create it (and tmp/) first. */
        mkdir(workdir.c_str(), 0700);
        mkdir((workdir + "/tmp").c_str(), 0700);
        if (chdir(workdir.c_str()) != 0) {
                ALOGE("chdir(%s) failed: %s", workdir.c_str(), strerror(errno));
                return false; /* the guest's relative sandbox paths would all break */
        }

        /* environment for box64 and the bridge shim */
        for (const auto &kv: env) {
                setenv(kv.first.c_str(), kv.second.c_str(), 1);
        }

        auto *sa = new SessionArgs();
        sa->storage.reserve(args.size() + 1);
        /* box64's core.c derives my_context->box64path from argv[0] via
         * ResolveFile() and then does box_strdup(box64path) — a logical
         * name like "box64" resolves to NULL in library mode (there is no
         * box64 binary on disk; the code lives inside base.apk) and the
         * NULL later crashes strdup() on-device (SIGSEGV in
         * __strlen_aarch64, core.c:1123). Give it a real, absolute path:
         * a marker file inside the session workdir. */
        std::string box64_marker = workdir + "/box64";
        {
                int mfd = open(box64_marker.c_str(), O_CREAT | O_WRONLY | O_CLOEXEC, 0644);
                if (mfd < 0) {
                        ALOGE("cannot create box64 marker (%s): %s",
                              box64_marker.c_str(), strerror(errno));
                        return false;
                }
                close(mfd);
        }
        sa->storage.push_back(box64_marker); /* box64's own argv[0] (resolvable) */
        for (const auto &a: args) {
                sa->storage.push_back(a);
        }
        for (auto &str: sa->storage) {
                sa->argv.push_back(str.data());
        }
        sa->argv.push_back(nullptr);

        s.exit_code.store(-1);
        s.exit_notified.store(false);
        s.running.store(true);
        s.emu_thread_started = true;
        s.emu_thread_running.store(true);

        /* large stack: the emulator's main thread (window loop + Vulkan +
         * shader recompiler) has deep call chains */
        pthread_attr_t attr {};
        pthread_attr_init(&attr);
        pthread_attr_setstacksize(&attr, 64u * 1024u * 1024u);
        int rc = pthread_create(&s.emu_thread, &attr, HostEmulatorMain, sa);
        pthread_attr_destroy(&attr);
        if (rc != 0) {
                ALOGE("pthread_create failed: %s", strerror(rc));
                delete sa;
                s.running.store(false);
                s.emu_thread_started = false;
                s.emu_thread_running.store(false);
                return false;
        }
        ALOGI("box64 session started: %s", args[0].c_str());
        return true;
}

void HostRequestQuit() {
        HostState &s = Host();
        /* graceful: the guest window loop exits on SDL_QUIT and main() returns */
        KytyBridgeEvent ev {};
        ev.type = KYTY_EV_QUIT;
        HostPushEvent(ev);
}

void HostKill() {
        HostState &s = Host();

        /* silence every audio device right away */
        {
                std::lock_guard<std::mutex> lock(s.audio_mutex);
                for (auto &slot: s.audio) {
                        slot.stop.store(true);
                        if (slot.stream != nullptr) {
                                AAudioStream_requestStop(slot.stream);
                        }
                }
        }

        /* also ask the guest window loop to quit (it drains on its own) */
        HostRequestQuit();

        if (s.emu_thread_started && s.emu_thread_running.load() &&
            !s.shutdown.load()) {
                /* the translated guest cannot be interrupted mid-dynarec safely;
                 * detach the pthread so a stuck session never blocks the app */
                pthread_detach(s.emu_thread);
        }
        s.running.store(false);
        s.exit_code.store(130); /* forced stop */
        /* tearing down: monitor/rumble/audio threads observe this and exit;
         * also makes the detach guard above meaningful on repeated calls */
        s.shutdown.store(true);
        HostNotifyExit(130);
}

/* rumble dispatch: emulator -> phone vibrator / controller haptics */
static void HostRumbleMain() {
        HostState &s = Host();
        while (!s.shutdown.load()) {
                if (s.shm == nullptr) {
                        break;
                }
                KytyRingHeader *ring = &s.shm->rumble_ring;
                bool had = false;
                for (;;) {
                        uint32_t head = __atomic_load_n(&ring->head, __ATOMIC_ACQUIRE);
                        uint32_t tail = __atomic_load_n(&ring->tail, __ATOMIC_ACQUIRE);
                        if (tail == head) {
                                break;
                        }
                        KytyBridgeRumble cmd = s.shm->rumbles[tail % KYTY_BRIDGE_RUMBLE_SLOTS];
                        __atomic_store_n(&ring->tail, tail + 1, __ATOMIC_RELEASE);
                        had = true;

                        uint32_t amp = cmd.high > cmd.low ? cmd.high : cmd.low;
                        if (amp == 0) {
                                continue;
                        }
                        if (s.vm != nullptr && s.java_callback != nullptr) {
                                JNIEnv *env = nullptr;
                                bool attached = false;
                                if (s.vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
                                        s.vm->AttachCurrentThread(&env, nullptr);
                                        attached = true;
                                }
                                if (env != nullptr) {
                                        jclass cls = env->GetObjectClass(s.java_callback);
                                        jmethodID mid = env->GetMethodID(cls, "onRumble", "(III)V");
                                        if (mid != nullptr) {
                                                int intensity = (int)((amp * 255) / 65535);
                                                if (intensity < 1) {
                                                        intensity = 1;
                                                }
                                                env->CallVoidMethod(s.java_callback, mid, (jint)intensity,
                                                                    (jint)cmd.duration_ms, (jint)cmd.instance_id);
                                        }
                                        if (env->ExceptionCheck()) {
                                                env->ExceptionClear();
                                        }
                                }
                                if (attached) {
                                        s.vm->DetachCurrentThread();
                                }
                        }
                }
                if (!had) {
                        usleep(10000); /* 10 ms */
                }
        }
}

/* -------------------------------------------------------------------------- */
/* lifecycle                                                                   */
/* -------------------------------------------------------------------------- */

bool HostInit(const std::string &files_root, const std::string &native_lib_dir) {
        HostState &s = Host();
        if (s.initialized) {
                return true;
        }
        s.files_root = files_root;
        s.native_lib_dir = native_lib_dir;

        s.shm_path = files_root + "/bridge.shm";

        /* ensure dirs */
        mkdir(files_root.c_str(), 0755);
        mkdir((files_root + "/logs").c_str(), 0755);
        mkdir((files_root + "/bin").c_str(), 0755);
        mkdir((files_root + "/rootfs").c_str(), 0755);
        mkdir((files_root + "/games").c_str(), 0755);
        mkdir((files_root + "/data").c_str(), 0755);
        mkdir((files_root + "/data/tmp").c_str(), 0755);

        /* create + map the bridge (shared with the in-process guest) */
        s.shm_fd = open(s.shm_path.c_str(), O_RDWR | O_CREAT | O_TRUNC, 0644);
        if (s.shm_fd < 0) {
                ALOGE("cannot create bridge shm: %s", strerror(errno));
                return false;
        }
        if (ftruncate(s.shm_fd, (off_t)sizeof(KytyBridgeShm)) != 0) {
                ALOGE("ftruncate failed: %s", strerror(errno));
                close(s.shm_fd);
                s.shm_fd = -1;
                return false;
        }
        void *mem = mmap(nullptr, sizeof(KytyBridgeShm), PROT_READ | PROT_WRITE, MAP_SHARED,
                         s.shm_fd, 0);
        if (mem == MAP_FAILED) {
                ALOGE("mmap failed: %s", strerror(errno));
                close(s.shm_fd);
                s.shm_fd = -1;
                return false;
        }
        memset(mem, 0, sizeof(KytyBridgeShm));
        s.shm = (KytyBridgeShm *)mem;
        s.shm->magic = KYTY_BRIDGE_MAGIC;
        s.shm->version = KYTY_BRIDGE_VERSION;
        s.shm->pid_host = (uint32_t)getpid();
        s.shm->host_alive = 1;
        s.shm->input_ring.capacity = KYTY_BRIDGE_INPUT_SLOTS;
        s.shm->rumble_ring.capacity = KYTY_BRIDGE_RUMBLE_SLOTS;
        for (auto &slot: s.shm->audio) {
                slot.ring.capacity = KYTY_BRIDGE_AUDIO_RING_BYTES;
        }

        s.shutdown.store(false);
        s.rumble_thread = std::thread(HostRumbleMain);
        s.audio_monitor = std::thread(HostAudioMonitorMain);

        s.initialized = true;
        ALOGI("host bridge ready: %s", s.shm_path.c_str());
        return true;
}

} // namespace KytyHost
