#!/usr/bin/env python3
"""Asserts that the scripted demo left the expected PluginFence state in the sandbox.

Reads the XML that the PluginFence plugin persists (no secrets are ever stored there) and checks:
  * the SSH-key read attempt by Demo Helper 1.1.0 was BLOCKED,
  * the network attempt to 198.51.100.42 was prevented (and correlated with the secret access),
  * a POTENTIAL_SECRET_EXFILTRATION incident exists with a CRITICAL score,
  * a behaviour drift 1.0.0 -> 1.1.0 was recorded with sensitive-file / network / process additions.
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
SANDBOX = glob.glob(os.path.join(ROOT, "intellij-plugin", "build", "idea-sandbox", "*", "config", "options"))
if not SANDBOX:
    print("FAIL: no sandbox config found (run the demo first)")
    sys.exit(1)
OPTIONS = SANDBOX[0]


def options(element):
    return {o.get("name"): o.get("value") for o in element.findall("option")}


def values(element, tag):
    node = element.find(tag)
    return [o.get("value") for o in node.findall("option")] if node is not None else []


history = ET.parse(os.path.join(OPTIONS, "pluginfence-history.xml"))
state = ET.parse(os.path.join(OPTIONS, "pluginfence.xml"))

events = [options(e) for e in history.getroot().iter("event")]
incidents = list(history.getroot().iter("incident"))
drifts = list(state.getroot().iter("drift"))

failures = []


def check(cond, message):
    print(("ok   " if cond else "FAIL ") + message)
    if not cond:
        failures.append(message)


v11 = [e for e in events if e.get("pluginVersion") == "1.1.0"]
ssh = [e for e in v11 if e.get("operation") == "FILE_READ" and "id_rsa" in (e.get("target") or "")]
check(any(e.get("verdict") == "BLOCK" for e in ssh), "SSH key read by Demo Helper 1.1.0 was BLOCKED")
check(all("FAKE_PLUGINFENCE" not in (e.get("target") or "") + (e.get("reason") or "") for e in events), "no secret contents in recorded events")

exfil = [e for e in v11 if e.get("operation") == "NETWORK_CONNECT" and "198.51.100.42" in (e.get("target") or "")]
check(any(e.get("verdict") in ("BLOCK", "ASK") for e in exfil), "connection to 198.51.100.42 was prevented")
check(any(e.get("ruleId") == "correlation.exfiltration" for e in exfil), "correlated network attempt blocked by the exfiltration rule")

proc = [e for e in v11 if e.get("operation") == "PROCESS_EXEC"]
check(len(proc) >= 1, "process execution attempt recorded")

normal = [e for e in events if e.get("pluginVersion") == "1.0.0" and e.get("verdict") == "ALLOW"]
check(len(normal) >= 2, "Demo Helper 1.0.0 normal behaviour allowed and recorded")

kinds = [options(i).get("kind") for i in incidents]
scores = [int(options(i).get("riskScore") or 0) for i in incidents if options(i).get("kind") == "POTENTIAL_SECRET_EXFILTRATION"]
check("POTENTIAL_SECRET_EXFILTRATION" in kinds, "potential secret exfiltration incident created")
check(any(s >= 80 for s in scores), "exfiltration incident rated CRITICAL (score >= 80)")

drift_ok = False
for d in drifts:
    o = options(d)
    if o.get("oldVersion") == "1.0.0" and o.get("newVersion") == "1.1.0":
        added_caps = values(d, "addedCapabilities")
        added_hosts = values(d, "addedHosts")
        added_procs = values(d, "addedProcesses")
        print("     drift 1.0.0 -> 1.1.0: capabilities=%s hosts=%s processes=%s score=%s" % (added_caps, added_hosts, added_procs, o.get("riskScore")))
        drift_ok = "SENSITIVE_FILES" in added_caps and "198.51.100.42" in added_hosts and len(added_procs) >= 1 and int(o.get("riskScore") or 0) >= 60
check(drift_ok, "behaviour drift 1.0.0 -> 1.1.0 recorded with new sensitive-file, network and process capabilities (HIGH+)")

# PluginFence must never throw inside the IDE: scan the sandbox log for its own stack traces.
logs = glob.glob(os.path.join(ROOT, "intellij-plugin", "build", "idea-sandbox", "*", "log", "idea.log"))
suspicious = []
for log in logs:
    with open(log, encoding="utf-8", errors="ignore") as fh:
        lines = fh.readlines()
    for i, line in enumerate(lines):
        if ("ERROR" in line or "Exception" in line) and ("com.pluginfence" in line or "PluginFence" in line) and "[PluginFence] INFO" not in line:
            context = "".join(lines[i:i + 3]).strip()
            if "com.pluginfence" in context or "PluginFence" in line:
                suspicious.append(line.strip()[:200])
detail = "" if not suspicious else ":\n     " + "\n     ".join(suspicious[:5])
check(not suspicious, "no PluginFence errors/exceptions in idea.log" + detail)

print()
if failures:
    print("%d check(s) failed" % len(failures))
    sys.exit(1)
print("all checks passed")
