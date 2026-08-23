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
engine with ChonkCraft's optional multi-job training queue disabled, as it is
in retail BNE. The current certified floor is 3,935 dispatcher records, 543
decoded commands, 189 submitted unit orders, 158 dispatcher acceptances, 151
orders with a physical effect or proved blocked-goal settlement, and 150
fulfilled objectives across 37 bound native units. Those figures describe the retained
Garden of War prefix gate, not complete replay-corpus outcome certification.
Execution stops fail-closed at native unit 1526's first unsupported
identity at record 3,935; moving that proved boundary forward is an
improvement, while any earlier stop fails the gate.

During play the desktop keeps bounded 512-entry in-memory intent and outcome
recorders. `Command/Ctrl-Shift-E` puts ordered selections, submitted orders,
targets and immediate acceptance into `player_intents`; `player_outcomes`
links each unit order by `intent_id` to its first physical progress and terminal
result. Merely changing the Java order label is not progress. Movement requires
a changed map pixel, construction requires the requested foundation at the
clicked site, and production requires an actual queue/research/upgrade change.
An already-adjacent click inside a blocked tree or building footprint is a
proved blocked-goal settlement, not a false no-progress alarm. The result
distinguishes rejection, supersession, objective fulfillment,
settlement, unit/target loss, window completion and an accepted order that
produced no physical effect for 600 cycles. Both are written beside the screenshot
and resumable save. Nothing is
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
python3 tools/bne-harness/scripts/bne_replay.py inspect /path/to/match.wir --records 3
```

Build reproducible collection metadata without copying any replay into the
repository:

```sh
python3 tools/bne-harness/scripts/bne_replay.py inventory /path/to/replay-pack-1 \
  --collection-id war2ru-replay-pack-1 \
  --source-url https://downloads.war2.ru/war2/Replays/replay_pack_1.zip \
  --archive-sha256 0dbccf0a82a465bad41b667ec5d25b2d49ec2ba6f162a25b7af14613ab99264b \
  --output tools/bne-harness/work/replays/war2ru-replay-pack-1.json
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
python3 tools/bne-harness/scripts/bne_replay_outcome.py plan /path/to/match.wir \
  --asset-pack "$HOME/.chonkcraft/packs/warcraft-ii-battle-net-edition-usa.chonkpack" \
  --output tools/bne-harness/work/replays/match.plan.json
python3 tools/bne-harness/scripts/bne_replay_outcome.py native-schedule \
  tools/bne-harness/work/replays/match.plan.json \
  --output tools/bne-harness/work/replays/match.schedule.bin
```

After a native playback, authenticate that every packet was injected in order:

```sh
python3 tools/bne-harness/scripts/bne_replay_outcome.py verify-native \
  tools/bne-harness/work/replays/match.plan.json \
  --trace tools/bne-harness/work/replays/match.trace.txt \
  --output tools/bne-harness/work/replays/match.native-proof.json
```

The normal oracle runner performs that authentication automatically when given
the paired plan and schedule:

```sh
python3 tools/bne-harness/scripts/bne_oracle.py run --game-dir /path/to/verified/BNE \
  --scenario human:1 --cycles 1800 \
  --output tools/bne-harness/work/replays/native \
  --replay-plan tools/bne-harness/work/replays/match.plan.json \
  --replay-schedule tools/bne-harness/work/replays/match.schedule.bin
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

Allocator ids are not unit identities. A mandatory
`chonkcraft-bne-replay-unit-lifecycle-1` table maps every local
`(unit, generation)` lifetime to a stable initial identity
`(owner, type, starting square, duplicate ordinal)` or a stable spawn identity
`(owner, type, birth record, producer identity, birth ordinal)`. The comparator
requires the table on both sides, rejects overlapping generations, impossible
birth/death bounds, duplicate stable identities and unknown or dead producer
lifetimes, and joins outcomes by stable identity. Every non-selection replay
command contributes exactly one required outcome per ordered selected unit.
Missing outcomes, unexpected outcomes, an empty outcome list and unresolved
lifetimes therefore remain explicit denominator failures; none can pass as an
empty comparison. This prevents a freed native slot and a reused Java id from
being paired merely because their integers happen to match.

Java traces and the 27-replay aggregate certification bind both the current
engine-input SHA-256 and the wider program-input SHA-256 covering desktop input
interpretation, adapters, build inputs and pack-facing code. Aggregate output
also seals the pinned retail executable identity and every comparison receipt
content hash.

When a completed pair differs, seal the smallest schedule prefix that still
reproduces the difference:

```sh
python3 tools/bne-harness/scripts/bne_replay_outcome.py divergence-packet \
  tools/bne-harness/work/replays/match.plan.json \
  --native tools/bne-harness/work/replays/match.native.json \
  --java tools/bne-harness/work/replays/match.java.json \
  --native-prefix-command 'native-prefix-adapter --plan {plan} --output {output}' \
  --java-prefix-command 'java-prefix-adapter --plan {plan} --output {output}' \
  --output-dir tools/bne-harness/work/replays/divergences
```

Replace the two adapter names with the real producer commands. Templates are
split as argv without a shell and must consume `{plan}` and write `{output}`;
`{records}` is also available. The command compares the full authenticated
traces, freshly executes both engines for every binary-search probe, proves the
immediately preceding prefix exact, and writes `<packet-sha256>/packet.json`.
Filtering a full-run trace is explicitly `projected-non-certifying`: a later
command may have superseded and changed an earlier terminal outcome. The
packet therefore contains fresh native and Java prefix receipts, the lifecycle
bridge, first causal difference and every bisection receipt. Successful packet
creation exits zero even when it found a divergence; add
`--fail-on-divergence` when a CI caller wants exit 2. `prefix-plan --records N`
only seals a schedule; it becomes proof after both producers execute that plan.
Its output remains consumable by `native-schedule`, so a minimized packet is a
runnable proof rather than a report-only summary.

`certify-corpus` accepts all comparison receipts in one invocation and joins
them to the frozen 27-replay identity and its fixed totals: 764,756 records and
168,788 commands. It reports semantic `content_exact` diagnostics, but a
detached comparison summary cannot prove that either producer trace actually
ran. `complete` therefore stays false until a retained proof-store validator
reopens every plan plus its native and Java trace and recomputes `compare()`.
A missing replay, stale program/engine identity, incomplete selected-unit
denominator, mixed producer, duplicated receipt or divergent outcome also
keeps the certification red.

The next-level gate must receive the frozen corpus through
`BNE_REPLAY_CORPUS` and producer comparison receipts through the
colon-separated `BNE_REPLAY_REPORTS`. A detached `BNE_REPLAY_CERTIFICATION`
summary is diagnostic only and cannot certify the lane. A filtered full-run
prefix is likewise `projected-non-certifying`; only fresh native and Java
executions of the sealed prefix can contribute proof.

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
high-resource bank, the synchronized turn cadence, the birth-slot bridge,
and founding the 90,6 blacksmith on the hall body now certify 3,935
records, 189 submitted orders and 37 bound native units.

The cadence is no longer inferred from a replay header. In the pinned 2.02b
executable the game loop at `0x00420e9a..0x00420eb5` compares the synchronized
dispatcher interval with `0x1f4` (500 ms), and the network manager is called at
`0x00420fbb`. At the Java simulation's 30 Hz this is exactly fifteen cycles per
turn. The lobby speed byte is a UI pacing-table index, not a cycle count.

The retained boundary is structural rather than opaque: native unit 1526
submits a move at record 3,935, after the guarded
native-high-slot/Java-low-ID birth-order bridge can no longer identify one
compatible Java mover without guessing. The smoke receipt retains the
complete player production state. It also structurally indexes every retained
record after that boundary—command-family counts, selected native identities,
and unsupported packets—while clearly labeling those records as unexecuted.
That keeps the certified prefix fail-closed without making the rest of the
match invisible or requiring a throwaway parser to choose the next adapter.

Replay packets are player inputs, not native success receipts. Retail opcode
`0x09` acknowledges a build packet in `0x00475e50`, then `0x0043afe0` begins
the approach/site state machine; collision or reachability can still reject the
foundation later. The recorded blacksmith click at `(90,6)` overlaps the
body of the 4x4 Great Hall at 89,5. CheckCanBuild ignores
`MapFieldBuilding` except at a solid building's origin, and a builder
walking to found uses the same occupancy, so that packet now founds and
native 1554's later shield research binds. The outcome gate consequently reports four
separate facts:
dispatcher acceptance, first physical effect, requested-objective fulfillment,
and terminal rejection/supersession/failure. Native packet injection remains
the authority for comparing those outcomes once deterministic skirmish startup
is wired into the oracle.

The native dispatcher path
is exact and guarded; comparison remains deliberately fail-closed until both
native and Java adapters prove the running match satisfies that recipe and
reference identity. Automating InSight's UI remains excluded because focus and
timing do not prove which bytes the game consumed.
