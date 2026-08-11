# Launcher and game updates

## What users run

The native installer contains one application entry point: the ChonkCraft
launcher. The launcher owns media import, ChonkPack selection, game-version
maintenance and child-process startup. The game JAR is deliberately not the
native entry point, and game versions are not a player-facing choice.

Every installer includes a complete game JAR. That bundled build is copied into
the launcher's version library and remains playable without a network. On each
launcher start, an asynchronous check asks `https://updates.chonkbase.net` for
new game code. A successful update is downloaded, authenticated, installed and
made current automatically before Play is enabled. A timeout, missing server,
bad TLS response, malformed
catalog, untrusted key, signature failure, size mismatch, hash mismatch or
interrupted download leaves the current verified game and every ChonkPack intact.

The launcher also checks the authenticated channel immediately after every
clean game exit. Multiplayer build rejection offers **Quit to Update**; that
closes only the child game, returns control to the still-running launcher, and
triggers this check. The update is installed and selected without asking the
player to find a download or choose between versions.

The launcher itself and its embedded trust keys still ship in a signed native
installer. OTA updates replace game code, not the signed launcher or bundled
runtime. A launcher protocol or UI change therefore requires a new DMG.

## Player-visible release history

The game-code row includes **Release Notes**. After the launcher observes a
new authenticated publication, a restrained **New / What's New** cue remains
there until the player opens it; the update never interrupts Play with a
modal. The history window lists the newest publication first and remains
available from the last verified local cache when the network is unavailable.

Release history is not trusted as arbitrary web content. The signed update
payload authorizes an immutable relative history URL, exact byte count and
SHA-256. The launcher verifies all three before parsing a bounded catalog and
atomically replacing its cache. A bad or unavailable history response cannot
erase the previous verified history or prevent a verified game from running.

## Trust and atomicity

`latest.properties` is a `chonkcraft-signed-release-1` envelope containing a
base64 release payload and Ed25519 signature. The payload authorizes:

- a safe monotonic version identifier;
- an immutable, relative JAR URL;
- the exact byte count and SHA-256;
- the source Git revision and player-visible notes;
- an immutable release-history URL, exact byte count and SHA-256.

The matching public key is embedded in the signed launcher. The private key is
only the GitHub Actions secret
`CHONKCRAFT_OTA_ED25519_PRIVATE_KEY_BASE64`. HTTPS protects privacy and normal
transport integrity; the Ed25519 signature means compromise of the static web
server alone cannot authorize executable game code.

Installation happens under `~/.chonkcraft/work`, verifies before promotion and
renames within the same filesystem. A process-wide file lock serializes two
launchers. Reusing a version label cannot pin stale bytes: the installed JAR is
checked against the signed size and hash and replaced safely when different.
The prior directory is retained until the replacement rename succeeds.

An automatically installed version is marked pending. A clean exit or fifteen
seconds of healthy process lifetime confirms it. A non-zero early exit restores
the previous verified build. A small bounded internal cache exists only for
automatic crash rollback; it is never exposed as a version library or selector.

The exact installed release identifier is passed to the child as both
`chonkcraft.version` and `chonkcraft.network.build`. The first is player-facing;
the second is multiplayer admission identity. They intentionally match for
published builds so gameplay changes cannot accidentally connect to an older
lockstep peer.

## Publication

`.github/workflows/publish-game-update.yml` runs automatically for game-code
changes on `master` and can also be dispatched manually. Automatic versions use
UTC calendar versioning (`YYYY.MMDD.<workflow-run-number>`). This is monotonic,
makes every publication immutable, and remains independent of an installer's
human-facing marketing version.

The workflow derives a concise title and change list from the commits in the
push. `CHONKCRAFT_RELEASE_TITLE` and `CHONKCRAFT_RELEASE_NOTES` remain explicit
inputs for a curated or manually dispatched publication. Before signing, the
pipeline uses the production launcher's embedded trust keys to fetch the prior
history, appends the new entry, and writes a content-addressed replacement.

The workflow:

1. builds with the pinned JBR 25;
2. verifies and carries forward the published release history;
3. creates a content-addressed JAR, history and signed catalog;
4. makes a fresh production launcher install that local catalog and history;
5. loads a namespace-scoped Kubernetes credential;
6. uploads and verifies both immutable assets;
7. atomically replaces `latest.properties` last;
8. installs again from the public HTTPS URL in another clean launcher home;
9. retains the signed catalog and history as a 90-day workflow receipt.

The serving stack is declared in `infra/k8s/updates`. It uses a retained Linode
block volume, an unprivileged read-only nginx container, cert-manager TLS and
`updates.chonkbase.net`. JARs and release histories are immutable-cached; the
small signed pointer is never cached.

The native-release workflow publishes a complete platform set under
`/downloads/<version>/`: notarized macOS DMG, Trusted Signed Windows MSI and
Linux AppImage, each with a SHA-256 receipt. It refreshes stable `/downloads/latest/`
names and atomically replaces `/downloads/latest.json` only after every remote
byte verifies. The public download page reads this catalog and the current
content-addressed release-history catalog. Cross-origin reads are limited to the
non-executable signed pointer, immutable release-note text and installer JSON;
game JAR responses do not expose browser CORS. The legacy `latest-macos.properties`
pointer remains available. The same narrowly scoped Kubernetes credential is used.

## Recovery and key rotation

To republish a failed workflow, rerun it. An immutable version may only contain
the same bytes; use a new version for changed bytes. To roll users back, fix the
engine and publish a higher version rather than pointing the signed channel at
an older number. Users already retain their last working local version.

For signing-key rotation, add the next public key to
`launcher/src/main/resources/chonkcraft-update-keys.properties`, ship and allow
adoption of a newly signed installer, then change `CHONKCRAFT_OTA_KEY_ID` and
the private-key secret. Keep the old public key through the transition. Never
copy a private key into the repository, a container image or the Kubernetes
volume.
