#!/usr/bin/env bash
# Run ON the Mac Studio (or another Apple Silicon machine), not the Linux dev box: MLX is Apple Silicon-only.
# Requires: pip install mlx-lm (matches the omlx server's own stack).
#
#   finetune/train_lora.sh [base model] [output adapter dir]
#
# Reference point from public benchmarks (see docs/model-finetuning.md): a 7B LoRA run on 5,000 examples took
# about 90 minutes on a 32 GB M2 Max, ~7 GB peak memory. A 4B/2B run on a Mac Studio that already serves a
# 35B-parameter-class model resident should be faster and well within memory.
set -euo pipefail
cd "$(dirname "$0")"

MODEL="${1:-mlx-community/Qwen3.5-4B-4bit}"
ADAPTERS="${2:-adapters}"

if [ ! -f data/train.jsonl ]; then
  echo "No finetune/data/train.jsonl. Run collect_traces.py then prepare_dataset.py first." >&2
  exit 1
fi

python3 -m mlx_lm lora \
  --model "$MODEL" \
  --train \
  --data data \
  --lora-layers 16 \
  --batch-size 4 \
  --iters 600 \
  --adapter-path "$ADAPTERS"

echo
echo "Adapter written to $ADAPTERS. Evaluate with:"
echo "  python3 -m mlx_lm.generate --model $MODEL --adapter-path $ADAPTERS --prompt '...'"
echo "Or run finetune/evaluate.py against the harness with --adapter-path $ADAPTERS once that path is wired up."
echo "To deploy on-device, see finetune/fuse_and_export.sh next."
