/*
 * KytyPS5 Android port — host audio: drains per-device PCM rings from the
 * bridge into AAudio streams. One AAudio stream is created for every audio
 * device the emulator opens (typically the main output plus controller
 * audio), using the exact (freq, channels, format) the guest requested.
 */

#include "kyty_host.h"

#include <android/log.h>

#include <cerrno>
#include <cstring>

#define LOG_TAG "KytyHost"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace KytyHost {

static aaudio_format_t KytyFormatToAAudio(uint32_t fmt) {
        switch (fmt) {
                case KYTY_AUDIO_FMT_S16: return AAUDIO_FORMAT_PCM_I16;
                case KYTY_AUDIO_FMT_S32: return AAUDIO_FORMAT_PCM_I32;
                case KYTY_AUDIO_FMT_F32: return AAUDIO_FORMAT_PCM_FLOAT;
                default: return AAUDIO_FORMAT_UNSPECIFIED;
        }
}

struct AudioCbData {
        int shm_slot;
};

/* AAudio pulls: read from the bridge ring, fill with silence on underrun */
static aaudio_data_callback_result_t HostAudioCallback(AAudioStream *stream, void *user_data,
                                                       void *audio_data, int32_t num_frames) {
        (void)stream;
        auto *data = (AudioCbData *)user_data;
        HostState &s = Host();
        if (data == nullptr || s.shm == nullptr) {
                return AAUDIO_CALLBACK_RESULT_STOP;
        }
        KytyBridgeAudio *slot = &s.shm->audio[data->shm_slot];
        if (__atomic_load_n(&slot->state, __ATOMIC_ACQUIRE) != 1u ||
            __atomic_load_n(&slot->paused, __ATOMIC_ACQUIRE) != 0u) {
                return AAUDIO_CALLBACK_RESULT_CONTINUE;
        }

        KytyRingHeader *ring = &slot->ring;
        uint32_t channels = slot->channels;
        uint32_t fmt = slot->format;
        uint32_t bytes_per_sample = (fmt == KYTY_AUDIO_FMT_F32 || fmt == KYTY_AUDIO_FMT_S32) ? 4 : 2;
        uint32_t frame_bytes = channels * bytes_per_sample;
        size_t want = (size_t)num_frames * frame_bytes;

        auto *out = (uint8_t *)audio_data;
        size_t done = 0;
        while (done < want) {
                uint32_t head = __atomic_load_n(&ring->head, __ATOMIC_ACQUIRE);
                uint32_t tail = ring->tail;
                uint32_t used = head - tail;
                if (used == 0) {
                        break; /* underrun */
                }
                size_t avail = used;
                size_t chunk = want - done < avail ? (want - done) : avail;
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
        if (done < want) {
                memset(out + done, 0, want - done);
        }
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

static void HostAudioThread(int shm_slot) {
        HostState &s = Host();

        KytyBridgeAudio *slot = &s.shm->audio[shm_slot];
        uint32_t fmt = __atomic_load_n(&slot->format, __ATOMIC_ACQUIRE);
        int32_t freq = __atomic_load_n(&slot->freq, __ATOMIC_ACQUIRE);
        uint32_t channels = __atomic_load_n(&slot->channels, __ATOMIC_ACQUIRE);

        auto *cb_data = new AudioCbData{shm_slot};

        AAudioStreamBuilder *builder = nullptr;
        aaudio_result_t res = AAudio_createStreamBuilder(&builder);
        if (res != AAUDIO_OK || builder == nullptr) {
                ALOGE("AAudio_createStreamBuilder failed");
                delete cb_data;
                __atomic_store_n(&slot->state, 0u, __ATOMIC_RELEASE);
                return;
        }
        AAudioStreamBuilder_setFormat(builder, KytyFormatToAAudio(fmt));
        AAudioStreamBuilder_setSampleRate(builder, freq);
        AAudioStreamBuilder_setChannelCount(builder, (int32_t)channels);
        AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
        AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
        AAudioStreamBuilder_setDataCallback(builder, HostAudioCallback, cb_data);

        AAudioStream *stream = nullptr;
        res = AAudioStreamBuilder_openStream(builder, &stream);
        AAudioStreamBuilder_delete(builder);
        if (res != AAUDIO_OK || stream == nullptr) {
                ALOGE("AAudio open failed: %s", AAudio_convertResultToText(res));
                delete cb_data;
                __atomic_store_n(&slot->state, 0u, __ATOMIC_RELEASE);
                return;
        }
        {
                std::lock_guard<std::mutex> lock(s.audio_mutex);
                s.audio[shm_slot].stream = stream;
        }
        AAudioStream_requestStart(stream);
        ALOGI("audio slot %d started: %d Hz, %u ch, fmt %u", shm_slot, freq, channels, fmt);

        while (!s.shutdown.load() && !s.audio[shm_slot].stop.load() &&
               __atomic_load_n(&slot->state, __ATOMIC_ACQUIRE) == 1u) {
                usleep(100000);
        }

        AAudioStream_requestStop(stream);
        AAudioStream_close(stream);
        {
                std::lock_guard<std::mutex> lock(s.audio_mutex);
                s.audio[shm_slot].stream = nullptr;
        }
        delete cb_data;
        ALOGI("audio slot %d closed", shm_slot);
}

void HostAudioMonitorMain() {
        HostState &s = Host();
        while (!s.shutdown.load()) {
                if (s.shm == nullptr) {
                        break;
                }
                for (uint32_t i = 0; i < KYTY_BRIDGE_MAX_AUDIO_DEVS; ++i) {
                        bool started = false;
                        {
                                std::lock_guard<std::mutex> lock(s.audio_mutex);
                                started = s.audio[i].active.load();
                        }
                        KytyBridgeAudio *slot = &s.shm->audio[i];
                        if (!started && __atomic_load_n(&slot->state, __ATOMIC_ACQUIRE) == 1u) {
                                std::lock_guard<std::mutex> lock(s.audio_mutex);
                                if (!s.audio[i].active.load()) {
                                        s.audio[i].active.store(true);
                                        s.audio[i].stop.store(false);
                                        s.audio[i].shm_slot = (int)i;
                                        auto run = [i]() {
                                                HostAudioThread((int)i);
                                                HostState &st = Host();
                                                st.audio[i].active.store(false);
                                        };
                                        s.audio[i].thread = std::thread(run);
                                }
                        }
                }
                usleep(100000);
        }
}

/* -------------------------------------------------------------------------- */
/* Vulkan device enumeration (for the settings screen — real devices)         */
/* -------------------------------------------------------------------------- */

static std::string JsonEscape(const char *s) {
        std::string out;
        for (const char *p = s; p != nullptr && *p != '\0'; ++p) {
                switch (*p) {
                        case '"':
                                out += "\\\"";
                                break;
                        case '\\':
                                out += "\\\\";
                                break;
                        case '\n':
                                out += "\\n";
                                break;
                        case '\r':
                                out += "\\r";
                                break;
                        case '\t':
                                out += "\\t";
                                break;
                        default:
                                if ((unsigned char)*p < 0x20) {
                                        char b[8];
                                        snprintf(b, sizeof(b), "\\u%04x", (unsigned)*p);
                                        out += b;
                                } else {
                                        out += *p;
                                }
                }
        }
        return out;
}

std::string HostEnumerateVulkanDevices() {
        std::string json = "[]";

        VkApplicationInfo app {};
        app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
        app.pApplicationName = "KytyPS5-Android";
        app.apiVersion = VK_API_VERSION_1_1;

        VkInstanceCreateInfo ci {};
        ci.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
        ci.pApplicationInfo = &app;

        VkInstance instance = VK_NULL_HANDLE;
        VkResult res = vkCreateInstance(&ci, nullptr, &instance);
        if (res != VK_SUCCESS) {
                return json;
        }

        uint32_t count = 0;
        if (vkEnumeratePhysicalDevices(instance, &count, nullptr) == VK_SUCCESS && count > 0) {
                std::vector<VkPhysicalDevice> devices(count);
                vkEnumeratePhysicalDevices(instance, &count, devices.data());

                json = "[";
                for (uint32_t i = 0; i < count; ++i) {
                        VkPhysicalDeviceProperties props {};
                        vkGetPhysicalDeviceProperties(devices[i], &props);
                        uint32_t major = VK_VERSION_MAJOR(props.apiVersion);
                        uint32_t minor = VK_VERSION_MINOR(props.apiVersion);
                        json += "{\"index\":" + std::to_string(i) + ",\"name\":\"" +
                                JsonEscape(props.deviceName) + "\",\"api\":" + std::to_string(major) + "." +
                                std::to_string(minor) + ",\"type\":" + std::to_string((int)props.deviceType) + "}";
                        if (i + 1 < count) {
                                json += ",";
                        }
                }
                json += "]";
        }
        vkDestroyInstance(instance, nullptr);
        return json;
}

} // namespace KytyHost
