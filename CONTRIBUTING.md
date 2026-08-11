# Contributing

How this codebase thinks. Read this before writing anything.

This is not a game inspired by Warcraft II. It is a **port**, and the target is
parity: the same numbers, the same order of operations, the same behaviour,
including the parts that look like mistakes. Almost every rule below follows
from that one fact.

**Parity with what, exactly: the Battle.net Edition of Warcraft II, the retail
game.** Formal GPL ancestry and attribution are in README. Ancestor project
names do not belong in implementation identifiers, configuration or other
current-tree prose.

Where the retail binary can be read, it is the answer. Historical GPL source
is provenance and a corroborating witness, not a competing behavior target or
runtime input. Where nobody has read the binary yet, inherited behavior is an
unverified default that any retail evidence overrides. The harness in
`tools/bne-harness/` exists to establish that evidence.

Two consequences that catch people out:

- **A deviation from inherited behavior towards retail is not a deviation.** It is the
  point, and it does not need the three-part apology below. What it needs is
  the retail evidence: the function, the address, or the capture. Deviations
  from *retail* are the ones to document.
- **"Upstream" is ambiguous and should not be used on its own.** Say
  "retail", "the native binary", "historical GPL source", or the exact
  vendored runtime project and mean it.

For getting a machine set up, see
[docs/development-setup.md](docs/development-setup.md). For what is ported and
what is not, see [docs/architecture.md](docs/architecture.md).

## Contents

- [The recurring bug](#the-recurring-bug)
- [Where things live](#where-things-live)
- [Javadoc: explain the current contract](#javadoc-explain-the-current-contract)
- [Comments explain what was broken, not what the code says](#comments-explain-what-was-broken-not-what-the-code-says)
- [Deviations are allowed, and must be documented](#deviations-are-allowed-and-must-be-documented)
- [Tests assert behaviour, never declarations](#tests-assert-behaviour-never-declarations)
- [Prove it with a probe before you fix it](#prove-it-with-a-probe-before-you-fix-it)
- [Determinism](#determinism)
- [Project status and findings](#project-status-and-findings)
- [Working several changes at once](#working-several-changes-at-once)
- [Commit messages](#commit-messages)
- [House style](#house-style)
- [The workflow, end to end](#the-workflow-end-to-end)

## The recurring bug

One failure shape accounts for many defects in this kind of port: a field is
parsed out of real data, given an accessor, documented, and then never read.
Recognising that shape is an important part of the job.

Real instances: `HarvestFromOutside`; `ResourceInfo.step`, which made chopping
instant; `UnitType.boxWidth`, which made moving units unclickable; the animation
runner's `sound` result, which made every battle silent; `ImpactMissile` and
`ImpactSound` on fifteen missile types, which made every shot land invisibly and
in silence; `NumBounces` on three, which cost a dragon two thirds of its damage;
`DefineBurningBuilding`, so a keep at a tenth of its health looked untouched.

Every one of those parsed correctly. Every one of them would have passed a test
that checked the parse. That is why the rules about tests below are as strict as
they are.

Use the authenticated playability gates and the retail comparison harness to
find behavior that is present in the original game but absent here. Confirm a
finding with an observable witness before changing production code.

## Where things live

Module order in the root pom is dependency order.

| Module | What belongs in it |
|---|---|
| `assetpack/` | The asset pack format: manifest, zip container, and the codecs payloads are stored in. **Zero dependencies, including on `data/`.** Nothing about Warcraft II belongs here. |
| `runtime/` | `net.chonkbase.runtime`, **vendored** from `seven-days-to-tomorrow`. Fixed-step timing, Java2D pipeline selection, fullscreen, SDL input, the PCM bus mixer. |
| `data/` | Archive reading, graphics, tilesets, audio, PUD maps, ISO/BIN and classic HFS images, and XMI-to-MIDI conversion. |
| `extractor/` | Warcraft II installation to pack. Depends on `assetpack` and `data` and on **nothing** in `engine` or `desktop`; `IsolationTest` enforces it. |
| `launcher/` | The first screen: source-media import, graphics-pack selection, game-version updates and the managed local library. Depends on `extractor`, not on the game. |
| `engine/` | The deterministic native Java game simulation. **Has no concept of a Warcraft II installation directory**; it reads an `AssetSource`. `NoInstallDirectoryTest` enforces it. |
| `desktop/` | The replaceable game entry point and its screens. Same rule and its own `NoInstallDirectoryTest`; it may *tell a player* where to point `-Dwc2.install.dir`, and may not itself go looking. |

Five things about that table trip people up.

**The wall between `extractor/` and the game is the point, not tidiness.** The
extractor produces a pack; the game consumes one; neither can see the other.
If the extractor could see the engine it would drift into producing whatever
today's engine happens to want, and the pack would stop being a contract an
artist can target and become a private cache. `extractor/IsolationTest` reads
the module's own sources and its pom and fails the build on any mention of
`engine`, `desktop` or `runtime`. Do not weaken it; if the extractor needs
something the game has, that something belongs in `assetpack/` or `data/`.

**The game has no concept of an installation directory.** The engine and the
desktop layer read an `AssetSource`, and that is the whole of what they know
about where the data comes from. Locating a 1995 install -- the `DATA`
subdirectory, the DOS upper-case names, the Mac renames, the CD lying beside
it -- is the extractor's job, and `data/.../source/InstallSource` is the only
class allowed to do it. `Warcraft2Install` is package-private inside that
package so nothing else can even name the type.

This is the same wall as the extractor's, from the other side, and it is
enforced the same way: `engine/NoInstallDirectoryTest` and
`desktop/NoInstallDirectoryTest` read their own module's sources, main and
test, and fail the build on a reference to `Warcraft2Install`, `WarArchive`,
`CdAudio` or `CdImage`. The reason is not tidiness. A source can be an asset
pack: one file, built years after the discs went in a drawer, possibly
repainted. A pack has no directory to walk, so every reach past the source is
a line that works today and cannot work for the player the pack exists for.

**What those tests forbid is a reference to one of four types, not the strings
`wc2.install.dir` or `WC2_INSTALL_DIR`.** Those two appear in the skip message
of every real-data test and in the help the launcher prints, and they must stay
exactly as they are: `scripts/ci/check-test-skips.py` greps for that wording to
classify a skip, and docs/development-setup.md documents it. Telling a person
where to put a path is not the same as holding one. Deleting a skip message to
turn one of these tests green breaks CI and fixes nothing; each test has a
second case that says so out loud.

**`assetpack/` may not depend on `data/` either.** It is a game-agnostic
format. The Warcraft II half of the conversion -- rebuilding a run-length
sprite entry from a stored PNG -- lives in `data/.../source/EntryCodec.java`,
which is where both sides of that conversion have to sit together so they can
never disagree.

**`runtime/` is not yours to edit.** It is kept source-identical to upstream so
that `scripts/sync-runtime.sh` is a plain diff. If a runtime change is needed,
it belongs in Seven Days to Tomorrow and then syncs across. This is why
`AudioOutputDriverTest`'s inherited timing flake is not patched here.
ChonkCraft-specific code goes in `engine/`, never in `runtime/`.

**There is no `game/` module.** There was one, declared in the reactor with no
sources at all; its pom described `generated/` and `ported/` packages that
never existed, so the layout read as though a ChonkCraft content layer was present.
It was removed. Content-layer work lands in `engine/` and `desktop/`.

The generated definitions at
`engine/src/main/java/.../engine/generated/` are compiled Java and are part of
the native game-data path. They require no interpreter or external source
checkout. See docs/architecture.md for the ownership boundary.

**`engine/` package names describe current ownership.** Put map state in
`engine.map`, actions in the system that executes them, presentation state in
`engine.ui`, and serialization in `engine.save`. New code belongs beside the
state and invariants it owns, not beside the location of an older implementation.

## Javadoc: explain the current contract

Javadoc is durable only when it helps a reader understand the code that exists
today. A class or non-obvious method should state:

- what game responsibility it owns;
- the invariant or ordering constraint that is easy to break;
- the player-visible consequence that makes the rule matter; and
- authenticated BNE evidence when precision matters: a retail symbol or
  address, fixture, cycle, measured value, or captured state transition.

Do not cite historical source filenames or line numbers. They age immediately,
cannot be verified from this repository, and turn comments into an archaeology
index instead of documentation. `@see` remains reserved for live intra-project
links. Generated files identify their durable generator or source table and say
how to regenerate them.

A useful class comment looks like this:

```java
/**
 * A projectile on its way somewhere.
 *
 * <p>Travel time is part of combat state. An arrow that arrives on the frame
 * it was loosed makes an archer a melee unit with reach; one that takes half a
 * second lets its target die, move, or be healed before impact.
 *
 * <p>Direction changes must select a frame from the projectile's declared
 * orientation set; the lifecycle gate verifies every playable projectile.
 */
```

This documents ownership, behavior, and proof without depending on another
source tree.

## Comments explain what was broken, not what the code says

The most distinctive habit in this codebase: comments narrate the defect the
code fixes, in terms of **what a player saw**, with numbers where numbers were
measured.

```java
     * <p>Not training. This used to call {@link #orderTrain}, which charges the
     * cost and then puts a whole new building on the ground beside the old one
     * -- and since the spot search only ever tested a single square, a four by
     * four Keep was jammed against the Town Hall that was supposed to have
     * become it, overlapping whatever stood there, with the original still
     * standing. Every tier of both tech trees behaved that way.
     *
     * <p>Upstream counts to the new type's time cost and then transforms the
     * unit where it stands.
```
-- `engine/.../World.java`

```java
        // It used to treat an unimplemented predicate as true. That is the
        // difference between "not implemented" and "yes", and the campaign
        // triggers are written as conditions: a mission that says "you win
        // when the three heroes reach the circle of power" calls
        // IfRescuedNearUnit, which this port has never had, and so it was
        // won on the first evaluation -- one second in, before a shot was
        // fired. Fifteen of the fifty-two missions ended in under twenty
        // seconds this way, some in victory and some in defeat depending on
        // which unimplemented call their triggers happened to reach for.
```
-- `engine/.../script/ScriptEnvironment.java`

```java
            // Argument two, the picture the briefing is read off. Every mission
            // names one of ten illustrated pages and this call read past it,
            // which is why all fifty-two briefings shared the menu's scroll.
```
-- `engine/.../GameData.java`

What this style is doing: the next person to read that line needs to know why
it cannot simply be simplified back. "Charges the cost and puts a new building
beside the old one" is a fact about the code; "every tier of both tech trees
behaved that way" is why the fix must stay.

Corollaries:

- **No tag comments.** There is not one `TODO`, `FIXME`, `NOTE:`, `XXX` or
  `beware` of the project's own anywhere in the tree. (The single `FIXME` that
  greps is a quotation of upstream's, inside a Javadoc explaining what upstream
  does.) Open work belongs in a focused failing test or a current work item,
  not in a comment where it cannot be verified.
- Write prose. `used to` and `which is why` are the house idioms.
- Numbers beat adjectives. "Fifteen of the fifty-two missions", "3018ns per
  unit-cycle against 244", "eleven missile types name an explosion".

## Deviations are allowed, and must be documented

Parity is the goal, not a straitjacket. When the port has to differ, say so in
the Javadoc, in three parts: **state the deviation, state upstream's rule, bound
the observable difference.**

```java
 * <p>Driven from the game loop rather than from inside the simulation, and
 * that is the one place this departs from upstream. Upstream credits the unit
 * that struck the last blow, because it is standing right there when the blow
 * lands; nothing in this port records who killed what, so a death is credited
 * to every active player that counted the dead unit as an enemy. In a two
 * sided game -- which every campaign mission and every skirmish is -- the two
 * rules give the same answer. In a three sided one an ally is credited for a
 * kill it did not make.
```
-- `engine/.../ScoreKeeper.java`, before the deviation was closed

The vocabulary the codebase actually uses is `departs from retail`,
`deliberate`/`deliberately`, and `on purpose`. Whichever words you pick, the
third part is the one people skip and the one that matters: a deviation whose
consequences are not bounded is a bug waiting to be rediscovered.

Deviations forced by the port's own architecture get the same treatment:

```java
     * <p>The retail rule reads the unit's state directly. The pathfinder does
     * not know about units, so this implementation carries
     * the same four answers instead.
```
-- `engine/.../pathfinder/PathFinder.java`

**Reference defects are documented, not silently reproduced or silently fixed.**
`WarArchive` deliberately tolerates malformed entries and says why.
`docs/architecture.md` records the one map script that will not parse because
its source data contains an unterminated string literal, notes the observable
effect, and bounds the compatibility decision.

## Tests assert behaviour, never declarations

**A test that checks a value was parsed is worthless in this repository.** Every
bug in [the recurring shape](#the-recurring-bug) parsed correctly. The test must
drive the thing and look at what came out.

The rule is stated inside the suite itself:

```java
/**
 * What a player sees and hears when a shot lands, a building falls, or a unit
 * dies.
 *
 * <p>Everything here was parsed from the shipped data and read by nothing. The
 * recurring shape of the fault is a field with no callers: {@code ImpactMissile}
 * and {@code ImpactSound} on fifteen missile types, {@code NumBounces} on three,
 * {@code Indestructible} on eight unit types. A test that asked whether the
 * value had been parsed would have passed against every one of them, so nothing
 * here asks that -- each test drives the simulation and looks at what came out.
 */
```
-- `engine/src/test/java/net/chonkbase/chonkcraft/engine/CombatFeedbackTest.java`

Four sub-rules, each with a real example.

### Start from the entry point the player uses, not the method you fixed

```java
 * <p>{@code World.board} was written, documented and had no callers. The one
 * method that could put a unit on a transport could not be reached from the
 * game at all, so a right click on a boat was read as an order to walk into
 * the sea and the unit stopped at the water's edge. Nothing failed and nothing
 * said anything; it simply was not possible.
 *
 * <p>These start from the order rather than from {@code board}, because the
 * order is the part that was missing. A test calling {@code board} directly
 * would have passed throughout.
```
-- `engine/src/test/java/.../TransportBoardingTest.java`

The UI form of the same rule:

```java
 * <p>Every check here starts from {@code open()} and reaches its page by
 * clicking, because that is the path that broke: the pages were right and the
 * clicks landed somewhere else. A test that called {@code showSound()} directly
 * would have passed throughout.
```
-- `desktop/src/test/java/.../GameMenuTest.java`

### Count, do not just check -- an empty sweep passes vacuously

```java
 * <p>Every assertion here counts as well as checks. A test that walks what it
 * found and declares it all loadable passes perfectly when it found nothing,
 * which is exactly how a campaign could go fourteen missions without an ending
 * and fifty-two briefings without a background while the suite stayed green.
```
-- `engine/src/test/java/.../campaign/CampaignEndingTest.java`

If your test iterates over something discovered at run time, assert on the size
of what it discovered first.

### Prove the fixture is not degenerate, and prove the measurement discriminates

Assertions that guard the setup are routine:
`"the fixture must start out of range or it proves nothing"`. Beyond that, the
strongest tests ship an **inverted control** -- a second test that runs the old
behaviour and requires it to fail the property the new one passes:

```java
    /**
     * The same frame without the masks handed over, to prove the measurement
     * above distinguishes the two rather than passing on anything. This is the
     * old behaviour, and it must fail the property the new one passes.
     */
    void theMeasurementCatchesTheOldBehaviour() { ... }
```
-- `desktop/src/test/java/.../FogRenderingTest.java`

At minimum, **check that your new test fails without your fix.** The audit
records this as the standard: "Each has a test that was checked to fail without
its fix."

### Fixtures must be able to tell the rules apart

From the commit that fixed kill credit: "The fixtures are three-sided now: a
two-sided one cannot tell the two rules apart, which is how this survived, all
52 campaign missions being two-sided." If two candidate rules give the same
answer on your fixture, the fixture proves nothing about which one you
implemented.

### Never phrase the measurement in terms of the thing under test

The sharpest version of the rule above, and it is easy to walk into because the
wrong version reads better.

A sweep was written to check that everything on the map answers a click. Its
filter said, in effect, *for every unit that should be pointable*, and it
expressed "should be pointable" by calling `Unit.isPointable()` -- the method
the fix had just added. It found the bug. Then the fix was reverted to confirm
the test caught it, and **the test passed**: the same clause that hid the oil
patches from the game hid them from the measurement. Phrased independently --
on the map, not playing a death animation -- it reports 20,540 breaches against
the old behaviour and none against the new.

So: state the property in the domain's own terms, or in upstream's, and never by
calling the port method whose behaviour is the question. If your measurement and
your implementation share a predicate, you have written a tautology with test
scaffolding around it.

The same applies to a sweep that can sample nothing. The first draft of that
class also checked harvesters, read the wrong pair of fields off the unit, and
sampled **nought** workers across all fifty-two missions while passing. Assert
on the size of what you found before you assert anything about it.

### Mechanics

- `*Test.java` only. There is no `*IT.java`, no Failsafe, no integration lane.
  Real-data tests run under Surefire and skip when the data is absent.
- Classes are package-private (`class FooTest`), never `public`.
- Real-data classes are named `*RealDataTest` where a pure counterpart exists --
  `WarArchiveTest` beside `WarArchiveRealDataTest`.
- Method names are lowerCamelCase declarative sentences: subject first, no
  `test` prefix, no `should`/`given`/`when`. `aLandedShotLeavesItsImpactMissile`,
  `buildingsAreNotBandSelectable`, `worthlessKillsStillCount`.
- `@DisplayName` goes on methods, in lowercase prose about the *game*, never
  restating the method name: `"a four-by-four building blows up in its middle,
  not on its corner"`. Never on classes.
- Plain `@Test`. There is no `@Nested`, no `@ParameterizedTest`, no `@Disabled`,
  no `@Tag` anywhere, and adding the first one should be a deliberate choice.
- **Every assertion carries a message**, in game terms, naming the field at
  fault where there is one: `"the bolt vanished on arrival: ImpactMissile is
  parsed and never read"`.
- **There is no `src/test/resources` in the repository.** Fixtures are built in
  code or read from the real installation. Keep it that way: a committed fixture
  is a copy of the game's data that can drift from it.
- Every test class carries a Javadoc header written as a bug narrative -- what
  broke, why, and the upstream function that gets it right.

### Skipping

Tests needing external inputs gate on `Assumptions.assumeTrue(...)` and skip.
The idiom, copy-pasted into a private fixture method in each class:

```java
    private static GameData load() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }
```

`InstallSource`, not `Warcraft2Install`: the latter is package-private now, and
an engine or desktop test that named it would fail to compile and then fail
`NoInstallDirectoryTest` for good measure. `InstallSource.fromEnvironment()`
reads the same two configuration names in the same order and answers `null` the
same way. A test that does not specifically need a real installation -- one
that would be just as happy with a pack -- should ask
`AssetSource.fromEnvironment()` instead.

Use the same two messages verbatim; 79 classes already do, and grepping for
them is how you audit the skip surface.

**Understand what this means for a green build.** With all three inputs configured,
18 of 2,014 tests skip. With none, 651 skip and Maven still says `BUILD
SUCCESS`. See
[docs/development-setup.md](docs/development-setup.md#reading-a-test-run-skips-are-not-passes)
for the numbers and how to tell which run you got. Never report a passing run
without saying what it was configured with.

## Prove it with a probe before you fix it

Findings are established by running the real game data headless and reading the
output, **before** any code is changed. Keep the strength of each claim clear:

> All findings below were confirmed either by a headless probe against the real
> 1995 data or by reading the exact code path.

Those are not the same strength of claim and they must never be blurred. The
invocation for a throwaway probe is:

```bash
scripts/jbr/with-jbr-25.sh java \
  -cp <module target/classes>:<probe dir> \
  -Dwc2.install.dir="/path/to/Warcraft" \
  -Djava.awt.headless=true ProbeN
```

Probe sources are **throwaway and not committed**. What gets committed is the
focused regression test and the smallest durable explanation needed to preserve
the behavior.

**Where an oracle exists, read it rather than fitting it.** A probe that
measures the game's behaviour and a probe that reads the game's code are not
the same standard of evidence, and for the BNE port the second is available for
anything that is a table or a function in the executable. Every quantity read
that way has been right first time; several fitted from captures were wrong for
weeks -- a goal skirt assumed to be three by three when the code reads the
target's footprint from a table, a flag believed decisive that decides 193 of
252,508 records, a give-up believed to clear a route when it writes one byte.
Fitting is for mapping the game's state onto this port's, where no equivalent
exists to read. [tools/bne-harness/PARITY.md](tools/bne-harness/PARITY.md)
carries the grades of evidence and the gate each one takes.

Three probe shapes exist in the repository, and the choice between them matters:

- **Throwaway.** A one-off `main()` outside the tree, for an audit. Record its
  output in the audit report, then delete it.
- **A committed harness with `main()`, in a test root but not run by Surefire.**
  `engine/src/test/java/.../perf/AiProbe.java` runs all 52 missions headless in
  about ten seconds and answers "does a computer player actually do anything?".
  `engine/src/test/java/.../perf/SimulationProfile.java` stack-samples the
  simulation thread, because "wall time per cycle says a map is slow; it does
  not say which line is slow". Use this when the answer is a table a human
  reads.
- **A probe shaped as a JUnit test**, when a number is worth watching but there
  is no threshold worth asserting. `FogOscillationProbeTest` says so outright:
  "A probe, not an assertion suite... so that 'the fog flickers' can be answered
  with a number instead of an opinion."

The harness-plus-test pairing is a deliberate convention: the harness produces
the full table, and a `*Test` pins a subset. `AiCompetenceTest` explains the
choice -- "This pins eight of the fifty-two. The full sweep is ten seconds and
cost is not the reason for the subset -- a failure naming one of eight missions
is a great deal easier to act on than one naming a total over fifty-two."

Beware sampling artefacts. `AiProbe`'s own Javadoc records one: "Unit orders are
sampled every cycle, not every second... a once-a-second sample beats against
the AI's own cadence and reports that nothing ever attacks, which is both wrong
and very believable."

## Determinism

Lockstep multiplayer means two machines run the same cycles from the same state
and must reach the same result, exchanging only commands. Saved games mean the
same sequence has to be restorable from a single integer. Everything below
follows.

### One random stream, and `World` owns it

All simulation randomness goes through `World.syncRand()` and
`World.syncRand(int)`. The state is one `int` field, `randomSeed`, implementing
the game's plain linear congruential generator. Its Javadoc explains why the
crude generator is the *feature*:

```java
     * <p>Simplicity is the feature, not a compromise. Because one integer is
     * the entire state, a saved game restores the sequence by restoring that
     * integer, and two machines agree without exchanging anything. A better
     * generator would give up both: {@code java.util.Random.nextInt(bound)}
     * rejects and redraws for bounds that are not powers of two, so it does
     * not even consume a fixed amount of its own stream, and a save could not
     * put it back by counting.
```

Forbidden in the simulation path, and currently at **zero** occurrences in
`engine/src/main`: `Math.random`, `new Random(...)`, `System.nanoTime`.
`System.currentTimeMillis` appears eight times, all in `engine/.../network/`,
all packet timeouts and last-heard timestamps, never simulation state.

Sub-systems get handed the *same* stream rather than their own:

```java
     * <p>Upstream's animations call {@code SyncRand} directly, so a random
     * wait in an idle animation advances the same seed a damage roll does.
     * Giving the runner its own generator would look harmless and put two
     * machines out of step the first time a unit stood still.
```

The initial seed is `0x87654321` and starting from zero is not merely a
different game: the generator is multiplicative, so from zero its first several
draws are all zero and every opening blow lands for full nominal damage.

### Draws must be unconditional

The subtlest rule here, and the easiest to break by adding an early return:

```java
     * <p>{@code pick} is asked for a number exactly once and before anything
     * can return early, including when there is no such sound and when there is
     * no sound device at all. The callers draw from the simulation's own
     * synchronised generator, and a draw that happens on one machine and not on
     * another puts the two games on different numbers from then on -- which is
     * a desync, over a sound effect.
```
-- `engine/.../sound/GameAudio.java`

If a code path can draw, every code path must draw.

Related: **do not roll at parse time.** `Rand` in a spell script is carried as a
marker and rolled at impact, per unit struck. Rolling when the table is built
bakes one number in for the whole game *and* draws from the wrong generator.

### Iteration order

`LinkedHashMap` is the default map -- 56 uses across 23 files in
`engine/src/main`. Bare `HashMap` survives in exactly four places, all keyed
point lookups that are never iterated for simulation output. `TreeMap` and
`TreeSet` are used where sorted output is wanted.

Anything derived from a map that reaches the wire is sorted explicitly:

```java
     * <p>Sorted rather than left in declaration order. The roster can be taken
     * as the scripts give it because both machines read the same files in the
     * same sequence; these come from tables whose iteration order is a
     * property of the map they are held in, so the order is imposed here
     * instead of trusted.
```
-- `engine/.../network/CommandApplier.java`

Tie-breaks must be total. The pathfinder's open-set comparator ends on an index
specifically so that "two machines must choose the same path from the same
state", and its comment records that breaking on the index *alone* biased every
route towards the top-left of the map.

### One path into the simulation

```java
 * <p>Every path into the simulation goes through here, in single player as
 * well as multiplayer. That is deliberate: if the local player's clicks took a
 * shortcut, the two paths would drift and a desync would only show up on a
 * network game, which is the worst place to find it.
```
-- `engine/.../network/CommandApplier.java`

Never let the local player bypass the command path.

### The simulation does not play sounds or draw

```java
     * <p>The simulation does not play sounds: it says what happened and the
     * interface decides what that sounds like. Keeping it that way is what
     * lets a headless peer run the same cycles as a windowed one without an
     * audio device, and what keeps the lockstep hash free of anything to do
     * with playback.
```

This is also why almost the entire suite runs headless.

### The sync hash

What enters `SyncHash` is a judgement, and the file states it: unit positions,
health, orders, ownership, and the players' banks. **Not** animation frames, not
the camera, not sound -- "or two machines with different window sizes would
appear to disagree". Adding presentational state to the hash creates false
desyncs; leaving out simulation state hides real ones.

### The cycle rate

`World.CYCLES_PER_SECOND` is 30 and must stay 30. Unit speeds, build times and
spell durations are all counted in cycles, so it is behaviour, not a rendering
choice.

## Project status and findings

**[STATUS.md](STATUS.md) describes only the current release posture.** It is not
an investigation log, issue archive, or permanent defect ledger. Update it only
when the present user-facing posture changes.

The durable record of a fixed behavior is executable: a focused regression
test, the authenticated playability gate, or a retained comparison artifact.
Transient probes and exploratory notes stay outside the committed tree. If a
new defect is not fixed immediately, represent it with a focused failing test
or a current work item rather than an accumulating historical document.

`scripts/check-docs.py` fails the build on broken links, references to missing
documents, and skip-count claims that disagree with the executable gate. Run it
after touching documentation; CI runs it in check-only mode before the build.

**The rest of the documentation set**, which is meant to stay this small:

| Document | What it answers |
|---|---|
| [README.md](README.md) | What this is, and where to go next |
| [docs/development-setup.md](docs/development-setup.md) | How to get a machine building, testing and running it |
| [docs/architecture.md](docs/architecture.md) | Modules, the mapping onto the C++ source, and the decisions that are not obvious |
| [docs/ci.md](docs/ci.md) | Both workflows, the skip gate, the self-hosted runner, how to debug a red build |
| [docs/asset-pack-format.md](docs/asset-pack-format.md) | The pack format; what an artist has to produce |
| [runtime/README.md](runtime/README.md) | The vendored module and the rule for changing it |

If you find yourself wanting a seventh, check first whether it belongs in one of
these. Documents multiply, and a stale one is worse than a missing one because
it is read as current.

## Working several changes at once

Much of this port was built by agents working in parallel, and everything that
cost real time came from one cause: two of them editing the same file. Two were
given work that both needed `World.java`; the tree stopped compiling for both,
and one had to verify in a throwaway `git worktree` at `HEAD`. Another finished,
reported success, and its report noted five test failures it had proved were
caused by a second agent's in-flight edit to `GameData.java`.

None of that was a mistake by any individual. It was a partitioning mistake at
assignment time.

**So partition by file, not by topic.** Topic is not what decides whether two
changes can run at once; the set of files each has to touch is. The
counter-example is worth stating too: three changes that owned `SidePanel.java`,
`MenuScreen.java` and `GameMenu.java` respectively ran concurrently with no
interference and merged cleanly.

`engine/World.java` is the bottleneck and that is structural: it is a single
very large class holding the simulation, and almost any behavioural fix reaches
into it. **Give all of it to one person at a time**, even though it is the
biggest pile of work and splitting it looks like the obvious speed-up. The
recovery cost exceeds the parallelism gain every time.

`desktop/GameScreen.java` is the second one to watch, because a networking fix
that routes command-panel actions through the command sink is a `GameScreen`
edit wearing a different label.

Rules that were learned here the hard way:

1. **Do not edit a file outside what you were given.** If the fix needs one,
   stop and report the precise change instead. That report is enough for the
   owner to apply it in seconds, and it worked well every time it was done.
2. **Prove the finding before fixing it.** See "Prove it with a probe before you
   fix it" above. Three separate agents caught their own mistaken diagnoses this
   way, and several hypotheses that sounded obviously right were refuted by the
   numbers.
3. **Verify the test actually fails without the fix.** Two agents found their
   own tests were vacuous -- one because `Unit.setHitPoints` clamps to the type
   maximum, so a 10,000-point assertion against a 60-point footman is always
   true.
4. **Check the skip count, not the exit code.** A run without the game data
   passes. `scripts/ci/check-test-skips.py` is what CI gates on.
5. **Run the full suite before reporting.** If it is red because of someone
   else's in-flight edit, say which file, and re-run yours in a throwaway
   worktree at `HEAD` to prove your own work is green.

## Commit messages

Commits here are prose, written as an explanation, and the subject line is a
sentence about the game rather than about the code. Real subjects:

```
A building faces one way, not eight
Units in combat move at their own speed instead of a twelfth of it
Let the prisoners be rescued, and the nine missions be won
Make the gap audit actually audit, and print what the game could not find
Aim at the middle of things
```

The body explains what was broken, why it survived, and the retail rule.
Compare, from the body of "A building faces one way, not eight":

> The retail game resolves a missing NumDirections by unit role:
> buildings use one direction and mobile units use eight.
> The implementation defaulted everything to eight.
>
> [...] For the fifty-three buildings whose sheets hold two frames, four modulo
> two is zero and the right frame comes out by accident, **which is why this
> survived**.

"Why this survived" is the part to make sure you write. It is what stops the
same bug coming back.

Larger commits use bare-capital section headings in the body (`THE TWELVE-FOLD
SLOWDOWN.`, `KILL CREDIT.`) to separate independent pieces of work.

Every commit ends with the co-author trailer. Do not commit unless you were
asked to.

## House style

- **No emojis. Anywhere.** Not in code, comments, docs, commit messages, UI, or
  CI config. This is a hard rule and the tree is currently clean: 367 text files
  were scanned for emoji, decorative arrows and status glyphs, with zero
  matches.
- ASCII only in prose. `--` for an em-dash, not a Unicode one. `<ul><li>` in
  Javadoc rather than bullet characters.
- Four-space indent, no tabs. Java 25 language level.
- Sentences in comments and Javadoc, not fragments. Lowercase prose in
  `@DisplayName`.
- No tag comments -- see above.
- No new third-party dependencies without a reason that survives being written
  down. The player runtime is native Java and must not regain an embedded
  interpreter or external source checkout.

## The workflow, end to end

1. **Set the machine up** and prove it: `scripts/check-setup.sh`, then a full
   test run with all three inputs configured. Confirm you got 18 skips, not 651.
2. **Find something.** Run the authenticated playability gates, retail
   comparison harness, or the game itself and capture a witness.
3. **Confirm it with a probe** against the real 1995 data, headless, before
   changing any code. Get a number.
4. **Read the authority.** Prefer authenticated retail data, native capture,
   and the pinned executable. Historical GPL source is supporting evidence,
   not a runtime input.
5. **Write the test first, or at least check it fails without the fix.** Start
   from the entry point a player uses. Assert on what came out, never on what
   was parsed.
6. **Fix it**, citing the upstream construct in the Javadoc, explaining in a
   comment what was broken and why it survived, and documenting any deviation
   with its bounded consequence.
7. **Run the full suite** with all three inputs, and check the skip count.
8. **Update durable evidence.** Keep the regression test and comparison proof;
   update STATUS.md only if the current release posture changed.
9. **Write the commit message as an explanation.** Do not commit unless asked.
