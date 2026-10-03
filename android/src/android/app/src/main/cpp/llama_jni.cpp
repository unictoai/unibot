// JNI bridge for unibot's on-device LLM chat (llama.cpp).
//
// Thin wrapper only: model load, one full-prompt generation with per-token
// callbacks, cancel, free. Prompt templating, history management, model
// download and threading all live in Kotlin (LocalLlamaBackend /
// LlamaModelManager in ai.unicto.unibot.local).
//
// Each generate() call re-encodes the FULL prompt (Kotlin templates the
// conversation history into it) after clearing the KV cache — slightly less
// efficient than incremental session-KV reuse, but stateless and far easier
// to keep correct.
//
// API provenance: written against the llama.cpp C API as of tag v0.5.0
// (llama_model_load_from_file, llama_init_from_model, llama_tokenize,
// llama_batch_get_one, llama_decode, llama_sampler_*_chain, llama_token_is_eog,
// llama_token_to_piece, llama_get_memory/llama_memory_seq_rm). This file has
// NOT been compiled locally (no NDK in this environment) — CI's
// externalNativeBuild is the first compile. If any symbol drifted between
// v0.5.0 and the pinned tag, the errors will point exactly here.

#include <jni.h>

#include <atomic>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <new>
#include <string>
#include <vector>

#include "llama.h"

namespace {

struct LlamaHandle {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    std::atomic<bool> cancelled{false};
    int n_ctx = 2048;
    // [v1.2 Batch F] Token counts from the most recent nativeGenerate, read
    // by Kotlin via nativeLastTurnStats (benchmark tokens/sec + context
    // indicator). Written on the generate thread, read after it returns.
    int32_t lastPromptTokens = 0;
    int32_t lastGenTokens = 0;
};

LlamaHandle *toHandle(jlong h) { return reinterpret_cast<LlamaHandle *>(h); }

// Longest prefix of `pending` that ends on a UTF-8 character boundary.
// Token pieces routinely split multi-byte characters; emitting the split
// bytes would surface as U+FFFD tofu in the chat, so a truncated tail is
// held back until the next piece completes it.
std::string takeCompleteUtf8(const std::string &pending) {
    const size_t n = pending.size();
    if (n == 0) return "";
    size_t i = n;
    while (i > 0 && (static_cast<unsigned char>(pending[i - 1]) & 0xC0) == 0x80) {
        --i;
    }
    if (i == 0) return "";  // only continuation bytes — wait for the lead byte
    const unsigned char lead = static_cast<unsigned char>(pending[i - 1]);
    size_t need = 1;
    if ((lead & 0x80) == 0x00) {
        need = 1;
    } else if ((lead & 0xE0) == 0xC0) {
        need = 2;
    } else if ((lead & 0xF0) == 0xE0) {
        need = 3;
    } else if ((lead & 0xF8) == 0xF0) {
        need = 4;
    } else {
        need = 1;  // invalid lead byte — emit rather than stall forever
    }
    if (n - (i - 1) < need) return "";  // truncated final character — hold it
    return pending;
}

void emitPiece(JNIEnv *env, jobject callback, jmethodID onToken, const std::string &piece) {
    if (onToken == nullptr || piece.empty()) return;
    jstring jpiece = env->NewStringUTF(piece.c_str());
    if (jpiece == nullptr) return;
    env->CallVoidMethod(callback, onToken, jpiece);
    env->DeleteLocalRef(jpiece);
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_ai_unicto_unibot_local_LlamaCpp_nativeInit(
        JNIEnv *env, jclass, jstring modelPath, jint nCtx, jint nThreads) {
    static std::once_flag backendOnce;
    std::call_once(backendOnce, []() { llama_backend_init(); });

    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    llama_model_params mparams = llama_model_default_params();
    llama_model *model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(modelPath, path);
    if (model == nullptr) return 0;

    auto *h = new (std::nothrow) LlamaHandle();
    if (h == nullptr) {
        llama_free_model(model);
        return 0;
    }
    h->model = model;
    h->vocab = llama_model_get_vocab(model);
    h->n_ctx = nCtx > 0 ? nCtx : 2048;

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(h->n_ctx);
    const int threads = nThreads > 0 ? nThreads : 4;
    cparams.n_threads = threads;
    cparams.n_threads_batch = threads;
    h->ctx = llama_init_from_model(model, cparams);
    if (h->ctx == nullptr) {
        llama_free_model(model);
        delete h;
        return 0;
    }
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT jstring JNICALL
Java_ai_unicto_unibot_local_LlamaCpp_nativeGenerate(
        JNIEnv *env, jclass, jlong handle, jstring prompt,
        jint maxTokens, jfloat temperature, jfloat topP, jint topK,
        jfloat repeatPenalty, jobject callback) {
    LlamaHandle *h = toHandle(handle);
    if (h == nullptr || h->ctx == nullptr || h->vocab == nullptr) {
        return env->NewStringUTF("");
    }
    h->cancelled.store(false);

    jmethodID onToken = nullptr;
    if (callback != nullptr) {
        jclass cbClass = env->GetObjectClass(callback);
        if (cbClass != nullptr) {
            onToken = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");
        }
    }

    // Fresh turn: the prompt already carries the full templated history, so
    // drop any KV state left by the previous turn.
    llama_memory_t mem = llama_get_memory(h->ctx);
    if (mem != nullptr) {
        llama_memory_seq_rm(mem, 0, -1, -1);
    }

    const char *cPrompt = env->GetStringUTFChars(prompt, nullptr);
    const size_t promptLen = strlen(cPrompt);
    // A token is always >= 1 byte, so promptLen + 8 slots can never overflow.
    std::vector<llama_token> tokens(promptLen + 8);
    int32_t nTokens = llama_tokenize(
            h->vocab, cPrompt, static_cast<int32_t>(promptLen),
            tokens.data(), static_cast<int32_t>(tokens.size()), true, true);
    env->ReleaseStringUTFChars(prompt, cPrompt);
    if (nTokens <= 0) {
        return env->NewStringUTF("");
    }

    // Make room for the reply: drop the OLDEST prompt tokens first (the
    // history head), keeping the recent turns and the current question.
    const int32_t nMax = maxTokens > 0 ? maxTokens : 1024;
    const int32_t keep = h->n_ctx - nMax;
    if (keep > 0 && nTokens > keep) {
        tokens.erase(tokens.begin(), tokens.begin() + (nTokens - keep));
        nTokens = keep;
    }
    tokens.resize(static_cast<size_t>(nTokens));
    // [v1.2 Batch F] Remember the prompt token count for nativeLastTurnStats.
    h->lastPromptTokens = nTokens;

    llama_batch batch = llama_batch_get_one(tokens.data(), nTokens);
    if (llama_decode(h->ctx, batch) != 0) {
        return env->NewStringUTF("");
    }

    // [v1.2 Batch F] Sampler chain honours the user's per-model sampler
    // settings: repeat penalty -> top-k -> top-p -> temperature -> dist.
    // temperature <= 0 keeps the previous greedy-only path.
    llama_sampler *sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    } else {
        if (repeatPenalty > 1.0f) {
            llama_sampler_chain_add(
                    sampler,
                    llama_sampler_init_penalties(
                        llama_vocab_n_tokens(h->vocab), 64, repeatPenalty, 0.0f, 0.0f));
        }
        if (topK > 0) {
            llama_sampler_chain_add(sampler, llama_sampler_init_top_k(topK));
        }
        if (topP > 0.0f && topP < 1.0f) {
            llama_sampler_chain_add(sampler, llama_sampler_init_top_p(topP, 1));
        }
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    std::string out;
    std::string pending;  // bytes held back until a UTF-8 boundary
    int32_t genCount = 0;  // [v1.2 Batch F] tokens sampled this turn
    for (int32_t i = 0; i < nMax; i++) {
        if (h->cancelled.load()) break;
        llama_token tok = llama_sampler_sample(sampler, h->ctx, -1);
        if (llama_token_is_eog(h->vocab, tok)) break;
        genCount++;
        char piece[64];
        const int32_t nPiece = llama_token_to_piece(h->vocab, tok, piece, sizeof(piece), 0, true);
        if (nPiece > 0) {
            pending.append(piece, static_cast<size_t>(nPiece));
            const std::string emit = takeCompleteUtf8(pending);
            if (!emit.empty()) {
                pending.erase(0, emit.size());
                out += emit;
                emitPiece(env, callback, onToken, emit);
            }
        }
        llama_batch next = llama_batch_get_one(&tok, 1);
        if (llama_decode(h->ctx, next) != 0) break;
    }
    // Flush any trailing complete bytes; a truncated tail is dropped rather
    // than emitted as tofu.
    const std::string tail = takeCompleteUtf8(pending);
    if (!tail.empty()) {
        out += tail;
        emitPiece(env, callback, onToken, tail);
    }
    llama_sampler_free(sampler);
    // [v1.2 Batch F] Tokens sampled this turn (decode iterations).
    h->lastGenTokens = genCount;
    return env->NewStringUTF(out.c_str());
}

// [v1.2 Batch F] Packed token counts from the last nativeGenerate on this
// handle: (promptTokens << 32) | generatedTokens. Kotlin decodes it for the
// benchmark screen (tokens/sec) and the context-size indicator.
JNIEXPORT jlong JNICALL
Java_ai_unicto_unibot_local_LlamaCpp_nativeLastTurnStats(JNIEnv *, jclass, jlong handle) {
    LlamaHandle *h = toHandle(handle);
    if (h == nullptr) return 0;
    const uint64_t packed =
            (static_cast<uint64_t>(static_cast<uint32_t>(h->lastPromptTokens)) << 32) |
            static_cast<uint64_t>(static_cast<uint32_t>(h->lastGenTokens));
    return static_cast<jlong>(packed);
}

JNIEXPORT void JNICALL
Java_ai_unicto_unibot_local_LlamaCpp_nativeCancel(JNIEnv *, jclass, jlong handle) {
    LlamaHandle *h = toHandle(handle);
    if (h != nullptr) {
        h->cancelled.store(true);
    }
}

JNIEXPORT void JNICALL
Java_ai_unicto_unibot_local_LlamaCpp_nativeFree(JNIEnv *, jclass, jlong handle) {
    LlamaHandle *h = toHandle(handle);
    if (h != nullptr) {
        if (h->ctx != nullptr) llama_free(h->ctx);
        if (h->model != nullptr) llama_free_model(h->model);
        delete h;
    }
}

}  // extern "C"
