# Rendering performance

The renderer keeps the game's simulation cadence and rules intact. The work
budget for a 144 Hz interface is 6.94 ms per paint. Static menus and waiting
lobbies do not need continuous painting; input and changed information request
new frames.

## Measurements

Measured on 2026-09-07 with an Apple M1 running Linux/Asahi, JBR
25.0.2+10-b329.117 and the OpenGL pipeline. The host has 16 GiB of unified memory
and a 60 Hz display. These are paint costs, including Java2D submission sync,
not measured 144 Hz scanout or a claim about an actual 8 GiB macOS machine.

The baseline is commit `c6a4402`. Both versions used the same owned Tides of
Darkness pack, 40 warm-up paints and 180 measured paints per scene. The display
processes used `-Xms64m -Xmx256m` inside a 2 GiB memory cgroup with no swap.
Timing comparisons ran sequentially. The benchmark also reports allocations
on the painting thread; that figure excludes driver memory and other threads.

At 1280 by 800 logical pixels with 2x device scaling (2560 by 1600 backing
pixels), all three menu samples meet the 6.94 ms budget at the 95th percentile:

| Scene | Before median / p95, ms | After median / p95, ms | Before / after bytes per paint |
|---|---:|---:|---:|
| Main menu | 40.57 / 63.91 | 3.94 / 5.21 | 14,953,778 / 8,888 |
| Map menu | 42.59 / 64.26 | 3.85 / 5.61 | 14,965,118 / 6,576 |
| Lobby | 52.12 / 63.08 | 3.94 / 6.20 | 15,006,729 / 25,453 |

The menu process's peak resident memory fell from 477 MiB to 235 MiB. Retained
heap after collection was 21 MiB before and 16 MiB after. These measurements
include the benchmark and the JVM, and exclude an audio session and the
separate launcher.

At 2560 by 1600 logical pixels and 1x device scaling, main-menu median paint
time fell from 86.94 ms to 3.58 ms. The map menu and lobby fell from 86.22 and
89.56 ms to 3.47 and 2.97 ms respectively. Their measured p95 values after the
change were 6.11, 5.41 and 4.73 ms.

Generating a growing stone panel at 3x scale fell from 71.42 ms median to
0.54 ms in the headless resize probe. Its sampled pixel witness was identical
before and after (`2879587974e9e69e`). Text measurement no longer constructs a
scratch raster for each label: the width probe fell from about 1,370 bytes to
32 bytes per call, and fitting a long name fell from about 3,170 to 820 bytes.

## The memory failure

The baseline did not finish the expanded display benchmark. Advancing the
palette at zoom 1 exhausted its 2 GiB cgroup at 07:42:38 UTC. The kernel reported
1,712,111,616 bytes of shmem and 1,679,687,680 unevictable bytes; the Java2D queue
thread invoked the OOM killer, and the process printed an Asahi VM-bind failure.
Its Java heap limit was only 256 MiB. A heap limit alone therefore did not bound
this failure.

The updated build completed that workload with a cgroup peak of about 455 MiB.
A separate 2x-scale stress run constructed, painted, panned and retired 30
matches, advancing each world 120 cycles. It completed with a nominal 256 MiB
heap limit and a 1.5 GiB cgroup limit without swap. Peak resident memory was
443 MiB, with a 609 MiB cgroup peak; retained heap after collection was 16 MiB.
Event dispatch runs between transitions, as it does during play, so queued
resize events do not artificially retain every retired screen.

The old zoom-1 static terrain blit was already fast: 5.52 ms median versus
4.48 ms with bounded terrain pieces in the final large-display run. The main
benefit is bounded memory during palette cycling. The updated large-display
game samples were about 4.5-5.6 ms median, with p95 values of 6.7-7.9 ms. This
is not a claim that every gameplay frame fits a 144 Hz budget. Unit simulation
and authored movie cadence are unchanged.

A 400-unit battle at 1280 by 800 with 2x device scaling completed using a
512 MiB heap. At zoom 2, median/p95 paint times were 4.42/6.25 ms; with palette
animation they were 4.42/6.00 ms. A simulation tick plus paint took 11.31/21.21 ms,
within the ordinary 30 Hz simulation budget. Cold palette phases at zoom 1
and panning still produced p95 spikes around 34-54 ms. Peak resident memory was
672 MiB and cgroup peak memory 842 MiB. Those cold-frame stalls remain an
optimization opportunity, rather than a reason to increase cache limits
without measuring the memory cost.

The baseline and updated headless ALAMO runs both ended at cycle 220 with
simulation hash `9e80782fd60587af` and frame CRC `bd7e794f`. The 400-unit battle
retained its `6098ad31c2b3dfed` simulation hash and `c68f97a2` frame CRC across
the terrain optimizations. The simulation sources were not changed.

## What is bounded and reused

- Menus and movies upload source pixels at one to one before scaling on the
  device. Device scaling is accounted for so a Retina intermediate does not
  consume four times its intended storage or change the sampling footprint.
  Surface loss has bounded retries and a software fallback. Resize, device
  changes and screen removal flush obsolete surfaces.
- The indexed map stays in software. Terrain is cached as immutable 512-pixel
  pieces, keyed by the palette colours each piece actually uses. Water does
  not invalidate unrelated grass. Changed ground invalidates every retained
  palette phase of the affected pieces.
- Palette expansion uses a lookup table and bulk raster writes. A clipped
  unscaled copy primes each new device image before its first transformed
  draw, avoiding the managed-image cache's initial software path.
- Terrain pictures have a 32 MiB raster budget, unit sprites 64 MiB, and stone
  textures 32 MiB, with entry limits as well. Eviction flushes managed device
  copies. These are raster budgets, not a total-process or total-VRAM limit.
- Stone resizing copies the existing grain and computes only new rows and
  columns. Generation uses one scratch row, not another full-size raster.
- Font metrics and standard faces are reused. The minimap is updated in bulk.
  Software fog uses reusable translucent tiles; device rendering retains the
  faster native rectangle primitive.
- Idle lobbies continue polling the network but repaint only changed display
  state. The launcher progress animation follows elapsed time at the display
  cadence, capped around 144 Hz, and stops when hidden. The game's starting
  heap reservation is 64 MiB; its existing 2 GiB maximum remains available.

## Reproduce

Configure `CHONKCRAFT_ASSET_PACK` with a readable owned pack containing
`ALAMO.PUD`, then build the application and benchmark classes:

```sh
scripts/jbr/with-jbr-25.sh mvn -pl desktop -am -DskipTests package
```

Run a software reference without opening a window:

```sh
scripts/jbr/with-jbr-25.sh java -Xms64m -Xmx256m \
  -Djava.awt.headless=true \
  -cp desktop/target/test-classes:desktop/target/chonkcraft-desktop-0.1.0-SNAPSHOT-app.jar \
  net.chonkbase.chonkcraft.desktop.RenderBenchmark \
  --mode=all --width=1280 --height=800
```

On Linux, isolate a display run with an OS memory limit. This is especially
necessary when testing the baseline: `-Xmx` does not contain its reproduced
graphics-memory growth.

```sh
systemd-run --user --scope -p MemoryMax=2G -p MemorySwapMax=0 \
  scripts/jbr/with-jbr-25.sh java -Xms64m -Xmx256m \
  -Dsun.java2d.opengl=True -Dsun.java2d.uiScale=2 \
  -cp desktop/target/test-classes:desktop/target/chonkcraft-desktop-0.1.0-SNAPSHOT-app.jar \
  net.chonkbase.chonkcraft.desktop.RenderBenchmark \
  --display --mode=all --width=1280 --height=800
```

On macOS, use the same Java invocation with the normal Metal pipeline
(`-Dsun.java2d.metal=true`) in place of the Linux OpenGL switch. A display run
opens and resizes a temporary test window. Run one benchmark at a time.
Modes are `menus`, `game`, `stone`, `soak`, and `all` (everything except the
longer soak). `--frames`, `--warmup`, `--width`, `--height`, `--sessions` and
`--battle` accept positive integers. `--battle=400 --mode=game` uses the game's
ordinary battle showcase and command machinery. The probe does not open an
audio device, save a game, record a session or connect to other players.

For a transition stress run, use `--mode=soak --sessions=30`. For allocation
stacks, add these JVM arguments before the class name:

```sh
-XX:FlightRecorderOptions=stackdepth=128 \
-XX:StartFlightRecording=filename=target/render.jfr,settings=profile
```

Compare an older shaded application JAR by putting it after
`desktop/target/test-classes` on the classpath. Keep the benchmark class,
arguments, source pack, heap/OS limits, scale and pipeline identical. The game
probe prints a simulation hash and a software frame CRC after the same number
of ticks. The stone probe prints a sampled picture witness.

`PixelScalerTest` and `TerrainViewTest` compare software pixels across scaling,
panning, palette changes, terrain changes and cache release. The optional
`PixelScalerDisplayCheck` compares the accelerated scaler with the original
two-pass renderer on the same device, including resize and release/recreation:

```sh
scripts/jbr/with-jbr-25.sh java -Xmx256m \
  -Dsun.java2d.opengl=True -Dsun.java2d.uiScale=2 \
  -cp desktop/target/test-classes:desktop/target/chonkcraft-desktop-0.1.0-SNAPSHOT-app.jar \
  net.chonkbase.chonkcraft.desktop.PixelScalerDisplayCheck
```

Forty changing frames passed at both 1x and 2x scaling. GPU interpolation
varied from the software transform by at most 2 out of 255 per colour channel;
geometry and image content agreed. Actual macOS Metal timing and 144 Hz
scanout remain hardware validation work.

The focused rendering and lifecycle run passed 76 tests with no skips, and
all 49 launcher tests passed. The complete desktop run with the owned classic
pack ran 370 tests with eight skips; its three failures exactly matched the
existing expected-failure identities. The complete data-free reactor ran
2,983 tests with 1,339 skips and the same 88 expected engine failures. Its
failure-identity gate passed; the inherited engine skip-count mismatch remains
(1,010 actual versus 989 recorded). No failure baselines were enlarged for this
work. These runs do not substitute for the external BNE and Opus fixtures.
