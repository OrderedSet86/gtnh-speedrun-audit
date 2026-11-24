# Submission Checks

A submission is one zip from `/audit export`. Read `SUMMARY.txt` first, and open the JSONL logs only for an event under
FLAGS FOR REVIEW, or an event that looks wrong. Before you flag an event, read [INNOCENCE.md](INNOCENCE.md). Several
events that look like cheats have innocent causes, and the quest book asks for some of them.

The fields of each log line are in [docs/LOG-FORMAT.md](docs/LOG-FORMAT.md).

## Bundle contents

- `SUMMARY.txt`: the sessions, a milestone timeline, and FLAGS FOR REVIEW. The timeline has quests, first visits to
  dimensions, multiblock formations, key items, and the first placement of each tracked block. The flags are gamemode
  changes, NEI cheats, explosions and rollbacks.
- `verify-report.json`: the mod's check of the chain at export.
- `anchor.json`: the anchor at export. The anchor is the last log line that the world saved with the world, so a
  restored world has an anchor behind the end of the log.
- `log/audit-*.jsonl`: the log. Each server session has one file. Each line has the hash of the line before.
- `snapshots/*.json.gz`: inventory snapshots and censuses. The name and the SHA-256 of each file are on a log line.
- `chains.json` and `chains/<uuid>/`: the logs of world copies (**World copies**). Bundles before v1.0.0 don't have
  them.

## Verdicts

Each `session_start` line has the verdict of the start:

- `NEW_WORLD`: the first session of the world.
- `OK`: the anchor and the end of the log match.
- `CRASH_RECOVERY`: the previous session ended without a `session_end` line, and that session saved the world last. The
  anchor can be up to one autosave behind the end of the log (900 ticks, or 45 s at 20 TPS), and more while world saving
  was off.
- `WORLD_ROLLBACK`: a backup was restored, which the rules allow. The `rollback` object has the count of events and the
  in-game time that the restore rewound.
- `WORLD_COPY`: the world started in a folder other than `homeFolder`, the folder of the world's audit UUID. The copy
  gets a new UUID and a separate chain (**World copies**).
- `LOG_TRUNCATED`, `TAMPERED_TAIL` and `ANCHOR_MISSING`: the log and the world disagree in a way that honest play
  doesn't produce.

Judge a rollback by the events before the stop. A `gt_explosion` shortly before the stop is the usual cause. When a
rollback goes back to just before a milestone, and the log doesn't have such a cause, ask the runner for an explanation.

A `WORLD_ROLLBACK`, `CRASH_RECOVERY` or `ANCHOR_MISSING` start has `loadedSave`, the logged save whose anchor values
match `session_start.anchor`:

| `kind` | The restored world is | `savedSidx`, `savedSeq` and `savedWall` point to |
|---|---|---|
| `backup` | a ServerUtilities backup. `file`, `bytes` and `fileModified` describe the zip | the `backup_done` line |
| `save` | an overworld save, such as an autosave, `/save-all`, or a folder copied after that save | the `world_saved` line |
| `stop` | the shutdown save of a clean stop | the `session_end` line |
| `unlogged` | a save without a log line, such as a save from a version before v1.0.0 | nothing |

A backup outranks a save with the same values, and a save outranks a stop. `matches` counts the lines of that kind with
those values. Saves before the timer starts have seq -1 in the anchor, and a restore of such a save starts with
`ANCHOR_MISSING`.

## World copies

A world that starts in another folder is a copy, such as a second singleplayer save, a backup unzipped into a new
folder, or a renamed folder. The mod gives the copy a new audit UUID and a separate chain, so play in the copy doesn't
enter the chain of the original folder.

The copy's first `session_start` has the verdict `WORLD_COPY` and names the parent chain and the copied save. When the
parent starts next, the mod logs the copy in the parent chain. `/audit export` puts the chain of each copy in the zip,
and the chains are under CHAINS in `SUMMARY.txt`. SESSIONS and MILESTONES have the ancestors of the exported chain up to
each copied save, and then the exported chain.

The mod compares folder names only. A copy in another instance or server folder with the same folder name isn't a copy
to the mod, and starts with `LOG_MISSING` unless `speedrun-audit/` was copied too. When someone deletes a copy's chain
before the parent's next start, the mod doesn't add the copy to the parent chain. Play in a copy that runs without this
mod doesn't get a line either.

## Chain check

Recompute the chain, and don't rely on `verify-report.json`:

1. Collect the lines of all `log/*.jsonl` files, and sort them by `seq`. The seqs are `0, 1, 2, …` without a gap.
2. Line 0's `prev` is `SHA-256("gtnhspeedrunaudit:genesis:" + worldAuditUuid)` in hex.
3. Each later line's `prev` is the SHA-256 of the previous line's UTF-8 bytes, without the trailing newline.
4. Each `inv_snapshot` and `ae2_census` line (`ae2_snapshot` before v1.0.0) has `fileSha256`. Hash the named file in
   `snapshots/`, and compare.

This Python checks steps 1 to 3 for one chain:

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
genesis hash of the copy's UUID. The `fork` block on the copy's first line names the parent chain, and the parent's line
at `parentSeq` hashes to `parentHash`.

## Logged events

Names such as moon% are leaderboard categories. Each category ends at that milestone.

| Area | Logged |
|---|---|
| Sessions | Server start and stop, and whether the server crashed. Ops, game rules, seed, server mods and the registered dimensions. Whether commands are allowed, each change of the setting, and each opening of a singleplayer world to LAN. Each player's join and leave, and the mods that the player's client reports. |
| Commands | Commands from players, the console and RCON (remote console), and whether each command was canceled. For a command block, the first run of each command in 10 minutes, and the count of the other runs. The replies of commands such as `/gamerule`, `/difficulty` and `/gt global_energy_display`. |
| Creative | Gamemode changes. The mod checks the gamemode each tick and at login. In creative, each block placed or broken and each inventory slot set. |
| NEI | Cheat requests to NEI (Not Enough Items), and the items of each request. The mod logs denied requests too. The requests are gives, slot sets, delete-all, gamemode, time, rain, heal, magnet, the creative+ stash, enchantments, mob spawners, time and rain locks, and ghost-slot filters. |
| Inventories | Full snapshots (main, armor, Baubles, ender chest) every 15 minutes and at join, leave, death and gamemode change. Each snapshot has the player's place and facing. Deaths and the killer. |
| AE2 | Item and fluid counts of each AE2 (Applied Energistics 2) network, what each network can autocraft, the network's channels in use, and the patterns in the network's interfaces with each pattern's author. This count is the AE2 census. The census runs hourly while a player is online and every 4 hours while the server is empty, and writes one file. A network that doesn't store anything is counted but not listed. Each pattern encoded, and each pattern put into or taken out of an interface. |
| Storage | Hourly contents of EnderStorage chests and pouches and of backpacks. Each `/inv view` of another player's inventory, and each click in the view. Items destroyed in trash cans. Items a player took from or put into a chest, machine or other GUI. In creative, each GUI opened and each click in the GUI. |
| Placements | GT machines placed by hand, Matter Manipulator, Vajra or multiblock projector, and the facing of each machine. Each later turn of a machine to a new facing. GT machines removed, and whether a player broke them, wrenched them or took them with a Vajra, an explosion or a fire destroyed them, or a Matter Manipulator took them. GT machine explosions. Stargate blocks. |
| Magic | Thaumcraft nodes, jarred nodes and Ethereal Shards every 15 minutes. Thaumcraft and Blood Magic blocks placed or broken. Blood Altar tier changes. |
| Power | The EU stored in Lapotronic Supercapacitors and each team's wireless EU, every 5 minutes. Wireless EU before and after each `/gt global_energy_*` command. GT power failures. |
| Edits | `/nbtedit` changes, and the data before and after. Game rule and difficulty changes. WorldEdit edits on servers that run WorldEdit. |
| Quests | Quest completions, and the party member who finished each task. Quest book edits, and a hash of the quest book. |
| Milestones | Dimension changes and first visits (moon%). Achievements. The first sighting and first craft of each key item in the config. |
| Prospecting | Each use of a GT prospecting tool or Advanced Seismic Prospector: who, where, and the ores, veins and fluids in the scan's result. |
| Positions | Each player's dimension, position and facing every 30 seconds, and every 5 seconds outside survival, except while the player stands still. Where each `/home`, `/back`, `/warp`, `/spawn`, `/tpa`, `/rtp` and `/tpl` teleport went, and each player that another command, such as `/tp`, moved. |
| Multiblocks | The formation of each GT and TecTech multiblock (assline%), and a new line when the hatch tiers, the amperage, or the coil or structure tiers change. Each line has the hatches, amperage, coils, tiers and bounding box of the multiblock, and the block at each position of the structure. The Bricked Blast Furnace (bbf%) and Railcraft multiblocks such as the coke oven (coke%). |
| Backups | Backup start and finish. |
| Mod files | A SHA-256 hash of each mod jar, `config/` and `scripts/` at each start. A changed jar, recipe or quest book changes the hash. |

## Teleports and dimension changes

A `dim_change` line has each dimension change of a player, and a `teleport` line has each teleport by a command. A
teleport command and the `dim_change` that the teleport causes are a tick or two apart. A portal or a rocket doesn't
have a command before the `dim_change`. Check the rules on teleports to another dimension with these lines.

`/cofh tpx` (CoFHCore) teleports the sender or another player to a player, a dimension id or a position, so a player can
use the command to skip a dimension. Each use is under FLAGS FOR REVIEW in `SUMMARY.txt`, and the `dim_change` lines
after the command have the arrival.

## Timing

Each line has three clocks:

- `ticks`: IGT (in-game time), the main metric. `ticks` is the sum of server ticks, so machine time while the players
  are away counts.
- `pticks`: ticks while at least one player is connected. Lines before 0.2.4 don't have `pticks`.
- `wall`: real time in Unix epoch milliseconds. RTA (real-time attack) is `wall` minus the `wall` of the
  `timing_started` line.

The tick clocks go back with a rollback, and `wall` doesn't. IGT starts at the first player movement, which the
`timing_started` line marks, so world generation doesn't count. The server can't read key presses, so the first movement
is the first horizontal move of more than 0.03 blocks in a tick. A mob that pushes a player before the player walks
starts the clock too, and the run time is then longer.

## Limits

- The mod is open source and runs on the runner's machine. Because of the chain, an edit of the log is detectable and
  costly. Someone can still write a whole log offline. Treat the log as a high bar, and keep in mind that the log isn't
  cryptographic proof.
- The mod logs a `command` line when the server dispatches a command, before the command runs. A `/give` without
  permission still has a line, and the line isn't canceled.
- Quest times are within 3 s. BetterQuesting checks for completed quests every 60 ticks.
- A multiblock formation has a time up to a few seconds after the last block was placed. GT checks a structure every few
  seconds.
- An AE2 census covers loaded grids only. A grid in an unloaded chunk can't change.
- Bundles from v0.5.0 and earlier have AE2 censuses only from `/audit snapshot`. A bug in the interval check stopped the
  hourly census, so a missing census in such a bundle is the mod's fault. From v0.6.0, a census that fails logs
  `ae2_census_failed`, and the line is under FLAGS FOR REVIEW in `SUMMARY.txt`.
- A `queue_overflow` line counts events that the mod dropped while the log writer was 50,000 events behind. The dropped
  events aren't in the log.
- In singleplayer, the mod doesn't log lines from the client thread. Those lines come from the structure-preview world
  (dim 2147483647). In bundles before v1.0.0, such lines have `"thread": "Client thread"`. Leave them out.
