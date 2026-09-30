#!/usr/bin/env bash
# Run ON the Mac Studio, after finetune/train_lora.sh has produced an adapter. Turns the LoRA adapter into a
# standalone GGUF model ready for llama.cpp -- the on-device runtime planned in docs/model-finetuning.md (not
# built yet; this script prepares the model file, the Android-side loader is separate future work).
#
# Requires: mlx-lm (for fuse) and a checkout of llama.cpp (for convert_hf_to_gguf.py and llama-quantize) --
# NOT this project's vendored third_party/whisper.cpp copy, a separate llama.cpp checkout for the conversion
# tools, built as usual (cmake -B build && cmake --build build).
#
#   finetune/fuse_and_export.sh <base model> <adapter dir> <llama.cpp checkout path> [quant type]
set -euo pipefail

MODEL="${1:?base model, e.g. mlx-community/Qwen3.5-4B-4bit}"
ADAPTERS="${2:?adapter dir from train_lora.sh, e.g. finetune/adapters}"
LLAMA_CPP="${3:?path to a llama.cpp checkout}"
QUANT="${4:-Q4_K_M}"

cd "$(dirname "$0")"
FUSED="fused"
GGUF_F16="model-f16.gguf"
GGUF_OUT="model-${QUANT}.gguf"

echo "1/3 Fusing the LoRA adapter into the base model (de-quantized, for GGUF compatibility)..."
python3 -m mlx_lm.fuse --model "$MODEL" --adapter-path "$ADAPTERS" --save-path "$FUSED" --de-quantize

echo "2/3 Converting to GGUF..."
python3 "$LLAMA_CPP/convert_hf_to_gguf.py" "$FUSED" --outfile "$GGUF_F16"

echo "3/3 Quantizing to $QUANT..."
"$LLAMA_CPP/build/bin/llama-quantize" "$GGUF_F16" "$GGUF_OUT" "$QUANT"

echo
echo "Done: $GGUF_OUT"
echo "Next (not built yet, see docs/model-finetuning.md 'On-device deployment'): vendor llama.cpp the same way"
echo "third_party/whisper.cpp already is, add a sibling native target, and a WhisperEngine-style Kotlin loader"
echo "that downloads this file on demand rather than bundling it."
