# BNE multiplayer replay corpus

## Player-intent gate

The replay lab decodes the complete embedded command stream while retaining
every command's original bytes. Names are limited to dispatcher shapes proved
against the pinned Battle.net Edition executable; an unmapped command remains
`opcode-xx` rather than acquiring a guessed rule.

The retained external evidence is War2.ru Replay Pack 1:

- archive SHA-256:
  `0dbccf0a82a465bad41b667ec5d25b2d49ec2ba6f162a25b7af14613ab99264b`
- 27 `.wir` replays
- authenticated corpus SHA-256:
  `306f7de5d8675d828f8a086fad3494e2dc2f25d0605df5175fc75010fc773673`
- 168,788 embedded events
- 87,241 ordered selection updates
- 22,518 events issued with more than one unit selected
- 593 selections at the retail limit of nine units

The copyrighted replay bytes stay outside Git. Put the extracted collection at
`../.chonkcraft-replay-evidence/replay-pack-1`, or set `BNE_REPLAY_ROOT`, then
run:

```sh
tools/bne-harness/scripts/check-player-intent-gate.sh
```

That command authenticates and decodes all 27 replays, proves the frozen
aggregate, compiles all 764,756 native dispatcher records, then checks Java's
exact ordered fan-out, nine-unit cap, acceptance recording, and progress by
every member of a congested 3×3 group. It also reconstructs the authenticated
Garden of War startup and executes the proved command subset in the Java
engine. The current certified floor is 2,451 dispatcher records, 288 decoded
commands, and 80 accepted orders with observable progress. Execution stops
fail-closed at native unit 1569's first grunt-production command because the
corresponding Java player has no Orc barracks; moving that proved boundary
forward is an improvement, while any earlier stop fails the gate.

During play the desktop keeps bounded 512-entry in-memory intent and outcome
recorders. `Command/Ctrl-Shift-E` puts ordered selections, submitted orders,
targets and immediate acceptance into `player_intents`; `player_outcomes`
links each unit order by `intent_id` to its first visible progress and terminal
result. The result distinguishes rejection, supersession, settlement,
unit/target loss, window completion and an accepted order that produced no progress for 600
cycles. Both are written beside the screenshot and resumable save. Nothing is
written continuously, and a long session cannot grow either journal without
bound.

War2BNE InSight `.wir` files are suitable authoritative inputs for multiplayer
parity. They are not videos, periodic state dumps, or mid-game saves. Each file
contains InSight's initial-game reference state followed by the exact command
packet delivered for every participating player on each recorded network
turn. InSight itself requires the operator to start the named map with matching
players/settings first; it then verifies that live state against the reference,
feeds the packet stream, and aborts if the game diverges.

## Proven container contract

InSight 1.05 RC2 writes one zlib stream with this layout:

```text
527-byte header
initial BNE game-state snapshot
command record 0
command record 1
...
```

The header is a Delphi record. Its first field is the short string
`War2BNE InSight replay`, followed by format version 1.1. The header identifies
the map, players, races, controllers, game type, record count, snapshot range,
and command-stream boundary. It also contains a CRC-32 over the complete
decompressed file with its mutable 256-byte description field and checksum
field zeroed; InSight stores the CRC's bitwise complement.

Every command record is:

```text
uint8  game_slot_status[8]
uint32 network_player_index
uint32 packet_length
uint8  packet[packet_length]
```

The eight slot bytes change when BNE's simulation-player controller state
changes, such as a player leaving. `network_player_index` identifies the
Battle.net participant whose turn packet is being dispatched. Participant
indexes can contain gaps and do not necessarily equal map/player-color slots.
Packet bytes are retained even when their embedded command boundaries are
decoded. This is the right boundary for parity: BNE and the Java engine must
consume the same packet stream, while any command whose semantics are not yet
proved remains named only by its opcode.

The contract was reconstructed from the recorder and loader in
`War2BNEInSight105RC2.exe`, then checked against all 27 files in War2.ru Replay
Pack 1. Every file passed its embedded checksum, header record count, stream
boundary, participant range, packet length, and exact end-of-file checks.

## Inspect and inventory

Validate one replay and print its immutable identities:

```sh
python3 scripts/bne_replay.py inspect /path/to/match.wir --records 3
```

Build reproducible collection metadata without copying any replay into the
repository:

```sh
python3 scripts/bne_replay.py inventory /path/to/replay-pack-1 \
  --collection-id war2ru-replay-pack-1 \
  --source-url https://downloads.war2.ru/war2/Replays/replay_pack_1.zip \
  --archive-sha256 0dbccf0a82a465bad41b667ec5d25b2d49ec2ba6f162a25b7af14613ab99264b \
  --output work/replays/war2ru-replay-pack-1.json
```

The inventory fingerprints the original compressed replay, decompressed
container, header, initial snapshot, and command stream separately. It also
records map/player metadata and per-player command counts. That makes source
drift, renamed duplicates, and parser boundary errors visible before an oracle
capture begins.

## Capture boundary

A replay fixture will contain four independent identities:

1. the original `.wir` bytes and collection provenance;
2. the validated initial-game reference state and command stream;
3. the exact 2.02b oracle executable/data/harness identities; and
4. the cycle-by-cycle BNE state produced while dispatching those packets.

The guarded wrapper is installed at `0x0047800b` immediately before BNE's
original `0x004782a0` synchronized packet dispatcher. For each synchronized
turn it verifies the expected participant index and eight-byte controller
vector, then calls the unchanged retail dispatcher with the plan's exact
recorded packet bytes. This is a headless packet injector, so it does not race
or overlap InSight's own callsite hook. The hook is opt-in through
`CHONK_BNE_REPLAY_SCHEDULE`; ordinary campaign captures do not install it.
Both the callsite bytes and target entry bytes are pinned, and a trace from any
executable other than the authenticated 2.02b binary is rejected.

Compile one replay into a stable plan and its compact native schedule:

```sh
python3 scripts/bne_replay_outcome.py plan /path/to/match.wir \
  --asset-pack "$HOME/.chonkcraft/packs/warcraft-ii-battle-net-edition-usa.chonkpack" \
  --output work/replays/match.plan.json
python3 scripts/bne_replay_outcome.py native-schedule \
  work/replays/match.plan.json --output work/replays/match.schedule.bin
```

After a native playback, authenticate that every packet was injected in order:

```sh
python3 scripts/bne_replay_outcome.py verify-native \
  work/replays/match.plan.json --trace work/replays/match.trace.txt \
  --output work/replays/match.native-proof.json
```

The normal oracle runner performs that authentication automatically when given
the paired plan and schedule:

```sh
python3 scripts/bne_oracle.py run --game-dir /path/to/verified/BNE \
  --scenario human:1 --cycles 1800 --output work/replays/native \
  --replay-plan work/replays/match.plan.json \
  --replay-schedule work/replays/match.schedule.bin
```

The example's campaign scenario is only illustrative: a replay capture is
valid only when the running map and lobby satisfy the plan's authenticated
startup recipe. A mismatched map, participant order or controller vector is a
failed proof, never a partial replay result.

The outcome comparator additionally requires native and Java traces to prove
the same initial-state identity, the full packet schedule, and authenticated
producer builds. It then compares each `(record, command, selected unit)`
through submission, acceptance, first progress, and terminal outcome, grouping
differences into fan-out, no-progress, cadence, destination/congestion,
attack/chase, boarding, harvesting, and placement families.

The active boundary is deterministic initial-game reconstruction. Static
analysis of InSight's playback routine proves it does not restore a mid-game
world: it validates map name/checksum, game type, fixed start order, controller
vector and computer count against the already-running match, then uses its
serialized reference state to detect drift during playback. Plans now compile
that header state into an explicit startup recipe.

The recipe also applies the retail lobby resource bank before record zero.
Pinned BNE 2.02b function `0x004338d0` establishes the exact table: low is
2,100 gold / 1,100 lumber / 1,000 oil, medium is 5,000 / 2,000 / 2,000,
and high is 10,000 / 5,000 / 5,000. Map-default keeps PUD values above the
retail multiplayer floor and raises lower active banks to 2,100 / 1,100 /
1,000. Treating every replay as map-default caused later worker production to
vanish even though every decoded command was correct. Applying the proved
high-resource bank advanced the certified Garden of War opening from 1,637 to
2,451 dispatcher records, from 62 to 100 submitted orders, and from 8 to 16
bound native units; all 80 accepted orders show observable progress.

The retained boundary is now structural rather than opaque: native unit 1569
submits the first Orc-grunt production order at record 2,451, while Java has no
Orc barracks for that player. The earlier barracks command targeted a peon
that Java had kept inside a pig-farm construction. The smoke receipt retains
the complete player production state so this boundary cannot be mistaken for
an arbitrary unit-identity failure.

Java currently calibrates one synchronized replay turn from the authenticated
lobby-speed byte. Values one through six all stop at record 1,637 because the
45-time-unit peon used by retail does not yet exist; speed seven is the first
cadence that satisfies that observed production boundary. This is deliberately
recorded as calibration evidence, not mislabelled as a disassembled network
interval. The native packet injector remains the authority that will replace
this calibration once deterministic skirmish startup is wired into the oracle.

The native dispatcher path
is exact and guarded; comparison remains deliberately fail-closed until both
native and Java adapters prove the running match satisfies that recipe and
reference identity. Automating InSight's UI remains excluded because focus and
timing do not prove which bytes the game consumed.
