# Native runtime audit

The player and build are native Java. The former interpreter, its Maven module,
runtime evaluator, executable resources, source-directory configuration, and
external content checkout have been removed.

## Runtime contract

The packaged game needs exactly two product inputs:

```text
verified game JAR + authenticated ChonkPack
```

The launcher owns both choices. It installs verified game releases atomically,
keeps the selected player-owned pack separate, and starts the game in a child
JVM. The original installation is an extractor input, not a game-runtime input.

## Native ownership

The game JAR owns typed catalogs and executable behavior for:

- units, animations, technologies, dependencies, buttons, and icons;
- tileset semantics, projectile definitions, construction, and spells;
- sound groups, unit voices, playlists, UI layout, and presentation policy;
- four campaigns, 52 mission wrappers, 137 triggers, and save/load;
- retail AI bytecode execution and deterministic simulation.

The authenticated pack owns player-supplied retail maps, archive entries,
sprites, palettes, tiles, fonts, voices, effects, movies, music, text tables,
and AI/action data decoded from the original media.

## Fail-closed proof

Run both checks from the repository root:

```bash
python3 scripts/check-source-boundary.py
python3 scripts/check-native-runtime.py
```

The first enforces the naming and attribution boundary. The second scans every
production module, runtime resource, launcher/release path, Maven reactor entry,
and any supplied JAR or ZIP artifact for an executable-script dependency or
interpreter class. Both checks run in CI and release verification.

The 17 playability lanes then prove that the authenticated pack can boot and
exercise the player-visible subsystems without a source checkout. The 52-case
precision gate remains the regression net for simulation changes.

## Provenance

Compiled catalogs retain their GPL provenance; compilation removes a runtime
dependency, not attribution. Formal ancestor attribution, the development
method, and the governing GPL version are in the README, the sole location for
ancestor project names in the current tree.
