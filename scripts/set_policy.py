#!/usr/bin/env python3
"""Set safety settings, app notes (playbooks) and the floating button on the connected device over adb.

Every argument is shell-quoted for the device shell (adb re-parses them). Examples:
  scripts/set_policy.py --denied com.example.app,keyword --gate
  scripts/set_policy.py --notes-file my_notes.txt        # see docs/playbooks.md for the format
  scripts/set_policy.py --clear-denied --clear-notes
  scripts/set_policy.py --overlay        # or --no-overlay
  scripts/set_policy.py --notif-apps com.example.messagingapp --notif-clear
"""
import argparse, shlex, subprocess, sys

p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
p.add_argument("--denied", help="comma-separated extra blocked packages or keywords")
p.add_argument("--clear-denied", action="store_true")
g = p.add_mutually_exclusive_group()
g.add_argument("--gate", dest="gate", action="store_true", default=None, help="ask before send/post/delete/buy/call")
g.add_argument("--no-gate", dest="gate", action="store_false")
p.add_argument("--notes-file", help="file with app notes to install (replaces the current notes)")
p.add_argument("--clear-notes", action="store_true")
o = p.add_mutually_exclusive_group()
o.add_argument("--overlay", dest="overlay", action="store_true", default=None, help="show the floating button")
o.add_argument("--no-overlay", dest="overlay", action="store_false")
p.add_argument("--notif-apps", help="comma-separated packages whose notifications the agent may read (replaces the list; empty string stops watching all)")
p.add_argument("--notif-clear", action="store_true", help="delete captured notifications")
p.add_argument("--serial")
a = p.parse_args()

args = ["am", "broadcast", "-a", "ga.egarcia.harness.ADB_POLICY", "-n", "ga.egarcia.harness/.AdbPolicyReceiver"]
if a.clear_denied: args += ["--es", "denied", ""]
elif a.denied is not None: args += ["--es", "denied", a.denied]
if a.gate is not None: args += ["--ez", "gate", str(a.gate).lower()]
if a.clear_notes: args += ["--es", "playbooks", ""]
elif a.notes_file: args += ["--es", "playbooks", open(a.notes_file).read().replace("\n", "\\n")]
if a.overlay is not None: args += ["--ez", "overlay", str(a.overlay).lower()]
if a.notif_apps is not None: args += ["--es", "notif_apps", a.notif_apps]
if a.notif_clear: args += ["--ez", "notif_clear", "true"]
if len(args) == 5: sys.exit("nothing to set; see --help")
cmd = ["adb"] + (["-s", a.serial] if a.serial else []) + ["shell", " ".join(shlex.quote(x) for x in args)]
out = subprocess.run(cmd, capture_output=True, text=True)
ok = "Broadcast completed: result=0" in (out.stdout + out.stderr)
print("done" if ok else "FAILED: " + (out.stdout + out.stderr).strip()[-200:])
sys.exit(0 if ok else 1)
