# Verifying a GTNH Speedrun Audit bundle

A submission is one zip from `/audit export`. Start with `SUMMARY.txt`; drill into the JSONL only when
something looks off.

## What the bundle contains

- `SUMMARY.txt` — session ledger, milestone timeline (quests, dimension first-visits, multiblock
  formations, key items), and a flags section (gamemode changes, NEI cheat actions, explosions, rollbacks).
- `verify-report.json` — the mod's own full-chain verification at export time.
- `anchor.json` — the world-side chain anchor at export time.
- `log/audit-*.jsonl` — the hash-chained event ledger, one file per server session.
- `snapshots/*.json.gz` — player inventory and AE2 network censuses, each one referenced (with its SHA-256)
  by a line in the ledger.

## Independent re-verification

Do not trust `verify-report.json` — recompute it. The chain rules:

1. Collect every line of every `log/*.jsonl`, order by `seq`. Seqs must be `0,1,2,…` with no gaps.
2. Line 0's `prev` must equal `SHA-256("gtnhspeedrunaudit:genesis:" + worldAuditUuid)` (hex).
3. Every other line's `prev` must equal the SHA-256 of the previous line's exact UTF-8 bytes
   (without the trailing newline).
4. Every `inv_snapshot`/`ae2_snapshot` line carries `fileSha256`; hash the named file in `snapshots/`
   and compare.

Twenty lines of Python:

```python
import json, hashlib, glob
lines = sorted(((json.loads(l), l.rstrip('\n').encode()) for f in glob.glob('log/*.jsonl')
                for l in open(f, encoding='utf-8') if l.strip()), key=lambda x: x[0]['seq'])
uuid = lines[0][0]['data']['worldAuditUuid']
prev = hashlib.sha256(f'gtnhspeedrunaudit:genesis:{uuid}'.encode()).hexdigest()
for i, (obj, raw) in enumerate(lines):
    assert obj['seq'] == i, f'seq gap at {i}'
    assert obj['prev'] == prev, f'chain break at seq {i}'
    prev = hashlib.sha256(raw).hexdigest()
print('chain OK,', len(lines), 'lines')
```

## Reading the verdicts

Every `session_start` line embeds the startup verdict:

- `NEW_WORLD` — first session ever.
- `OK` — anchor and log tail agree.
- `CRASH_RECOVERY` — previous session died dirty; the anchor may lag the tail by up to ~5s of events.
- `WORLD_ROLLBACK` — the world was restored from a backup. **Allowed by the rules.** The `rollback` object
  says how many events and how much in-game time were rewound. Judge with context: a `gt_explosion` shortly
  before the stop is the normal story; a rollback that lands just before a milestone, without context, is not.
- `LOG_TRUNCATED` / `TAMPERED_TAIL` / `ANCHOR_MISSING` — the log and world disagree in ways honest play
  does not produce.

## Known limits (be honest about these)

- The mod is open source and runs on the runner's machine. The chain makes editing history detectable and
  expensive; it cannot stop someone from fabricating an entire log offline. Treat it as raising the bar,
  not as cryptographic proof.
- `command` lines record *dispatch*, not success — a permission-denied `/give` still appears (uncanceled).
- Quest timestamps are ±3 s (BetterQuesting detects completions on a 60-tick poll).
- Multiblock formation timestamps lag real placement by up to a few seconds (structure checks are periodic).
- AE2 censuses only cover loaded grids — which is fine: an unloaded grid cannot change.
- Timing: every line carries both `wall` (epoch ms, for RTA) and `ticks` (cumulative server ticks, for
  in-game time). The tick clock rewinds with a rollback; wall time never does.
- IGT starts at the first player movement, not at world creation (worldgen lag doesn't count) — the
  `timing_started` line marks the moment. The server can't see keypresses, so "movement" means the first
  horizontal position change > 0.03 blocks in a tick; being pushed by a mob before ever walking would
  technically start it, which errs in the honest direction (earlier start = longer time).
