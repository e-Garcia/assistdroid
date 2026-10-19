// Thin JNI layer over whisper.cpp: load a model file, transcribe 16 kHz mono float audio, free.
#include <jni.h>
#include <android/log.h>
#include <string.h>
#include <stdlib.h>
#include "whisper.h"

#define TAG "HarnessWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

JNIEXPORT jlong JNICALL
Java_ga_egarcia_harness_voice_WhisperNative_nativeInit(JNIEnv *env, jobject thiz, jstring path) {
    (void) thiz;
    const char *chars = (*env)->GetStringUTFChars(env, path, NULL);
    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    struct whisper_context *ctx = whisper_init_from_file_with_params(chars, cparams);
    (*env)->ReleaseStringUTFChars(env, path, chars);
    return (jlong) ctx;
}

JNIEXPORT void JNICALL
Java_ga_egarcia_harness_voice_WhisperNative_nativeFree(JNIEnv *env, jobject thiz, jlong ptr) {
    (void) env; (void) thiz;
    if (ptr != 0) whisper_free((struct whisper_context *) ptr);
}

// Returns the transcript as UTF-8 bytes (decoded in Kotlin: JNI's modified UTF-8 cannot carry every character), or null on failure.
JNIEXPORT jbyteArray JNICALL
Java_ga_egarcia_harness_voice_WhisperNative_nativeTranscribe(JNIEnv *env, jobject thiz, jlong ptr, jfloatArray audio, jint threads, jstring language) {
    (void) thiz;
    struct whisper_context *ctx = (struct whisper_context *) ptr;
    if (ctx == NULL) return NULL;

    jfloat *samples = (*env)->GetFloatArrayElements(env, audio, NULL);
    const jsize count = (*env)->GetArrayLength(env, audio);
    const char *lang = (*env)->GetStringUTFChars(env, language, NULL);

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.language = lang;              // "en", "es", ... or "auto"
    params.detect_language = false;      // "auto" is handled by whisper_full itself
    params.n_threads = threads;
    params.no_context = true;
    params.single_segment = false;
    params.no_timestamps = true;
    params.suppress_blank = true;

    int rc = whisper_full(ctx, params, samples, count);
    (*env)->ReleaseFloatArrayElements(env, audio, samples, JNI_ABORT);
    (*env)->ReleaseStringUTFChars(env, language, lang);
    if (rc != 0) {
        LOGI("whisper_full failed: %d", rc);
        return NULL;
    }

    const int n = whisper_full_n_segments(ctx);
    size_t total = 0;
    for (int i = 0; i < n; i++) total += strlen(whisper_full_get_segment_text(ctx, i));
    char *buffer = (char *) malloc(total + 1);
    if (buffer == NULL) return NULL;
    size_t at = 0;
    for (int i = 0; i < n; i++) {
        const char *text = whisper_full_get_segment_text(ctx, i);
        size_t len = strlen(text);
        memcpy(buffer + at, text, len);
        at += len;
    }
    jbyteArray out = (*env)->NewByteArray(env, (jsize) at);
    (*env)->SetByteArrayRegion(env, out, 0, (jsize) at, (const jbyte *) buffer);
    free(buffer);
    return out;
}
