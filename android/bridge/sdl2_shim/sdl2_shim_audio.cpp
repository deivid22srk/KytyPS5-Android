/*
 * SDL2 Android-bridge shim — queued-audio device API.
 *
 * kyty_emulator opens audio devices in queued mode (callback == nullptr) and
 * pushes PCM with SDL_QueueAudio, pacing itself on SDL_GetQueuedAudioSize.
 * The shim forwards bytes into per-device rings in shared memory; the ARM64
 * host drains each ring into its own AAudio stream. Because the host accepts
 * any (freq, channels, format) combination, the obtained spec always echoes
 * the desired spec and no conversion is ever needed (SDL_AudioCVT path is a
 * successful no-op).
 */

#include "sdl2_shim_internal.h"

#include "SDL_audio.h"

#include <stdatomic.h>
#include <stdio.h>
#include <string.h>
#include <time.h>

extern "C" {

static ShimAudioDev g_audio[KYTY_BRIDGE_MAX_AUDIO_DEVS];
static SDL_AudioDeviceID g_next_audio_id = 2;

static uint32_t KytyFormatFromSdl(SDL_AudioFormat fmt) {
        switch (fmt) {
                case AUDIO_S16LSB: return KYTY_AUDIO_FMT_S16;
                case AUDIO_S32LSB: return KYTY_AUDIO_FMT_S32;
                case AUDIO_F32LSB: return KYTY_AUDIO_FMT_F32;
                default: return KYTY_AUDIO_FMT_INVALID;
        }
}

static SDL_AudioFormat SdlFormatFromKyty(uint32_t fmt) {
        switch (fmt) {
                case KYTY_AUDIO_FMT_S16: return AUDIO_S16LSB;
                case KYTY_AUDIO_FMT_S32: return AUDIO_S32LSB;
                case KYTY_AUDIO_FMT_F32: return AUDIO_F32LSB;
                default: return 0;
        }
}

ShimAudioDev *ShimAudioDevById(SDL_AudioDeviceID id) {
        for (auto &dev: g_audio) {
                if (dev.used && dev.id == id) {
                        return &dev;
                }
        }
        return nullptr;
}

/* byte-ring write; blocks while full so the emulator paces itself */
static int KytyRingWriteBytes(KytyBridgeAudio *slot, const uint8_t *data, uint32_t len) {
        KytyRingHeader *ring = &slot->ring;
        uint32_t written = 0;
        while (written < len) {
                uint32_t tail = ring->tail;
                uint32_t head = KYTY_ALOAD(&ring->head);
                uint32_t used = head - tail;
                uint32_t space = ring->capacity - used;
                if (space == 0) {
                        /* host not draining: give it a moment, but do not deadlock forever */
                        struct timespec ts {0, 500 * 1000};
                        nanosleep(&ts, nullptr);
                        uint32_t host_alive = g_shim.shm->host_alive;
                        if (host_alive == 0u) {
                                return (int)written;
                        }
                        continue;
                }
                uint32_t chunk = len - written;
                if (chunk > space) {
                        chunk = space;
                }
                uint32_t pos = head % ring->capacity;
                uint32_t first = ring->capacity - pos;
                if (first > chunk) {
                        first = chunk;
                }
                memcpy(&slot->data[pos], data + written, first);
                if (chunk > first) {
                        memcpy(&slot->data[0], data + written + first, chunk - first);
                }
                KYTY_ASTORE(&ring->head, head + chunk);
                slot->queued_total += chunk;
                written += chunk;
        }
        return (int)written;
}

static uint32_t KytyRingUsedBytes(const KytyBridgeAudio *slot) {
        const KytyRingHeader *ring = &slot->ring;
        uint32_t tail = ring->tail;
        uint32_t head = KYTY_ALOAD(&ring->head);
        return head - tail;
}

/* callback-mode thread: fills buffers via the app callback, then queues them */
struct CbThreadArg {
        ShimAudioDev *dev;
};

static void *ShimAudioCbMain(void *arg_p) {
        auto *arg = (CbThreadArg *)arg_p;
        ShimAudioDev *dev = arg->dev;
        free(arg);

        const uint32_t bytes_per_sample =
            (dev->spec.format == AUDIO_F32LSB || dev->spec.format == AUDIO_S32LSB) ? 4 : 2;
        const uint32_t bytes_per_frame = (uint32_t)dev->spec.channels * bytes_per_sample;
        const uint32_t buf_frames = dev->spec.samples ? dev->spec.samples : 256;
        const uint32_t buf_bytes = buf_frames * bytes_per_frame;
        auto *buf = (uint8_t *)malloc(buf_bytes);
        memset(buf, dev->spec.silence, buf_bytes);

        while (dev->cb_thread_running) {
                if (dev->paused) {
                        struct timespec ts {0, 5 * 1000000};
                        nanosleep(&ts, nullptr);
                        continue;
                }
                if (dev->callback != nullptr) {
                        dev->callback(dev->userdata, (Uint8 *)buf, buf_bytes);
                }
                KytyBridgeAudio *slot = &g_shim.shm->audio[dev->shm_slot];
                if (KYTY_ALOAD(&slot->state) != 1u) {
                        break;
                }
                KytyRingWriteBytes(slot, buf, buf_bytes);
        }
        free(buf);
        return nullptr;
}

SDL_AudioDeviceID SDL_OpenAudioDevice(const char *device, int iscapture,
                                      const SDL_AudioSpec *desired, SDL_AudioSpec *obtained,
                                      int allowed_changes) {
        (void)device;
        (void)allowed_changes;
        if (iscapture != 0 || desired == nullptr || g_shim.shm == nullptr) {
                return 0;
        }

        /* find a free shm slot + local slot */
        int shm_slot = -1;
        int local_slot = -1;
        for (uint32_t i = 0; i < KYTY_BRIDGE_MAX_AUDIO_DEVS; ++i) {
                if (local_slot < 0 && !g_audio[i].used) {
                        local_slot = (int)i;
                }
                if (shm_slot < 0 &&
                    KYTY_ALOAD(&g_shim.shm->audio[i].state) ==
                        0u) {
                        shm_slot = (int)i;
                }
        }
        if (shm_slot < 0 || local_slot < 0) {
                ShimSetError("bridge: no free audio device slot");
                return 0;
        }

        if (KytyFormatFromSdl(desired->format) == KYTY_AUDIO_FMT_INVALID) {
                ShimSetError("bridge: unsupported audio format 0x%04x", (unsigned)desired->format);
                return 0;
        }

        ShimAudioDev *dev = &g_audio[local_slot];
        memset(dev, 0, sizeof(*dev));
        dev->used = 1;
        dev->shm_slot = shm_slot;
        dev->id = g_next_audio_id++;
        dev->spec = *desired;
        dev->paused = 1;
        pthread_mutex_init(&dev->queue_mutex, nullptr);

        KytyBridgeAudio *slot = &g_shim.shm->audio[shm_slot];
        slot->device_id = dev->id;
        KYTY_ASTORE(&slot->freq, desired->freq);
        KYTY_ASTORE(&slot->channels, desired->channels);
        KYTY_ASTORE(&slot->format, KytyFormatFromSdl(desired->format));
        KYTY_ASTORE(&slot->paused, 1u);
        slot->ring.capacity = KYTY_BRIDGE_AUDIO_RING_BYTES;
        KYTY_ASTORE(&slot->ring.head, 0u);
        slot->ring.tail = 0;
        KYTY_ASTORE(&slot->state, 1u);

        /* echo the desired spec: the host plays any configuration natively */
        if (obtained != nullptr) {
                *obtained = *desired;
                obtained->size = (Uint32)desired->samples * desired->channels *
                                 (desired->format == AUDIO_F32LSB || desired->format == AUDIO_S32LSB
                                      ? 4u
                                      : 2u);
        }

        if (desired->callback != nullptr) {
                dev->callback = desired->callback;
                dev->userdata = desired->userdata;
                dev->cb_thread_running = 1;
                auto *arg = (CbThreadArg *)malloc(sizeof(CbThreadArg));
                arg->dev = dev;
                pthread_create(&dev->cb_thread, nullptr, ShimAudioCbMain, arg);
        }

        return dev->id;
}

void SDL_CloseAudioDevice(SDL_AudioDeviceID dev_id) {
        ShimAudioDev *dev = ShimAudioDevById(dev_id);
        if (dev == nullptr) {
                return;
        }
        if (dev->cb_thread_running) {
                dev->cb_thread_running = 0;
                pthread_join(dev->cb_thread, nullptr);
        }
        KytyBridgeAudio *slot = &g_shim.shm->audio[dev->shm_slot];
        KYTY_ASTORE(&slot->state, 2u);
        pthread_mutex_destroy(&dev->queue_mutex);
        dev->used = 0;
}

void ShimAudioCloseAll(void) {
        for (auto &dev: g_audio) {
                if (dev.used) {
                        SDL_CloseAudioDevice(dev.id);
                }
        }
}

void SDL_PauseAudioDevice(SDL_AudioDeviceID dev_id, int pause) {
        ShimAudioDev *dev = ShimAudioDevById(dev_id);
        if (dev == nullptr) {
                return;
        }
        dev->paused = pause;
        KytyBridgeAudio *slot = &g_shim.shm->audio[dev->shm_slot];
        KYTY_ASTORE(&slot->paused, (uint32_t)(pause != 0));
}

int SDL_QueueAudio(SDL_AudioDeviceID dev_id, const void *data, Uint32 len) {
        ShimAudioDev *dev = ShimAudioDevById(dev_id);
        if (dev == nullptr || data == nullptr) {
                return -1;
        }
        KytyBridgeAudio *slot = &g_shim.shm->audio[dev->shm_slot];
        if (KYTY_ALOAD(&slot->state) != 1u) {
                return -1;
        }
        KytyRingWriteBytes(slot, (const uint8_t *)data, len);
        return 0;
}

Uint32 SDL_GetQueuedAudioSize(SDL_AudioDeviceID dev_id) {
        ShimAudioDev *dev = ShimAudioDevById(dev_id);
        if (dev == nullptr) {
                return 0;
        }
        return KytyRingUsedBytes(&g_shim.shm->audio[dev->shm_slot]);
}

void SDL_ClearQueuedAudio(SDL_AudioDeviceID dev_id) {
        ShimAudioDev *dev = ShimAudioDevById(dev_id);
        if (dev == nullptr) {
                return;
        }
        KytyBridgeAudio *slot = &g_shim.shm->audio[dev->shm_slot];
        KytyRingHeader *ring = &slot->ring;
        uint32_t head = KYTY_ALOAD(&ring->head);
        /* producer moves the consumer index; documented benign race with a
         * concurrent host read (at worst one stale chunk is replayed) */
        KYTY_AFENCE();
        KYTY_ASTORE(&ring->tail, head);
}

int SDL_BuildAudioCVT(SDL_AudioCVT *cvt, SDL_AudioFormat src_format, Uint8 src_channels,
                      int src_rate, SDL_AudioFormat dst_format, Uint8 dst_channels,
                      int dst_rate) {
        if (cvt == nullptr) {
                return -1;
        }
        memset(cvt, 0, sizeof(*cvt));
        (void)src_format;
        (void)src_channels;
        (void)src_rate;
        (void)dst_format;
        (void)dst_channels;
        (void)dst_rate;
        cvt->needed = 0; /* host accepts the source layout natively */
        return 0;
}

int SDL_ConvertAudio(SDL_AudioCVT *cvt) {
        if (cvt == nullptr || cvt->needed != 0) {
                return -1;
        }
        return 0;
}

void SDL_LockAudioDevice(SDL_AudioDeviceID dev_id) {
        ShimAudioDev *dev = ShimAudioDevById(dev_id);
        if (dev != nullptr) {
                pthread_mutex_lock(&dev->queue_mutex);
        }
}

void SDL_UnlockAudioDevice(SDL_AudioDeviceID dev_id) {
        ShimAudioDev *dev = ShimAudioDevById(dev_id);
        if (dev != nullptr) {
                pthread_mutex_unlock(&dev->queue_mutex);
        }
}

} /* extern "C" */
