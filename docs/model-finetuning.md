# Fine-tuning a driver model

Plan and tooling for fine-tuning a small model specifically against this harness's own tool contract, instead of
prompting a general model. Tracked as an open question in `docs/requirements.md` §7. The competitive research behind
*why* this is worth doing (is anyone else doing it, does it hold up) is private (see `CLAUDE.local.md`); this page
is the engineering plan itself, which isn't competitive intelligence and belongs in the repo.

## What "driving this harness efficiently" actually means

The agent loop (`AgentLoop.kt`) sends a fixed system prompt, the current screen as a compact element list
(`SnapshotFormatter`), and a fixed tool schema (`AgentTools.definitions()`) to an OpenAI-compatible chat-completions
endpoint, and expects exactly one tool call back. A fine-tuned model's job is to get better and faster at exactly
that: given `<screen_state>` + task, emit the one correct tool call, in the harness's own JSON shape. Nothing about
the safety layer changes — `PolicyAutomation` still enforces the confirm gate and denylist outside the model,
whichever model is driving. See `docs/requirements.md` §7 for that boundary stated explicitly.

## Base model

**Qwen3.5-4B** (Apache-2.0), with **Qwen3.5-2B** as a faster fallback if 4B proves too slow on-device. Reasoning:

- Both are well below the memory-bandwidth wall that caps 7B+ models at roughly 12 tok/s decode even on 2026's
  newest phone chips (see the private on-device feasibility research) — this is the size class that can be
  genuinely interactive on a phone, not just loadable.
- 4B is described by Qwen as a multimodal base meant for lightweight agents (useful if vision/`look` stays part of
  the tool set); 2B trades some of that for raw speed.
- Both ship under Apache-2.0, matching this project's likely license direction.

**Known gotcha to design around:** Qwen3.5's official chat template has documented tool-calling bugs — crashes on
certain JSON-string tool arguments, and the 9B variant has been reported to sometimes emit tool calls inside a
`<thinking>` block and stop instead of finishing them when thinking is left on. Mitigations, both cheap: disable
thinking for this use case (the agent loop doesn't need chain-of-thought, it needs one tool call), and use a
community-fixed chat template (several exist on Hugging Face) rather than the stock one, verified against this
project's own tool schema before training starts.

## Data

Two sources, deliberately kept separate from the app's own privacy-preserving logging:

1. **Synthetic traces from `sample-app` and Bot Lab** (its own repository) — safe, reproducible, no real personal data. A script
   drives the harness's existing driven-mode HTTP API (`HarnessServer`, bearer-token protected) through a battery
   of scripted tasks against these two controlled apps, using the currently configured model (today, the 35B-A3B
   server model) as a teacher: it calls the LLM endpoint directly with the harness's exact system prompt and tool
   schema, executes the chosen action, and records the pair only when the task actually completes successfully.
2. **Distillation from the larger model**, which is really the same mechanism as (1) — the teacher is whatever
   model is configured on the server today. No separate step needed.

**Deliberately not used as a source (for now): the app's own diagnostics/session records.** Those exist specifically
to *not* capture typed text, questions or summaries (`Describe.redactedCall` strips them to a length) — that's a
privacy feature, not a gap to work around. If real-usage traces are wanted later, that needs its own explicit,
separate opt-in and redaction pass, not silent reuse of data collected for a different purpose.

Tooling: `finetune/collect_traces.py`. Output: `finetune/traces/*.jsonl` (gitignored — generated data, not checked
in; a small sample stays in `finetune/traces/sample.jsonl` for reference).

## Training

LoRA via `mlx-lm`, run on the Mac Studio (MLX is Apple Silicon-only; this cannot run on the Linux dev machine). The
Mac Studio already comfortably serves a 35B-parameter-class MoE model (~20 GB resident, inside an engine-pool budget
of at least 32 GB, confirmed live via its own `/health` endpoint) — a LoRA run on a 4B or 2B dense model needs a
small fraction of that (published reference point: ~7 GB peak for a 7B LoRA run on a much smaller Mac). No hardware
change needed.

```bash
# On the Mac Studio, with mlx-lm installed:
python3 -m mlx_lm lora --model mlx-community/Qwen3.5-4B-4bit \
  --train --data finetune/data --lora-layers 16 --batch-size 4 --iters 600 \
  --adapter-path finetune/adapters
```

See `finetune/train_lora.sh` for the wrapped, documented version and `finetune/prepare_dataset.py` for building
`finetune/data/{train,valid,test}.jsonl` from collected traces in `mlx-lm`'s chat-messages format.

## Evaluation

`finetune/evaluate.py` replays a held-out set of scenarios (not seen during training) through the driven-mode API
using the fine-tuned model, and scores its tool-call choices against what the teacher model chose on the same
screens — catching both outright regressions and the schema-staleness problem (a fine-tune trained against an older
version of `AgentTools.definitions()` degrades silently otherwise).

## On-device deployment (future work, once a checkpoint exists)

No code for this yet — no point building a loader before there's a model to load. The planned path:

1. Fuse the LoRA adapter into the base model and export to GGUF (`finetune/fuse_and_export.sh`: `mlx_lm.fuse
   --de-quantize`, then llama.cpp's `convert_hf_to_gguf.py`, then `llama-quantize` to Q4_K_M or similar).
2. Vendor `llama.cpp` the same way `third_party/whisper.cpp` already is (CPU backend only, pinned tag, unmodified —
   see `VENDORED.md`), and add a sibling native target next to `whisper_jni` in
   `assistdroid/src/main/cpp/CMakeLists.txt`. The NDK/CMake setup for this already exists; this is mostly
   repeating a pattern already proven for speech.
3. A `LocalDriverEngine` analogous to `voice/WhisperEngine.kt`: load-on-demand, idle-release, downloaded (not
   bundled) the same way Whisper models are — same on-demand-download rationale as `docs/voice-input.md`.
4. Target size for this: the 2–4B tier, per the on-device feasibility research — the 4B/2B model stays the
   on-device target even if a 9B variant is ever explored server-side, because 7B+ is bandwidth-bound on current
   phone hardware regardless of chip generation.

## Status

- [x] Base model chosen and reasoned about (this page).
- [x] Data collection tooling built and smoke-tested against the real configured model (`finetune/collect_traces.py`).
- [ ] A real training run (needs the Mac Studio; not executable from the Linux dev machine used to build this).
- [ ] Evaluation of a trained adapter.
- [ ] On-device deployment (llama.cpp integration, step 4 above) — not started.
