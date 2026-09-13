package dev.kytyps5.android.emu

import android.view.Surface

/**
 * JNI boundary to libkytyhost.so (ARM64 host library).
 *
 * Every function maps to a real native operation: process management for the
 * box64 child, shared-memory bridge IO, input injection and Vulkan device
 * enumeration. See app/src/main/cpp/kyty_host_jni.cpp.
 */
object NativeBridge {
    init {
        System.loadLibrary("kytyhost")
    }

    fun setCallback(callback: Any) = nativeSetCallback(callback)

    fun init(filesRoot: String, nativeLibDir: String): Boolean = nativeInit(filesRoot, nativeLibDir)

    /** argv[0] is the executable (box64); remaining entries are its arguments. */
    fun start(args: Array<String>, envPairs: Array<Array<String>>): Boolean =
        nativeStart(args, envPairs)

    fun stop(force: Boolean) = nativeStop(force)

    fun isRunning(): Boolean = nativeIsRunning()

    fun exitCode(): Int = nativeGetExitCode()

    /** Drains the accumulated child stdout/stderr and returns it. */
    fun readLog(): String = nativeReadLog()

    fun setSurface(surface: Surface?, w: Int, h: Int) = nativeSetSurface(surface, w, h)

    fun sendKey(keycode: Int, down: Boolean, meta: Int) = nativeSendKey(keycode, down, meta)

    fun sendText(text: String) = nativeSendText(text)

    fun sendMouse(x: Int, y: Int, dx: Int, dy: Int, buttons: Int, wheelDx: Int, wheelDy: Int) =
        nativeSendMouse(x, y, dx, dy, buttons, wheelDx, wheelDy)

    fun sendFinger(finger: Int, phase: Int, x: Float, y: Float) =
        nativeSendFinger(finger, phase, x, y)

    /** Returns the pad instance id, or -1 when the table is full. */
    fun padConnect(type: Int, name: String): Int = nativePadConnect(type, name)

    fun padDisconnect(instance: Int) = nativePadDisconnect(instance)

    fun padAxis(instance: Int, axis: Int, value: Int) = nativePadAxis(instance, axis, value)

    fun padButton(instance: Int, button: Int, down: Boolean) = nativePadButton(instance, button, down)

    fun padTouchpad(instance: Int, finger: Int, phase: Int, x: Float, y: Float) =
        nativePadTouchpad(instance, finger, phase, x, y)

    fun sendWindowResized(w: Int, h: Int) = nativeSendWindowResized(w, h)

    /** ev is a KYTY_EV_APP_* constant from the bridge protocol. */
    fun sendLifecycle(ev: Int) = nativeSendLifecycle(ev)

    fun sendOrientation(orientation: Int) = nativeSendOrientation(orientation)

    /** JSON array of {index, name, api, type} for every physical Vulkan device. */
    fun enumerateVulkanDevices(): String = nativeEnumerateVulkanDevices()

    fun isGuestReady(): Boolean = nativeIsGuestReady()

    /** Real dlopen probe of libbox64.so (cached native-side). Heavy on the
     *  first call — run it off the main thread. */
    fun box64Available(): Boolean = nativeBox64Available()

    // ---- raw JNI ----
    private external fun nativeSetCallback(callback: Any)
    private external fun nativeInit(filesRoot: String, nativeLibDir: String): Boolean
    private external fun nativeStart(args: Array<String>, envPairs: Array<Array<String>>): Boolean
    private external fun nativeStop(force: Boolean)
    private external fun nativeIsRunning(): Boolean
    private external fun nativeGetExitCode(): Int
    private external fun nativeReadLog(): String
    private external fun nativeSetSurface(surface: Surface?, w: Int, h: Int)
    private external fun nativeSendKey(keycode: Int, down: Boolean, meta: Int)
    private external fun nativeSendText(text: String)
    private external fun nativeSendMouse(x: Int, y: Int, dx: Int, dy: Int, buttons: Int,
                                         wheelDx: Int, wheelDy: Int)
    private external fun nativeSendFinger(finger: Int, phase: Int, x: Float, y: Float)
    private external fun nativePadConnect(type: Int, name: String): Int
    private external fun nativePadDisconnect(instance: Int)
    private external fun nativePadAxis(instance: Int, axis: Int, value: Int)
    private external fun nativePadButton(instance: Int, button: Int, down: Boolean)
    private external fun nativePadTouchpad(instance: Int, finger: Int, phase: Int, x: Float,
                                           y: Float)
    private external fun nativeSendWindowResized(w: Int, h: Int)
    private external fun nativeSendLifecycle(ev: Int)
    private external fun nativeSendOrientation(orientation: Int)
    private external fun nativeEnumerateVulkanDevices(): String
    private external fun nativeIsGuestReady(): Boolean
    private external fun nativeBox64Available(): Boolean
}
