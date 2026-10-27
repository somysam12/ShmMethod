#include <jni.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <cstring>
#include "esp_renderer.h"

#define TAG "ArkRenderJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static EspRenderer g_renderer;

// Pending font data stored until render thread starts
static uint8_t*  g_fontRgba    = nullptr;
static float*    g_fontCw      = nullptr;
static float*    g_fontCo      = nullptr;
static int       g_fontAw      = 0, g_fontAh = 0;
static float     g_fontCharH   = 0.f, g_fontAtlasW = 0.f;
static bool      g_fontPending = false;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_bgmi_EspOverlay_nativeInit(JNIEnv* env, jclass,
                                     jobject surface, jint w, jint h) {
    ANativeWindow* win = ANativeWindow_fromSurface(env, surface);
    if (!win) { LOGE("nativeInit: null window"); return JNI_FALSE; }
    bool ok = g_renderer.init(win, (int)w, (int)h);
    LOGI("nativeInit: %s %dx%d", ok ? "OK" : "FAIL", (int)w, (int)h);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_bgmi_EspOverlay_nativeSetFont(JNIEnv* env, jclass,
                                        jbyteArray rgba,
                                        jint aw, jint ah,
                                        jfloatArray charWidths,
                                        jfloatArray charOffsets,
                                        jfloat charH, jfloat atlasW) {
    // Copy all JNI arrays into native heap — they persist until render thread uses them
    jsize rgbaLen = env->GetArrayLength(rgba);
    delete[] g_fontRgba; g_fontRgba = new uint8_t[rgbaLen];
    jbyte* jrgba = env->GetByteArrayElements(rgba, nullptr);
    memcpy(g_fontRgba, jrgba, rgbaLen);
    env->ReleaseByteArrayElements(rgba, jrgba, JNI_ABORT);

    jsize cwLen = env->GetArrayLength(charWidths);
    delete[] g_fontCw; g_fontCw = new float[cwLen];
    jfloat* jcw = env->GetFloatArrayElements(charWidths, nullptr);
    memcpy(g_fontCw, jcw, cwLen * sizeof(float));
    env->ReleaseFloatArrayElements(charWidths, jcw, JNI_ABORT);

    jsize coLen = env->GetArrayLength(charOffsets);
    delete[] g_fontCo; g_fontCo = new float[coLen];
    jfloat* jco = env->GetFloatArrayElements(charOffsets, nullptr);
    memcpy(g_fontCo, jco, coLen * sizeof(float));
    env->ReleaseFloatArrayElements(charOffsets, jco, JNI_ABORT);

    g_fontAw = (int)aw; g_fontAh = (int)ah;
    g_fontCharH  = (float)charH;
    g_fontAtlasW = (float)atlasW;
    g_fontPending = true;

    g_renderer.setFontAtlas(g_fontRgba, g_fontAw, g_fontAh,
                             g_fontCw, g_fontCo,
                             g_fontCharH, g_fontAtlasW);
    LOGI("nativeSetFont: %dx%d charH=%.1f", (int)aw, (int)ah, (float)charH);
}

JNIEXPORT void JNICALL
Java_com_bgmi_EspOverlay_nativeStartRender(JNIEnv*, jclass) {
    LOGI("nativeStartRender");
    g_renderer.startRenderThread();
}

JNIEXPORT void JNICALL
Java_com_bgmi_EspOverlay_nativeSetFlags(JNIEnv*, jclass,
                                         jboolean line, jboolean box,
                                         jboolean skel, jboolean hp,
                                         jboolean name, jboolean dist,
                                         jboolean count, jboolean veh) {
    g_renderer.setFlags(line, box, skel, hp, name, dist, count, veh);
}

JNIEXPORT void JNICALL
Java_com_bgmi_EspOverlay_nativeStop(JNIEnv*, jclass) {
    LOGI("nativeStop");
    g_renderer.destroy();
    delete[] g_fontRgba; g_fontRgba = nullptr;
    delete[] g_fontCw;   g_fontCw   = nullptr;
    delete[] g_fontCo;   g_fontCo   = nullptr;
    g_fontPending = false;
}

} // extern "C"
