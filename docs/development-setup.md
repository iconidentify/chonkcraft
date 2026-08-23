# Development setup

Everything needed to build, test and run this port, with Linux treated as a
first-class target rather than an afterthought.

The repository was developed on macOS on Apple silicon. Every claim below is
marked with how it was established. Where something could not be verified from
a macOS machine it says so rather than guessing, because a documented
uncertainty costs a newcomer minutes and a confident wrong instruction costs
them a day.

- **Verified here** means it was run on the development machine (macOS 27,
  aarch64) and the output was read.
- **Verified by inspection** means the artefact itself was fetched or read and
  checked, without executing it on the target platform.
- **Unverified** means it follows from the tool's own documentation and nobody
  has run it on Linux.

## Contents

- [The JDK](#the-jdk)
- [Maven](#maven)
- [The source boundary](#the-source-boundary)
- [The Warcraft II game data](#the-warcraft-ii-game-data)
- [The Opus test vectors](#the-opus-test-vectors)
- [Building and testing](#building-and-testing)
- [Reading a test run: skips are not passes](#reading-a-test-run-skips-are-not-passes)
- [Display, audio and headless](#display-audio-and-headless)
- [Running the game](#running-the-game)
- [Continuous integration](ci.md)
- [Packaging](#packaging)
- [Linux-specific risks not yet exercised](#linux-specific-risks-not-yet-exercised)

## The JDK

The project is pinned to a specific **JetBrains Runtime 25 SDK** build:
`jbrsdk-25.0.2-linux-x64-b329.117` and its siblings, from JetBrains release tag
`jbr-release-25.0.2b329.117`. The pin lives in one file,
[`scripts/jbr/jbr-25.env`](../scripts/jbr/jbr-25.env), as a URL and an SHA-512
per platform.

It is an **SDK** (`jbrsdk-`, not `jbr-`), which matters: the release lane needs
`jpackage` and `jlink`, and a plain JBR runtime does not ship them.

### The scripts are not macOS-specific

`scripts/jbr/with-jbr-25.sh` is a three-line wrapper. It runs
`install-jbr-25.sh`, exports the resulting `JAVA_HOME` and prepends its `bin`
to `PATH`, then `exec`s whatever you gave it. All the work is in
`scripts/jbr/lib-jbr-25.sh`, and `jbr_detect_platform` there handles
`linux:x64`, `linux:aarch64`, `macos:x64`, `macos:aarch64` and `windows:x64`.
Only `macos:*` gets the `Contents/Home` suffix; a Linux install is used as
extracted.

So on Linux the command is the same as on macOS:

```bash
scripts/jbr/with-jbr-25.sh mvn -DskipTests install
scripts/run-tests.sh
```

**Verified by inspection**, and thoroughly: the Linux x64 archive was
downloaded from the pinned URL on this machine (249,917,983 bytes), its
SHA-512 was computed and matched `CHONK_JBR_LINUX_X64_SHA512` exactly, the
tarball was confirmed to contain `bin/java`, `bin/jlink` and `bin/jpackage`,
and its `release` file was read:

```
JAVA_VERSION="25.0.2"
IMPLEMENTOR="JetBrains s.r.o."
JAVA_RUNTIME_VERSION="25.0.2+10-b329.117"
```

Those are the three strings `jbr_verify_home` checks, and they match
`CHONK_JBR_DISPLAY_VERSION`, `CHONK_JBR_VENDOR_EXPECTED` and
`CHONK_JBR_RUNTIME_VERSION`. The install path on Linux is therefore expected to
work end to end; what was not done is execute a Linux ELF, which cannot be done
from macOS.

The `linux-aarch64` URL was confirmed to resolve and serve 247,862,041 bytes.
Its checksum was **not** verified.

### Where it puts things

`install-jbr-25.sh` first looks for an already-good JDK, in this order:

1. `$CHONK_JBR_HOME`, if set
2. `~/Documents/jdks/jbrsdk-25.0.2-<platform>-b329.117`
3. `~/Documents/jdk/jbrsdk-25.0.2-<platform>-b329.117`
4. `$CHONK_JBR_INSTALL_ROOT`, default `~/.chonk/jdks`, same directory name

A candidate only counts if running its `java -XshowSettings:properties` reports
the pinned vendor, version and runtime version. Otherwise the archive is
downloaded to `~/.chonk/jdks/downloads`, checksummed, and extracted. Override
the roots with `CHONK_JBR_INSTALL_ROOT` and `CHONK_JBR_DOWNLOAD_ROOT`.

The download is roughly 250 MB and happens once.

### Host tools the JDK bootstrap needs

`bash`, `curl`, `tar`, and either `shasum` or `sha512sum`. The library prefers
`shasum` and falls back to `sha512sum`, which is the one Linux normally has, so
no coreutils extras are required. **Verified by inspection** of
`jbr_hash_file`.

### Checking what you actually have

```bash
scripts/jbr/with-jbr-25.sh scripts/jbr/assert-jbr-25.sh
```

Prints the release tag, vendor, `java.version`, `java.runtime.version`,
`java.home` and the `jpackage` version, and exits non-zero if any of the first
three is not the pin. Run this first when a build behaves oddly. **Verified
here** on macOS aarch64.

### Using a different JDK

Nothing in the poms requires JetBrains specifically; `maven.compiler.release`
is 25 and that is the hard requirement. Any JDK 25 will compile and test the
project. The pin exists so that this repository, ChonkBlocker and Seven Days to
Tomorrow fail in the same way on the same machine, and so that the packaging
lane has a known `jpackage`. If you set `JAVA_HOME` to some other JDK 25 and
call `mvn` directly, the build works and you have simply opted out of the pin.
The release scripts still call `with-jbr-25.sh` explicitly and will fetch the
pinned SDK regardless.

## Maven

Maven 3.9+ on `PATH`, installed by you. Nothing in the repository installs it.
**Verified here** with Maven 3.9.9. On Debian/Ubuntu, `apt install maven` gives
a recent enough version on current releases; check with `mvn -v` if in doubt.

Do not rely on the `java` your distribution's Maven package pulls in. Always go
through `scripts/jbr/with-jbr-25.sh`, or set `JAVA_HOME` yourself.

## The source boundary

The player runtime does not read an external source checkout or separately
installed content tree. Its complete input contract is a game JAR plus one
authenticated chonkpack.

Historical GPL source remains available through repository history and the
provenance described in README, but no sibling checkout, source-directory
property, interpreter, or content tree participates in a build or test. Run
`python3 scripts/check-source-boundary.py` and
`python3 scripts/check-native-runtime.py` to verify that invariant.

## The Warcraft II game data

Warcraft II data is not in this repository and never will be. The port reads
your own 1995 installation, either directly or through an asset pack built from
it.

Point at the installation with `-Dwc2.install.dir=/path/to/Warcraft` or the
environment variable `WC2_INSTALL_DIR`. Either the game directory or its `DATA`
subdirectory works. **This is what the test suite uses and it is not going
away.** Everything below about archives, disc images and the cache applies to
it unchanged.

### The launcher route

Players start with `scripts/run-launcher.sh` in a checkout, or `ChonkCraft` in
a native package. The first screen requires a graphics pack before Play is
enabled. Its graphics selector opens the ChonkPack manager. Drop an installed
directory, mounted physical CD, ZIP, ISO or Toast
image, BIN/CUE or IMG/CCD image, StuffIt, 7z, RAR or tar archive there, or use
the file chooser. The same manager selects, exports and deletes completed
packs. Cooked ISO9660, raw 2352-byte sectors and classic Mac HFS data tracks
are read directly. 7-Zip and unar are accepted as fallbacks for archive
containers. The launcher reports each normalization step, builds through the
same verifier as `scripts/build-asset-pack.sh`, records source-version and
checksum provenance, and installs the pack only after verification passes.

The managed library defaults to `~/.chonkcraft`. Set `chonkcraft.home` or
`CHONKCRAFT_HOME` to isolate it for testing. Packs and game versions are kept
separately, so updating game code does not rebuild or modify a pack.

### Or an asset pack

A pack is one file holding everything the game draws, plays and reads, in a
modern encoding. It is what a player gets, and it is what the game loads when
one is available.

```bash
scripts/build-asset-pack.sh                             # writes chonkcraft.chonkpack
scripts/build-asset-pack.sh --out /tmp/wc2.chonkpack    # somewhere else
CHONKCRAFT_ASSET_PACK=/tmp/wc2.chonkpack scripts/run-game.sh
```

`AssetSource.fromEnvironment()` checks `-Dchonkcraft.pack`, then
`CHONKCRAFT_ASSET_PACK`, then a `chonkcraft.chonkpack` sitting beside the installation,
and falls back to reading the installation directly. So a machine with only
`WC2_INSTALL_DIR` set behaves exactly as it did before this existed.

Building a pack takes about forty seconds and verifies itself: every one of its
1,355 assets is read back out of the finished file, rebuilt into the archive
entry the engine expects, and compared against what the installation produced.
On the reference installation with both discs, 1.0 GB of 1995 data becomes a
157 MB pack. The format is specified in
[asset-pack-format.md](asset-pack-format.md).

Two things to know when working on the pack path:

- **The extractor cannot see the game.** `extractor/IsolationTest` fails the
  build on any mention of `engine`, `desktop` or `runtime` in the module. If
  the extractor needs something the game has, it belongs in `assetpack/` or
  `data/`.
- **`PackParityTest` is the end-to-end proof and it needs a pack**, which the
  suite cannot build for itself. It skips unless you give it one:

  ```bash
  scripts/build-asset-pack.sh --out /tmp/wc2.chonkpack
  mvn -pl engine test -Dtest=PackParityTest \
      -Dchonkcraft.pack=/tmp/wc2.chonkpack -Dwc2.install.dir="$WC2_INSTALL_DIR"
  ```

### What is actually required

`Warcraft2Install` (in
[`data/src/main/java/net/chonkbase/chonkcraft/data/source/Warcraft2Install.java`](../data/src/main/java/net/chonkbase/chonkcraft/data/source/Warcraft2Install.java))
treats **`maindat.war`** as the marker. Without it, `fromEnvironment()` returns
`null` and everything that needs game data skips.

That class is **package-private**, and deliberately: it is the only thing in
the port that knows an installation is a directory, and
[`InstallSource`](../data/src/main/java/net/chonkbase/chonkcraft/data/source/InstallSource.java)
in the same package is the only class that can construct or name one. Callers
outside `data.source` -- the extractor, the tests, the game -- go through
`InstallSource.fromEnvironment()`, which reads the same two configuration names
in the same order. The engine and the desktop layer may not name it at all;
see CONTRIBUTING.md and the `NoInstallDirectoryTest` in each of those modules.

The archives it knows how to find, by PC name and by the Mac release's
different name:

| PC name | Mac name | Holds |
|---|---|---|
| `maindat.war` | `War Data` | Graphics, tilesets, fonts, cursors. Required |
| `strdat.war` | `War Strings` | The name table. Required |
| `rezdat.war` | `War Resources` | Interface art. Also how the expansion is detected, by file size |
| `sfxdat.sud` | `War Sounds` | Sound effects |
| `snddat.war` | `War Music` | Music. Not present in a DOS hard-disk install |
| `muddat.cud` | `War Movies` | Cutscenes. Not present in a DOS hard-disk install |

`.PUD` maps sit loose in the install root.

Resolution walks the root, then `DATA/`, then `data/`, and matches names
case-insensitively by explicit code (`findIgnoringCase`). This matters on Linux
far more than on macOS: a DOS install has `DATA/MAINDAT.WAR` in upper case,
ext4 is case-sensitive, and macOS's default APFS volume is not. The
install-locating code is careful about this and CI exercises the boundary on a
case-sensitive filesystem. See
[Linux-specific risks](#linux-specific-risks-not-yet-exercised).

### The CD, and why the install directory must be writable

The DOS release installs only part of itself. The music archive and the video
archive stay on the disc. If `snddat.war` or `muddat.cud` is not on disk,
`Warcraft2Install.fromDisc` scans the install root for `*.img`, `*.iso` or
`*.bin`, reads the raw-sector CloneCD image and its ISO 9660 filesystem
directly, and extracts the archive to:

```
<install root>/chonkcraft-cache/MUDDAT.CUD
<install root>/chonkcraft-cache/SNDDAT.WAR
```

So **the install directory must be writable** if you want music or cutscenes
from a DOS install with a disc image beside it. A read-only mount, or a copy
under `/usr/share`, will silently give you a game without either. This is not
Linux-specific, but a Linux user is more likely to mount game data read-only.

The reference installation used here looks like this:

```text
WarcrafD/
  ALAMO.PUD  CHANNEL.PUD  DEATH.PUD  ...      the shipped skirmish maps
  DATA/
    MAINDAT.WAR  REZDAT.WAR  SFXDAT.SUD  STRDAT.WAR
  cd/
    WC2TOD.img  WC2TOD.cue  WC2BTDP.img  ...  disc images
  chonkcraft-cache/
    MUDDAT.CUD  SNDDAT.WAR                    extracted from the images
```

## The Opus test vectors

The `assetpack` module ships a pure-Java Opus decoder and encoder, and the
claim it rests on is not "it sounds right" but "every pure-CELT packet in the
official RFC 6716 test vectors decodes bit-exact". That claim is checked by 21
tests, and those 21 skip without the vectors, which are a 20 MB download that
this repository does not carry:

```bash
curl -O https://opus-codec.org/static/testvectors/opus_testvectors.tar.gz
tar xzf opus_testvectors.tar.gz
curl -O https://www.rfc-editor.org/rfc/rfc6716.txt
scripts/run-tests.sh -Dopus.testvectors="$PWD/opus_testvectors" \
  -Dopus.rfc="$PWD/rfc6716.txt"
```

Ten allocation-table tests also read the public RFC text. Put `rfc6716.txt`
beside the vectors directory as above, or set `-Dopus.rfc` / `OPUS_RFC`.

This is the fourth external input, and the one most worth configuring per
megabyte: without it a codec that decodes the game's audio wrongly still gets
a green build.

There is a fifth, `-Dopus.music`, and it is deliberately *not* asked for. Five
tests in `CeltEncoderTest` -- determinism, misalignment, real-music round trip,
ffmpeg interop and achieved bitrate -- want a directory of 16-bit WAV files to
encode. A rip of the red book audio is a heavier ask than the other four
inputs, so those five are recorded as expected skips even in the `full`
profile. Point `-Dopus.music` at a directory of WAVs and they run.

## Building and testing

```bash
scripts/jbr/with-jbr-25.sh mvn -DskipTests install    # compile everything
scripts/run-tests.sh                                  # the full reactor test run
```

`scripts/run-tests.sh` forwards its arguments to Maven and then runs `test`, on
the pinned JDK. Configure the authenticated player input once:

```bash
export CHONKCRAFT_ASSET_PACK=/path/to/warcraft-ii-bne.chonkpack
scripts/run-tests.sh
```

Raw `WC2_INSTALL_DIR` remains an extractor/development input, not a shipped-game
input. The game and 17-lane playability certification never read a ChonkCraft
source directory.

`-Djava.awt.headless=true` is often written out in commands and audit notes. It
is redundant: the root pom already puts it in the Surefire `argLine`. Passing
it does no harm and makes the intent visible.

## Reading a test run: skips are not passes

Tests needing retail assets still use JUnit assumptions, so an ordinary Maven
run can be green with those tests skipped. Inspect the Surefire summaries. For
the player contract, run `scripts/run-bne-playability-gate.py`: all 17 selected
player/referee lanes must pass, any skip makes the receipt fail, and the runner
forces the retired ChonkCraft source property to a nonexistent path.

## Display, audio and headless

### What does not need a display

Almost everything, including the rendering tests. Twenty-odd tests in `desktop`
and `engine` paint into a `BufferedImage` and assert on pixels --
`FogRenderingTest`, `MenuRenderSweepTest`, `InfoPanelRenderTest`,
`SidePanelVisualTest`, `MissileRenderingTest`, `ImpactRenderingTest` and the
rest. Java2D's software pipeline handles all of it headless. `MenuRenderSweepTest`
additionally writes PNGs to `desktop/target/menu-qa` as diagnostics for a human
to look at; they are not what it asserts on.

Two places in main code guard the calls that genuinely throw without a display
(`GameCursors` asking for a custom cursor size, `SidePanel` adding an
`AWTEventListener`), so headless runs do not trip over them.

### What does need a display

Only real `JFrame` work: `AppWindowTest` and part of `PlatformFullscreenTest`.
On Linux you can run them:

```bash
xvfb-run -a scripts/run-tests.sh -Dsurefire.argLine=  ...
```

The empty `-Dsurefire.argLine=` is needed because the root pom hard-codes
`-Djava.awt.headless=true` into the `argLine`, and the tests skip on the
`GraphicsEnvironment.isHeadless()` check rather than on the presence of a
display. **Unverified**: this follows from reading the pom and the tests, and
has not been run.

Requires `xvfb` (`apt install xvfb`). If you have a real desktop session, the
same works without `xvfb-run`.

### Audio

**No test opens an audio device.** The runtime's audio tests drive a fake
device rather than `AudioSystem`; the MIDI tests use `javax.sound.midi` only to
parse and inspect sequences. So a Linux box with no sound card, no ALSA and no
PulseAudio runs the full suite.

The **game** does want audio, and fails soft without it:

- `JavaSoundPcmSink` acquires the output line on a single daemon lane with a
  deadline. A missing, failing or stalled device leaves the mixer running and
  accepting frames into silence, rather than hanging startup.
- `MusicPlayer` wraps `MidiSystem.getSequencer()` in a `try/catch`; no
  sequencer means no music and a running game.

On Linux, expect the default Java Sound line to go through ALSA. **Unverified**
which Linux audio stack the pinned JBR resolves to in practice, and whether
PulseAudio or PipeWire needs the ALSA compatibility layer installed. If the
game is silent, that is the first thing to check.

### Gamepads and SDL2

Controller support comes from `libsdl4j`, a JNA binding, in the vendored
runtime. `SdlNativeRuntime` fails soft when the native library is missing, so a
machine without SDL2 simply has no gamepad support. Keyboard and mouse do not
go through SDL.

On Debian/Ubuntu the native is `libsdl2-2.0-0`. **Unverified** on Linux.

Override the search path with `SEVEN_SDL_LIBRARY_PATH` or
`-Dseven.sdl.library.path`.

### The Java2D pipeline

`Java2DPipeline` sets rendering-backend system properties before AWT
initialises, chosen by operating system:

| OS | Default |
|---|---|
| macOS | Metal |
| Windows | Direct3D |
| Linux, BSD | **OpenGL** |
| anything else | software |

Override with `SEVEN_JAVA2D_PIPELINE=metal|opengl|xrender|d3d|software` or
`-Dseven.java2d.pipeline=`. If the game renders wrongly or crashes in the
driver on Linux, try `xrender` and then `software` before suspecting the port.
**Verified by inspection** of `Java2DPipeline.defaultForOs`; the OpenGL default
has not been exercised on Linux hardware.

### Fullscreen

`PlatformFullscreen.detectStrategy` gives macOS the `MAC_EAWT` strategy and
**everything else `BORDERLESS_BOUNDS`**, which is plain AWT. Linux therefore
needs none of the macOS `--add-opens=java.desktop/com.apple.eawt=ALL-UNNAMED`
plumbing, and `scripts/run-game.sh` already adds that flag only when `uname -s`
is `Darwin`. Force borderless anywhere with
`-Dseven.fullscreen.force.borderless=true`.

On Wayland, the JDK runs under XWayland. **Unverified** whether borderless
fullscreen behaves under a Wayland compositor.

## Running the game

```bash
export CHONKCRAFT_ASSET_PACK=/path/to/warcraft-ii-bne.chonkpack
scripts/run-launcher.sh
scripts/run-game.sh ALAMO.PUD
```

`run-launcher.sh` installs the current verified game JAR and starts it with the
selected pack. `run-game.sh` is the direct developer shortcut. Neither command
reads executable scripts, a source checkout or a separate content archive. Set
`CHONKCRAFT_SKIP_BUILD=1` to reuse the last build.

### Capturing a playtest bug

Press **Command-Shift-E** during play on macOS. You do not need the `Fn` key;
this shortcut uses the letter E, not an F-row key. On Windows or Linux use
**Ctrl-Shift-E**. The game prints a visible `evidence saved` confirmation and
writes one directory under:

```text
~/.chonkcraft/evidence/playtest-<timestamp>-c<cycle>/
```

The directory contains `screen.png`, a resumable `state.sav.gz`, and
`evidence.json`. The JSON records the map, mission, cycle, camera, selected and
nearby units, orders, AI behavior, targets, projectile position/frame/facing,
both RNG streams, and nearby visual tile codes plus simulation flags. Capture
it immediately when a projectile flips, a unit appears idle, or a ship seems
to enter shore. Ordinary saves remain small and unchanged.

The map/window/music flags remain `-Dchonkcraft.map`, `-Dchonkcraft.window` and
`-Dchonkcraft.music` for settings compatibility; those names do not identify a
source-tree dependency.

## Continuous integration

Moved to its own document: **[ci.md](ci.md)**. Both workflows, the skip gate,
the self-hosted runner and its data mounts, how to debug a red run and how to
re-baseline the counts.

The hosted, data-free job runs on every push and pull request. A private
authenticated job runs only for `master` pushes or a maintainer's manual
dispatch, using a read-only copy of the installation and authenticated pack.
It asserts 1,053 skips, so 1,578 tests actually run without exposing licensed
media to public pull-request code. The authenticated lane asserts 25 skips out
of 2,631 tests. Tests backed by retail sequences deliberately join the hosted
lane's skip inventory while running on the private authenticated runner.

## Packaging

Native packages bundle a trimmed JetBrains Runtime, so a player needs no Java.
They never include game data.

```bash
scripts/release/build-macos-app.sh --dmg        # macOS
scripts/release/build-linux-package.sh          # app image only
scripts/release/install-pinned-appimagetool.sh /tmp/appimagetool
scripts/release/build-linux-appimage.sh /tmp/appimagetool  # portable AppImage
scripts/release/build-windows-package.sh --msi  # Windows
scripts/release/build-update-assets.sh           # game jar and hash catalog
```

Each platform's script refuses to run anywhere else -- `jpackage` builds for the
platform it runs on, and there is no cross-compiling. The `Release Builds`
GitHub Actions workflow runs all three; it is `workflow_dispatch` only, on
purpose, because the artefacts are large.

The CI macOS lane runs on the shared `chonk-mac-arm64` Apple-silicon runner and
requires a real Developer ID identity plus Apple notarization credentials. It
does not fall back to an ad-hoc release. Use the same `MAC_CERTIFICATE_*` and
App Store Connect API-key secrets as ChonkBlocker, or the documented
`APPLE_ID`/`APPLE_APP_PASSWORD`/`APPLE_TEAM_ID` fallback. The resulting DMG is
signed, notarized, stapled, Gatekeeper-assessed and accompanied by a SHA-256
file. The optional `publish_macos_release` workflow input attaches both files
to `v<version>`; leave it disabled for an Actions-only test artifact.

`CHONKCRAFT_VERSION` stamps the public version, currently `0.1.1-beta11`.
`jpackage` receives a private nonzero seed to satisfy its validation; before
signing, the bundle metadata is replaced with the honest `0.1.0` marketing
version and an Actions run-number build value.

The update script writes a signed `latest.properties` plus a content-addressed
JAR under `desktop/target/dist/update`. The launcher resolves the JAR URL
relative to the catalog, checks its mandatory byte count and SHA-256, and only
then makes it the one current game.

Smoke-test the real macOS bundle, including its bundled Java executable,
launcher render, pack selection, child game process and one gameplay frame:

```bash
CHONKCRAFT_ASSET_PACK=/path/to/chonkcraft.chonkpack \
  scripts/release/verify-macos-app.sh desktop/target/dist/macos/ChonkCraft.app
```

The Linux release is a Type-2 x86-64 AppImage. CI downloads the same
checksum-pinned appimagetool release used by the sister project, inspects the
SquashFS payload, and launches it with `APPIMAGE_EXTRACT_AND_RUN=1` so the proof
does not depend on FUSE being available on the build host.

## Linux-specific risks not yet exercised

Written down so the next person knows where to look first, rather than
discovering them one at a time.

1. ~~**Case sensitivity.**~~ **Verified continuously.** The hosted Linux job
   asserts a case-sensitive filesystem and exercises the data-free path. The
   dedicated case-sensitivity script copies only the retail installation onto
   a case-sensitive APFS volume and runs the native data tests. That covers
   archive names and resource paths without any external source tree,
   including the DOS-uppercase `DATA/MAINDAT.WAR` layout. `Warcraft2Install`'s
   deliberate case-insensitive lookup and the `Locale.ROOT` normalisation in
   the `.PUD` and disc-image scans do hold up.

   Reproduce with `scripts/ci/check-case-sensitivity.sh`. Note the limit of
   the claim: this verifies **filesystem case sensitivity**, not Linux. Items
   3, 4 and 5 below are untouched by it. If assets still fail to load on Linux
   and load on macOS, `Main` prints unresolved asset paths at startup, which is
   the fastest way to see it.
2. ~~**`java.awt.headless` and the seven display tests.**~~ Partly closed. Both
   CI jobs run headless without `xvfb` and the seven skip as designed, which is
   the documented steady state; what remains unrun is the `xvfb-run` recipe that
   would make them execute. Related and now known: a container with no fonts
   installed renders no text at all, which is not a skip but a silent blank --
   see [ci.md](ci.md#the-skip-gate).
3. **Audio stack.** Which of ALSA, PulseAudio or PipeWire the pinned JBR's Java
   Sound resolves to, and whether a stalled device path behaves as the sink's
   recovery policy expects.
4. **OpenGL Java2D pipeline.** The Linux default. Untested on real drivers.
5. **`jpackage` deb/rpm host tooling.** See above.
6. **`scripts/sync-runtime.sh`** needs `rsync` and a checkout of
   `seven-days-to-tomorrow` beside this one; set `SEVEN_DAYS_DIR`. It is a
   diff tool for keeping `runtime/` source-identical to upstream, not part of
   the build.
7. ~~**No CI runs the test suite.**~~ **Closed.** `.github/workflows/tests.yml`
   now runs the suite on Linux on every push, and gates on the skip count
   rather than on Maven's exit code. See [ci.md](ci.md).

   **Verified, 2026-07-27**, and the first runs were findings rather than a
   clean bill. The hosted job had been red on master for nine consecutive
   pushes on the same step: nine assetpack tests skip on Linux for want of
   `ffmpeg` and `flac`, which the development Mac has from Homebrew. Both jobs
   install them now.

   A second job was added on a self-hosted runner that has the game data, so
   the `full` profile is asserted too: 23 of 2,421 tests skip, so 2,398 tests
   actually run. See [the self-hosted runner](ci.md#the-self-hosted-runner).
