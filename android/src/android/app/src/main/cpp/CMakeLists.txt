cmake_minimum_required(VERSION 3.22.1)
project(minis_native CXX C)

add_library(pty_bridge SHARED pty_bridge.c)

# T283: NDK signal handler for native crashes (SIGSEGV/SIGABRT/...).
# Separate library so loadLibrary() can pull it in independently of
# pty_bridge — the crash handler must be installed before any other
# native lib has a chance to fault.
add_library(minis_crash_handler SHARED crash_handler.cpp)

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
target_link_libraries(minis_crash_handler ${log-lib})
target_link_libraries(jieba_jni ${log-lib})

# ── Offline speech-to-text (whisper.cpp) ────────────────────────────────
# The app never bundles the model in the APK: the user taps
# "Download offline voice" in the voice panel and it fetches
# ggml-tiny.en-q5_1.bin (~31 MB) from the voice-model-v1 GitHub release.
# whisper.cpp itself is built here from a pinned tag via FetchContent.
include(FetchContent)
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
