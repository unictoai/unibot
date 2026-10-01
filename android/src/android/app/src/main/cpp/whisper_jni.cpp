// JNI bridge for unibot's offline speech-to-text (whisper.cpp).
//
// Thin wrapper only: model load, one-shot transcription of 16 kHz mono PCM,
// context free. All capture, threading and download logic lives in Kotlin
// (WhisperCppSpeechRecognitionEngine / WhisperModelManager).

#include <jni.h>

#include <string>
#include <vector>

#include "whisper.h"

namespace {

std::string trim(const std::string &s) {
    size_t b = s.find_first_not_of(" \t\r\n");
    if (b == std::string::npos) return "";
    size_t e = s.find_last_not_of(" \t\r\n");
    return s.substr(b, e - b + 1);
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_ai_unicto_unibot_speech_WhisperCpp_nativeInit(JNIEnv *env, jclass, jstring modelPath) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    struct whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jstring JNICALL
Java_ai_unicto_unibot_speech_WhisperCpp_nativeTranscribe(
        JNIEnv *env, jclass, jlong ctxHandle, jshortArray pcmArray) {
    auto *ctx = reinterpret_cast<struct whisper_context *>(ctxHandle);
    if (ctx == nullptr) {
        return env->NewStringUTF("");
    }

    const jsize n = env->GetArrayLength(pcmArray);
    jshort *elems = env->GetShortArrayElements(pcmArray, nullptr);
    std::vector<float> pcm(static_cast<size_t>(n));
    for (jsize i = 0; i < n; i++) {
        pcm[static_cast<size_t>(i)] = elems[i] / 32768.0f;
    }
    env->ReleaseShortArrayElements(pcmArray, elems, JNI_ABORT);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.language = "en";
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.n_threads = 4;

    std::string out;
    if (whisper_full(ctx, params, pcm.data(), static_cast<int>(pcm.size())) == 0) {
        const int nseg = whisper_full_n_segments(ctx);
        for (int i = 0; i < nseg; i++) {
            const char *t = whisper_full_get_segment_text(ctx, i);
            if (t != nullptr && t[0] != '\0') {
                if (!out.empty()) out += " ";
                out += t;
            }
        }
    }
    out = trim(out);
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT void JNICALL
Java_ai_unicto_unibot_speech_WhisperCpp_nativeFree(JNIEnv *, jclass, jlong ctxHandle) {
    auto *ctx = reinterpret_cast<struct whisper_context *>(ctxHandle);
    if (ctx != nullptr) {
        whisper_free(ctx);
    }
}

}  // extern "C"
