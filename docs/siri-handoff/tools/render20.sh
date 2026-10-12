#!/bin/bash
# usage: render20.sh OUTDIR — renders the 24 idle frames (0.5 s steps, ae-v11 renderer) and writes OUTDIR/stats.txt
# needs the reference frames in /tmp/ae_v5_work/ref (r000.png..r359.png, 30 fps):
#   mkdir -p /tmp/ae_v5_work/ref && ffmpeg -i docs/siri-handoff/ref/ref_idle_12s.mp4 -start_number 0 /tmp/ae_v5_work/ref/r%03d.png
set -e
here=$(cd "$(dirname "$0")" && pwd); root=$(cd "$here/../../.." && pwd)
out=$(realpath -m "$1"); rm -rf "$out"
cd "$root/tools/siri-preview" && AEV=11 FPS=2 SECONDS=12 SIZE=${SIZE:-870} ../../gradlew -q -p . run --args="--sheets 6 --out $out"
python3 "$here/stats20.py" "$out" > "$out/stats.txt"; tail -3 "$out/stats.txt"
