/*
 * Bridge protocol integration test (host-side, native x86_64).
 *
 * Links the REAL SDL2 shim (the same code compiled into the emulator) and
 * drives it against a REAL shared-memory bridge laid out exactly as the
 * ARM64 host library creates it. Runs on any Linux x86_64 host — no box64,
 * no Android — so CI catches protocol regressions (shared-memory mapping,
 * event delivery, audio rings, rumble) before the APK is even built.
 */

#include "kyty_bridge.h"

#include "SDL.h"
#include "SDL_events.h"
#include "SDL_gamecontroller.h"

#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <cassert>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <cstdlib>

static const char *kShmPath = "/tmp/kyty-bridge-test.shm";

static KytyBridgeShm *g_host = nullptr; /* host-side mapping */

static void HostWrite() {
        int fd = open(kShmPath, O_RDWR | O_CREAT | O_TRUNC, 0644);
        assert(fd >= 0);
        assert(ftruncate(fd, (off_t)sizeof(KytyBridgeShm)) == 0);
        void *mem = mmap(nullptr, sizeof(KytyBridgeShm), PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
        close(fd);
        assert(mem != MAP_FAILED);
        memset(mem, 0, sizeof(KytyBridgeShm));
        g_host = (KytyBridgeShm *)mem;

        g_host->magic = KYTY_BRIDGE_MAGIC;
        g_host->version = KYTY_BRIDGE_VERSION;
        g_host->pid_host = (uint32_t)getpid();
        g_host->host_alive = 1;
        g_host->input_ring.capacity = KYTY_BRIDGE_INPUT_SLOTS;
        g_host->rumble_ring.capacity = KYTY_BRIDGE_RUMBLE_SLOTS;
        for (auto &slot: g_host->audio) {
                slot.ring.capacity = KYTY_BRIDGE_AUDIO_RING_BYTES;
        }

        /* a pad connected before the guest attaches (hotplug synthesis path) */
        auto *pad = &g_host->pads[0];
        pad->in_use = 1;
        pad->instance_id = 7;
        pad->type = KYTY_PAD_TYPE_PS5;
        pad->connected = 1;
        snprintf(pad->name, sizeof(pad->name), "Test Pad");

        setenv("KYTY_BRIDGE_SHM", kShmPath, 1);
}

static void HostPushEvent(const KytyBridgeEvent &ev) {
        KytyRingHeader *ring = &g_host->input_ring;
        uint32_t head = __atomic_load_n(&ring->head, __ATOMIC_ACQUIRE);
        uint32_t tail = ring->tail;
        assert(head - tail < ring->capacity);
        g_host->input_events[head % KYTY_BRIDGE_INPUT_SLOTS] = ev;
        __atomic_thread_fence(__ATOMIC_SEQ_CST);
        __atomic_store_n(&ring->head, head + 1, __ATOMIC_RELEASE);
}

/* drains up to len bytes from an audio ring like the AAudio callback does */
static size_t HostDrainAudio(KytyBridgeAudio *slot, uint8_t *out, size_t len) {
        KytyRingHeader *ring = &slot->ring;
        size_t done = 0;
        while (done < len) {
                uint32_t head = __atomic_load_n(&ring->head, __ATOMIC_ACQUIRE);
                uint32_t tail = ring->tail;
                uint32_t used = head - tail;
                if (used == 0) {
                        break;
                }
                size_t chunk = std::min(len - done, (size_t)used);
                uint32_t pos = tail % ring->capacity;
                uint32_t first = ring->capacity - pos;
                if (first > chunk) {
                        first = (uint32_t)chunk;
                }
                memcpy(out + done, &slot->data[pos], first);
                if (chunk > first) {
                        memcpy(out + done + first, &slot->data[0], chunk - first);
                }
                __atomic_store_n(&ring->tail, tail + (uint32_t)chunk, __ATOMIC_RELEASE);
                done += chunk;
        }
        return done;
}

#define CHECK(cond)                                                            \
        do {                                                                       \
                if (!(cond)) {                                                         \
                        fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond);    \
                        return 1;                                                          \
                }                                                                      \
        } while (0)

int main() {
        HostWrite();

        /* ---- init + guest attachment (catches the "read instead of mmap"
         * class of bug: guest_ready must become visible on the HOST mapping) */
        CHECK(SDL_InitSubSystem(SDL_INIT_VIDEO | SDL_INIT_GAMECONTROLLER | SDL_INIT_AUDIO) == 0);
        CHECK(__atomic_load_n(&g_host->guest_ready, __ATOMIC_ACQUIRE) == 1);
        CHECK(g_host->pid_guest != 0);

        /* ---- hotplug synthesis for the pre-registered pad ---- */
        SDL_Event ev {};
        CHECK(SDL_PollEvent(&ev) == 1);
        CHECK(ev.type == SDL_CONTROLLERDEVICEADDED);
        CHECK(ev.cdevice.which == 0);
        CHECK(SDL_PollEvent(&ev) == 0); /* queue drained */

        /* ---- keyboard event roundtrip ---- */
        KytyBridgeEvent kev {};
        kev.type = KYTY_EV_KEY_DOWN;
        kev.p1 = 29; /* AKEYCODE_A */
        kev.p2 = 0;
        HostPushEvent(kev);
        CHECK(SDL_WaitEvent(&ev) == 1);
        CHECK(ev.type == SDL_KEYDOWN);
        CHECK(ev.key.keysym.sym == SDLK_a);

        /* ---- timeout semantics: empty queue must time out ---- */
        CHECK(SDL_WaitEventTimeout(&ev, 120) == 0);

        /* ---- audio device + PCM roundtrip ---- */
        SDL_AudioSpec desired {};
        desired.freq = 48000;
        desired.format = AUDIO_S16LSB;
        desired.channels = 2;
        desired.samples = 1024;
        desired.callback = nullptr; /* queued mode — what the emulator uses */
        SDL_AudioSpec obtained {};
        SDL_AudioDeviceID dev = SDL_OpenAudioDevice(nullptr, 0, &desired, &obtained, 0);
        CHECK(dev != 0);
        CHECK(obtained.freq == 48000 && obtained.channels == 2 &&
              obtained.format == AUDIO_S16LSB);

        /* the host sees the slot open with the right parameters */
        KytyBridgeAudio *slot = nullptr;
        for (auto &s: g_host->audio) {
                if (__atomic_load_n(&s.state, __ATOMIC_ACQUIRE) == 1u) {
                        slot = &s;
                }
        }
        CHECK(slot != nullptr);
        CHECK(slot->freq == 48000);
        CHECK(slot->channels == 2);
        CHECK(slot->format == KYTY_AUDIO_FMT_S16);

        /* queue PCM and drain it on the host side */
        uint8_t pcm[4096];
        for (size_t i = 0; i < sizeof(pcm); ++i) {
                pcm[i] = (uint8_t)(i * 7);
        }
        CHECK(SDL_QueueAudio(dev, pcm, sizeof(pcm)) == 0);
        CHECK(SDL_GetQueuedAudioSize(dev) == sizeof(pcm));
        uint8_t drain[4096];
        CHECK(HostDrainAudio(slot, drain, sizeof(drain)) == sizeof(drain));
        CHECK(memcmp(pcm, drain, sizeof(pcm)) == 0);
        CHECK(SDL_GetQueuedAudioSize(dev) == 0);

        /* ---- game controller + rumble roundtrip ---- */
        SDL_GameController *pad = SDL_GameControllerOpen(0);
        CHECK(pad != nullptr);
        CHECK(SDL_GameControllerRumble(pad, 0x1234, 0x5678, 100) == 0);
        {
                KytyRingHeader *ring = &g_host->rumble_ring;
                uint32_t head = __atomic_load_n(&ring->head, __ATOMIC_ACQUIRE);
                uint32_t tail = ring->tail;
                CHECK(head - tail == 1);
                KytyBridgeRumble cmd = g_host->rumbles[tail % KYTY_BRIDGE_RUMBLE_SLOTS];
                CHECK(cmd.instance_id == 7);
                CHECK(cmd.low == 0x1234 && cmd.high == 0x5678);
                CHECK(cmd.duration_ms == 100);
                __atomic_store_n(&ring->tail, tail + 1, __ATOMIC_RELEASE);
        }

        /* ---- axis events through the pad ---- */
        KytyBridgeEvent aev {};
        aev.type = KYTY_EV_PAD_AXIS;
        aev.p1 = 7; /* instance */
        aev.p2 = 0; /* left x */
        aev.p3 = -12345;
        HostPushEvent(aev);
        CHECK(SDL_WaitEvent(&ev) == 1);
        CHECK(ev.type == SDL_CONTROLLERAXISMOTION);
        CHECK(ev.caxis.which == 7);
        CHECK(ev.caxis.value == -12345);

        /* ---- shutdown must not hang ---- */
        SDL_Quit();

        printf("bridge protocol test: ALL PASS\n");
        return 0;
}
