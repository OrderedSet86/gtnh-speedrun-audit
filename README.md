# gtnh-speedrun-audit

A tamper-evident log of a [GT: New Horizons](https://gtnh.miraheze.org/) (GTNH) run, for the speedrun.com
leaderboard. GTNH runs take months, mostly in multiplayer, and the game has no replay system. This mod
records what a moderator needs to check a run. At the end, `/audit export` writes one zip to submit.

## Setup

Download the jar from [Releases](https://github.com/OrderedSet86/gtnh-speedrun-audit/releases). The jar
works with GTNH 2.8.4 and the dailies.

- Multiplayer: put it in the server's `mods/`. Players don't need it.
- Singleplayer: put it in your instance's `mods/`.

Install it before you create the world. On an existing world, the log starts when the mod is added.

The log is in `speedrun-audit/` in the server folder, or the instance folder in singleplayer. It's
outside the world folder. Keep it with the server, and don't edit, move or delete anything in it.

## What gets logged

Names like moon% are leaderboard categories, each ending at that milestone.

| Area | Logged |
|---|---|
| Sessions | Server start and stop, and whether it crashed. Ops, game rules, seed and server mods. Each player's join and leave, with the mods their client reports. |
| Commands | Every command from a player, the console, RCON (remote console) or a command block, and whether it was canceled. The replies of commands such as `/gamerule`, `/difficulty` and `/gt global_energy_display`. |
| Creative | Gamemode changes, checked every tick and at login. In creative, every block placed or broken and every inventory slot set. |
| NEI | Every cheat request to NEI (Not Enough Items), denied ones too: gives, slot sets, delete-all, gamemode, time and heal, with the items involved. |
| Inventories | Full snapshots (main, armor, Baubles, ender chest) every 15 minutes and at join, leave, death and gamemode change. Deaths, with the killer. |
| AE2 | Item and fluid counts of every AE2 (Applied Energistics 2) network, and what each network can autocraft. Hourly while a player is online, every 4 hours while the server is empty. |
| Storage | Hourly contents of EnderStorage chests and pouches and of backpacks. Every `/inv view` of another player's inventory, and every click in it. Items destroyed in trash cans. |
| Placements | GT machines placed by hand, Matter Manipulator, Vajra or multiblock projector. Stargate blocks. |
| Magic | Thaumcraft nodes, jarred nodes and Ethereal Shards every 15 minutes. Thaumcraft and Blood Magic blocks placed or broken. Blood Altar tier changes. |
| Power | Every 5 minutes, the EU stored in Lapotronic Supercapacitors and every team's wireless EU. Wireless EU before and after each `/gt global_energy_*` command. GT power failures. |
| Edits | `/nbtedit` changes, with the data before and after. Game rule and difficulty changes. WorldEdit edits, on servers that run WorldEdit. |
| Quests | Quest completions, with the party member who finished each task. Quest book edits, and a hash of the quest book. |
| Milestones | Dimension changes and first visits (moon%). Achievements. The first sighting and first craft of each key item in the config. |
| Multiblocks | The first formation of every GT and TecTech multiblock (assline%), with its hatches, amperage, coils and tiers. The Bricked Blast Furnace (bbf%) and Railcraft multiblocks such as the coke oven (coke%). |
| Backups | Backup start and finish. GT machine explosions. |
| Mod files | A SHA-256 hash of every mod jar, `config/` and `scripts/` at each start. A changed jar, recipe or quest book changes the hash. |

## Backups and crashes

Restoring a backup is allowed, and so is playing on after a crash. A restore doesn't rewind the log, and
the mod records how far the world went back.

[INNOCENCE.md](INNOCENCE.md) lists a few events that look suspicious but have innocent causes, such as
the quest book's `/nbtedit` crop step.

## Timing

Every log line has three clocks:

- `ticks`: IGT (in-game time), the main metric. Server ticks summed over every session. It stops while a
  singleplayer game is paused, runs while a dedicated server is up with nobody online, and goes back with a
  restore.
- `pticks`: ticks while at least one player is online.
- `wall`: real time, as Unix epoch milliseconds. RTA (real-time attack) runs from the first movement.

Both tick clocks stay at 0 until a player first walks. Falling and looking around don't count, so world
generation and the spawn drop add no time.

## Commands

```
/audit status     clocks and log health
/audit verify     check that the log is intact
/audit snapshot   take every snapshot and census now
/audit export     write the submission zip
```

On a server, `/audit` is an op command. In singleplayer it works without cheats. The zip goes in
`speedrun-audit/<world-id>/export/` and has the logs, the snapshots, a `SUMMARY.txt` and a verification
report.

## Server cost

The AE2 census is the heaviest work, and it runs inside one server tick. On a network with 100,000 item
types it takes about 13 ms of the 50 ms tick, and about 38 ms for the first census after a start. At one
census an hour, that's one slow tick in 72,000.

Disk is the larger cost. At 100,000 item types, AE2 snapshots add about 420 MB over a 500-hour run, and all
of it goes into the submission zip. To take fewer censuses, raise `ae2SnapshotActiveMinutes` in
`config/gtnhspeedrunaudit.cfg`.

## Development

Build with `./gradlew build`. Start a dev server with `./gradlew runServer`.

### Self-test

`scripts/selftest.sh` takes about 5 to 7 minutes. It boots a headless server where two fake players perform
most logged actions, checks the log, then runs the tamper drills:

| Drill | Expected |
|---|---|
| fresh world | `NEW_WORLD` |
| restart | `OK` |
| world restore | `WORLD_ROLLBACK` |
| world restore after a crash | `WORLD_ROLLBACK` |
| newest log hidden | `LOG_TRUNCATED` |
| one byte edited | chain break at that line |
| `kill -9` | `CRASH_RECOVERY` |

It ends with `ALL DRILLS PASSED` or the name of the failed check.

The scenario alone: `./gradlew runServer -Pselftest=selftest-out`, then look for `SELFTEST PASSED`. Details
are in `run/server/selftest-out/selftest.json`. The dev server loads AE2, so the scenario builds a real ME
network. The released jar has no AE2 dependency.

On a real pack server, add `-Dspeedrunaudit.selftest=<dir>` to the java command line. NEVER do this on a
run's world. The scenario writes its events into that world's audit log.

The client mod list and NEI packets are untested headless. To test them, join a pack server with a real
client.

### AE2 bench

`./gradlew runServer -Pae2bench=bench-out` builds synthetic networks and times the census. Set the sizes
with `-Pae2benchSizes=1000,50000` and the share of NBT-bearing entries with `-Pae2benchNbtPercent=20`.
Results are in `run/server/bench-out/ae2bench.json`. The bench also checks every census for
miscounts.

Measured 2026-10-13, median of 5 runs. 20% of entries carry NBT and merge four to a key, so there are fewer
keys than types. "First" is the census after a server start, before the key cache fills.

| Item types | Keys | Census | First census | Snapshot (gz) | Serialize (writer thread) | Per 500 h, hourly |
|---|---|---|---|---|---|---|
| 20,000 | 17,000 | 1.6 ms | 5.8 ms | 165 KB | 20 ms | 81 MB |
| 50,000 | 42,500 | 11.8 ms | 21.7 ms | 419 KB | 46 ms | 204 MB |
| 100,000 | 85,000 | 12.9 ms | 37.8 ms | 857 KB | 87 ms | 418 MB |

Census times vary about 2× between runs on a busy machine.
