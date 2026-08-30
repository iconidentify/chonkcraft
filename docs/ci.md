# Continuous integration

Two workflows in `.github/workflows/`, and one script that decides whether a
green Maven run meant anything.

- [What CI is for here](#what-ci-is-for-here)
- [`tests.yml`](#testsyml----the-suite-on-every-push)
- [The skip gate](#the-skip-gate)
- [The self-hosted runner](#the-self-hosted-runner)
- [`release.yml`](#releaseyml----installers-on-manual-dispatch)
- [Debugging a red run](#debugging-a-red-run)
- [Re-baselining the skip counts](#re-baselining-the-skip-counts)

## What CI is for here

**This suite does not fail when its inputs are missing.** Tests that need the
1995 Warcraft II data, an asset pack or the Opus vectors call
`Assumptions.assumeTrue(...)` and skip, and Maven reports `BUILD SUCCESS`
either way. With nothing configured, 1,208 of 2,830 tests skip and
the run takes 25 seconds.

So the exit code certifies almost nothing on its own, and a CI job that trusts
it converts "nobody is checking" into "something is checking" without either
being true. Everything below exists to make the exit code mean something.

The hosted lane cannot exercise tests requiring original game media. The
self-hosted authenticated lane exists so those tests run continuously instead
of being silently counted as successful skips.

## `tests.yml` -- the suite, on every push

Runs on push, pull request and manual dispatch. **Two jobs**, deliberately
different, and neither is redundant.

### `linux` -- hosted, no game data

`ubuntu-latest`. Checks out only this repository, verifies the native source
boundary, and runs without proprietary game data. It asserts the `data-free`
profile.

This is the newcomer's path. It proves the suite behaves correctly for somebody
who has cloned the repository and owns no copy of the game, which is most people
who will ever look at it.

Two things about it are deliberate:

**It asserts the runner's filesystem is case-sensitive before anything else.**
The reason this job is on Linux rather than macOS is to exercise asset path
resolution where case matters. On a case-insensitive filesystem one wrong
letter cannot fail. If GitHub ever moved the Ubuntu runners onto a
case-insensitive filesystem, that coverage would vanish without a symptom, so
the job fails loudly instead.

**`-Dwc2.install.dir` points at a path that does not exist**, so the run is
unambiguous: the data-gated tests skip because there is no data, not because of
anything subtler.

### Authenticated data -- private self-hosted inputs

Asserts the `full` profile: **27 skips of 2,830**, re-measured after the BNE
parity, explicit-team, multiplayer-wall, allied-vision, wood-command, team-outcome,
mine-collapse-audio, and gryphon order-handoff coverage additions against the runner's
authenticated classic retail installation. An exact Battle.net Edition source
has a different per-module inventory because its data lives in TOMEs and it
does not expose several classic loose-file fixtures. That layout is covered by
the separate `full-bne-with-playtest-saves` profile instead of being folded
into the classic count.
`scripts/ci/check-test-skips.py` explains each release-dependent assertion.
About nine minutes.

It builds an asset pack from the mounted installation and runs the whole suite
against the installation, pack and Opus vectors together.

## The skip gate

`scripts/ci/check-test-skips.py` is what decides both jobs. The test step is
`continue-on-error: true`; the gate runs after it and reads the **Surefire XML**
rather than Maven's console output, because the XML records what each module
actually ran. Grepping a log cannot tell "the engine module skipped 226 tests"
from "the engine module never ran", and those must not look alike.

Per module and in total:

- skipped must be **exactly** the recorded number
- tests run must be **at least** the recorded number

The asymmetry is deliberate. Adding a test that always runs raises the run count
and needs no change here. Adding a test that skips without game data raises the
skip count and turns CI red until somebody writes the new number down. That
second case is the one worth catching: it is how hundreds of tests came to be skippable
in the first place, one at a time, with nothing objecting.

| Profile | Inputs | Skips |
|---|---|---|
| `data-free` | none | 1,208 |
| `full` | installation, pack, Opus vectors | 27 |
| `full-with-playtest-saves` | full inputs plus three private save referees | 24 |
| `full-bne-with-playtest-saves` | exact BNE source, matching pack, Opus references, and three private save referees | 30 |

The twenty-seven that skip even in `full` want nothing anyone should have to
provide: seven need a display, five need a directory of 16-bit WAVs
(`-Dopus.music`) that this project does not ask anybody for, four are
fixture-sensitive, five need custom maps absent from the retail pack, three
need private playtest saves, one three-map recording matrix needs a BNE pack,
and the production service smoke runs only in the deploy workflow. One video
assertion depends on which retail release is mounted.

The `full-with-playtest-saves` profile is the exact local counterpart for a
development machine that has the three private save referees installed. It
runs those checks instead of weakening the hosted `full` profile when stronger
local coverage legitimately removes three skips.

The `full-bne-with-playtest-saves` profile is the exact counterpart for the
sealed Battle.net Edition source archive and its matching signed pack. Its 30
skips are not weaker authentication: they record BNE's TOME layout, data-only
CUE, absent classic loose-map fixtures, display-only checks, optional music
fixture, release-sensitive unit declarations, and opt-in production smoke.

## The failure gate

`scripts/ci/check-test-failures.py`, against the authenticated-data baseline
in `scripts/ci/expected-failures.txt` or the data-free baseline in
`scripts/ci/expected-failures-data-free.txt`.

The engine has a documented baseline failure set with all three inputs
configured, and both are stable. That is not a broken build. This is a parity
port whose suite is partly a specification written ahead of the code, so a
test asserting a behaviour nobody has ported yet does its job by failing.
`parity-clean.txt` treats LegacyEngine map traces this way -- as a list that only
grows on purpose, though it gates nothing and is a sanity check rather than a
statement about correctness -- and the skip gate treats
skips this way; failures had no equivalent, and the cost was that no run could
report a regression -- attributing a failure meant reverting a change and
re-running, and comparing two runs meant diffing test-method names by hand.

It compares the **set** of failing tests, not the count, because a count
cannot tell one test being fixed and another breaking from nothing happening.
Two things fail it, and they are different problems:

- a failure not in the file -- a regression, and the usual red;
- a file entry that now passes -- good news that still goes red, because a
  baseline nobody prunes drifts into fiction. Re-baseline with `--write` and
  say in the commit which test was fixed.

Each line carries a `verdict` and a `cites`. `cites` is mechanical and
rewritten on every `--write`. `verdict` is a human judgement, preserved across
`--write`, and follows CONTRIBUTING.md's rule that parity is with the retail
Battle.net Edition: `retail-verified` asserts something read off the binary and
is the count worth watching, `legacyEngine-default` asserts a LegacyEngine rule with
no retail evidence, `port-bug` is an ordinary defect, `unsorted` means nobody
has looked. Today that is 0, 42, 4 and 126.

**Both jobs install `ffmpeg` and `flac`, and the self-hosted one also installs
`fontconfig` and `fonts-dejavu-core`.** These are not "inputs" in the sense the
other four are -- nothing points at them -- but the baselines assume them:

- Without `ffmpeg` and `flac`, `FlacInteropTest`'s seven tests and `OggTest`'s
  two skip. Those nine are the only checks in the tree that compare this
  project's FLAC and Ogg writers against the reference decoders, and their
  absence is what had CI red for nine pushes.
- Without fonts, Java2D rasterises nothing, every piece of HUD lettering comes
  out blank, and `HudInkTest` fails. The runner container has no fonts at all.

## The self-hosted runner

The authenticated job uses a maintainer-managed Actions Runner Controller
scale set. Network addresses, host layout, runner registration, and recovery
procedures intentionally live in private infrastructure configuration rather
than this public repository.

Public pull requests and non-default branch pushes can never select this job.
It runs only after a commit reaches `master`, or when a maintainer explicitly
dispatches it. The hosted data-free job remains the required public path.

### The data never leaves the box

It is not downloaded, not stored in an Actions secret, and not present in this
repository. The private runner mounts the installation, authenticated
movie/speech media, and official Opus vectors read-only at `/opt/wc2`.

The workflow fetches RFC 6716 from the RFC Editor into its temporary directory
and passes that exact file to the tests; it does not silently depend on a stale
host copy.

Private Maven and runtime caches persist between runs. They contain no source
credentials or original game media.

The job asserts all seven required data files are readable **before it builds anything**,
so a broken mount says "the data is not there" rather than surfacing forty
minutes later as an arithmetic mismatch in the skip gate.

### The asset pack is built per run

Not cached. About 95 seconds including verification. The point is that
`PackParityTest` compares what *this commit's* extractor produces against the
installation, rather than a stale artifact: a cached pack would make that test
report a difference belonging to neither.

## `release.yml` -- installers, on manual dispatch

Manual on purpose: the artefacts are large and slow. macOS `.dmg`, Linux `.AppImage`
and Windows `.msi`, each stamped with a version passed as a workflow input. The
optional Linux job also retains the same signed game-update assets used by the
automatic publisher. See [packaging](development-setup.md#packaging) and
[game updates](game-updates.md).

The macOS job deliberately uses the same Apple-silicon host as ChonkBlocker:

```text
[self-hosted, macOS, ARM64, chonk-mac-arm64]
```

Because `iconidentify` is a personal account, GitHub runner registrations are
repository-scoped. The host therefore runs a separate service named
`m1-chonkcraft-arm64` from `~/actions-runners/chonkcraft-arm64`; it shares the
physical Mac and Homebrew/JBR caches without sharing another repository's
credentials or work directory.

It imports the Developer ID certificate into a job-scoped keychain, signs the
JBR's nested native code from the inside out, signs the application and DMG with
the hardened runtime, submits the DMG to Apple's notary service, staples the
ticket, and verifies both Gatekeeper and a real launcher render. The keychain is
removed in an `always()` step and the shared runner's search list is restored to
the System keychain, matching the concurrency-safe ChonkBlocker pattern.

The signed application is copied into the DMG with `hdiutil`. Do not replace
that step with `jpackage --type dmg --app-image`: the latter silently re-signed
the already-valid nested application ad-hoc in the first production run. The
macOS JNA dispatcher is likewise extracted from the shaded game JAR, signed as
a standalone bundle library, and supplied to both launcher and child JVM; Apple
notarization inspects native code inside archives and rejects an otherwise valid
top-level signature when an unsigned `.jnilib` remains in a JAR.

The lane is release-strict: an ad-hoc or unnotarized artifact is a failure, not
an upload with a misleading name. It accepts the same repository or
organization secrets as the sister projects:

| Purpose | Secrets |
|---|---|
| Developer ID | `MAC_CERTIFICATE_P12_BASE64`, `MAC_CERTIFICATE_PASSWORD`, `MAC_SIGNING_IDENTITY` |
| Preferred notarization | `APP_STORE_CONNECT_API_KEY_BASE64`, `APP_STORE_CONNECT_KEY_ID`, `APP_STORE_CONNECT_ISSUER_ID` |
| Notarization fallback | `APPLE_ID`, `APPLE_APP_PASSWORD`, `APPLE_TEAM_ID` |

The Developer ID identity may instead already be installed on a self-hosted
runner. Notarization still requires one complete credential set. A release
always builds the notarized Apple-silicon DMG, signed Windows x64 MSI, and Linux
x64 AppImage. The Linux lane runs on the private ARC fleet; the other native lanes can
move back to repository-scoped home runners without changing their proof steps.
Windows signs both the packaged launcher and the final MSI with Azure Trusted
Signing before either is accepted.

Only after all three lanes pass does the publish job create or update
`v<version>`, upload every installer and checksum, and copy the exact artifacts
to the retained release volume. Immutable versioned files land first, stable
download names follow, and `downloads/latest.json` moves last. The ChonkCraft
page reads that no-cache catalog, so a release updates the website without a
frontend redeploy and can never advertise a partial platform set. The workflow
never downloads or packages Warcraft II data.

## `publish-game-update.yml` -- automatic engine updates

A push to `master` that changes a game input builds the self-contained desktop
JAR and publishes it through `updates.chonkbase.net`. The workflow signs a
single catalog envelope, proves the production launcher installs it locally,
uploads the content-addressed JAR to the retained Linode volume, replaces the
catalog last, and finally repeats the install through the public HTTPS endpoint.
Its Kubernetes credential is restricted to listing the update-server pod and
executing the upload there; it cannot read Secrets or mutate workloads. Full
protocol, rollback, and operating instructions are in
[game-updates.md](game-updates.md).

## Debugging a red run

**Read which step failed first.** The order matters:

| Step | What a failure means |
|---|---|
| Assert the game data is mounted | the host mount is broken; nothing else is worth reading |
| Check the documentation | `check-docs.py` -- a dead link, missing document, or stale skip-count claim |
| Build | a real compilation failure |
| Run the test suite | `continue-on-error`, so this never fails the job by itself |
| Assert the run exercised what it should | the skip gate; see below |
| Fail if any test failed | a genuine test failure -- download the Surefire artifact |

**A skip-gate failure is not a test failure.** It says a different *set* of
tests ran than expected. Read the per-module table it prints:

- **A count went UP**: either an input was not configured for that run, or a new
  test skips without it. The first is a CI setup fault. The second is a decision
  that belongs in `check-test-skips.py`, written down with a reason.
- **A count went DOWN**: usually good news -- more ran than expected. Record the
  new floor rather than leaving the gate loose.

**Do not raise a number to make the build green.** Raise it because the new skip
is one the project accepts.

**Getting the actual failing test names:**

```bash
gh run view --log-failed --job=<job-id>
gh run download <run-id> -n surefire-reports-full   # or -data-free
```

Then read the XML rather than the log:

```bash
python3 -c "
import glob, xml.etree.ElementTree as ET
for f in glob.glob('*/target/surefire-reports/TEST-*.xml'):
    r = ET.parse(f).getroot()
    for tc in r.iter('testcase'):
        for bad in tc.findall('failure') + tc.findall('error'):
            print(tc.get('classname'), tc.get('name'), (bad.get('message') or '')[:200])
"
```

### A failure that only happens on Linux

This has happened twice and both times the test was at fault rather than the
port, so suspect the measurement first:

- `HudInkTest` counted pixels of one **exact** ARGB value. `GameFont` draws with
  antialiasing on, so only fully covered pixels land on the ink exactly, and how
  many of those a 13-point glyph produces is a question about the platform's
  hinting. It now counts within a tolerance that cannot confuse the two inks it
  distinguishes.
- The runner container had **no fonts at all**, so nothing rendered.

The general form: a test that measures a rendered pixel, a filesystem ordering,
or anything the JDK delegates to the host is measuring the host until proven
otherwise.

## Re-baselining the skip counts

When the counts legitimately change, re-measure **all three** profiles rather
than patching the one that failed:

```bash
scripts/run-tests.sh -Dwc2.install.dir=/nonexistent
scripts/ci/check-test-skips.py --profile data-free --write

scripts/run-tests.sh -Dwc2.install.dir="$WC2_INSTALL_DIR" \
    -Dchonkcraft.pack="$CHONKCRAFT_ASSET_PACK" -Dopus.testvectors="$OPUS_TESTVECTORS"
scripts/ci/check-test-skips.py --profile full --write
```

Then update the totals quoted in prose -- `README.md`,
[development-setup.md](development-setup.md), `scripts/check-setup.sh` and the
workflow's own comments. `scripts/check-docs.py` fails the build if a quoted
total disagrees with the gate's baselines, so this is checked rather than
remembered.
