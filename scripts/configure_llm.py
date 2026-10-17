#!/usr/bin/env python3
"""Push the LLM settings to the connected device/emulator through the DUMP-gated ADB_CONFIGURE receiver.

The API key comes from (in this order) the environment variable named by --key-var (default HARNESS_LLM_API_KEY),
the same variable in the file given by --env-file (KEY=value lines), or a hidden prompt. It is never printed.
Every argument is shell-quoted for the device shell, because keys may contain metacharacters.

Example (an emulator cannot resolve MagicDNS names, so use the server's IP; a phone with Tailscale can use the name):
  HARNESS_LLM_API_KEY=... scripts/configure_llm.py --base-url http://100.x.y.z:8000/v1 --model your-model-name
"""
import argparse, getpass, os, re, shlex, subprocess, sys

p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
p.add_argument("--base-url", required=True, help="OpenAI-compatible base URL including /v1")
p.add_argument("--model", required=True)
p.add_argument("--key-var", default="HARNESS_LLM_API_KEY", help="name of the environment variable (or env-file entry) holding the API key")
p.add_argument("--env-file", help="optional file with KEY=value lines to read the key from")
p.add_argument("--no-vision", action="store_true")
p.add_argument("--thinking", action="store_true", help="let the model emit reasoning tokens (slower)")
p.add_argument("--max-tokens", type=int, default=1024)
p.add_argument("--extra-body", default="{}", help='JSON merged into every request, e.g. \'{"repetition_penalty":1.1}\'')
p.add_argument("--serial", help="adb device serial")
a = p.parse_args()

key = os.environ.get(a.key_var)
if not key and a.env_file:
    for line in open(os.path.expanduser(a.env_file)):
        m = re.match(rf"\s*{re.escape(a.key_var)}\s*=\s*(.*)", line)
        if m:
            key = m.group(1).strip().strip('"').strip("'")
if not key and sys.stdin.isatty():
    key = getpass.getpass("API key (hidden): ")
if not key:
    sys.exit(f"no API key: set {a.key_var}, use --env-file, or run interactively")

args = ["am", "broadcast", "-a", "dev.egarcia.harness.ADB_CONFIGURE", "-n", "dev.egarcia.harness/.AdbConfigReceiver",
        "--es", "base_url", a.base_url, "--es", "model", a.model, "--es", "api_key", key,
        "--ez", "vision", str(not a.no_vision).lower(), "--ez", "thinking", str(a.thinking).lower(),
        "--ei", "max_tokens", str(a.max_tokens), "--es", "extra_body", a.extra_body]
adb = ["adb"] + (["-s", a.serial] if a.serial else []) + ["shell", " ".join(shlex.quote(x) for x in args)]
out = subprocess.run(adb, capture_output=True, text=True)
ok = "Broadcast completed: result=0" in (out.stdout + out.stderr)
print("configured" if ok else "FAILED (output withheld so the key is not printed)")
sys.exit(0 if ok else 1)
