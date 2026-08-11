# BNE multiplayer replay corpus

War2BNE InSight `.wir` files are suitable authoritative inputs for multiplayer
parity. They are not videos and they are not periodic state dumps. Each file
contains a complete initial BNE game snapshot followed by the exact command
packet delivered for every participating player on each recorded network
turn.

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
Packet bytes are retained without interpretation. This is
the right boundary for parity: BNE and the Java engine must consume the same
packet stream, even while individual command opcodes are still being named.

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
2. the validated initial BNE snapshot and command stream;
3. the exact 2.02b oracle executable/data/harness identities; and
4. the cycle-by-cycle BNE state produced while dispatching those packets.

Playback will replace the guarded call at `0x0047800b` immediately before
BNE's original `0x004782a0` synchronized packet dispatcher. InSight's 2.02b
address table and playback routine prove the exact player-slot, controller,
packet-pointer, and packet-length handoff; [`LAYOUT.md`](LAYOUT.md) records the
addresses and byte signature. Automating InSight's UI is deliberately
excluded: it would add timing and focus ambiguity and would not prove which
bytes BNE consumed. The remaining work is to restore the replay's initial BNE
snapshot, add the guarded in-process packet wrapper, and seal the resulting
cycle trace with all replay identities.
