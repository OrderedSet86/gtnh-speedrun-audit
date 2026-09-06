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

Three clocks on every line, all frozen until the first player movement (worldgen lag doesn't count):

- `ticks` — IGT, the main metric: cumulative server ticks across all sessions. Pauses with the integrated
  server, keeps counting while a dedicated server runs unattended, rewinds with a rollback.
- `pticks` — player-online ticks: only counts while at least one player is connected.
- `wall` — epoch ms; RTA is the span since the `timing_started` moment.

## Building

Standard GTNH mod template: `./gradlew build`. Dev server: `./gradlew runServer`.

## Self-test

`scripts/selftest.sh` (~5–7 min) is the full headless harness: one scenario boot where two fake players
perform every loggable action (join, movement→timer start, gamemode flips, snapshots, key items,
commands, difficulty changes, death, milestones, sink-layer events) with in-JVM assertions against the
actual JSONL, followed by the tamper drills — restart→OK, world-restore→WORLD_ROLLBACK, hidden
log→LOG_TRUNCATED, byte-edit→chain break at the exact line, kill -9→CRASH_RECOVERY. Ends with
`ALL DRILLS PASSED` or a named failing check.

Scenario boot alone: `./gradlew runServer -Pselftest=selftest-out` → grep for `SELFTEST PASSED`, details
in `run/server/selftest-out/selftest.json`. The same scenario runs on a **real dedicated pack server**
(mixin integrations live) by adding `-Dspeedrunaudit.selftest=<dir>` to its java command line — never do
this on a run's real world; it writes scenario events into the audit trail by design.

Not covered headless (needs one real client join on a pack server): FML-handshake client-mod capture and
NEI packets over the wire.
