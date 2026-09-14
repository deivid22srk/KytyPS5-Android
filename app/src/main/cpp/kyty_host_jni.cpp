/*
 * KytyPS5 Android port — JNI boundary between the Kotlin app and the host
 * library. Every UI action maps to a real native operation: process launch
 * (box64 -> x86_64 kyty_emulator), surface publication, input injection,
 * log streaming and Vulkan device enumeration.
 */

#include "kyty_host.h"

#include <android/native_window_jni.h>
#include <android/log.h>

#include <cstring>

#define LOG_TAG "KytyHost"
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace KytyHost;

static std::string JStringToStd(JNIEnv *env, jstring s) {
        if (s == nullptr) {
                return {};
        }
        const char *c = env->GetStringUTFChars(s, nullptr);
        std::string out(c != nullptr ? c : "");
        if (c != nullptr) {
                env->ReleaseStringUTFChars(s, c);
        }
        return out;
}

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void * /*reserved*/) {
        Host().vm = vm;
        return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL
Java_dev_kytyps5_android_emu_NativeBridge_nativeSetCallback(JNIEnv *env, jclass /*cls*/,
                                                            jobject callback) {
        HostState &s = Host();
        if (s.java_callback != nullptr) {
                env->DeleteGlobalRef(s.java_callback);
        }
        s.java_callback = env->NewGlobalRef(callback);
}

JNIEXPORT jboolean JNICALL
Java_dev_kytyps5_android_emu_NativeBridge_nativeInit(JNIEnv *env, jclass /*cls*/,
                                                     jstring files_root, jstring native_lib_dir) {
        return HostInit(JStringToStd(env, files_root), JStringToStd(env, native_lib_dir))
                   ? JNI_TRUE
                   : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_dev_kytyps5_android_emu_NativeBridge_nativeStart(JNIEnv *env, jclass /*cls*/,
                                                      jobjectArray jargs,
                                                      jobjectArray jenv_pairs) {
        HostState &s = Host();
        if (!s.initialized) {
                return JNI_FALSE;
        }

        std::vector<std::string> args;
        jsize argc = env->GetArrayLength(jargs);
        for (jsize i = 0; i < argc; ++i) {
                auto str = (jstring)env->GetObjectArrayElement(jargs, i);
                args.push_back(JStringToStd(env, str));
                env->DeleteLocalRef(str);
        }
        if (args.empty()) {
                return JNI_FALSE;
        }

        std::vector<std::pair<std::string, std::string>> env_pairs;
        if (jenv_pairs != nullptr) {
                jsize n = env->GetArrayLength(jenv_pairs);
                for (jsize i = 0; i < n; ++i) {
                        auto pair = (jobjectArray)env->GetObjectArrayElement(jenv_pairs, i);
                        auto k = (jstring)env->GetObjectArrayElement(pair, 0);
                        auto v = (jstring)env->GetObjectArrayElement(pair, 1);
                        env_pairs.emplace_back(JStringToStd(env, k), JStringToStd(env, v));
                        env->DeleteLocalRef(k);
                        env->DeleteLocalRef(v);
                        env->DeleteLocalRef(pair);
                }
        }

        std::string workdir = s.files_root + "/data";
        return HostStart(workdir, args, env_pairs) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_dev_kytyps5_android_emu_NativeBridge_nativeStop(JNIEnv *env, jclass /*cls*/,
                                                     jboolean force) {
        if (force == JNI_TRUE) {
                HostKill();
        } else {
                HostRequestQuit();
        }
}

JNIEXPORT jboolean JNICALL
Java_dev_kytyps5_android_emu_NativeBridge_nativeIsRunning(JNIEnv * /*env*/, jclass /*cls*/) {
        return Host().running.load() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_dev_kytyps5_android_emu_NativeBridge_nativeGetExitCode(JNIEnv * /*env*/, jclass /*cls*/) {
        return Host().exit_code.load();
}

JNIEXPORT jstring JNICALL
Java_dev_kytyps5_android_emu_NativeBridge_nativeReadLog(JNIEnv *env, jclass /*cls*/) {
        return env->NewStringUTF(HostReadLogTail().c_str());
}

JNIEXPORT void JNICALL
Java_dev_kytyps5_android_emu_NativeBridge_nativeSetSurface(JNIEnv *env, jclass /*cls*/,
                                                           jobject surface, jint w, jint h) {
        if (surface == nullptr) {
                HostClearSurface();
                return;
        }
        ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
        if (window == nullptr) {
                ALOGE("ANativeWindow_fromSurface failed");
                return;
        }
        HostSetSurface(window, (uint32_t)w, (uint32_t)h);
}

JNIEXPORT void JNICALL
Java_dev_kytyps5_android_emu_NativeBridge_nativeSendKey(JNIEnv * /*env*/, jclass /*cls*/,
                                                        jint keycode, jboolean down,
                                                        jint meta) {
        HostSendKey(keycode, down == JNI_TRUE, (uint32_t)meta);
}

JNIEXPORT void JNICALL
Java_dev_kytyps5_android_emu_NativeBridge_nativeSendText(JNIEnv *env, jclass /*cls*/,
                                                         jstring text) {
        HostSendText(JStringToStd(env, text));
}

JNIEXPORT void JNICALL
Java_dev_kytyps5_android_emu_NativeBridge_nativeSendMouse(JNIEnv * /*env*/, jclass /*cls*/,
                                                          jint x, jint y, jint dx, jint dy,
                                                          jint buttons, jint wheel_dx,
                                                          jint wheel_dy) {
        HostSendMouse(x, y, dx, dy, buttons, wheel_dx, wheel_dy);
}

JNIEXPORT void JNICALL
Java_dev_kytyps5_android_emu_NativeBridge_nativeSendFinger(JNIEnv * /*env*/, jclass /*cls*/,
                                                           jint finger, jint phase, jfloat x,
                                                           jfloat y) {
        HostSendFinger(finger, phase, x, y);
}

JNIEXPORT jint JNICALL
Java_dev_kytyps5_android_emu_NativeBridge_nativePadConnect(JNIEnv *env, jclass /*cls*/,
                                                           jint type, jstring name) {
        return HostPadConnect((uint32_t)type, JStringToStd(env, name));
}

JNIEXPORT void JNICALL
Java_dev_kytyps5_android_emu_NativeBridge_nativePadDisconnect(JNIEnv * /*env*/, jclass /*cls*/,
                                                              jint instance) {
        HostPadDisconnect(instance);
}

JNIEXPORT void JNICALL
Java_dev_kytyps5_android_emu_NativeBridge_nativePadAxis(JNIEnv * /*env*/, jclass /*cls*/,
                                                        jint instance, jint axis, jint value) {
        HostPadAxis(instance, axis, value);
}

JNIEXPORT void JNICALL
Java_dev_kytyps5_android_emu_NativeBridge_nativePadButton(JNIEnv * /*env*/, jclass /*cls*/,
                                                          jint instance, jint button,
                                                          jboolean down) {
        HostPadButton(instance, button, down == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_dev_kytyps5_android_emu_NativeBridge_nativePadTouchpad(JNIEnv * /*env*/, jclass /*cls*/,
                                                            jint instance, jint finger,
                                                            jint phase, jfloat x, jfloat y) {
        HostPadTouchpad(instance, finger, phase, x, y);
}

JNIEXPORT void JNICALL
Java_dev_kytyps5_android_emu_NativeBridge_nativeSendWindowResized(JNIEnv * /*env*/,
                                                                  jclass /*cls*/, jint w,
                                                                  jint h) {
        HostSendWindowResized((uint32_t)w, (uint32_t)h);
}

JNIEXPORT void JNICALL
Java_dev_kytyps5_android_emu_NativeBridge_nativeSendLifecycle(JNIEnv * /*env*/, jclass /*cls*/,
                                                              jint ev) {
        HostSendLifecycle(ev);
}

JNIEXPORT void JNICALL
Java_dev_kytyps5_android_emu_NativeBridge_nativeSendOrientation(JNIEnv * /*env*/, jclass /*cls*/,
                                                                jint orientation) {
        HostSendOrientation(orientation);
}

JNIEXPORT jstring JNICALL
Java_dev_kytyps5_android_emu_NativeBridge_nativeEnumerateVulkanDevices(JNIEnv *env,
                                                                       jclass /*cls*/) {
        return env->NewStringUTF(HostEnumerateVulkanDevices().c_str());
}

JNIEXPORT jboolean JNICALL
Java_dev_kytyps5_android_emu_NativeBridge_nativeIsGuestReady(JNIEnv * /*env*/, jclass /*cls*/) {
        HostState &s = Host();
        return (s.shm != nullptr && s.shm->guest_ready != 0u) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_dev_kytyps5_android_emu_NativeBridge_nativeBox64Available(JNIEnv * /*env*/, jclass /*cls*/) {
        /* real probe: resolves libbox64.so through the class-loader namespace
         * (extracted to nativeLibraryDir by useLegacyPackaging=true) */
        return HostBox64Available() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_dev_kytyps5_android_emu_NativeBridge_nativeInstallVulkanDriver(JNIEnv *env,
                                                                   jclass /*cls*/,
                                                                   jstring driver_dir,
                                                                   jstring driver_soname) {
        return HostInstallVulkanDriver(JStringToStd(env, driver_dir),
                                       JStringToStd(env, driver_soname))
                   ? JNI_TRUE
                   : JNI_FALSE;
}

} /* extern "C" */
