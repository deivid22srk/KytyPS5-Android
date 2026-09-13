package dev.kytyps5.android.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import dev.kytyps5.android.emu.NativeBridge

/**
 * Bridges physical Android controllers (Bluetooth/USB gamepads and
 * keyboards) to the emulator through the bridge pads table. One bridge pad
 * is registered per InputDevice; Android axes/buttons are translated to
 * SDL controller semantics, which the emulator maps onto the DualSense.
 */
class GamepadBridge {

    data class Pad(val instanceId: Int, val deviceId: Int, val name: String)

    /** device id -> bridge pad */
    private val pads = mutableMapOf<Int, Pad>()

    var onPadChanged: ((String) -> Unit)? = null

    fun onResume() {
        InputDevice.getDeviceIds().forEach { id ->
            val dev = InputDevice.getDevice(id) ?: return@forEach
            if (isGamepad(dev)) {
                connect(id)
            }
        }
    }

    fun onPause() {
        pads.keys.toList().forEach { disconnect(it) }
    }

    private fun isGamepad(dev: InputDevice): Boolean =
        (dev.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (dev.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

    fun connect(deviceId: Int): Pad? {
        if (pads.containsKey(deviceId)) {
            return pads[deviceId]
        }
        val dev = InputDevice.getDevice(deviceId) ?: return null
        val type = when (dev.vendorId) {
            0x054C -> if (dev.productId in 0x0CE6..0x0DF2) 1 else 2 // Sony: DualSense range / DS4
            else -> 0
        }
        val name = dev.name ?: "Controller"
        val instance = NativeBridge.padConnect(type, name)
        if (instance < 0) {
            return null
        }
        val pad = Pad(instance, deviceId, name)
        pads[deviceId] = pad
        onPadChanged?.invoke(name)
        return pad
    }

    fun disconnect(deviceId: Int) {
        val pad = pads.remove(deviceId) ?: return
        NativeBridge.padDisconnect(pad.instanceId)
    }

    /** KeyEvent from the activity dispatch; returns true when consumed. */
    fun onKeyEvent(event: KeyEvent): Boolean {
        val dev = event.device ?: return false
        if (isGamepad(dev)) {
            val pad = pads[dev.id] ?: connect(dev.id) ?: return false
            when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_L2 -> {
                    NativeBridge.padAxis(pad.instanceId, AXIS_TRIGGERLEFT,
                        if (event.action == KeyEvent.ACTION_DOWN) 32767 else 0)
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_R2 -> {
                    NativeBridge.padAxis(pad.instanceId, AXIS_TRIGGERRIGHT,
                        if (event.action == KeyEvent.ACTION_DOWN) 32767 else 0)
                    return true
                }
                else -> {
                    val button = mapButton(event.keyCode)
                    if (button >= 0) {
                        val down = event.action == KeyEvent.ACTION_DOWN
                        NativeBridge.padButton(pad.instanceId, button, down)
                        return true
                    }
                }
            }
            return false
        }
        // hardware keyboard: forward as keyboard events to the guest
        val down = event.action == KeyEvent.ACTION_DOWN
        NativeBridge.sendKey(event.keyCode, down, event.metaState)
        return true
    }

    /** Generic MotionEvent from the activity dispatch; returns true when consumed. */
    fun onMotionEvent(event: MotionEvent): Boolean {
        val dev = event.device ?: return false
        if (!isGamepad(dev)) {
            return false
        }
        val pad = pads[dev.id] ?: connect(dev.id) ?: return false
        mapAxes(dev, event, pad)
        return true
    }

    private fun mapAxes(dev: InputDevice, event: MotionEvent, pad: Pad) {
        val i = pad.instanceId

        val x = event.getAxisValue(MotionEvent.AXIS_X)
        val y = event.getAxisValue(MotionEvent.AXIS_Y)
        val z = event.getAxisValue(MotionEvent.AXIS_Z)
        val rz = event.getAxisValue(MotionEvent.AXIS_RZ)
        NativeBridge.padAxis(i, AXIS_LEFTX, floatToS16(x))
        NativeBridge.padAxis(i, AXIS_LEFTY, floatToS16(y))
        NativeBridge.padAxis(i, AXIS_RIGHTX, floatToS16(z))
        NativeBridge.padAxis(i, AXIS_RIGHTY, floatToS16(rz))

        // triggers: brake/gas (modern) or RX/RY (legacy)
        val brake = event.getAxisValue(MotionEvent.AXIS_BRAKE)
        val gas = event.getAxisValue(MotionEvent.AXIS_GAS)
        if (brake != 0f || gas != 0f || hasAxis(dev, MotionEvent.AXIS_BRAKE) ||
            hasAxis(dev, MotionEvent.AXIS_GAS)
        ) {
            NativeBridge.padAxis(i, AXIS_TRIGGERLEFT, triggerToS16(brake))
            NativeBridge.padAxis(i, AXIS_TRIGGERRIGHT, triggerToS16(gas))
        } else if (hasAxis(dev, MotionEvent.AXIS_RX) && hasAxis(dev, MotionEvent.AXIS_RY)) {
            val rx = event.getAxisValue(MotionEvent.AXIS_RX)
            val ry = event.getAxisValue(MotionEvent.AXIS_RY)
            NativeBridge.padAxis(i, AXIS_TRIGGERLEFT, triggerToS16((rx + 1f) / 2f))
            NativeBridge.padAxis(i, AXIS_TRIGGERRIGHT, triggerToS16((ry + 1f) / 2f))
        }

        // hat (dpad as axis) — deduplicated: only send on state change so a
        // stream of MotionEvents does not flood the bridge ring
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val left = hatX < -0.5f
        val right = hatX > 0.5f
        val up = hatY < -0.5f
        val down = hatY > 0.5f
        val last = lastDpad.getOrPut(i) { BooleanArray(4) }
        if (left != last[0]) { NativeBridge.padButton(i, BUTTON_DPAD_LEFT, left); last[0] = left }
        if (right != last[1]) { NativeBridge.padButton(i, BUTTON_DPAD_RIGHT, right); last[1] = right }
        if (up != last[2]) { NativeBridge.padButton(i, BUTTON_DPAD_UP, up); last[2] = up }
        if (down != last[3]) { NativeBridge.padButton(i, BUTTON_DPAD_DOWN, down); last[3] = down }
    }

    /** Per-instance hat dedup: one shared array would make pad B's hat
     *  state suppress pad A's dpad events. */
    private val lastDpad = HashMap<Int, BooleanArray>()

    /** True when the device reports [axis] from a joystick/gamepad source. */
    private fun hasAxis(dev: InputDevice, axis: Int): Boolean =
        dev.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK) != null ||
            dev.getMotionRange(axis, InputDevice.SOURCE_GAMEPAD) != null

    companion object {
        // SDL_GameControllerButton values (protocol order)
        const val BUTTON_A = 0
        const val BUTTON_B = 1
        const val BUTTON_X = 2
        const val BUTTON_Y = 3
        const val BUTTON_BACK = 4
        const val BUTTON_GUIDE = 5
        const val BUTTON_START = 6
        const val BUTTON_LEFTSTICK = 7
        const val BUTTON_RIGHTSTICK = 8
        const val BUTTON_LEFTSHOULDER = 9
        const val BUTTON_RIGHTSHOULDER = 10
        const val BUTTON_DPAD_UP = 11
        const val BUTTON_DPAD_DOWN = 12
        const val BUTTON_DPAD_LEFT = 13
        const val BUTTON_DPAD_RIGHT = 14
        const val BUTTON_TOUCHPAD = 20 // SDL_CONTROLLER_BUTTON_TOUCHPAD in SDL 2.33

        // SDL_GameControllerAxis values
        const val AXIS_LEFTX = 0
        const val AXIS_LEFTY = 1
        const val AXIS_RIGHTX = 2
        const val AXIS_RIGHTY = 3
        const val AXIS_TRIGGERLEFT = 4
        const val AXIS_TRIGGERRIGHT = 5

        fun floatToS16(v: Float): Int = (v.coerceIn(-1f, 1f) * 32767f).toInt()
        fun triggerToS16(v: Float): Int = (v.coerceIn(0f, 1f) * 32767f).toInt()

        fun mapButton(keyCode: Int): Int = when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> BUTTON_A
            KeyEvent.KEYCODE_BUTTON_B -> BUTTON_B
            KeyEvent.KEYCODE_BUTTON_X -> BUTTON_X
            KeyEvent.KEYCODE_BUTTON_Y -> BUTTON_Y
            KeyEvent.KEYCODE_BUTTON_L1 -> BUTTON_LEFTSHOULDER
            KeyEvent.KEYCODE_BUTTON_R1 -> BUTTON_RIGHTSHOULDER
            KeyEvent.KEYCODE_BUTTON_SELECT -> BUTTON_BACK
            KeyEvent.KEYCODE_BUTTON_START -> BUTTON_START
            KeyEvent.KEYCODE_BUTTON_THUMBL -> BUTTON_LEFTSTICK
            KeyEvent.KEYCODE_BUTTON_THUMBR -> BUTTON_RIGHTSTICK
            KeyEvent.KEYCODE_BUTTON_MODE -> BUTTON_GUIDE
            KeyEvent.KEYCODE_DPAD_UP -> BUTTON_DPAD_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> BUTTON_DPAD_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> BUTTON_DPAD_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> BUTTON_DPAD_RIGHT
            else -> -1
        }
    }
}
