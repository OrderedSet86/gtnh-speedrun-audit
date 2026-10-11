# Verifying a GTNH Speedrun Audit bundle

A submission is one zip from `/audit export`. Start with `SUMMARY.txt`; drill into the JSONL only when
something looks off. Before flagging anything, read [MITIGATIONS.md](MITIGATIONS.md) — several
flag-looking events have questbook-endorsed or otherwise innocent explanations.

## What the bundle contains

- `SUMMARY.txt` — session ledger, milestone timeline (quests, dimension first-visits, multiblock
  formations, key items, first placement of each tracked block), and a flags section (gamemode changes,
  NEI cheat actions, explosions, rollbacks).
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
- `CRASH_RECOVERY` — the previous session ended without a `session_end` line, and the world was last saved
  mid-session by that same session. The anchor may lag the tail by up to one autosave (900 ticks, 45 s at
  20 TPS), longer while world saving was off.
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
- GT `multiblock_formed` lines carry `energyHatches`/`energyTier` and `dynamoHatches`/`dynamoTier`: how many
  hatches the structure had when it formed and the highest voltage tier among them (`LV` … `MAX+`), from each
  hatch's own voltage. A multiblock logs once per position per session, and again when its hatch tier
  changes, so a hatch upgrade is a new line. Some TecTech and GT++ multiblocks keep hatches in their own
  lists and can report fewer hatches or no tier. Railcraft multiblocks, the bricked blast furnace and lines
  from earlier versions have no hatch fields.
- `dim_change` lines record every dimension change (`fromDim`, `toDim`, arrival position). A teleport
  command and the `dim_change` it causes are a tick or two apart; a portal or rocket has no command before
  it. Use them to check rules on cross-dimension teleports. Older bundles have only `dim_first_visit`, which
  also re-fires after a world rollback.
- `creative_slot` lines are creative-inventory packets: the client setting one of its own slots (or
  dropping, slot < 0). Moving an item between slots and taking one from the creative tabs both arrive as slot
  sets, so each line also has the slot's previous contents. Net `item` against `previousKey` over a player's
  lines to find what was spawned. `inCreative: false` means a packet vanilla ignored, which only a modified
  client sends.
- `nbt_edit` lines are `/nbtedit` saves. `nbtB64` is what the save wrote and `beforeB64` is the target just
  before it, so the two show what the edit changed; above 64 KB only the hashes (`nbtHash`, `beforeHash`) are
  kept. `beforeMissing` says why there is no before state (`chunk not loaded`, `no tile entity`, `empty hand`,
  …). Older bundles have no before fields. A block opened with `/nbtedit` but never saved has only the
  command line.
- `death` lines name the `killer` (`player:Name` or the entity name, such as `Zombie`), the `directKiller`
  when it differs (an arrow), and the death `message`. Older bundles have only `damageSource`.
- `world_saving` lines mark overworld saving turning off (`enabled: false`) and back on. ServerUtilities turns
  it off on every world for the length of each backup, and `/save-off` turns it off too. `savingOffDims` lists
  every world not saving at that moment; some never save (Gadomancy's Outer Lands, dim 173).
  `session_end` has the same fields: dim 0 in `savingOffDims` means the shutdown save skipped the overworld and
  the anchor with it, so the next start is a `WORLD_ROLLBACK` to the last save. `backupRunning` is present when
  ServerUtilities is installed. Bundles before this version have none of these.
- `block_placed` lines are player placements of the server's `trackedPlacements` blocks, with coordinates
  (default: the Stargate structure — base, ring/chevron, DHD, power units). GT machines log as
  `machine_placed` instead. SUMMARY shows only the first of each block; the JSONL has every one.
- AE2 censuses only cover loaded grids — which is fine: an unloaded grid cannot change.
- Bundles from v0.5.0 and earlier contain **no automatic AE2 censuses**: an overflow in the interval check
  meant only a manual `/audit snapshot` ever produced one. An absent census in those bundles is the mod's
  fault, not the runner's. From the fix on, a census that fails logs `ae2_census_failed` (flagged in
  SUMMARY) instead of failing silently.
- An AE2 census **is** instantaneous: every loaded grid is read to completion within a single server tick,
  so all the `ae2_snapshot` lines of one `census` index describe the same instant. Two censuses that
  disagree disagree because something actually moved in between, not because the reader was mid-walk.
  (Versions before this one spread the walk over ~50 ticks and recorded a `walkTicks` field; if you are
  handed a bundle that has one, treat any count as ±whatever a factory moves in `walkTicks` ticks.)
- AE2 census keys are `modid:name@meta`. NBT is **not** part of the key, so variants of one item — a tool at
  a different durability, a suit of armor at a different charge — are **summed into a single entry**. Totals
  are exact regardless, because merging only ever adds counts together; what you lose is the ability to tell
  two otherwise-identical stacks apart by their tags, which is not a quantity anyone audits. If
  `nbtVariantsCollapsed` is present and non-zero, that many entries merged. NBT-level evidence, where it
  matters, lives in the inventory snapshots and `nbt_edit` lines.
- Timing — three clocks, every line carries all of them: `wall` (epoch ms; RTA = wall minus the anchored
  timing_started moment), `ticks` (cumulative server ticks = IGT, the main metric; AFK machine time counts),
  and `pticks` (ticks with at least one player connected). The tick clocks rewind with a rollback; wall time
  never does. Lines written before v0.2.4 lack `pticks`.
- IGT starts at the first player movement, not at world creation (worldgen lag doesn't count) — the
  `timing_started` line marks the moment. The server can't see keypresses, so "movement" means the first
  horizontal position change > 0.03 blocks in a tick; being pushed by a mob before ever walking would
  technically start it, which errs in the honest direction (earlier start = longer time).
