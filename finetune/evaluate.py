#!/usr/bin/env python3
"""Offline evaluation: replay finetune/data/test.jsonl (held out by prepare_dataset.py, never seen in training)
against a candidate model, and score how often it picks the same tool call the teacher model picked on the same
screen. Does not need the emulator/phone -- the screen text is already recorded in the test set.

Run the candidate behind any OpenAI-compatible endpoint (mlx_lm.server with --adapter-path, or the fine-tuned
model deployed back onto omlx, or the base model with no adapter as a baseline for comparison).

  finetune/evaluate.py --base-url http://127.0.0.1:8080/v1 --model qwen3.5-4b-finetuned
"""
import argparse, getpass, json, os, re, sys, urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent

def call(base_url, model, api_key, messages, tools, extra_body):
    body = {"model": model, "messages": messages, "tools": tools, "temperature": 0, "max_tokens": 512, **extra_body}
    req = urllib.request.Request(f"{base_url.rstrip('/')}/chat/completions", json.dumps(body).encode(),
                                  {"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=180) as resp:
        reply = json.loads(resp.read())
    calls = reply["choices"][0]["message"].get("tool_calls") or []
    if not calls: return None
    c = calls[0]["function"]
    return c["name"], json.loads(c["arguments"] or "{}")

def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--base-url", required=True)
    p.add_argument("--model", required=True)
    p.add_argument("--key-var", default="HARNESS_LLM_API_KEY")
    p.add_argument("--env-file")
    p.add_argument("--extra-body", default="{}")
    p.add_argument("--test-file", default=str(HERE / "data" / "test.jsonl"))
    a = p.parse_args()

    key = os.environ.get(a.key_var)
    if not key and a.env_file:
        for line in open(os.path.expanduser(a.env_file)):
            m = re.match(rf"\s*{re.escape(a.key_var)}\s*=\s*(.*)", line)
            if m: key = m.group(1).strip().strip('"').strip("'")
    if not key and sys.stdin.isatty():
        key = getpass.getpass("Candidate model API key (hidden, empty is fine for a local server): ")
    key = key or ""

    rows = [json.loads(l) for l in open(a.test_file) if l.strip()]
    if not rows:
        sys.exit(f"{a.test_file} is empty; run collect_traces.py then prepare_dataset.py first")

    tool_match = arg_match = no_call = 0
    mismatches = []
    for row in rows:
        msgs = row["messages"]
        gold_call = msgs[-1]["tool_calls"][0]["function"]
        gold_name, gold_args = gold_call["name"], json.loads(gold_call["arguments"])
        prompt_msgs = msgs[:-1]  # everything except the gold assistant turn
        # The real tool schema isn't stored per-example (it's fixed); rebuild it from collect_traces.
        sys.path.insert(0, str(HERE))
        from collect_traces import TOOLS
        got = call(a.base_url, a.model, key, prompt_msgs, TOOLS, json.loads(a.extra_body))
        if got is None:
            no_call += 1
            continue
        name, args = got
        if name == gold_name:
            tool_match += 1
            if args == gold_args:
                arg_match += 1
            else:
                mismatches.append((gold_name, gold_args, args))
        else:
            mismatches.append((gold_name, gold_args, {"_tool": name, **args}))

    n = len(rows)
    print(f"{n} test examples")
    print(f"tool name match:      {tool_match}/{n} ({100 * tool_match / n:.1f}%)")
    print(f"exact argument match: {arg_match}/{n} ({100 * arg_match / n:.1f}%)")
    print(f"no tool call at all:  {no_call}/{n}")
    if mismatches:
        print("\nFirst mismatches (gold vs. candidate):")
        for gold_name, gold_args, got_args in mismatches[:10]:
            print(f"  gold: {gold_name}({gold_args})  ->  got: {got_args}")

if __name__ == "__main__":
    main()
