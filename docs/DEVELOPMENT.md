# Development

Build with `./gradlew build`. Start a dev server with `./gradlew runServer`.

## Self-test

`scripts/selftest.sh` takes about 5 to 7 minutes. The script boots a headless server where two fake players
perform most logged actions, checks the log, and then runs the tamper drills:

| Drill | Expected |
|---|---|
| fresh world | `NEW_WORLD` |
| restart | `OK` |
| world restore | `WORLD_ROLLBACK` |
| world restore after a crash | `WORLD_ROLLBACK` |
| newest log hidden | `LOG_TRUNCATED` |
| one byte edited | chain break at that line |
| `kill -9` | `CRASH_RECOVERY` |

The script ends with `ALL DRILLS PASSED` or the name of the failed check.

To run the scenario alone, run `./gradlew runServer -Pselftest=selftest-out`, and search the server log for
`SELFTEST PASSED`. The details are in `run/server/selftest-out/selftest.json`. The dev server loads AE2, so
the scenario builds a real ME network. The released jar doesn't depend on AE2.

To run the scenario on a real pack server, add `-Dspeedrunaudit.selftest=<dir>` to the java command line.
NEVER do this on a run's world. The scenario writes the scenario's events into that world's audit log.

The self-test doesn't connect a real client, so the self-test doesn't check the client mod list or NEI
packets. To check the client mod list and NEI packets, join a pack server with a real client.

## AE2 bench

The AE2 census is the heaviest work of the mod, and the census runs inside one server tick of 50 ms.
`./gradlew runServer -Pae2bench=bench-out` builds synthetic networks and times the census. Set the sizes with
`-Pae2benchSizes=1000,50000` and the share of entries with NBT with `-Pae2benchNbtPercent=20`. The results are
in `run/server/bench-out/ae2bench.json`. The bench also checks each census for miscounts.[^bench]

| Item types | Keys | Census | First census after a start | Snapshot (gz) | Serialize (writer thread) | Disk over 500 h, hourly census |
|--:|--:|--:|--:|--:|--:|--:|
| 20,000 | 17,000 | 1.6 ms | 5.8 ms | 165 KB | 20 ms | 81 MB |
| 50,000 | 42,500 | 11.8 ms | 21.7 ms | 419 KB | 46 ms | 204 MB |
| 100,000 | 85,000 | 12.9 ms | 37.8 ms | 857 KB | 87 ms | 418 MB |

[^bench]: Measured on 2026-10-13 with the AE2 bench. Each value is the median of 5 runs. 20% of the entries have
    NBT and merge four to a key, so a network has fewer keys than item types. The first census after a server
    start runs before the key cache fills. Census times vary about 2× between runs on a busy machine.
