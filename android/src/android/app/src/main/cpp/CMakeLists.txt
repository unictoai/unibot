cmake_minimum_required(VERSION 3.22.1)
project(unibot_native CXX C)

add_library(pty_bridge SHARED pty_bridge.c)

# T283: NDK signal handler for native crashes (SIGSEGV/SIGABRT/...).
# Separate library so loadLibrary() can pull it in independently of
# pty_bridge — the crash handler must be installed before any other
# native lib has a chance to fault.
add_library(unibot_crash_handler SHARED crash_handler.cpp)

# [T-android-text-segmenter] CppJieba JNI bridge for TextSegmenter's Chinese
# path. cppjieba is header-only (no static lib to build); this version vendors
# limonp inline, so a single include root (cppjieba/include) is all it needs.
# Headers are included as "cppjieba/<Name>.hpp", so we add the parent dir.
add_library(jieba_jni SHARED jieba_jni.cpp)
target_include_directories(jieba_jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/cppjieba/include
)

find_library(log-lib log)
target_link_libraries(pty_bridge ${log-lib})
target_link_libraries(unibot_crash_handler ${log-lib})
target_link_libraries(jieba_jni ${log-lib})

# ── On-device LLM chat (llama.cpp) ──────────────────────────────────────
# Declared FIRST on purpose: both whisper.cpp and llama.cpp vendor ggml and
# each skips its own ggml when a `ggml` target already exists
# (`if (NOT TARGET ggml)`). llama.cpp is newer and needs newer ggml symbols,
# so its ggml must win; whisper.cpp then reuses the newer ggml (backward
# compatible). Reversing this order breaks the llama build with
# "use of undeclared identifier 'ggml_dsv4_hc_pre_gated'".
# The app never bundles a GGUF in the APK — the user opts into a download
# from the llm-model-v1 GitHub release (see .github/workflows/llm-model.yml),
# and chat templates are applied in Kotlin (LocalLlamaBackend), so
# LLAMA_BUILD_COMMON stays off.
include(FetchContent)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
FetchContent_Declare(
    llamacpp
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG v0.5.0
)
FetchContent_MakeAvailable(llamacpp)

add_library(unibot_llama SHARED llama_jni.cpp)
target_link_libraries(unibot_llama PRIVATE llama ${log-lib})

# ── Offline speech-to-text (whisper.cpp) ────────────────────────────────
# The app never bundles the model in the APK: the user taps
# "Download offline voice" in the voice panel and it fetches
# ggml-tiny.en-q5_1.bin (~31 MB) from the voice-model-v1 GitHub release.
# whisper.cpp itself is built here from a pinned tag via FetchContent.
# NOTE: declared AFTER llama.cpp (see above) so it reuses llama's newer ggml.
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
FetchContent_Declare(
    whispercpp
    GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
    GIT_TAG v1.9.4
)
FetchContent_MakeAvailable(whispercpp)

add_library(unibot_whisper SHARED whisper_jni.cpp)
target_link_libraries(unibot_whisper PRIVATE whisper ${log-lib})
