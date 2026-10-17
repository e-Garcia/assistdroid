# Fine-tuning tooling

Plan: `docs/model-finetuning.md`. This directory is the tooling described there. Pipeline, in order:

```
collect_traces.py  -->  finetune/traces/*.jsonl  -->  prepare_dataset.py  -->  finetune/data/{train,valid,test}.jsonl
                                                                                        |
                                                                                        v
                                                          (on the Mac Studio) train_lora.sh --> finetune/adapters/
                                                                                        |
                                                                        +---------------+---------------+
                                                                        v                               v
                                                                 evaluate.py                  fuse_and_export.sh
                                                          (offline, against test.jsonl)      (GGUF, for on-device --
                                                                                               deployment not built yet)
```

1. **Collect traces** (needs the harness running on a connected device/emulator, `sample-app` and Bot Lab
   (its own repository; see `docs/detectability.md`) installed, and a reachable teacher model):
   ```bash
   HARNESS_LLM_API_KEY=... ./collect_traces.py --base-url http://<model-host>:8000/v1 --model Qwen3.6-35B-A3B-4bit --repeat 5
   ```
   Only scenarios that complete successfully (the teacher reaches `done` with no failed action) are kept — see
   the safety note in the script's docstring about never using gated (Send/Post/Delete/Buy/Call) actions here.

2. **Prepare the dataset**:
   ```bash
   ./prepare_dataset.py
   ```

3. **Train** (on the Mac Studio; MLX is Apple Silicon-only, this step cannot run on a Linux machine):
   ```bash
   ./train_lora.sh mlx-community/Qwen3.5-4B-4bit adapters
   ```

4. **Evaluate** (offline, works anywhere with network access to the candidate's endpoint):
   ```bash
   ./evaluate.py --base-url http://127.0.0.1:8080/v1 --model qwen3.5-4b-finetuned
   ```

5. **Export for on-device** (once a trained adapter looks good; the Android-side loader is future work, not
   built yet):
   ```bash
   ./fuse_and_export.sh mlx-community/Qwen3.5-4B-4bit adapters /path/to/llama.cpp
   ```

## Kept out of git

`traces/` and `data/` (generated, potentially large, regenerate them instead of committing them) and
`adapters/`/`fused/`/`*.gguf` (large binary training artifacts). See the repo `.gitignore`. The scripts
themselves, and this plan, are tracked normally -- none of this is competitive intelligence, it's this
project's own engineering.

## Keep in sync

`collect_traces.py` copies three things from the Kotlin source verbatim: the system prompt (`AgentLoop.kt`
`SYSTEM_PROMPT`), the tool schema (`AgentTools.kt` `definitions()`), and the screen-to-text format
(`SnapshotFormatter.kt`). If any of those change, this directory goes stale silently -- there's no automated
check for that yet. Re-diff by hand before a real training run if it's been a while.
