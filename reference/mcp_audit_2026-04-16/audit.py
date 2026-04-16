#!/usr/bin/env python3
"""
Autonomous folder-anomaly audit across all title-based volumes.

For each volume with title_locations:
  1. mount_volume
  2. find_multi_cover_titles — paginate through all locations
  3. find_misfiled_covers — paginate through all locations
  4. unmount_volume

Writes per-volume results as JSON lines to results.jsonl.
Writes a progress log to audit.log.
"""

import json
import sys
import time
import urllib.request
from pathlib import Path

MCP = "http://localhost:8080/mcp"
OUT = Path(__file__).parent
RESULTS = OUT / "results.jsonl"
LOG = OUT / "audit.log"

# From get_stats — exclude avstars volumes (no title_locations) and pool (sort_pool).
# Actually sort_pool HAS title_locations. Keep everything that get_stats reported.
VOLUME_COUNTS = {
    "a": 5099, "bg": 1423, "classic": 1385, "classic_pool": 2178,
    "collections": 2325, "hj": 3612, "k": 3261, "m": 4902, "ma": 2189,
    "n": 2342, "pool": 2531, "qnap": 11516, "qnap_archive": 807,
    "r": 4930, "s": 3491, "tz": 5105, "unsorted": 112,
}

PAGE = 500  # keep each scan call well under HTTP 600s timeout


def call(tool, args=None, req_id=0):
    payload = {
        "jsonrpc": "2.0",
        "id": req_id,
        "method": "tools/call",
        "params": {"name": tool, "arguments": args or {}},
    }
    req = urllib.request.Request(
        MCP,
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=600) as resp:
        body = json.loads(resp.read())
    if "error" in body:
        raise RuntimeError(f"MCP error calling {tool}: {body['error']}")
    text = body["result"]["content"][0]["text"]
    if body["result"].get("isError"):
        raise RuntimeError(f"Tool error from {tool}: {text}")
    return json.loads(text)


def log(msg):
    stamp = time.strftime("%H:%M:%S")
    line = f"[{stamp}] {msg}"
    print(line, flush=True)
    with LOG.open("a") as f:
        f.write(line + "\n")


def paginate(tool, expected_total):
    hits = []
    total_scanned = 0
    total_errors = 0
    offset = 0
    while True:
        page_start = time.time()
        res = call(tool, {"limit": PAGE, "offset": offset})
        dt = time.time() - page_start
        scanned = res.get("scanned", 0)
        page_hits = len(res.get("hits", []))
        total_scanned += scanned
        total_errors += res.get("errors", 0)
        hits.extend(res.get("hits", []))
        log(f"    {tool} offset={offset} scanned={scanned} hits={page_hits} in {dt:.1f}s")
        if scanned < PAGE:
            break
        offset += PAGE
        if offset > expected_total + PAGE:
            log(f"  {tool}: offset ran past expected {expected_total}, stopping safely")
            break
    return {"scanned": total_scanned, "errors": total_errors, "hits": hits}


def run_volume(vol, expected_total):
    log(f"=== {vol} (expected {expected_total} locations) ===")
    try:
        m = call("mount_volume", {"volumeId": vol})
        if not m.get("mounted"):
            log(f"  mount failed: {m}")
            return {"volumeId": vol, "skipped": True, "reason": "mount_failed", "raw": m}
    except Exception as e:
        log(f"  mount exception: {e}")
        return {"volumeId": vol, "skipped": True, "reason": f"mount_exception: {e}"}

    result = {"volumeId": vol, "expected": expected_total, "mounted": True}

    try:
        log(f"  scanning multi_cover...")
        mc = paginate("find_multi_cover_titles", expected_total)
        log(f"    scanned={mc['scanned']} hits={len(mc['hits'])} errors={mc['errors']}")
        result["multi_cover"] = mc

        log(f"  scanning misfiled_covers...")
        mf = paginate("find_misfiled_covers", expected_total)
        log(f"    scanned={mf['scanned']} hits={len(mf['hits'])} errors={mf['errors']}")
        result["misfiled_covers"] = mf
    except Exception as e:
        log(f"  scan exception: {e}")
        result["scan_error"] = str(e)
    finally:
        try:
            u = call("unmount_volume")
            log(f"  unmounted ({u.get('state')})")
        except Exception as e:
            log(f"  unmount exception: {e}")

    return result


def main(volumes=None, append=False):
    if not append:
        RESULTS.unlink(missing_ok=True)
        LOG.unlink(missing_ok=True)
    target = {v: VOLUME_COUNTS[v] for v in volumes} if volumes else VOLUME_COUNTS
    log(f"Starting audit of {len(target)} volumes: {list(target)}")
    start = time.time()

    for vol, cnt in target.items():
        res = run_volume(vol, cnt)
        with RESULTS.open("a") as f:
            f.write(json.dumps(res) + "\n")

    elapsed = time.time() - start
    log(f"Done in {elapsed:.0f}s ({elapsed/60:.1f} min)")


if __name__ == "__main__":
    # Usage: audit.py                       — all volumes, fresh start
    #        audit.py k                     — only 'k' (calibration)
    #        audit.py --append bg hj m      — append these volumes to existing results
    args = sys.argv[1:]
    append = False
    if args and args[0] == "--append":
        append = True
        args = args[1:]
    main(volumes=args or None, append=append)
