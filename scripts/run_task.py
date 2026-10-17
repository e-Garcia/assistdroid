#!/usr/bin/env python3
"""Start an on-device agent task via adb and follow its log until it finishes (Ctrl-C sends STOP).

  scripts/run_task.py "Open Settings and tell me the Android version"
"""
import re, shlex, subprocess, sys, time

if len(sys.argv) < 2:
    sys.exit(__doc__)
task = " ".join(sys.argv[1:])

def adb(*args):
    return subprocess.run(["adb", *args], capture_output=True, text=True).stdout

def broadcast(action, *extra):
    cmd = " ".join(shlex.quote(x) for x in ["am", "broadcast", "-a", f"dev.egarcia.harness.{action}",
                                              "-n", "dev.egarcia.harness/.AdbAgentReceiver", *extra])
    adb("shell", cmd)

adb("logcat", "-c")
broadcast("RUN_TASK", "--es", "task", task)
seen = 0
try:
    while True:
        time.sleep(1.5)
        lines = [re.sub(r"^\S+ \S+ +\d+ +\d+ I HarnessAgent: ", "", l)
                 for l in adb("logcat", "-d", "-s", "HarnessAgent:I").splitlines() if "HarnessAgent" in l]
        for l in lines[seen:]:
            print(l[:240])
        seen = len(lines)
        if any(l.startswith("RESULT:") for l in lines):
            break
except KeyboardInterrupt:
    broadcast("STOP_TASK")
    print("STOP sent")
