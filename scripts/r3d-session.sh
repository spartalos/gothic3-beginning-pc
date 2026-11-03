#!/bin/bash
# Interactive 3D tuning / screenshot session (docs/render3d.md "Dev tooling"):
# plays the intro of walk-ticks.script, then executes script commands appended to out/r3d/cmd.txt, e.g.
#   echo "warp 1 20 20" >> out/r3d/cmd.txt; echo "waitticks 25" >> out/r3d/cmd.txt; echo "shot village" >> out/r3d/cmd.txt
#   (shots land in out/r3d/s/shot_<name>.png; "reload3d" re-reads render3d/tiles.properties; "exit" ends the session)
cd "$(dirname "$0")/.."
mkdir -p out/r3d
: > out/r3d/cmd.txt
{ head -75 scripts/walk-ticks.script; echo "waitticks 5"; echo "repl out/r3d/cmd.txt"; } > out/r3d/session.script
exec ./gradlew :desktop:run -q -Dg3.debug=true -Dg3.render3d.dump=out/r3d/dump \
  --args="--view3d --hidden --no-audio --scale 2 --frame-every 100000000 --frames-dir out/r3d/s --script out/r3d/session.script --deterministic $*"
