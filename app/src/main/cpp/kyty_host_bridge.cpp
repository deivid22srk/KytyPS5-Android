/*
 * KytyPS5 Android port — host bridge core: shared memory, child process,
 * input injection, rumble dispatch, log streaming.
 */

#include "kyty_host.h"

#include <android/log.h>
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

#define LOG_TAG "KytyHost"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace KytyHost {

static constexpr size_t kLogCap = 512 * 1024;

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
        uint32_t tail = ring->tail;
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
                /* encode as button press/release pair (buttons is +n/-n for down/up) */
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

int HostPadConnect(uint32_t type, const std::string &name) {
        HostState &s = Host();
        if (s.shm == nullptr) {
                return -1;
        }
        static int next_instance = 1;
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
/* logs                                                                        */
/* -------------------------------------------------------------------------- */

static void HostAppendLog(HostState &s, const char *data, size_t len) {
        std::lock_guard<std::mutex> lock(s.log_mutex);
        s.log_buffer.append(data, len);
        if (s.log_buffer.size() > kLogCap) {
                s.log_buffer.erase(0, s.log_buffer.size() - kLogCap);
        }
        if (s.log_file != nullptr) {
                fwrite(data, 1, len, s.log_file);
        }
}

static void HostReaderMain() {
        HostState &s = Host();
        char buf[8192];
        struct pollfd fds[2] {};
        fds[0].events = POLLIN;
        fds[1].events = POLLIN;

        while (!s.shutdown.load()) {
                {
                        std::lock_guard<std::mutex> lock(s.proc_mutex);
                        fds[0].fd = s.stdout_pipe;
                        fds[1].fd = s.stderr_pipe;
                }
                if (fds[0].fd < 0 && fds[1].fd < 0) {
                        break;
                }
                int r = poll(fds, 2, 250);
                if (r <= 0) {
                        continue;
                }
                for (auto &fd: fds) {
                        if ((fd.revents & (POLLIN | POLLHUP)) != 0) {
                                ssize_t n = read(fd.fd, buf, sizeof(buf));
                                if (n > 0) {
                                        HostAppendLog(s, buf, (size_t)n);
                                } else if (n <= 0 && (fd.revents & POLLHUP) != 0) {
                                        std::lock_guard<std::mutex> lock(s.proc_mutex);
                                        if (fd.fd == s.stdout_pipe) {
                                                s.stdout_pipe = -1;
                                        } else if (fd.fd == s.stderr_pipe) {
                                                s.stderr_pipe = -1;
                                        }
                                        close(fd.fd);
                                        fd.fd = -1;
                                }
                        }
                }
        }
}

static void HostWaiterMain() {
        HostState &s = Host();
        pid_t pid;
        int status = 0;
        {
                std::lock_guard<std::mutex> lock(s.proc_mutex);
                pid = s.child_pid;
        }
        if (pid <= 0) {
                return;
        }
        if (waitpid(pid, &status, 0) == pid) {
                int code = WIFEXITED(status) ? WEXITSTATUS(status)
                                              : (WIFSIGNALED(status) ? 128 + WTERMSIG(status) : -1);
                s.exit_code.store(code);
                ALOGI("emulator exited: code=%d", code);
        }
        s.running.store(false);

        /* notify java */
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
                        uint32_t tail = ring->tail;
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
                                        jmethodID mid =
                                            env->GetMethodID(cls, "onRumble", "(III)V");
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
        s.log_file_path = files_root + "/logs/emulator.log";

        /* ensure dirs */
        mkdir(files_root.c_str(), 0755);
        mkdir((files_root + "/logs").c_str(), 0755);
        mkdir((files_root + "/bin").c_str(), 0755);
        mkdir((files_root + "/rootfs").c_str(), 0755);
        mkdir((files_root + "/games").c_str(), 0755);
        mkdir((files_root + "/data").c_str(), 0755);
        mkdir((files_root + "/data/tmp").c_str(), 0755);

        /* create + map the bridge */
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

        s.log_file = fopen(s.log_file_path.c_str(), "wb");
        s.shutdown.store(false);

        s.rumble_thread = std::thread(HostRumbleMain);
        s.audio_monitor = std::thread(HostAudioMonitorMain);

        s.initialized = true;
        ALOGI("host bridge ready: %s", s.shm_path.c_str());
        return true;
}

bool HostStart(const std::string &binary, const std::string &workdir,
               const std::vector<std::string> &args,
               const std::vector<std::pair<std::string, std::string>> &env) {
        HostState &s = Host();
        if (!s.initialized || s.running.load()) {
                return false;
        }

        /* fresh log section */
        if (s.log_file != nullptr) {
                fclose(s.log_file);
        }
        s.log_file = fopen(s.log_file_path.c_str(), "wb");

        int out_p[2];
        int err_p[2];
        if (pipe(out_p) != 0 || pipe(err_p) != 0) {
                return false;
        }

        pid_t pid = fork();
        if (pid < 0) {
                close(out_p[0]);
                close(out_p[1]);
                close(err_p[0]);
                close(err_p[1]);
                return false;
        }
        if (pid == 0) {
                /* child */
                setsid();
                dup2(out_p[1], STDOUT_FILENO);
                dup2(err_p[1], STDERR_FILENO);
                close(out_p[0]);
                close(out_p[1]);
                close(err_p[0]);
                close(err_p[1]);
                int devnull = open("/dev/null", O_RDONLY);
                if (devnull >= 0) {
                        dup2(devnull, STDIN_FILENO);
                        close(devnull);
                }
                if (chdir(workdir.c_str()) != 0) {
                        /* keep going; relative mounts may fail later */
                }
                for (const auto &kv: env) {
                        setenv(kv.first.c_str(), kv.second.c_str(), 1);
                }

                std::vector<char *> argv;
                argv.push_back(const_cast<char *>(args[0].c_str()));
                for (size_t i = 1; i < args.size(); ++i) {
                        argv.push_back(const_cast<char *>(args[i].c_str()));
                }
                argv.push_back(nullptr);
                execv(binary.c_str(), argv.data());

                /* exec failed */
                fprintf(stderr, "execv(%s) failed: %s\n", binary.c_str(), strerror(errno));
                _exit(127);
        }

        /* parent */
        close(out_p[1]);
        close(err_p[1]);
        {
                std::lock_guard<std::mutex> lock(s.proc_mutex);
                s.child_pid = pid;
                s.stdout_pipe = out_p[0];
                s.stderr_pipe = err_p[0];
        }
        s.exit_code.store(-1);
        s.running.store(true);

        if (s.reader_thread.joinable()) {
                s.reader_thread.join();
        }
        if (s.waiter_thread.joinable()) {
                s.waiter_thread.join();
        }
        s.reader_thread = std::thread(HostReaderMain);
        s.waiter_thread = std::thread(HostWaiterMain);
        ALOGI("launched: %s (pid %d)", binary.c_str(), (int)pid);
        return true;
}

void HostRequestQuit() {
        HostState &s = Host();
        KytyBridgeEvent ev {};
        ev.type = KYTY_EV_QUIT;
        HostPushEvent(ev);
        std::lock_guard<std::mutex> lock(s.proc_mutex);
        if (s.child_pid > 0) {
                kill(s.child_pid, SIGTERM);
        }
}

void HostKill() {
        HostState &s = Host();
        std::lock_guard<std::mutex> lock(s.proc_mutex);
        if (s.child_pid > 0) {
                kill(s.child_pid, SIGKILL);
        }
}

void HostShutdown() {
        HostState &s = Host();
        if (!s.initialized) {
                return;
        }
        s.shutdown.store(true);
        HostRequestQuit();
        usleep(50000);
        HostKill();

        if (s.reader_thread.joinable()) {
                s.reader_thread.join();
        }
        if (s.waiter_thread.joinable()) {
                s.waiter_thread.join();
        }
        if (s.rumble_thread.joinable()) {
                s.rumble_thread.join();
        }
        if (s.audio_monitor.joinable()) {
                s.audio_monitor.join();
        }

        {
                std::lock_guard<std::mutex> lock(s.audio_mutex);
                for (auto &slot: s.audio) {
                        if (slot.thread.joinable()) {
                                slot.stop.store(true);
                                slot.thread.join();
                        }
                }
        }

        if (s.shm != nullptr) {
                s.shm->host_alive = 0;
                munmap(s.shm, sizeof(KytyBridgeShm));
                s.shm = nullptr;
        }
        if (s.shm_fd >= 0) {
                close(s.shm_fd);
                s.shm_fd = -1;
        }
        if (s.log_file != nullptr) {
                fclose(s.log_file);
                s.log_file = nullptr;
        }
        HostClearSurface();
        s.initialized = false;
}

} // namespace KytyHost
