#!/usr/bin/env bash
# Full self-test: one scenario boot (fake players perform every loggable action, in-JVM assertions),
# then the multi-boot tamper drills, asserting the startup verdict of each. Wipes the dev server's
# scratch world + audit dir. Runtime ~5-7 minutes. Exit 0 = everything passed.
#
# The same scenario can run on a REAL dedicated pack server (mixin integrations live) by adding
# -Dspeedrunaudit.selftest=<dir> to its java command line — this script only drives the dev server.
# SELFTEST_PORT sets the dev server's port (default 25565), so drills in two worktrees can run at once.

set -euo pipefail
cd "$(dirname "$0")/.."

SERVER=run/server
AUDIT_GLOB="$SERVER/speedrun-audit/*/log"
SCRATCH=$(mktemp -d)
trap 'rm -rf "$SCRATCH"' EXIT

step() { echo; echo "=== $1"; }
boot() { # boot <label> <extra gradle args...>; console gets EULA answer, BOOT_COMMAND if set, timed stop
    local label=$1; shift
    (echo "y"; sleep "${BOOT_PLAY_SECONDS:-45}"; [ -n "${BOOT_COMMAND:-}" ] && { echo "$BOOT_COMMAND"; sleep 15; }
        echo "stop"; sleep 20) \
        | ./gradlew runServer --console=plain "$@" > "$SCRATCH/$label.log" 2>&1 || true
}
logdir() { ls -d $AUDIT_GLOB 2>/dev/null | head -1; }


step "wipe scratch world + audit dir"
rm -rf "$SERVER/world" "$SERVER/world-copy" "$SERVER"/speedrun-audit "$SERVER/selftest-out"
mkdir -p "$SERVER" && echo "eula=true" > "$SERVER/eula.txt"
touch "$SERVER/server.properties"
set_property() { # set_property <key> <value>: replace the key's line, or append one
    if grep -q "^$1=" "$SERVER/server.properties"; then
        sed -i "s/^$1=.*/$1=$2/" "$SERVER/server.properties"
    else
        echo "$1=$2" >> "$SERVER/server.properties"
    fi
}
set_property server-port "${SELFTEST_PORT:-25565}"
set_property level-name world

step "boot A: scenario (fake players, in-JVM checks)"
BOOT_PLAY_SECONDS=75 boot A -Pselftest=selftest-out
grep -q "SELFTEST PASSED" "$SCRATCH/A.log" || {
    echo "FAILED: scenario boot did not pass"; grep -E "SELFTEST|FAIL —" "$SCRATCH/A.log" | head -20; exit 1; }
python3 scripts/assert_log.py "$(logdir)" verdict NEW_WORLD
python3 scripts/assert_log.py "$(logdir)" field homeFolderSet true
python3 scripts/assert_log.py "$(logdir)" continuity
# the world-copy drill adds a second chain directory. Every later step reads the first world's chain
MAIN_LOG=$(logdir)
logdir() { echo "$MAIN_LOG"; }

step "boot B: plain restart -> OK (world copied aside first for the rollback drill)"
cp -r "$SERVER/world" "$SCRATCH/world-backup"
boot B
python3 scripts/assert_log.py "$(logdir)" verdict OK
python3 scripts/assert_log.py "$(logdir)" continuity

step "boot G: start a copy of the world in another folder -> WORLD_COPY with a chain of its own"
rm -rf "$SERVER/world-copy" && cp -r "$SERVER/world" "$SERVER/world-copy"
set_property level-name world-copy
boot G
set_property level-name world
COPY_LOG=$(ls -d $AUDIT_GLOB | grep -v -x -F "$MAIN_LOG" | head -1 || true)
[ -n "$COPY_LOG" ] || { echo "FAILED: the copy has no chain directory of its own"; exit 1; }
python3 scripts/assert_log.py "$COPY_LOG" verdict WORLD_COPY
python3 scripts/assert_log.py "$COPY_LOG" forkof "$MAIN_LOG"
python3 scripts/assert_log.py "$COPY_LOG" field fork.loadedSave.kind stop
python3 scripts/assert_log.py "$COPY_LOG" continuity
python3 scripts/assert_log.py "$MAIN_LOG" continuity

step "boot H: back in the first folder -> OK, the copy listed, export bundles both chains"
BOOT_COMMAND="audit export" boot H
python3 scripts/assert_log.py "$MAIN_LOG" verdict OK
python3 scripts/assert_log.py "$MAIN_LOG" hascopy "$COPY_LOG"
python3 - "$(dirname "$MAIN_LOG")/export" "$(basename "$(dirname "$COPY_LOG")")" <<'EOF3'
import glob, json, sys, zipfile
export_dir, copy_uuid = sys.argv[1], sys.argv[2]
zips = sorted(glob.glob(export_dir + "/*.zip"))
assert zips, f"no export zip in {export_dir}"
with zipfile.ZipFile(zips[-1]) as bundle:
    chains = json.loads(bundle.read("chains.json"))["chains"]
    names = bundle.namelist()
assert len(chains) == 2, chains
copy = [c for c in chains if c["uuid"] == copy_uuid]
assert copy and copy[0]["parentLinkOk"] is True, chains
assert all(c["verdict"] == "OK" for c in chains), chains
assert any(n.startswith(f"chains/{copy_uuid}/log/") for n in names), names
print(f"ok: export has both chains, fork link intact ({zips[-1]})")
EOF3
rm -rf "$SERVER/world-copy"

step "boot C: restore world backup -> WORLD_ROLLBACK"
rm -rf "$SERVER/world" && cp -r "$SCRATCH/world-backup" "$SERVER/world"
boot C
python3 scripts/assert_log.py "$(logdir)" verdict WORLD_ROLLBACK
python3 scripts/assert_log.py "$(logdir)" rollback
python3 scripts/assert_log.py "$(logdir)" field loadedSave.kind stop
python3 scripts/assert_log.py "$(logdir)" field loadedSave.savedSidx 1
python3 scripts/assert_log.py "$(logdir)" continuity

step "boot C2: restore the backup again + strip the clean-stop line to fake a crash -> still WORLD_ROLLBACK"
rm -rf "$SERVER/world" && cp -r "$SCRATCH/world-backup" "$SERVER/world"
python3 - "$(ls -t "$(logdir)"/*.jsonl | head -1)" <<'EOF2'
import json, sys
p = sys.argv[1]
lines = open(p, 'rb').read().rstrip(b'\n').split(b'\n')
assert json.loads(lines[-1])['t'] == 'session_end', 'newest log does not end on session_end'
open(p, 'wb').write(b'\n'.join(lines[:-1]) + b'\n')
EOF2
python3 scripts/assert_log.py "$(logdir)" unclean
boot C2
python3 scripts/assert_log.py "$(logdir)" verdict WORLD_ROLLBACK
python3 scripts/assert_log.py "$(logdir)" crashed
python3 scripts/assert_log.py "$(logdir)" continuity

step "boot D: newest log file hidden -> LOG_TRUNCATED"
NEWEST=$(ls -t "$(logdir)"/*.jsonl | head -1)
mv "$NEWEST" "$SCRATCH/hidden.jsonl"
boot D
python3 scripts/assert_log.py "$(logdir)" verdict LOG_TRUNCATED

step "byte-edit drill: chain must break at the tampered line (no boot)"
TARGET=$(ls "$(logdir)"/*.jsonl | head -1)
cp "$TARGET" "$SCRATCH/pristine.jsonl"
python3 - "$TARGET" <<'EOF'
import sys
p = sys.argv[1]
raw = open(p, 'rb').read()
t = raw.replace(b'"t":"command"', b'"t":"commanx"', 1)
assert t != raw, "no command line found to tamper"
open(p, 'wb').write(t)
EOF
if python3 scripts/assert_log.py "$(logdir)" continuity > "$SCRATCH/tamper.out" 2>&1; then
    echo "FAILED: tampered chain passed continuity"; exit 1
fi
grep -q "chain break" "$SCRATCH/tamper.out" || { echo "FAILED: wrong tamper failure"; cat "$SCRATCH/tamper.out"; exit 1; }
echo "ok: tamper detected ($(cat "$SCRATCH/tamper.out"))"
cp "$SCRATCH/pristine.jsonl" "$TARGET"

step "boot E+F: save-all, then kill -9 -> CRASH_RECOVERY from the logged save"
(echo "y"; sleep 30; echo "save-all"; sleep 60) | ./gradlew runServer --console=plain > "$SCRATCH/E.log" 2>&1 &
GRADLE_PID=$!
for _ in $(seq 1 60); do grep -q "audit session" "$SCRATCH/E.log" 2>/dev/null && break; sleep 2; done
for _ in $(seq 1 40); do grep -q '"t":"world_saved"' "$(ls -t "$(logdir)"/*.jsonl | head -1)" && break; sleep 1; done
sleep 2
# Kill ONLY the java process whose cwd is OUR run/server — never other Minecraft instances on the
# box. Matched by cwd, not cmdline: RFG launches the JVM via an @argfile, so class names don't
# appear in the command line.
KILLED=0
for pid in $(pgrep java || true); do
    if [ "$(readlink -f "/proc/$pid/cwd" 2>/dev/null)" = "$(readlink -f "$SERVER")" ]; then
        kill -9 "$pid" && KILLED=1 || true
    fi
done
[ "$KILLED" = 1 ] || { echo "FAILED: crash drill found no server process to kill"; exit 1; }
kill -9 "$GRADLE_PID" 2>/dev/null || true
wait "$GRADLE_PID" 2>/dev/null || true
sleep 3
python3 scripts/assert_log.py "$(logdir)" unclean
boot F
python3 scripts/assert_log.py "$(logdir)" verdict CRASH_RECOVERY
python3 scripts/assert_log.py "$(logdir)" crashed
python3 scripts/assert_log.py "$(logdir)" field loadedSave.kind save

echo
echo "ALL DRILLS PASSED"
