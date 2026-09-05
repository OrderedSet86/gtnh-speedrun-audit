# gtnh-speedrun-audit

Tamper-evident audit trail for [GT: New Horizons](https://gtnh.miraheze.org/) speedruns, built for the
speedrun.com leaderboard. GTNH runs take months and are mostly played multiplayer; there is no replay
system to verify against. This mod produces one instead of a replay: a hash-chained ledger of everything a
verifier needs, submitted as a single zip.

One jar, no hard dependencies. Works identically on a dedicated server and in singleplayer (the
integrated server runs the same code). Clients without the mod can join an audited server.

## What it records

| | |
|---|---|
| Commands | every dispatch — player, console, RCON, command block — with the final canceled state |
| Sessions | start/stop, clean-vs-crash, ops list, gamerules, seed, mod list |
| Gamemode | per-tick transition watch + gamemode at login (catches offline NBT edits) |
| NEI | every cheat-channel packet (before auth — denied attempts too) + decoded give/set-slot/delete/gamemode/time/heal |
| Inventories | full-NBT snapshots (main, armor, baubles, ender chest) on an interval and at join/leave/death/gamemode change |
| AE2 | periodic per-network item+fluid census, counts only, one grid per tick, coarser while the server is empty |
| Milestones | BetterQuesting completions, dimension first-visits (moon%), achievements, configurable key-item first sightings |
| Multiblocks | formation of every GT multiblock (assline%), the Bricked Blast Furnace (bbf%) and Railcraft multiblocks (coke%) |
| Explosions | GT machine explosions — the context next to a backup restore |
| Fingerprint | SHA-256 of every mod jar + config/ + scripts/ each session (catches modified jars, recipes, questbook) |

## Tamper evidence

Every log line embeds the SHA-256 of the previous line's exact bytes. The chain head is also anchored in a
`WorldSavedData` inside the world save, while the log itself lives **outside** the world folder
(`speedrun-audit/<world-uuid>/` next to the server root). A backup restore — allowed, bases explode —
rewinds the world and its anchor but not the log, so the next boot records a `WORLD_ROLLBACK` verdict with
exactly how much was rewound instead of losing the evidence. Editing, truncating or swapping logs breaks
the chain in ways `/audit verify` (and any 20-line script, see [VERIFIERS.md](VERIFIERS.md)) pinpoints.

Honest limit: an open-source, locally-run mod cannot make history unforgeable — it makes forging expensive
and mistakes detectable. Board rules should treat it as a high bar, not proof.

## Commands

```
/audit status     writer health, chain head, run clock
/audit verify     full chain re-verification (background thread)
/audit snapshot   immediate inventory snapshots + AE2 census
/audit export     build the submission zip (logs + snapshots + verify report + SUMMARY.txt)
```

## Timing

Undecided boards need both clocks, so every line carries both: `wall` (epoch ms — RTA) and `ticks`
(cumulative server ticks across all sessions — in-game time; pauses with the integrated server, rewinds
with a rollback).

## Building

Standard GTNH mod template: `./gradlew build`. Dev server: `./gradlew runServer`.
