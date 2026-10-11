#!/usr/bin/env python3
"""Assertions over a speedrun-audit log directory. One subcommand per check; exit 1 on failure.

Usage:
  assert_log.py <logDir> verdict <EXPECTED>       last session_start's verifyVerdict == EXPECTED
  assert_log.py <logDir> crashed                  last session_start has previousCrashed == true
                                                  (the log before it had no session_end)
  assert_log.py <logDir> unclean                  the last line is not session_end (no clean stop)
  assert_log.py <logDir> continuity               seqs are 0..N with no gaps and the chain links
  assert_log.py <logDir> rollback                 last session_start has rollback.eventsRewound > 0
"""

import glob
import hashlib
import json
import os
import sys


def lines(log_dir):
    out = []
    for f in glob.glob(os.path.join(log_dir, "*.jsonl")):
        for raw in open(f, encoding="utf-8"):
            raw = raw.rstrip("\n")
            if raw:
                out.append((json.loads(raw), raw.encode()))
    out.sort(key=lambda x: x[0]["seq"])
    return out


def fail(msg):
    print(f"ASSERT FAIL: {msg}")
    sys.exit(1)


def main():
    log_dir, cmd = sys.argv[1], sys.argv[2]
    ls = lines(log_dir)
    if not ls:
        fail(f"no log lines in {log_dir}")
    starts = [o for o, _ in ls if o["t"] == "session_start"]

    if cmd == "verdict":
        want = sys.argv[3]
        got = starts[-1]["data"]["verifyVerdict"]
        if got != want:
            fail(f"verdict: expected {want}, got {got}")
        print(f"ok: verdict {got}")
    elif cmd == "crashed":
        if not starts[-1]["data"].get("previousCrashed"):
            fail("previousCrashed not set on last session_start")
        print("ok: previous session flagged as crashed")
    elif cmd == "unclean":
        if ls[-1][0]["t"] == "session_end":
            fail(f"last line is session_end (seq {ls[-1][0]['seq']}): the server stopped cleanly")
        print(f"ok: log ends without session_end (last line {ls[-1][0]['t']})")
    elif cmd == "rollback":
        rb = starts[-1]["data"].get("rollback", {})
        if rb.get("eventsRewound", 0) <= 0:
            fail(f"rollback delta missing/zero: {rb}")
        print(f"ok: rollback rewound {rb['eventsRewound']} events")
    elif cmd == "continuity":
        uuid = starts[0]["data"]["worldAuditUuid"]
        prev = hashlib.sha256(f"gtnhspeedrunaudit:genesis:{uuid}".encode()).hexdigest()
        for i, (obj, raw) in enumerate(ls):
            if obj["seq"] != i:
                fail(f"seq gap: expected {i}, got {obj['seq']}")
            if obj["prev"] != prev:
                fail(f"chain break at seq {i}")
            prev = hashlib.sha256(raw).hexdigest()
        print(f"ok: {len(ls)} lines continuous and chained")
    else:
        fail(f"unknown subcommand {cmd}")


if __name__ == "__main__":
    main()
