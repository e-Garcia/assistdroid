#!/usr/bin/env python3
"""Push the LLM settings to the connected device/emulator through the DUMP-gated ADB_CONFIGURE receiver.

The API key is read from an env file (default: Hermes' ~/.hermes/.env, variable OMLX_API_KEY) and is never
printed. Every argument is shell-quoted for the device shell, because keys may contain metacharacters.

Example (emulator cannot resolve MagicDNS names, so use the tailnet IP; the phone with Tailscale can use the name):
  scripts/configure_llm.py --base-url http://100.x.y.z:8000/v1 --model Qwen3.6-35B-A3B-4bit
"""
import argparse, os, re, shlex, subprocess, sys

p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
p.add_argument("--base-url", required=True, help="OpenAI-compatible base URL including /v1")
p.add_argument("--model", required=True)
p.add_argument("--key-var", default="OMLX_API_KEY", help="variable name in the env file")
p.add_argument("--env-file", default=os.path.expanduser("~/.hermes/.env"))
p.add_argument("--no-vision", action="store_true")
p.add_argument("--thinking", action="store_true", help="let the model emit reasoning tokens (slower)")
p.add_argument("--max-tokens", type=int, default=1024)
p.add_argument("--extra-body", default='{"repetition_penalty":1.1}', help="JSON merged into every request")
p.add_argument("--serial", help="adb device serial")
a = p.parse_args()

key = None
for line in open(a.env_file):
    m = re.match(rf"\s*{re.escape(a.key_var)}\s*=\s*(.*)", line)
    if m:
        key = m.group(1).strip().strip('"').strip("'")
if not key:
    sys.exit(f"{a.key_var} not found in {a.env_file}")

args = ["am", "broadcast", "-a", "ga.egarcia.harness.ADB_CONFIGURE", "-n", "ga.egarcia.harness/.AdbConfigReceiver",
        "--es", "base_url", a.base_url, "--es", "model", a.model, "--es", "api_key", key,
        "--ez", "vision", str(not a.no_vision).lower(), "--ez", "thinking", str(a.thinking).lower(),
        "--ei", "max_tokens", str(a.max_tokens), "--es", "extra_body", a.extra_body]
adb = ["adb"] + (["-s", a.serial] if a.serial else []) + ["shell", " ".join(shlex.quote(x) for x in args)]
out = subprocess.run(adb, capture_output=True, text=True)
ok = "Broadcast completed: result=0" in (out.stdout + out.stderr)
print("configured" if ok else "FAILED (output withheld so the key is not printed)")
sys.exit(0 if ok else 1)
