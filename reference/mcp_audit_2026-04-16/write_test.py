#!/usr/bin/env python3
"""
Per-volume sandbox write test.

Mounts each jav volume in turn, runs sandbox_write_test (creates + writes + moves +
renames + reads back inside _sandbox), unmounts, and records the result.

Output: write_test_results.json + human-readable matrix in stdout.
"""

import json
import sys
import time
import urllib.request
from pathlib import Path

MCP = "http://localhost:8080/mcp"
OUT = Path(__file__).parent
RESULTS = OUT / "write_test_results.json"

JAV_VOLUMES = [
    "a", "bg", "hj", "k", "m", "ma", "n", "r", "s", "tz",
    "unsorted", "pool", "qnap_archive", "collections",
    "qnap", "classic", "classic_pool",
]


def call(tool, args=None, timeout=60):
    payload = {
        "jsonrpc": "2.0", "id": 1,
        "method": "tools/call",
        "params": {"name": tool, "arguments": args or {}},
    }
    req = urllib.request.Request(MCP,
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        body = json.loads(resp.read())
    if "error" in body:
        raise RuntimeError(f"MCP error in {tool}: {body['error']}")
    text = body["result"]["content"][0]["text"]
    if body["result"].get("isError"):
        raise RuntimeError(f"Tool error from {tool}: {text}")
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        return {"_raw": text}


def test_one(vol):
    print(f"  [{vol}] mounting...", flush=True)
    entry = {"volumeId": vol}
    try:
        m = call("mount_volume", {"volumeId": vol}, timeout=30)
        if not m.get("mounted"):
            entry["mount"] = "failed"
            entry["error"] = str(m)
            return entry
    except Exception as e:
        entry["mount"] = "failed"
        entry["error"] = f"mount exception: {e}"
        return entry
    entry["mount"] = "ok"

    try:
        r = call("sandbox_write_test", {}, timeout=60)
        entry["success"] = r.get("success")
        entry["steps"] = r.get("steps")
        entry["runDir"] = r.get("runDir")
    except Exception as e:
        entry["success"] = False
        entry["error"] = f"write-test exception: {e}"
    finally:
        try:
            call("unmount_volume", timeout=10)
        except Exception as e:
            entry["unmount_error"] = str(e)
    return entry


def main():
    results = []
    for vol in JAV_VOLUMES:
        print(f"=== {vol} ===")
        r = test_one(vol)
        results.append(r)
        # print concise line
        if r.get("success"):
            print(f"  ✓ {vol}: PASS  sandbox={r.get('runDir')}")
        else:
            failed_step = None
            for s in (r.get("steps") or []):
                if not s.get("ok"):
                    failed_step = s
                    break
            why = failed_step.get("error") if failed_step else r.get("error", "unknown")
            print(f"  ✗ {vol}: FAIL  ({why})")

    RESULTS.write_text(json.dumps(results, indent=2))
    print(f"\nWrote {RESULTS}")

    # matrix
    print("\n--- Per-volume write-op matrix ---")
    print(f"{'volume':<16}  {'mount':<8}  {'test':<6}")
    for r in results:
        mount = r.get("mount", "?")
        test = "PASS" if r.get("success") else "FAIL"
        if mount != "ok":
            test = "-"
        print(f"{r['volumeId']:<16}  {mount:<8}  {test:<6}")


if __name__ == "__main__":
    main()
