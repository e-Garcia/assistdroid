# whisper.cpp (vendored)

- Upstream: https://github.com/ggml-org/whisper.cpp, tag **v1.9.4** (MIT, see LICENSE)
- Kept: `include/`, `src/`, `ggml/` (headers, core and the CPU backend only), `cmake/`, `LICENSE`.
- Removed: examples, tests, bindings, models and every ggml backend except CPU (CUDA, Metal, Vulkan, OpenCL, ...),
  because the app runs on the phone's CPU only.
- Not modified. To update, replace these directories from a newer tag and rebuild.
- Built into `libwhisper.so` by `android-harness/src/main/cpp/CMakeLists.txt`; the model files are NOT part of the app,
  they are downloaded after the user enables Local Whisper in Settings (see `docs/voice-input.md`).
