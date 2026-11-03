#!/bin/bash
# stop the rig server, wipe the tower dimension and run state, start it again, wait for boot
SP=${RIG:?set RIG to the rig folder}
REPO=${REPO:-$(cd "$(dirname "$0")/../.." && pwd)}
cd $SP/srv
echo "stop" >> console.txt
for i in $(seq 1 40); do pgrep -f KnotServer >/dev/null || break; sleep 2; done
rm -rf world/dimensions/cobbletowers/tower
rm -f world/data/cobbletowers_runs.dat world/data/cobbletowers_cells.dat world/data/cobbletowers_run_stats.dat world/data/cobbletowers_*pending*.dat
rm -f mods/CobbleTowers-*.jar
JAR=$(ls -t $REPO/build/libs/CobbleTowers-*+fabric-1.21.1.jar | head -1)   # the newest build, never a -sources jar
cp "$JAR" mods/
if [ -n "$RAIDS_JAR" ]; then rm -f mods/CobbleRaids-*.jar; cp "$RAIDS_JAR" mods/; fi   # a CobbleRaids build to test against
: > console.txt
(setsid bash $SP/srv_up.sh >/dev/null 2>&1 &)
for i in $(seq 1 60); do grep -aq "Done (" $SP/live.log && break; sleep 2; done
grep -a "Done (" $SP/live.log | tail -1
