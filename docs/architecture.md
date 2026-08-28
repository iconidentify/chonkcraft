# Architecture

What this port is made of and the decisions that are not obvious from the
source.

For how to build and run it see [development-setup.md](development-setup.md);
for the conventions the code follows see [CONTRIBUTING.md](../CONTRIBUTING.md);
for the present release posture see [STATUS.md](../STATUS.md).

- [Origins and authority](#origins-and-authority)
- [Module layout](#module-layout)
- [The launcher](#the-launcher)
- [The native data boundary](#the-native-data-boundary)
- [The generated definitions](#the-generated-definitions)
- [The vendored runtime](#the-vendored-runtime)
- [Game data](#game-data)
- [Where it stands](#where-it-stands)

## Origins and authority

ChonkCraft is self-contained source and native Java at build and runtime. Its
GPL ancestry and formal attribution are documented in the
[README](../README.md#provenance-attribution-and-license); those ancestor names
are intentionally kept out of implementation identifiers and configuration.

The behavioral authority is Warcraft II: Battle.net Edition 2.02b. Retail
archives supply player-owned data, the pinned Windows executable supplies
observable behavior, and authenticated captures connect findings to focused
Java tests. Historical GPL code remains provenance and supporting evidence,
but is not an external checkout, build input, runtime dependency, or competing
behavior profile.

## Module layout

Seven Maven modules, in reactor order.

| Module | What it is |
|---|---|
| `assetpack` | The pack container format and the codecs it needs, including a pure-Java Opus encoder and decoder |
| `runtime` | Vendored desktop foundation -- timing, Java2D, fullscreen, PCM mixer. See [the rule for changing it](../runtime/README.md) |
| `data` | Archive readers, sprite and tileset decoders, PUD maps, and the only code that knows what a 1995 installation directory looks like |
| `extractor` | Builds an asset pack from an installation. Isolated from the engine by a test that fails on a reference in the wrong direction |
| `launcher` | The durable first screen: source-media import, graphics-pack selection, game updates and child-process launch |
| `engine` | The simulation: units, orders, combat, pathfinding, fog, AI, spells, triggers, save and load |
| `desktop` | The replaceable game entry point and its screens, panels, input and sound policy |

Two walls are enforced by tests rather than remembered:

- **`engine` and `desktop` have no concept of an installation directory.**
  `NoInstallDirectoryTest` in each module reads its own sources and fails on a
  reference to `Warcraft2Install`, `WarArchive`, `CdAudio` or `CdImage`. They
  read an `AssetSource`; locating a 1995 install is `data`'s job alone.
- **The extractor cannot see the engine.** `IsolationTest` is the same wall from
  the other side.

## The native data boundary

The shipped runtime is deliberately two-file: the game JAR supplies behavior,
typed catalogs, mission declarations and presentation policy; the authenticated
BNE chonkpack supplies the player's retail maps, media and decoded archive
data. The former retired scripting language parser/interpreter module and script-content distribution
were deleted after field-complete differentials and player/referee gates. See
[native-data-boundary.md](native-data-boundary.md) for authority and provenance.

## Inside the engine: World and its Battle.net systems

The engine is retail Battle.net Edition 2.02b and nothing else. There is no
legacy compatibility profile, no flag to consult, and no second path to keep alive; the
field that used to say so, `World.battleNetProfile`, was a `private final
boolean` set to `true`, which made it a JLS constant variable that javac
folded at compile time. Every obsolete compatibility branch behind it had been compiled out of
the shipped game for as long as the flag had been true, and the source now
says what the bytecode already meant.

`World` remains the simulation's state owner and its cycle orchestrator. It
holds the unit table, the map, the players, both random streams, the missile
list and the per-cycle bookkeeping, and `tick` still drives the cycle. What it
no longer holds is the rules. Those live in eight package-private
collaborators beside it, each constructed once by `World` and each holding the
`World` it reads:

| Collaborator | The question it answers |
|---|---|
| `BattleNetIdleSystem` | What a unit does when it has been given nothing to do -- BNE's startup Still dispatcher and its animation markers |
| `BattleNetMovementSystem` | When a unit may take its next step, and where that step lands |
| `BattleNetCombatSystem` | Closing on something, swinging at it, and taking the blow back |
| `BattleNetTargetSelection` | What a unit may shoot at, and which of those it picks |
| `BattleNetProjectileSystem` | A shot from the cycle it is constructed to the cycle it lands |
| `BattleNetHarvestSystem` | A worker's round trip: out to the resource, and home with the load |
| `BattleNetConstructionSystem` | Putting a building up, and keeping it up |
| `BattleNetBuildingPlacement` | Where the computer decides to put a building |

The boundaries are drawn along the questions a parity investigation actually
asks. When the 52-case corpus reports that a peasant stepped a cycle early,
the file to open is the movement system; when a grunt attacked where retail
moved, it is combat; when a tanker never entered its platform, harvest. Before
this, all of them were one 24,000-line file, and finding the rule meant
knowing where in that file it had happened to be written.

Three rules keep the split honest:

- **The collaborators are package-private and so are their seams.** A
  collaborator reaches `World` state directly, so members it needs stopped
  being `private` -- but none became `public`. `engine.ai`, `desktop` and the
  tests see exactly the surface they saw before, because `World` keeps a
  one-line delegation for every entry point outside the package that used to
  call it.
- **No profile interface, no strategy hierarchy, no service locator.** There
  is one supported product, so runtime polymorphism would buy nothing and cost
  a layer of indirection between a divergence and the rule that caused it.
  Each collaborator is a `final class` with a `World` field and nothing else.
- **A collaborator may name another.** `combat` asks `targets` what to shoot
  at and `movement` how to close; `movement` asks `construction` to abandon a
  pending build. The dependency graph is not a tree and pretending otherwise
  would mean inventing events the native engine does not have.

### How the split was proved to change nothing

The rule was that a move may not alter behaviour, and each of the nine commits
carries the same three-part proof:

1. **Structural.** Every moved method body is byte-for-byte its original once
   the `world.` and `World.` qualifiers are stripped, and no qualified name
   shadows a local or a parameter inside the moved code. The exceptions are
   individually listed in the commit that made them -- `this` passed to an AI
   callback where it meant the `World`, and one record read through its own
   accessors rather than as fields.
2. **Bytecode**, for the profile removal only. Because the flag was
   constant-folded, a correct specialization had to leave every method's
   bytecode unchanged apart from the removed field, its `putfield` in the
   constructor and the two accessors. `javap -c -p` before and after says it
   did.
3. **Behavioural.** The 52-case Battle.net corpus is run at every step. All 52
   first divergences hold, and all 52 *full 1800-cycle* Java semantic traces
   keep their SHA-256 -- so nothing moved even past the accepted parity
   frontier, where the gate itself would not have looked.

## The launcher

The ownership rule for the retired scripting language-free product is specified in
[the native BNE data boundary](native-data-boundary.md): edition-authored data
belongs in the authenticated pack, while engine behavior and fixed application
presentation belong in the game JAR.

`launcher/` is the first process a packaged player runs. It owns
`~/.chonkcraft/`: verified graphics packs, complete game-version directories,
temporary import work and an atomically replaced choice file. The game is a
separate shaded jar under one version directory. Play starts that jar in a
child JVM with the selected pack and version content root, so installing a new
game version never replaces the running launcher and never touches a pack.
The compact first window contains only the active graphics pack, game-version
selector and Play. Its graphics control opens the ChonkPack manager, where
packs are imported by file chooser or drag and drop, selected, exported and
deleted. Pack creation records the selected source's release, container,
filename, byte length and SHA-256 in the manifest when those facts are
available; the temporary normalized directory is not treated as provenance.

The import boundary deliberately ends at the existing `PackBuilder`. A folder,
mounted CD, ZIP, StuffIt archive, ISO/Toast image,
BIN/CUE or IMG/CCD image is first normalized to a temporary installation that
`InstallSource` recognizes.
Cooked ISO9660, raw 2352-byte sectors and classic Mac HFS data tracks have
direct Java readers. External archive tools are a fallback for container
formats such as StuffIt; no external tool handles or converts the game assets.
Battle.net Edition's `INSTALL.EXE` is itself an MPQ and contains a second
`War2Dat.mpq`; the data module reads both in-process and presents their named
voices and movies as overlays on the classic numbered archives. This preserves
the edition's 16-bit unit effects and campaign narration instead of the
TOMEs' 8-bit copies, along with its 320x288, 16-bit stereo Smacker movies.
Its twenty 22.05 kHz stereo soundtrack recordings flow through the same
recorded-music interface as CD audio, and its 153 maps join the ordinary
`AssetSource` map list, which is what makes the single-player and multiplayer
menu entries available. Four BNE-only text tables are retained as standalone
supplemental pack assets.

The BNE music boundary is fail-closed. `InstallSource` requires the exact
twenty-name logical catalog, 22,050 Hz stereo PCM, positive frame counts and a
stable `INSTALL.EXE:Music\\*.WAV` origin. `PackVerifier` independently requires
twenty logical Opus assets at the 144 kbps policy and retains both the 22,050 Hz
source rate and Opus's 48 kHz decode rate. Main Menu and Orc Briefing are two
logical identities over one recording; the pack stores one physical payload
without losing either name, offset or length.

The pack becomes selectable only after `PackBuilder` reads it back and passes
its exact and perceptual verification.

Game updates use a signed pointer plus two immutable assets:
`latest.properties`, a content-addressed game JAR and a content-addressed
release-history catalog. The signed payload names each relative URL, exact byte
count and mandatory SHA-256. Downloads move into `versions/` or the launcher's
offline history cache only after verification.
The player-selected authenticated pack is passed separately and is never part
of an application update.

## Native catalogs and missions

The engine reads typed catalogs committed in the JAR for the roster, animation,
technology, buttons, presentation, tilesets, projectiles, construction, spells,
sound bindings, four campaigns and 52 mission wrappers. The mission catalog
contains 137 native trigger programs. Retail maps, media and `ai.bin` remain in
the authenticated pack. The former retired scripting language evaluator and generated-at-runtime
definition path were removed after complete differentials and protected gates.

Generated snapshots retain their actual historical GPL provenance; compilation is
a runtime-detachment mechanism, not a claim of independent retail authority.
New behavior is established from the pinned BNE executable and native captures.

## The vendored runtime

`runtime/` is a copy of `seven-days-to-tomorrow/runtime`, kept **source-identical**
so a sync is a plain diff. It supplies `FixedStepLoop` for simulation timing,
`Java2DPipeline` for backend selection, `PlatformFullscreen`, the SDL controller
hub and the PCM bus mixer. `src/video` and `src/sound` are ported onto these
rather than reimplemented.

Changes belong upstream and sync in; they do not start here. See
[runtime/README.md](../runtime/README.md) for the rule and what this port
actually uses.

Packaging follows ChonkBlocker: pinned JetBrains Runtime 25, `jpackage` with a
jlink-trimmed runtime that retains `bin/java` for the replaceable child game,
macOS `.dmg`, Windows `.msi`, and a portable Linux `.AppImage`.

## Game data

The port reads a Warcraft II installation, or an asset pack built from one. Both
are configured by system property or environment variable, and **tests skip
rather than fail without them** -- which means a green build can have verified
almost nothing. That is documented in full, with the counts that tell you which
kind of run you got, in
[development-setup.md](development-setup.md#reading-a-test-run-skips-are-not-passes)
and [ci.md](ci.md).

Extracted assets are never committed. They derive from the user's own copy.

The pack format is specified in [asset-pack-format.md](asset-pack-format.md). A
real pack built from a 1995 install is 157 MB against the installation's 1.04 GB,
and the game plays from it alone with output byte-identical to a run off the
installation.

### One deliberate divergence from wartool

`maindat.war` contains filler slots -- entries 28 to 32 in the DOS build --
whose offsets sit one byte apart while declaring multi-megabyte lengths.
`wartool` never notices, because it extracts by index from a fixed table and
those indices are not in it. Anything that sweeps the archive does notice, so
`WarArchive` rejects an entry with fewer than five bytes of room and returns
wartool's `EmptyEntry` placeholder for it.

## Where it stands

**The port plays.** Campaigns, skirmish maps, combat, economy, construction, fog
of war, computer opponents, spells, upgrades, sound, music, save and load, and
lockstep multiplayer all run against a real 1995 installation or an asset pack
built from one. All 52 campaign missions load, run and can be won.

The present release posture is summarized in [STATUS.md](../STATUS.md).
Executable tests and authenticated comparison evidence remain authoritative.

What is deliberately **not** ported: the map editor, replays, and the 741 `Q`
conversion rows.

The automated production gate runs two independent rendered game processes
through the public HTTPS/WSS matchmaker and relay, forces exact map replacement,
and proves 180 lockstep cycles converge to one world hash. A recorded match
between two physically separate player machines remains useful field
confirmation; it is no longer the only evidence for the public network path.

The test suite is **2,796 tests**. On a fully configured CI machine 27 skip; with
no external inputs 1,184 do. Both numbers matter and the difference between them is the
subject of [ci.md](ci.md).
