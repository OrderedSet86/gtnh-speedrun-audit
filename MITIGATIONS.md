# Mitigating circumstances

Companion to [VERIFIERS.md](VERIFIERS.md). Every entry in SUMMARY's `FLAGS FOR REVIEW` section is a
*flag*, not a verdict — this doc lists the known-innocent explanations for each, how to confirm them
from the bundle, and what should actually raise eyebrows. Board rules decide what is allowed; this doc
exists so runners aren't punished for things the pack itself tells them to do.

## `nbt_edit` on `player <name>` — questbook-endorsed crop editing

The GTNH questbook (IC2 crops chapter) **explicitly teaches** `/nbtedit me` as the sanctioned way to
edit seed-bag growth/gain/resistance (GGR) stats, as an alternative to generations of crossbreeding.
A runner following the questbook produces an `nbt_edit` line on their own player. Do not flag this by
itself.

**How to confirm innocence:** the `nbt_edit` line carries the full applied NBT (`nbtB64`, gzip+base64
of the player tag). Decode it and compare against the nearest earlier `inv_snapshot` of the same
player: an innocent GGR edit changes only `Scale`/`Growth`/`Gain`/`Resistance`-style values inside a
seed-bag item's tag. Anything else that changed in the same edit — item ids, counts, new items,
machine charge values — is not covered by the questbook and goes to the rules.

**Escalate when:** the edit targets a `block` (machine inventories/state), another player, or the
diff touches anything beyond seed-bag stats.

## `WORLD_ROLLBACK` — backup restore after a disaster

Machines explode; bases burn; admins restore backups. Explicitly allowed.

**How to confirm innocence:** look for context in the minutes before the prior `session_end` —
`gt_explosion` lines, a `death`, chat about it. The `rollback` object quantifies what was rewound;
the log retains the entire rolled-back window, so nothing is hidden.

**Escalate when:** a rollback lands just before a milestone with no disaster context, rollbacks are
frequent and always "profitable", or post-restore snapshots contain items the pre-restore snapshots
can't explain.

## `CRASH_RECOVERY` / `ANCHOR_MISSING`

Crashes are a fact of modded 1.7.10. `CRASH_RECOVERY` with the anchor lagging the log tail by up to
~100 ticks (the anchor write cadence) is the normal signature. `ANCHOR_MISSING` can appear once if
the very first session of a world crashed before the world ever saved (seen in testing). Neither is
suspicious alone; a *pattern* of crashes bracketing milestones is.

## `gamemode_change` to creative

Every transition triggers an inventory snapshot on both sides of the boundary, so what was carried
across is on record. Innocent cases boards may choose to allow: server admins doing spawn/hub work in
MP (not the runner), or media/screenshot sessions after the run ends. Compare the bracketing
snapshots: creative time that adds nothing to the survival inventory is verifiable as harmless.

## `nei_cheat` `set_time`

Skipping night via NEI's time buttons. Some boards treat time-skips as legal (equivalent to sleeping),
some don't — decide once and write it into the rules. IGT counts ticks, so time-of-day skips don't
shorten IGT either way.

## Command lines that look like cheats but were denied

The command log records *dispatch*, not success. A non-op player typing `/give` produces an uncanceled
`command` line even though ServerUtilities' rank check rejected it inside execution. Corroborate with
effect: a real `/give` shows up in the next inventory snapshot; a denied one doesn't. NEI packets
before the permission check (`nei_packet` without a matching `nei_cheat`) are likewise denied
attempts.

## Repeated `multiblock_formed` at one position

Chunk reload re-forms are deduped per session, but a machine that is wrenched, moved, or rebuilt forms
again legitimately — and every later session re-logs a formation once per position. Only
`firstOfClass: true` entries are milestones; the rest are texture.

## `difficulty_change` to peaceful

1.7.10 has no /difficulty command: in singleplayer the options menu changes it silently (the per-tick
poll catches it the moment it happens), and on servers it changes via offline server.properties edits
(visible as a delta between one session_start's `difficulty` and the next). Peaceful clears hostile
mobs instantly, so once the board bans it, even a brief dip is material. Innocent-ish case: a dip
while nobody is online (compare against player_join/leave) changed nothing a player could exploit —
but under a ban the clean answer is "never during the run".

## `client_mods` / `flaggedClientMods` (Schematica, WorldEdit, …)

Joining clients report their mod list in the FML handshake; the watchlist flags matches (default:
schematica, worldedit). Two honesty caveats, both load-bearing:

- **Self-reported.** A modified client can omit anything. A flag here catches honest runners with a
  gray-area mod installed; a clean list proves nothing. Do not treat absence as evidence.
- **Presence is not usage.** Schematica installed ≠ printer used. Corroborate with effect: printer
  abuse shows as inhumanly fast/regular block placement (machine_placed timestamps at tick
  resolution), WorldEdit usage shows in the command log (its commands dispatch like any other).

Boards should write down which client mods are outright banned vs allowed-but-flagged, so a runner
with Schematica for schematic *viewing* (if allowed) isn't rejected on presence alone.

## Timing quirks

- `timing_started` fires on first *horizontal movement*, not literal keypresses — a mob shoving the
  runner at spawn starts the clock early, which errs against the runner, never for them.
- Quest completion timestamps are ±3 s (BetterQuesting polls on a 60-tick cycle).
- Multiblock formation timestamps lag placement by up to a few seconds (periodic structure checks).
- On a lagging server (TPS < 20) IGT and player-online ticks run slower than real time; RTA is the
  only lag-immune clock. All three are on every line — cross-check, don't assume.

## Session gaps

Runs are segmented by design. Wall-clock gaps between `session_end` and the next `session_start` are
life, not evidence. Only *in-world* discontinuities (rollback/truncation verdicts) matter.
