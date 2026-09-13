package dev.kytyps5.android.input

import dev.kytyps5.android.emu.NativeBridge

/**
 * On-screen virtual DualSense: registers itself as a real bridge pad, so
 * the emulator treats it exactly like a physical controller (same code path
 * in window.cpp / hostInput.cpp). Touch positions are translated into
 * axes/buttons with proper ranges.
 */
class VirtualPadController {

    var instanceId: Int = -1
        private set

    fun connect(): Boolean {
        if (instanceId >= 0) {
            return true
        }
        instanceId = NativeBridge.padConnect(1, "Virtual DualSense")
        return instanceId >= 0
    }

    fun disconnect() {
        if (instanceId >= 0) {
            NativeBridge.padDisconnect(instanceId)
            instanceId = -1
        }
    }

    fun setStick(left: Boolean, x: Float, y: Float) {
        if (instanceId < 0) {
            return
        }
        val axisX = if (left) GamepadBridge.AXIS_LEFTX else GamepadBridge.AXIS_RIGHTX
        val axisY = if (left) GamepadBridge.AXIS_LEFTY else GamepadBridge.AXIS_RIGHTY
        NativeBridge.padAxis(instanceId, axisX, GamepadBridge.floatToS16(x))
        NativeBridge.padAxis(instanceId, axisY, GamepadBridge.floatToS16(y))
    }

    fun setTrigger(left: Boolean, value01: Float) {
        if (instanceId < 0) {
            return
        }
        val axis = if (left) GamepadBridge.AXIS_TRIGGERLEFT else GamepadBridge.AXIS_TRIGGERRIGHT
        NativeBridge.padAxis(instanceId, axis, GamepadBridge.triggerToS16(value01))
    }

    fun setButton(button: Int, down: Boolean) {
        if (instanceId < 0) {
            return
        }
        NativeBridge.padButton(instanceId, button, down)
    }

    /** touchpad region (the emulator exposes it as a DualSense touchpad) */
    fun touchpad(finger: Int, phase: Int, x: Float, y: Float) {
        if (instanceId < 0) {
            return
        }
        NativeBridge.padTouchpad(instanceId, finger, phase, x, y)
    }
}
