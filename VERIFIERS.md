# Verifying a GTNH Speedrun Audit bundle

A submission is one zip from `/audit export`. Start with `SUMMARY.txt`; drill into the JSONL only when
something looks off. Before flagging anything, read [INNOCENCE.md](INNOCENCE.md) — several
flag-looking events have questbook-endorsed or otherwise innocent explanations.

## What the bundle contains

- `SUMMARY.txt` — session ledger, milestone timeline (quests, dimension first-visits, multiblock
  formations, key items, first placement of each tracked block), and a flags section (gamemode changes,
  NEI cheat actions, explosions, rollbacks).
- `verify-report.json` — the mod's own full-chain verification at export time.
- `anchor.json` — the world-side chain anchor at export time.
- `log/audit-*.jsonl` — the hash-chained event ledger, one file per server session.
- `snapshots/*.json.gz` — player inventory and AE2 network censuses, each one referenced (with its SHA-256)
  by a line in the ledger. A file name ends with the seq of that line, such as `nodes-477200-5123.json.gz`, so
  a restore that replays tick 477200 writes a new file. Bundles from earlier versions end in the tick, and a
  replayed tick replaced the earlier file.
- `chains.json` and `chains/<uuid>/` — the chains of world copies (see **World copies**). `chains.json` has one
  entry per chain, the exported chain first: `folder`, `fork` (absent for the first chain), `parentLinkOk`,
  `verdict`, `lines`, `lastSeq` and `lastHash`. Each other chain's files are in `chains/<uuid>/log/` and
  `chains/<uuid>/snapshots/`. Bundles from earlier versions have neither.

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

Run the same check in each `chains/<uuid>/` directory. A copy's chain starts at seq 0, and line 0's `prev` is the
genesis hash of the copy's UUID. Its first line's `fork` block names the parent chain, and the parent's line at `parentSeq` must hash to
`parentHash`.

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
- `WORLD_COPY` — the world started in a folder other than `homeFolder`, the folder of the world's audit UUID. The
  copy got a new UUID and a new chain (see **World copies**).
- `LOG_TRUNCATED` / `TAMPERED_TAIL` / `ANCHOR_MISSING` — the log and world disagree in ways honest play
  does not produce.

A `WORLD_ROLLBACK`, `CRASH_RECOVERY` or `ANCHOR_MISSING` start has `loadedSave`: the logged save with the same
anchor values as `session_start.anchor`. The anchor of a save from before the timer starts has seq -1, and a
restore of that save starts with `ANCHOR_MISSING`.

| `kind` | The world is | `savedSidx`, `savedSeq`, `savedWall` name |
|---|---|---|
| `backup` | a ServerUtilities backup. `file`, `bytes` and `fileModified` describe the zip | the `backup_done` line |
| `save` | an overworld save, such as an autosave, `/save-all`, or a folder copied after that save | the `world_saved` line |
| `stop` | the shutdown save of a clean stop | the `session_end` line |
| `unlogged` | a save that no line logged, such as a save from a version before `world_saved` | nothing |

A backup outranks a save with the same values, and a save outranks a stop. `matches` counts the lines of that
kind with those values. Saves before the timer starts all have the same values.

## World copies

A world started in another folder is a copy: a second singleplayer save made from the run's folder, a backup
unzipped into a new folder, or a renamed folder. The anchor has `homeFolder`, the folder of the world's audit
UUID. When the world starts in any other folder, the mod gives the world a new audit UUID and starts a new chain.
Play in a copy then never enters the chain of the original folder.

- The copy's first `session_start` has verdict `WORLD_COPY` and a `fork` block: `parentUuid`, `parentFolder`,
  and `parentSeq`, `parentHash`, `parentTicks` and `parentWall` of the copied save. `parentLogPresent` is false
  when the parent chain's directory is missing. `fork.loadedSave` names the copied save in the parent's log, as
  `loadedSave` does for a restore.
- A `session_start` has `copies` when chains forked from the session's chain: `uuid`, `folder`, `parentSeq`, and the
  copy's `tailSeq` and `tailHash` at that start. A copy and its length go into the parent chain at the parent's
  next start.
- `homeFolderSet: true` marks the start that set `homeFolder`: a new world, or the first start on this version.
- `SUMMARY.txt` lists the chains under CHAINS. Its SESSIONS and MILESTONES have the exported chain's ancestors
  up to each copied save, then the exported chain.
- The folder name is the only key. A copy in another instance or server folder with the same folder name is not
  a fork, and starts with `LOG_MISSING` unless `speedrun-audit/` was copied too. A copy's chain deleted before
  the parent's next start leaves no trace. Play in a copy without this mod leaves none either.

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
- GT `multiblock_formed` lines also carry the footprint. `bounds` is `[minX, minY, minZ, maxX, maxY, maxZ]`,
  inclusive world coordinates of every block position the structure check visited through StructureLib, plus
  the controller. A probe past the end of a variable-length multiblock (the Assembly Line's next slice, the
  LSC's next layer) fails and is left out. `facing` is the controller's front, and `rotation`/`flip` are
  StructureLib's (absent for a controller that is not a StructureLib multiblock). Multiblocks that check by hand
  (Cleanroom, Charcoal Pit, LESU) have no `bounds`. Railcraft and the bricked blast furnace have neither.
  A formed multiblock whose footprint changes without a new formation (a wrench rotation, an LSC that grew
  layers) logs a `multiblock_footprint` line with the same fields. The controller saves the footprint it last
  logged, so after updating to a version with footprints each GT multiblock logs one `multiblock_footprint`
  at its first check (TecTech ones at their next re-check), and nothing again until it changes.
- `dim_change` lines record every dimension change (`fromDim`, `toDim`, arrival position). A teleport
  command and the `dim_change` it causes are a tick or two apart; a portal or rocket has no command before
  it. Use them to check rules on cross-dimension teleports. Older bundles have only `dim_first_visit`, which
  also re-fires after a world rollback.
- `pos_sample` lines are the place of each online player, sampled every 30 s in survival and every 5 s in any
  other mode (creative, adventure, spectator), by default (`positionSampleSeconds`,
  `positionSampleSecondsOutsideSurvival`). A player gets the shorter interval within 5 s of leaving survival.
  One line has a row in `players` for each player sampled on that tick:

  ```
  [player, mode, dim, x, y, z, yaw, pitch]
  [0,"s",0,1335,1050,-2150,-160,30]
  ```

  - `player` is the `playerNumber` of the player's `player_join` line in the same session. The first player to
    join is 0, the next is 1. A player who rejoins keeps the number.
  - `mode` is `s` (survival), `c` (creative), `a` (adventure), an empty string for a player with no gamemode, or
    the full name of another mode, such as `spectator`.
  - `x`, `y` and `z` are the feet, in tenths of a block: 1335 is x = 133.5.
  - `yaw` is in whole degrees from -180 to 179: 0 faces south (+z), 90 west, -90 east, -180 north. `pitch` is
    from -90 (looking up) to 90 (looking down).

  A sample with no row for an online player is a player who stayed still: in the dimension and mode of the
  player's last row, within 1 block of that row on each axis, and within 10 degrees of that row's yaw and pitch.
  The first sample after each join always has a row. A row is the player's place on that tick only. Between
  samples, the player can have gone anywhere. Who was online is in the `player_join` and `player_leave` lines.
  Bundles before this version
  have no samples: positions there come only from joins, leaves, commands and dimension changes.
- `inv_snapshot` lines, and the snapshot files they name, have `dim`, `pos` `[x, y, z]` (the feet, to 0.1 block),
  `yaw` and `pitch` (to 0.1 degree), so a snapshot's armour and the player's place come from one record. Older
  bundles have neither.
- `teleport` lines are ServerUtilities teleports of a player: `type` `HOME`, `WARP`, `SPAWN`, `BACK`, `TPA` (the
  requester, moved by `/tpaccept`), `RTP`, `VANILLA_TP` (the admin teleport of the claimed-chunks screen) or `TPL`
  (`/tpl`). `warmupSeconds` is the warmup the player waited, so the `command` line is that many seconds earlier.
  The line is written just before the move, so for a move to another dimension the `teleport` line comes before
  the `dim_change` line. `from` has `dim`, `pos`, `yaw` and `pitch`. `to` has `dim` and `pos`: the player's feet
  are put at exactly that spot, and the facing does not change. A teleport cancelled by a move or a hit during the
  warmup has a `command` line and no `teleport` line. Vanilla `/tp` and `/cofh tpx` do not go through
  ServerUtilities: use their `command` arguments and the `dim_change` and `pos_sample` lines after them. The pack
  ships `/home`, `/back`, `/warp`, `/spawn`, `/tpa` and `/rtp` turned off in ServerUtilities' config. A runner who
  turns them on gets these lines.
- `creative_slot` lines are creative-inventory packets: the client setting one of its own slots (or
  dropping, slot < 0). Moving an item between slots and taking one from the creative tabs both arrive as slot
  sets, so each line also has the slot's previous contents. Net `item` against `previousKey` over a player's
  lines to find what was spawned. `inCreative: false` means a packet vanilla ignored, which only a modified
  client sends. Past 20 of those per player in a minute, they are counted in a `rate_limited` line instead.
- `nbt_edit` lines are `/nbtedit` saves. `nbtB64` is what the save wrote and `beforeB64` is the target just
  before it, so the two show what the edit changed; above 64 KB only the hashes (`nbtHash`, `beforeHash`) are
  kept. `beforeMissing` says why there is no before state (`chunk not loaded`, `no tile entity`, `empty hand`,
  …). `beforeMissing: no pending edit` is a save packet from a sender with no `/nbtedit` open. ServerUtilities
  skips such a packet, and such a packet comes only from a modified client. Past 20 of those per player in a
  minute, they are counted in a `rate_limited` line instead. Older bundles have no before fields. A block opened with `/nbtedit` but
  never saved has only the command line.
- `rate_limited` lines count client packets that had no effect, past 20 lines per player and type in a
  minute: `nbt_edit` with no pending edit, `creative_slot` outside creative, `nei_packet`, and `quest_edit`
  from a player who isn't an op. `dropped` is the count for that minute. Many of them mean a modified client
  flooding the server. A packet that changes the world is never counted in place of a line.
- `queue_overflow` lines count events dropped because the writer fell 50,000 events behind. Events in that
  gap aren't in the log.
- `unserializable_event` lines stand in for an event whose line couldn't be written, with the event `type`
  and the `error` class. The chain goes on past one.
- NBT hashes (`nbtHash`, `beforeHash`, `payloadHash`, `liveHash`) have the format in
  `session_start.nbtHashFormat`. Format 2 covers every value of the NBT. Bundles with no `nbtHashFormat` have
  format 1, where two NBTs can share a hash when they differ only inside a byte array, or by string text that
  looks like other fields. Compare hashes only between sessions of one format.
- `session_start.logTail.cutLastLineIn` names a log file whose last line was cut, as by a power loss mid-write.
  The chain resumes from the line before the cut line. The cut line shows as `CORRUPT_LINE` in the verifier's
  report.
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
- A `machine_placed` line's `itemKey` and `displayName` are the item in the player's main hand. That is a tool,
  not the machine, when a Vajra swaps in the offhand block (`gregtech:gt.Tool_Vajra`) or a StructureLib hologram
  projector builds from the inventory (`structurelib:item.structurelib.constructableTrigger`). `machineId` (the
  meta tile ID) and `machine` name the GT machine read from the world. Lines from earlier versions have neither.
- `machine_removed` lines are GT machines, hatches, pipes and cables leaving the world, one line per block, with
  the same `machineId`, `machine` and `kind` as `machine_placed`. `cause` is `broken` or `wrenched` (a player's
  break, by the tool in hand, with `uuid`, `name` and `tool`), `vajra` (a break or a right-click with GT's or
  Gravitation Suite's Vajra, with the same player fields), `exploded` (the machine's explosion or any
  explosion over the block), `burned` (a GT cable or pipe set on fire) or `other` (a command, a WorldEdit edit or
  another mod's code). A line with `kind` `unknown` had no GT tile at the moment of removal. A spot placed twice
  with no removal between is a log from before this version.
- `bulk_removed` lines are GT machines a Matter Manipulator removed or replaced, in the shape of `bulk_placed`
  with no `checked`.
- `machineKey` (on `machine_placed` and `machine_removed`) and `keys` (on `bulk_placed` and `bulk_removed`) are
  GT's unlocalized machine keys, such as `gt.blockmachines.hull.bronze`. `achKey` on `achievement` is the
  achievement's lang key, and `provider` on `dim_first_visit` is the dimension's WorldProvider class. Use these
  to compare runs: `machine`, `displayName`, `achName` and `dimName` are in the server's language, which in
  singleplayer is the player's client language.
- `dim_table` lists every registered dimension at each start: `id`, provider `type`, `provider` class, `loaded`,
  and `name` and `saveFolder` for a loaded one. `dim_loaded` names each dimension that loads later in the
  session. Together they name every dim id in `savingOffDims`.
- In singleplayer, lines from the client thread are not logged: they come from the structure-preview world
  (dim 2147483647), not from the run's world. `session_end` has `clientThreadLinesDropped` when any were. In
  earlier bundles, such lines have `"thread": "Client thread"`. Leave them out.
- `world_saving` lines with `enabled: false` also have `lastSave {seq, ticks, wall}`: the anchor values the
  overworld's latest save wrote. A backup saves every world and then turns saving off, so these are the anchor
  inside that backup. A `WORLD_ROLLBACK` whose `session_start.anchor` equals a `lastSave` restored that backup.
  `backup_done` names the newest file in ServerUtilities' backup folder when a backup thread finishes.
  `backup_done.save` has the same values as that `lastSave`. `loadedSave` on `session_start` does this match.
- A `world_saved` line is logged at each overworld save while saving is on, with `anchor {seq, ticks, wall}`.
  These saves are the autosave (every 900 ticks), `/save-all`, and the save before each backup. The shutdown
  save after `session_end` has no line. Every line is flushed to the OS when written, and a `world_saved` line is also forced to disk. After a
  `CRASH_RECOVERY` start with `loadedSave.kind` `save`, the log is known to be complete up to the save that the
  world came back to. A stretch of more than 900 ticks without `world_saved` is a stretch with saving off, or with
  lines lost.
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
  NBT. Jarring a node with a wand fires no event; compare `node_census` files. In v0.6.0 and earlier, a place line's
  `meta` was the meta of the block it replaced (Forge's `PlaceEvent.blockMetadata`), so placements of the
  meta-specific entries (`blockAiry@0`/`@5`, `blockJar@2`, `blockStoneDevice@9..11`) were matched against the
  replaced block and usually missed; use `node_census` files to cover those runs.
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
  in any of them re-logs the formation. `structureUnreadable` lists the controller classes whose own tier fields
  the server could not read: `structure` is incomplete for that line, not wrong. Only a server on Java 8 should
  show it, for a class with a field typed by a client-only class (the Extreme Entity Crusher, the BEC storage).
  Newer Java reads those fields by name.
- `multiblock_log_failed` lines are formations or footprint changes the mod failed to record (see INNOCENCE.md).
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
