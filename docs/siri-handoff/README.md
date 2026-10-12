# Siri idle ball handoff
- ref/ref_idle_12s.mp4: primary reference (stats baseline). Other ref/ clips: idle excerpts and full state loop.
- spec/: 设计师 spec (spec.md) + per-petal evidence images + frames.
- bili_tutorial_notes.md / aep_extract.md: notes from the Bilibili AE tutorial (原视频 bili-siri.mp4 65MB，未入库；原始链接未在本地找到).
- history/vNN/: side-by-side stills at 0.5/2.5/4.5s and 24-frame stats for ae-v11..v19.
- history/v20-step1..5/: ae-v20, rebuilt on v12 one change at a time; each has triptychs (reference | v12 | step) at 0.5/2.5/4.5s + stats.txt. step1 HDR additive + single tone map, step2 sharp blades, step3 no pink red/cyan overlap, step4 crisp wide-petal rims, step5 white overlap bloom. history/v20/ = step5 (side-by-side reference | v20 + ae_v20_stats.txt).
- history/v21/: step3 + wide-petal interior r^3 (feather back to 0.84..1.0), no overlap bloom, pure-white round core 0.2R x5, expo 0.7; triptychs (reference | v12 | v21) + ae_v21_stats.txt.
- tools/: v19 render/side-by-side/stats scripts (stats.py computes dark/red/blue/cyan/white shares); v20: render20.sh (render 24 frames + stats), stats20.py, tri20.py (triptych).
Left out (too big/not idle): bili-siri.mp4 (65MB), 4K-Siri-Visual-Animation-5-Minutes-360p.mp4 (10MB), Siri-button-animation-screensaver-360p.mp4 (6.9MB).
