// Copyright (c)  2023  Xiaomi Corporation
//
// Hand port of the official sherpa-onnx Java-API Tts.kt
// (sherpa-onnx/sherpa-onnx/java-api). The Maven Central AAR
// (com.bihe0832.android:lib-sherpa-onnx) ships libsherpa-onnx-jni.so with
// full TTS JNI support but its classes.jar contains NO TTS Kotlin classes,
// so the bindings needed by unibot live here.
//
// PORTING NOTES:
//  - Field names and types must match the official file EXACTLY: the native
//    side reads every config field reflectively (GetFieldID), and a renamed
//    or missing field aborts the JNI call.
//  - OfflineTtsModelConfig keeps ALL sub-config fields (vits, matcha,
//    kokoro, zipvoice, kitten, pocket, supertonic) even though unibot only
//    uses vits: the native ParseOfflineTtsModelConfig looks every one of
//    them up eagerly, so dropping any would crash newFromFile.
//  - Trimmed surface vs. the official file: constructor(config) only (no
//    AssetManager variant), no generateWithCallback / generateWithConfig /
//    GenerationConfig, no GeneratedAudio.save, no getOfflineTtsConfig helper.
package com.k2fsa.sherpa.onnx

data class OfflineTtsVitsModelConfig(
    var model: String = "",
    var lexicon: String = "",
    var tokens: String = "",
    var dataDir: String = "",
    var dictDir: String = "", // unused
    var noiseScale: Float = 0.667f,
    var noiseScaleW: Float = 0.8f,
    var lengthScale: Float = 1.0f,
)

data class OfflineTtsMatchaModelConfig(
    var acousticModel: String = "",
    var vocoder: String = "",
    var lexicon: String = "",
    var tokens: String = "",
    var dataDir: String = "",
    var dictDir: String = "", // unused
    var noiseScale: Float = 1.0f,
    var lengthScale: Float = 1.0f,
)

data class OfflineTtsKokoroModelConfig(
    var model: String = "",
    var voices: String = "",
    var tokens: String = "",
    var dataDir: String = "",
    var lexicon: String = "",
    var lang: String = "",
    var dictDir: String = "", // unused
    var lengthScale: Float = 1.0f,
)

data class OfflineTtsZipVoiceModelConfig(
    var tokens: String = "",
    var encoder: String = "",
    var decoder: String = "",
    var vocoder: String = "",
    var dataDir: String = "",
    var lexicon: String = "",
    var featScale: Float = 0.1f,
    var tShift: Float = 0.5f,
    var targetRms: Float = 0.1f,
    var guidanceScale: Float = 1.0f,
)

data class OfflineTtsKittenModelConfig(
    var model: String = "",
    var voices: String = "",
    var tokens: String = "",
    var dataDir: String = "",
    var lengthScale: Float = 1.0f,
)

data class OfflineTtsPocketModelConfig(
    var lmFlow: String = "",
    var lmMain: String = "",
    var encoder: String = "",
    var decoder: String = "",
    var textConditioner: String = "",
    var vocabJson: String = "",
    var tokenScoresJson: String = "",
    var voiceEmbeddingCacheCapacity: Int = 50,
)

data class OfflineTtsSupertonicModelConfig(
    var durationPredictor: String = "",
    var textEncoder: String = "",
    var vectorEstimator: String = "",
    var vocoder: String = "",
    var ttsJson: String = "",
    var unicodeIndexer: String = "",
    var voiceStyle: String = "",
)

data class OfflineTtsModelConfig(
    var vits: OfflineTtsVitsModelConfig = OfflineTtsVitsModelConfig(),
    var matcha: OfflineTtsMatchaModelConfig = OfflineTtsMatchaModelConfig(),
    var kokoro: OfflineTtsKokoroModelConfig = OfflineTtsKokoroModelConfig(),
    var zipvoice: OfflineTtsZipVoiceModelConfig = OfflineTtsZipVoiceModelConfig(),
    var kitten: OfflineTtsKittenModelConfig = OfflineTtsKittenModelConfig(),
    var pocket: OfflineTtsPocketModelConfig = OfflineTtsPocketModelConfig(),
    var supertonic: OfflineTtsSupertonicModelConfig = OfflineTtsSupertonicModelConfig(),

    var numThreads: Int = 1,
    var debug: Boolean = false,
    var provider: String = "cpu",
)

data class OfflineTtsConfig(
    var model: OfflineTtsModelConfig = OfflineTtsModelConfig(),
    var ruleFsts: String = "",
    var ruleFars: String = "",
    var maxNumSentences: Int = 1,
    var silenceScale: Float = 0.2f,
)

class GeneratedAudio(
    val samples: FloatArray,
    val sampleRate: Int,
)

class OfflineTts(
    var config: OfflineTtsConfig,
) {
    private var ptr: Long

    /**
     * [T-android-v121-tts-use-after-free] Guards every native entry point.
     * The engine object is shared between the speak coroutine and release()
     * on another thread: without this lock, release() could delete() the
     * native object between the Kotlin null-check and generateImpl(), and
     * the freed pointer would SIGABRT/SIGSEGV inside sherpa-onnx. The lock
     * is held across the (blocking) native call so free() waits for any
     * in-flight synthesis instead of pulling the object out from under it.
     */
    private val nativeLock = Any()

    init {
        ptr = newFromFile(config)
        require(ptr != 0L) {
            "Invalid OfflineTtsConfig: failed to create native OfflineTts"
        }
    }

    fun sampleRate(): Int = synchronized(nativeLock) {
        checkAlive()
        getSampleRate(ptr)
    }

    fun numSpeakers(): Int = synchronized(nativeLock) {
        checkAlive()
        getNumSpeakers(ptr)
    }

    fun generate(
        text: String,
        sid: Int = 0,
        speed: Float = 1.0f
    ): GeneratedAudio = synchronized(nativeLock) {
        checkAlive()
        generateImpl(ptr, text = text, sid = sid, speed = speed)
    }

    fun free() {
        synchronized(nativeLock) {
            if (ptr != 0L) {
                delete(ptr)
                ptr = 0
            }
        }
    }

    /** Throws instead of letting a null native pointer reach JNI. */
    private fun checkAlive() {
        check(ptr != 0L) { "OfflineTts used after free()" }
    }

    private external fun newFromFile(
        config: OfflineTtsConfig,
    ): Long

    private external fun delete(ptr: Long)
    private external fun getSampleRate(ptr: Long): Int
    private external fun getNumSpeakers(ptr: Long): Int

    // The returned array has two entries:
    //  - the first entry is an 1-D float array containing audio samples.
    //    Each sample is normalized to the range [-1, 1]
    //  - the second entry is the sample rate
    private external fun generateImpl(
        ptr: Long,
        text: String,
        sid: Int = 0,
        speed: Float = 1.0f
    ): GeneratedAudio
}
