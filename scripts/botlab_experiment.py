#!/usr/bin/env python3
"""Operate the Bot Lab arena in different ways and save a session for each, to compare what an app can observe.

Modes:
  api-actions   harness driven over its local API using accessibility actions (tap by ref, set text, scroll)
  api-gestures  harness using touch gestures (taps on non-clickable items, swipes)
  adb-input     plain `adb shell input` (a reference point: this is also injected input, not a person)

Pacing between actions is random 0.8 to 3 s, roughly what a model's thinking time looks like; that is a property of this
script, not of the harness. To compare with a person, use the arena by hand and press "Save session".

  scripts/botlab_experiment.py api-actions api-gestures adb-input
Requires the harness service running with its debug token in logcat (or HARNESS_TOKEN), and the botlab app installed.
"""
import json, os, random, re, subprocess, sys, time, urllib.request

API = "http://127.0.0.1:8427/api"

def adb(*args, check=False):
    return subprocess.run(["adb", *args], capture_output=True, text=True).stdout

def token():
    if os.environ.get("HARNESS_TOKEN"): return os.environ["HARNESS_TOKEN"]
    m = re.findall(r"API token: (\S+)", adb("logcat", "-d", "-s", "Harness:I"))
    if not m: sys.exit("no harness token: set HARNESS_TOKEN or restart the service (debug builds log it)")
    return m[-1]

TOKEN = None
def api(path, body=None):
    req = urllib.request.Request(API + path, json.dumps(body).encode() if body is not None else None,
                                 {"Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json"})
    return json.loads(urllib.request.urlopen(req, timeout=60).read())

def snap(): return api("/snapshot")
def els(s): return s.get("elements", [])
def find(s, text=None, role=None, prefix=None):
    for e in els(s):
        t = e.get("text") or ""
        if (text is None or t == text) and (role is None or e["role"] == role) and (prefix is None or t.startswith(prefix)): return e
    return None
def smallest_scrollable(s):
    return min((e for e in els(s) if e.get("scrollable")), key=lambda e: (e["bounds"][2] - e["bounds"][0]) * (e["bounds"][3] - e["bounds"][1]))
def center(e): l, t, r, b = e["bounds"]; return (l + r) // 2, (t + b) // 2
def pause(): time.sleep(random.uniform(0.8, 3.0))

def start_lab(label):
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    adb("shell", "am", "force-stop", "ga.egarcia.botlab")  # a fresh launch every time, so runs are comparable
    adb("shell", "am", "start", "-n", "ga.egarcia.botlab/.ui.BotLabActivity")
    for _ in range(20):
        s = snap()
        if s.get("package") == "ga.egarcia.botlab" and find(s, "Like"): break
        time.sleep(0.5)
    adb("shell", "am", "broadcast", "-a", "ga.egarcia.botlab.RESET", "-n", "ga.egarcia.botlab/.ControlReceiver")
    time.sleep(0.5)

def save(label):
    time.sleep(1.5)
    adb("shell", "am", "broadcast", "-a", "ga.egarcia.botlab.SAVE", "-n", "ga.egarcia.botlab/.ControlReceiver", "--es", "label", label)
    time.sleep(1.5)
    return adb("shell", "run-as", "ga.egarcia.botlab", "cat", f"files/sessions/{label}.report.txt")

def fresh_tap(text):
    s = snap(); e = find(s, text)
    return api("/tap", {"snapshot_id": s["snapshot_id"], "ref": e["ref"]})

def api_actions():
    for name in ["Like", "Follow", "Message", "Like", "Follow", "Message", "Like", "Follow"]:
        fresh_tap(name); pause()
    s = snap(); box = find(s, role="input")
    api("/tap", {"snapshot_id": s["snapshot_id"], "ref": box["ref"]}); pause()
    s = snap(); box = find(s, role="input")
    api("/type", {"snapshot_id": s["snapshot_id"], "ref": box["ref"], "text": "Hello, thanks for connecting!"}); pause()
    for d in ["down", "down", "down", "up"]:
        api("/scroll", {"direction": d}); pause()

def api_gestures():
    for _ in range(6):                      # items of the feed are not clickable, so the harness taps by gesture
        s = snap(); e = find(s, prefix="Post #")
        api("/tap", {"snapshot_id": s["snapshot_id"], "ref": e["ref"]}); pause()
    for _ in range(4):
        s = snap(); feed = smallest_scrollable(s); l, t, r, b = feed["bounds"]   # the feed, not the whole page (a swipe from the screen edge is the home gesture)
        api("/swipe", {"x1": (l + r) // 2, "y1": b - 40, "x2": (l + r) // 2, "y2": t + 40, "duration_ms": 300}); pause()

def adb_input():
    s = snap()
    for name in ["Like", "Follow", "Message", "Like", "Follow", "Message", "Like", "Follow"]:
        x, y = center(find(s, name)); adb("shell", "input", "tap", str(x), str(y)); pause()
    x, y = center(find(s, role="input")); adb("shell", "input", "tap", str(x), str(y)); pause()
    adb("shell", "input", "text", "Hello%sthere%sfriend"); pause()
    s = snap(); feed = smallest_scrollable(s); l, t, r, b = feed["bounds"]
    for _ in range(4):
        adb("shell", "input", "swipe", str((l + r) // 2), str(b - 40), str((l + r) // 2), str(t + 40), "300"); pause()

MODES = {"api-actions": api_actions, "api-gestures": api_gestures, "adb-input": adb_input}

if __name__ == "__main__":
    modes = sys.argv[1:] or list(MODES)
    TOKEN = token()
    s = snap()
    print("Protected view (accessibilityDataSensitive) visible to the harness snapshot?", "YES" if any("Secret code" in (e.get("text") or "") for e in els(s)) else "unknown (open the lab first)")
    for m in modes:
        print(f"\n################ {m}")
        start_lab(m)
        s = snap()
        print("protected text in harness snapshot:", "VISIBLE" if any("4242" in (e.get('text') or '') for e in els(s)) else "HIDDEN")
        MODES[m]()
        print(save(m))
