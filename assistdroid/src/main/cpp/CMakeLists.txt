cmake_minimum_required(VERSION 3.18)
project(harness_whisper C CXX)

# Builds whisper.cpp (vendored in third_party/whisper.cpp, CPU only) into libwhisper_jni.so.
# The speech model is NOT part of the app: it is downloaded after the user enables Local Whisper in Settings.

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_C_STANDARD 11)
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
if (ANDROID_ABI STREQUAL "arm64-v8a")
    # Needs half-precision and dot-product instructions (Snapdragon 8 Gen 1 and newer, most phones since 2019);
    # the app checks the CPU flags before loading the library and says so if they are missing.
    set(GGML_CPU_ARM_ARCH "armv8.2-a+fp16+dotprod" CACHE STRING "" FORCE)
endif ()

add_subdirectory(${WHISPER_DIR}/ggml ${CMAKE_BINARY_DIR}/ggml)

add_library(whisper_jni SHARED ${WHISPER_DIR}/src/whisper.cpp jni.c)
target_include_directories(whisper_jni PRIVATE ${WHISPER_DIR}/include ${WHISPER_DIR}/src ${WHISPER_DIR}/ggml/include ${WHISPER_DIR}/ggml/src)
target_compile_definitions(whisper_jni PRIVATE WHISPER_VERSION="1.9.4")
target_compile_options(whisper_jni PRIVATE -O3 -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(whisper_jni PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
find_library(LOG_LIB log)
target_link_libraries(whisper_jni ggml ${LOG_LIB})
