/*
 * SDL2 Android-bridge shim — game controller API.
 *
 * Pads are registered by the host in the bridge pads table. The shim
 * synthesizes hotplug events, keeps per-slot handles and forwards
 * rumble/LED/effect requests back to the host (rumble reaches the phone
 * vibrator / controller haptics; LED and adaptive-trigger effects are
 * accepted no-ops).
 */

#include "sdl2_shim_internal.h"

#include <stdatomic.h>
#include <string.h>

extern "C" {

ShimGameController *ShimPadByIndex(int slot) {
        if (slot < 0 || slot >= (int)KYTY_BRIDGE_MAX_PADS) {
                return nullptr;
        }
        return &g_shim.pads[slot];
}

ShimGameController *ShimPadByInstance(SDL_JoystickID instance) {
        for (auto &pad: g_shim.pads) {
                if (pad.used && pad.instance == instance) {
                        return &pad;
                }
        }
        return nullptr;
}

static ShimGameController *ShimPadAlloc(int slot, SDL_JoystickID instance) {
        ShimGameController *pad = &g_shim.pads[slot];
        pad->used = 1;
        pad->slot = slot;
        pad->instance = instance;
        return pad;
}

SDL_GameController *SDL_GameControllerOpen(int device_index) {
        if (g_shim.shm == nullptr || device_index < 0 ||
            device_index >= (int)KYTY_BRIDGE_MAX_PADS) {
                return nullptr;
        }
        const KytyBridgePad *src = &g_shim.shm->pads[device_index];
        if (KYTY_ALOAD(&src->in_use) == 0u) {
                ShimSetError("bridge: no controller at index %d", device_index);
                return nullptr;
        }
        ShimGameController *pad = &g_shim.pads[device_index];
        if (pad->used) {
                return (SDL_GameController *)pad; /* already open */
        }
        pad = ShimPadAlloc(device_index, (SDL_JoystickID)src->instance_id);
        return (SDL_GameController *)pad;
}

void SDL_GameControllerClose(SDL_GameController *gamecontroller) {
        if (gamecontroller == nullptr) {
                return;
        }
        auto *pad = (ShimGameController *)gamecontroller;
        pad->used = 0;
}

SDL_Joystick *SDL_GameControllerGetJoystick(SDL_GameController *gamecontroller) {
        return (SDL_Joystick *)gamecontroller;
}

SDL_JoystickID SDL_JoystickInstanceID(SDL_Joystick *joystick) {
        if (joystick == nullptr) {
                return -1;
        }
        return ((ShimGameController *)joystick)->instance;
}

SDL_GameController *SDL_GameControllerFromInstanceID(SDL_JoystickID instance) {
        ShimGameController *pad = ShimPadByInstance(instance);
        if (pad == nullptr || !pad->used) {
                return nullptr;
        }
        return (SDL_GameController *)pad;
}

const char *SDL_GameControllerName(SDL_GameController *gamecontroller) {
        if (gamecontroller == nullptr || g_shim.shm == nullptr) {
                return nullptr;
        }
        auto *pad = (ShimGameController *)gamecontroller;
        return g_shim.shm->pads[pad->slot].name;
}

SDL_GameControllerType SDL_GameControllerGetType(SDL_GameController *gamecontroller) {
        if (gamecontroller == nullptr || g_shim.shm == nullptr) {
                return SDL_CONTROLLER_TYPE_UNKNOWN;
        }
        auto *pad = (ShimGameController *)gamecontroller;
        const KytyBridgePad *src = &g_shim.shm->pads[pad->slot];
        switch (src->type) {
                case KYTY_PAD_TYPE_PS5: return SDL_CONTROLLER_TYPE_PS5;
                case KYTY_PAD_TYPE_PS4: return SDL_CONTROLLER_TYPE_PS4;
                case KYTY_PAD_TYPE_XBOX: return SDL_CONTROLLER_TYPE_XBOXONE;
                default: return SDL_CONTROLLER_TYPE_UNKNOWN;
        }
}

static void ShimPushRumble(ShimGameController *pad, uint16_t low, uint16_t high,
                           uint32_t duration_ms) {
        if (g_shim.shm == nullptr) {
                return;
        }
        KytyRingHeader *ring = &g_shim.shm->rumble_ring;
        uint32_t head = KYTY_ALOAD(&ring->head);
        uint32_t tail = ring->tail;
        if (head - tail >= ring->capacity) {
                return; /* full: drop (best-effort haptics) */
        }
        KytyBridgeRumble *cmd = &g_shim.shm->rumbles[head % ring->capacity];
        cmd->instance_id = (uint32_t)pad->instance;
        cmd->duration_ms = duration_ms;
        cmd->low = low;
        cmd->high = high;
        KYTY_AFENCE();
        KYTY_ASTORE(&ring->head, head + 1);
}

int SDL_GameControllerRumble(SDL_GameController *gamecontroller, Uint16 low_frequency,
                             Uint16 high_frequency, Uint32 duration_ms) {
        if (gamecontroller == nullptr) {
                return -1;
        }
        ShimPushRumble((ShimGameController *)gamecontroller, low_frequency, high_frequency,
                       duration_ms);
        return 0;
}

int SDL_GameControllerSetLED(SDL_GameController *gamecontroller, Uint8 r, Uint8 g, Uint8 b) {
        (void)gamecontroller;
        (void)r;
        (void)g;
        (void)b;
        return 0; /* accepted no-op: no LED on the bridge transport */
}

int SDL_GameControllerSendEffect(SDL_GameController *gamecontroller, const void *data,
                                 int size) {
        (void)data;
        (void)size;
        if (gamecontroller == nullptr) {
                return -1;
        }
        /* DualSense adaptive-trigger effects degrade to a light rumble pulse */
        ShimPushRumble((ShimGameController *)gamecontroller, 0, 0x2000, 120);
        return 0;
}

} /* extern \"C\" */
