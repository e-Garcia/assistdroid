#!/usr/bin/env python3
"""Turn collected traces (finetune/traces/*.jsonl, from collect_traces.py) into mlx-lm's chat-messages training
format (finetune/data/{train,valid,test}.jsonl). Run this after collecting traces, before finetune/train_lora.sh.

Each output line is one step, as a standalone example: system prompt + task/screen -> the one correct tool call,
in OpenAI tool-call message format (mirrors what the real agent loop sends and expects back).
"""
import argparse, json, random, sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from collect_traces import SYSTEM_PROMPT  # keep the prompt in exactly one place

def to_example(record, call_id="call_0"):
    arguments = json.dumps(record["args"], ensure_ascii=False)
    return {
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": f"Task: {record['task']}\n\nCurrent screen:\n{record['screen']}"},
            {
                "role": "assistant",
                "content": None,
                "tool_calls": [{"id": call_id, "type": "function", "function": {"name": record["tool"], "arguments": arguments}}],
            },
        ],
    }

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("traces", nargs="*", help="Trace JSONL files; default: all of finetune/traces/*.jsonl")
    p.add_argument("--out", default=str(HERE / "data"))
    p.add_argument("--valid-frac", type=float, default=0.1)
    p.add_argument("--test-frac", type=float, default=0.1)
    p.add_argument("--seed", type=int, default=0)
    a = p.parse_args()

    files = [Path(f) for f in a.traces] or sorted((HERE / "traces").glob("*.jsonl"))
    if not files:
        sys.exit("no trace files found; run collect_traces.py first")

    examples = []
    for f in files:
        for line in open(f):
            line = line.strip()
            if line:
                examples.append(to_example(json.loads(line)))

    random.Random(a.seed).shuffle(examples)
    n = len(examples)
    n_valid = int(n * a.valid_frac)
    n_test = int(n * a.test_frac)
    splits = {
        "valid": examples[:n_valid],
        "test": examples[n_valid:n_valid + n_test],
        "train": examples[n_valid + n_test:],
    }

    out_dir = Path(a.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    for name, rows in splits.items():
        path = out_dir / f"{name}.jsonl"
        with open(path, "w") as f:
            for row in rows:
                f.write(json.dumps(row, ensure_ascii=False) + "\n")
        print(f"{name}: {len(rows)} examples -> {path}")

    if n < 200:
        print(f"\nNote: only {n} examples total. The mlx-lm LoRA guide's own reference runs use thousands; "
              "collect more traces (more scenarios, higher --repeat) before a real training run.")

if __name__ == "__main__":
    main()
