# The native BNE data boundary

The finished player contract is exactly two inputs:

```
chonkcraft-game.jar + authenticated BNE chonkpack
```

There is no external source checkout, script archive, evaluator or SMS loader
on the far side of this boundary.

## The pack owns edition data

The chonkpack contains bytes or decoded tables whose authority is the player's
authenticated Blizzard installation:

- numbered TOME archive entries and BNE named overlays, with their precedence;
- PUD maps and campaign media;
- sprites, palettes, tiles, fonts, cursors, voices, effects, movies and music;
- BNE text tables;
- `maindat` entry 277 (`ai.bin`) and entry 278 (action timing);
- any future structured retail table that can be decoded from those inputs.

A new structured table in the pack must have an explicit schema name and
version. Its representation remains generic bytes/text/table data; the generic
`assetpack` module must not learn Warcraft unit, spell, mission or AI types.
The engine is responsible for interpreting the schema.

Every derived pack object retains its source archive/container identity. A
missing retail object fails import or verification when it is part of the BNE
contract. Compatibility art and application chrome are never relabelled as
Blizzard media.

## The JAR owns behavior and presentation

The game JAR contains:

- simulation algorithms and fixed application behavior;
- independently transcribed BNE executable behavior, with address/capture
  provenance in code and tests;
- versioned Java DTOs/readers for structured pack tables;
- native title, menu, HUD and results presentation;
- immutable declarative resources only where the retail files do not directly
  encode the fact, with their real provenance and licence retained;
- native save/load and mission evaluators.

Black backgrounds, layout primitives and other synthetic presentation are
rendered by Java. For example, the historical GPL ancestor's `ui/black_title` is an OpenGL
workaround added in 2019, so Java paints that one-second background rather
than requesting a nonexistent retail asset.

## Authority order

For every migrated field, use the first authority that actually answers it:

1. decoded data in the authenticated BNE source;
2. pinned BNE 2.02b executable disassembly or authenticated native capture;
3. an independent, documented transcription with a focused behavioral proof;
4. historical GPL source only as a temporary differential comparison.

Generated Java is not automatically independent authority. If an accepted
resource remains derived from historical GPL definitions, its origin and licence
remain explicit; generation is not used to disguise provenance.

## Migration ledger

| Player-visible area | Current production source | Native destination | Retail authority | Status |
|---|---|---|---|---|
| recorded soundtrack | BNE pack with former synthetic fallback | pack + `GameAudio` | `INSTALL.EXE:Music\\*.WAV` | native pack complete |
| sound groups and unit voices | TSV resources | JAR resources/readers | retail media plus documented legacy differential | native complete |
| title sequence | fixed Java `TitleSequence` | fixed Java title model | retail movies plus native black background | native complete |
| units and animations | `GeneratedUnitRoster` and `GeneratedAnimations` through typed catalogs | complete native roster/model | historical GPL declarations, sealed after a 143-type/all-field/all-instruction differential; retail behavior remains governed by the precision tier | native runtime complete |
| global upgrades, effects and dependencies | generated snapshot of documented historical declarations | `UpgradeCatalog` in the JAR | historical GPL declarations, retained with provenance | native global runtime complete; mission-wrapper override seam remains open |
| buttons and icons | generated snapshot of the documented historical declarations | `GeneratedInterface` + `IconCatalog` in the JAR | historical GPL declarations, retained with provenance; retail art remains in the pack | native runtime complete; retail-authority replacement remains precision work |
| missiles and burning-building rows | generated snapshot of documented historical declarations | `MissileCatalog` in the JAR | historical GPL declarations plus pinned executable behavior | native runtime complete |
| construction animations | generated snapshot of documented historical declarations | `ConstructionCatalog` in the JAR | historical GPL declarations, retained with provenance; retail art remains in the pack | native runtime complete |
| spells | generated snapshot of documented historical declarations | `SpellCatalog` in the JAR | historical GPL declarations plus pinned retail dispatch/behavior evidence | native runtime complete; all declared effect behaviors are explicitly modelled and fail closed if an unknown effect appears |
| tileset semantics | `TilesetCatalog` + versioned `tilesets.tsv` | versioned terrain schema + native reader | historical GPL declarations sealed after an all-code differential; retail tile data and movement oracle | native runtime complete |
| UI layout and colour policy | typed `UiLayout`, `PlayerColours` and `FogOfWarSettings` | fixed Java presentation | GPL historical GPL layout declarations retained with provenance; retail pixels and executable behavior | native runtime complete |
| campaigns and 52 mission wrappers | `MissionDefinitionCatalog` + `missions.tsv` | versioned native mission declarations | retail PUD/campaign data plus sealed wrapper differential | native runtime complete: 52 missions, 137 triggers |
| mission triggers | typed postfix predicates/actions in `TriggerSystem` | native predicate/action evaluator | authenticated mission behavior | native runtime complete |
| retail AI | `ai.bin` bytecode and native managers | native bytecode/state machine only | `maindat` 277 and executable | native runtime complete for all campaign assignments |
| save/load | `chonkcraft-save` schema 3 + `NativeSaveReader` | versioned native save format | complete deterministic engine state plus portable terrain pictures | native runtime complete; historical schemas 1 and 2 remain migration-only |
| launcher/update content | verified game JAR plus selected pack | game artifact plus chosen pack | application release contract | native two-file runtime complete |

## Acceptance rule for each row

Before a legacy reader is removed, its replacement must have a player/referee
test, an efficacy proof that fails without it, an authority record, a temporary
differential comparison where useful, focused and related playability lanes,
and the 52-case precision gate whenever simulation can change. Only then is
the superseded script path deleted.

The final static gate treats historical documentation separately from live
code. Production sources, release artifacts, runtime classpaths and player
instructions may not depend on an interpreter or external content checkout.
