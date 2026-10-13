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
  hatch's own voltage. A GT multiblock logs once when it forms, and again when its hatch tier changes, so a
  hatch upgrade is a new line. The controller saves the tiers it last logged in its NBT, so a server restart
  logs nothing new, and a restored backup that predates the formation logs it again. Logs from earlier
  versions repeat every formed multiblock once per session. Some TecTech and GT++ multiblocks keep hatches in their own
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
- `/cofh tpx` (CoFHCore) teleports the sender or another player to any player, dimension id or position, so it
  can skip a dimension entirely. SUMMARY counts and lists every use under FLAGS FOR REVIEW. The `command` line
  logs the arguments as typed. The `dim_change` lines that follow show where the player arrived.
- `block_placed` lines are player placements of the server's `trackedPlacements` blocks, with coordinates
  (default: the Stargate structure — base, ring/chevron, DHD, power units). GT machines log as
  `machine_placed` instead. SUMMARY shows only the first of each block; the JSONL has every one.
- `bulk_placed` lines are GT machines a Matter Manipulator placed: one line per player, dimension, manipulator
  and tick, with `machines` and `pipes` as `[x, y, z, meta tile ID]`, `names` by meta tile ID, and `checked`,
  the spots the manipulator went over that already held that machine. In logs from earlier versions, the
  manipulator's `machine_placed` lines (`itemKey` `matter-manipulator:…`) are those already-built spots, not
  placements, and what it placed is not logged.
- `world_saving` lines with `enabled: false` also have `lastSave {seq, ticks, wall}`: the anchor values the
  overworld's latest save wrote. A backup saves every world and then turns saving off, so these are the anchor
  inside that backup. A `WORLD_ROLLBACK` whose `session_start.anchor` equals a `lastSave` restored that backup.
  `backup_done` names the newest file in ServerUtilities' backup folder when a backup thread finishes.
- `gamerule_change` lines are polled every tick, so they catch `/gamerule`, the ServerUtilities config screen and
  mods alike. The `/gamerule` command line just before one names who changed it. Values at boot are in
  `session_start.gamerules`.
- `command_output` lines are the text replies of commands on the server's `commandOutputCapture` allowlist (by
  default `/gt global_energy_display`, `/gamerule`, `/difficulty`, `/powerfails`, `/bq_admin`,
  `mothership_info`, `/backup`). Only replies to the sender are kept, at most 4 KB per command. Chat
  commands are never captured.
- `gt_global_energy` lines are `/gt global_energy_add|set|join|display` with each named player's team leader
  and wireless EU balance at dispatch (`before`) and at the end of that tick (`after`), as decimal strings.
- `nei_cheat` `give` lines have `inserted`: how many items actually went into the inventory (0 when it was
  full; NEI's client then fills open-container slots with `set_slot`). `set_slot` lines have `previous`, the
  slot's stack before the set, so a delete shows what was deleted. `delete_all_items` lines list every
  stack in `items`. NEI's load-saved-state also runs `delete_all_items` and then refills by `set_slot`.
- `trash_insert` lines are stacks a player put into an Extra Utilities trash can by hand. They are voided at once.
  Pipe and hopper inserts are not logged. `trash_can_closed` lines are the contents of a ServerUtilities
  `/trash_can` when it closed, which is when they are destroyed.
- `/inv view` (ServerUtilities) is logged per view. `invsee_open` starts a view with a `viewId`, says whether
  the target was online, and lists other open views of the same target (`concurrentViews`). `invsee_click`
  lines are single clicks that changed a slot: each changed slot with `owner` (`viewer` or `target`),
  inventory, index, before and after. The target's moves during the view are not in these lines.
  `invsee_close` says how the view ended (`how`: `closed`, `replaced` by a new `/inv view` without a close, or
  `viewerLogout`), counts the target's joins and leaves during the view, lists `overlappingViews`, and gives an
  `outcome`.
  The outcome follows ServerUtilities' rules. An offline target's inventories are a copy, written to the
  target's file only by a normal close while the target is offline. If the target is online at close, or the view
  ends without a close, that write is skipped: what the viewer took from the target then exists twice, and what
  the viewer put in is lost (`changes lost …`). A view opened on an online target edits the live inventories
  until the target logs out; later clicks go to a player object that is no longer saved (`changes after target
  logout lost`). Two views of one offline target each save their own copy, and the one closed last overwrites the
  other (`saved, last writer wins`). A save after the target logged in and out again overwrites the target's
  session in between. The Backpack-mod and Adventure Backpack tabs save by different rules and are not covered
  by the outcome; their clicks are listed apart in the analyzer.
- `watched_block` lines are player place and break of the `watchedBlocks` list (Thaumcraft nodes, node jars,
  stabilizers and transducer, Ethereal Shards, Blood Magic altars and ritual stones). A break has the tile's
  NBT. Jarring a node with a wand fires no event; compare `node_census` files.
- `creative_block` lines are every block a player in creative mode placed or broke, whatever the block, with
  `action`, `block`, `meta`, `itemKey` (place) and coordinates; `tile: true` when the block has a tile entity. A
  creative placement uses up no item, so no inventory snapshot shows it. A multi-block placement (a bed, a door)
  logs each block. Matter Manipulator placements are `bulk_placed`, not `creative_block`.
- `worldedit_edit` lines are what each WorldEdit edit changed: one line per edit session per tick, from a
  command, a brush, a wand tool or `//undo`. `placed` and `removed` are `block@meta` counts of blocks that
  actually changed, `min`/`max` the bounds, and `tileEntities` the first 256 blocks written with tile data as
  `[x, y, z, block@meta, tile id, GT meta tile ID?]` (`tileEntitiesTotal` is exact). An edit with no player
  (a script without an actor) has no `uuid`/`name`. The `command` line in the same tick has the text. Only
  logged when the server runs WorldEdit; the pack does not ship it.
- `node_census` files (`nodes-<ticks>.json.gz`, every 15 min and on `/audit snapshot`) hold the full NBT of
  every Thaumcraft node, node jar and Ethereal Shard in loaded chunks.
- `power_census` files (`power-<ticks>.json.gz`, every 5 min and on `/audit snapshot`) list every Lapotronic
  Supercapacitor in loaded chunks: stored EU, capacity, the capacitors installed per tier, wireless mode and owner.
  They also list every team's wireless EU. EU values are decimal strings. Capacitor counts come from the LSC's last
  structure check and are valid only when `formed` is true. The log line has the totals (`lscStored`,
  `wirelessStored`) and `readErrors`. A census has no entry for an LSC in an unloaded chunk.
- `storage_census` files (hourly) hold every EnderStorage item inventory, by frequency and owner, and the
  Backpack mod's backpack files. Backpack files are as of the mod's last save of each backpack.
- `item_id_table` is the world's numeric block and item ids at each server start (keys prefixed `\u0001`
  block, `\u0002` item, as in level.dat). The file is written only when the table changed.
- `quest_db` is the quest book's hash at each server start: the file on disk and the live database. `quest_edit`
  lines are BetterQuesting edit packets (quest edits, chapter edits, imports, settings such as edit mode) with
  the sender and a payload hash; refused attempts by non-ops appear too. `quest_task_complete` lines name the
  `actor`, the party member whose detection completed a task, next to each member credited with it.
- `blood_altar_tier` lines are altar tier changes, seen when the altar next re-checks (on use, or periodically).
- `gt_powerfail` lines are multiblocks stopped for power loss, the data behind GT's `/powerfails`.
- `multiblock_formed` lines from GT controllers can have `structure`: coil level (`HeatingCoilLevel` fields),
  `mHeatingCapacity`, and integer `…Tier` fields of the controller class (glass, casing, motor tiers). A change
  in any of them re-logs the formation.
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
- AE2 census entries have `craftable: true` when the network could autocraft the item at that instant (a
  pattern for it, or a crafting level emitter). An entry with `count: 0` is craftable only: AE2 lists craftable
  items in the network's item list, and the census records what it lists. `craftableItems` on the line counts
  them. Bundles from v0.6.0 and earlier also have count-0 entries, without the flag; read those as craftable.
- Timing — three clocks, every line carries all of them: `wall` (epoch ms; RTA = wall minus the anchored
  timing_started moment), `ticks` (cumulative server ticks = IGT, the main metric; AFK machine time counts),
  and `pticks` (ticks with at least one player connected). The tick clocks rewind with a rollback; wall time
  never does. Lines written before v0.2.4 lack `pticks`.
- IGT starts at the first player movement, not at world creation (worldgen lag doesn't count) — the
  `timing_started` line marks the moment. The server can't see keypresses, so "movement" means the first
  horizontal position change > 0.03 blocks in a tick; being pushed by a mob before ever walking would
  technically start it, which errs in the honest direction (earlier start = longer time).
