#!/usr/bin/env bash
# Renders the orb and builds side-by-side comparisons against the idle reference clip.
# Usage: siriOrbPreview/compare.sh [outDir=/tmp/orb] [seconds=12] [fps=30]
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
out="${1:-/tmp/orb}"
seconds="${2:-12}"
fps="${3:-30}"
ref="$root/reference-raw/ref_idle_12s.mp4"

mkdir -p "$out"
(cd "$root" && ./gradlew --no-daemon -q -Pkotlin.compiler.execution.strategy=in-process \
    :siriOrbPreview:run --args="$out/frames 720 $fps $seconds 0")

ffmpeg -v error -y -framerate "$fps" -i "$out/frames/f_%04d.png" \
    -c:v libx264 -pix_fmt yuv420p -crf 18 "$out/orb.mp4"
ffmpeg -v error -y -i "$ref" -i "$out/orb.mp4" \
    -filter_complex "[0:v]trim=0:$seconds,setpts=PTS-STARTPTS,fps=$fps[a];[1:v]fps=$fps[b];[a][b]hstack" \
    -c:v libx264 -pix_fmt yuv420p -crf 20 "$out/side_by_side.mp4"
ffmpeg -v error -y -i "$out/side_by_side.mp4" \
    -vf "fps=1/2,scale=720:-1,tile=2x3" -frames:v 1 "$out/sheet.png"
echo "Wrote $out/orb.mp4, $out/side_by_side.mp4, $out/sheet.png"
