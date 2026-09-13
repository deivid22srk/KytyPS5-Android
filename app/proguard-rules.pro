# KytyPS5 Android — R8/ProGuard rules
# The release build keeps the JNI entry points and the classes referenced
# from native code intact; everything else may be shrunk when minify is
# enabled.

-keep class dev.kytyps5.android.emu.NativeBridge { *; }
-keepclassmembers class dev.kytyps5.android.emu.NativeBridge {
    private void native*(...);
}

# referenced from JNI (kyty_host_bridge.cpp / kyty_host_jni.cpp)
-keep class dev.kytyps5.android.emu.EmuCallbacks {
    public void onEmulatorExit(int);
    public void onRumble(int, int, int);
}
