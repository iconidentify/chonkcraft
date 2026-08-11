import hashlib
import importlib.util
import json
from pathlib import Path
import struct
import sys
import tempfile
import unittest

SCRIPTS = Path(__file__).parents[1] / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import bne_fixture

ORACLE_SCRIPT = SCRIPTS / "bne_oracle.py"
ORACLE_SPEC = importlib.util.spec_from_file_location("fixture_bne_oracle", ORACLE_SCRIPT)
bne_oracle = importlib.util.module_from_spec(ORACLE_SPEC)
assert ORACLE_SPEC.loader is not None
ORACLE_SPEC.loader.exec_module(bne_oracle)


def identity(path: Path) -> dict[str, int | str]:
    data = path.read_bytes()
    return {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}


def raw_unit(*, live: bool = False, owner: int = 0,
        x: int = 0, y: int = 0, hp: int = 0) -> bytes:
    raw = bytearray(152)
    raw[30] = 0 if live else 1
    raw[44] = owner
    raw[24:26] = x.to_bytes(2, "little")
    raw[26:28] = y.to_bytes(2, "little")
    raw[34:36] = hp.to_bytes(2, "little")
    return bytes(raw)


def raw_bullet(*, live: bool = False, x: int = 0, y: int = 0,
        kind: int = 0) -> bytes:
    raw = bytearray(64)
    raw[0:2] = x.to_bytes(2, "little")
    raw[2:4] = y.to_bytes(2, "little")
    raw[52] = kind
    raw[53] = 0 if live else 1
    return bytes(raw)


def cycle_chunk(cycle: int, seed: int,
        changes: list[tuple[int, int, bytes]]) -> bytes:
    players = []
    for player in range(16):
        if player == 0:
            players.append(struct.pack("<IIII", 0, 1000, 1000, 0))
        else:
            players.append(struct.pack("<IIII", 3, 0, 0, 0))
    payload = [struct.pack("<IIII", cycle, seed, 1600, len(changes)), *players]
    payload.extend(struct.pack("<II", slot, generation) + raw
                   for slot, generation, raw in changes)
    body = b"".join(payload)
    return struct.pack("<4sI", b"CYCL", len(body)) + body


def aux_chunk(cycle: int,
        bullet_changes: list[tuple[int, int, bytes]],
        map_changes: list[tuple[int, int, int]]) -> bytes:
    player_records = []
    for player in range(16):
        values = ([10, 1, 1, 0, 0, 0, 0, 0]
                  + [0] * 10 + [0xffffffff, 0xfffff, 0xfffff, 0])
        if player != 0:
            values = [0] * 22
        player_records.append(struct.pack("<8H10B2x4I", *values))
    payload = [
        struct.pack("<IIIII", cycle, 2, len(bullet_changes), 2,
                    len(map_changes)),
        *player_records,
    ]
    payload.extend(struct.pack("<II", slot, generation) + raw
                   for slot, generation, raw in bullet_changes)
    payload.extend(struct.pack("<IHH", index, cell, square)
                   for index, cell, square in map_changes)
    body = b"".join(payload)
    return struct.pack("<4sI", b"AUXL", len(body)) + body


def state_stream() -> bytes:
    header = struct.pack(
        "<8sHHIIIII", b"BNESTATE", 1, 1, 32, 152, 1600, 16, 15)
    initial = [(slot, 0, raw_unit()) for slot in range(1600)]
    initial[-1] = (1599, 1, raw_unit(live=True, x=10, y=10, hp=60))
    moved = [(1599, 1, raw_unit(live=True, x=11, y=10, hp=60))]
    initial_bullets = [
        (0, 0, raw_bullet()),
        (1, 1, raw_bullet(live=True, x=10, y=10, kind=4)),
    ]
    moved_bullet = [
        (1, 1, raw_bullet(live=True, x=11, y=10, kind=4)),
    ]
    initial_map = [(index, 100 + index, index) for index in range(4)]
    changed_map = [(2, 999, 7)]
    done = struct.pack("<4sII", b"DONE", 4, 2)
    return (header
            + cycle_chunk(1, 1, initial)
            + aux_chunk(1, initial_bullets, initial_map)
            + cycle_chunk(2, 1, moved)
            + aux_chunk(2, moved_bullet, changed_map)
            + done)


def legacy_state_stream() -> bytes:
    header = struct.pack(
        "<8sHHIIIII", b"BNESTATE", 1, 0, 32, 152, 1600, 16, 1)
    initial = [(slot, 0, raw_unit()) for slot in range(1600)]
    initial[-1] = (1599, 1, raw_unit(live=True, x=10, y=10, hp=60))
    done = struct.pack("<4sII", b"DONE", 4, 1)
    return header + cycle_chunk(1, 1, initial) + done


class FixtureFormatTest(unittest.TestCase):

    def test_reconstructs_unit_deltas_and_cross_checks_the_text_trace(self):
        trace_text = """\
# bne-trace event=storm-open-file archive=0 path="Campaign\\\\Human\\\\Human01.pud" scope=1 result=1 handle=1 error=0
# bne-trace event=initialization-seed-applied seed=1
# bne-trace event=match-ready slots=1600
cycle 1 seed 00000001
p 0 gold 1000 wood 1000 oil 0
u 1599 unit-footman p0 10 10 hp 60 o STILL
cycle 2 seed 00000001
p 0 gold 1000 wood 1000 oil 0
u 1599 unit-footman p0 11 10 hp 60 o MOVE
# bne-trace event=cycle-limit cycle=2
# bne-trace protocol=2 event=detach cycles=2 screens=0
"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            state = root / "state.bin"
            trace = root / "trace.txt"
            state.write_bytes(state_stream())
            trace.write_text(trace_text)
            state_validation = bne_fixture.validate_state_stream(state, 2)
            trace_validation = bne_oracle.validate_trace(
                trace, 2, r"Campaign\Human\Human01.pud", 0, 1)
            bne_oracle.cross_validate_trace_state(
                trace_validation, state_validation)
        self.assertEqual(2, state_validation["cycles"])
        self.assertEqual(1601, state_validation["unit_delta_records"])
        self.assertEqual(2, state_validation["active_player_records"])
        self.assertEqual(2, state_validation["live_unit_records"])
        self.assertEqual(3, state_validation["bullet_delta_records"])
        self.assertEqual(5, state_validation["map_delta_records"])
        self.assertEqual(2, state_validation["live_bullet_records"])
        self.assertEqual(2, state_validation["player_sim_records"])

    def test_accepts_the_legacy_unit_only_state_schema(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory) / "state.bin"
            state.write_bytes(legacy_state_stream())
            validation = bne_fixture.validate_state_stream(state, 1)
        self.assertEqual("1.0", validation["schema"])
        self.assertNotIn("bullet_delta_records", validation)

    def test_rejects_a_truncated_state_stream(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory) / "state.bin"
            state.write_bytes(state_stream()[:-1])
            with self.assertRaisesRegex(ValueError, "truncated"):
                bne_fixture.validate_state_stream(state, 2)

    def test_seals_and_validates_a_fixture_bundle(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            state = root / "state.bin"
            trace = root / "trace.txt"
            manifest_path = root / "manifest.json"
            fixture = root / "sample.bnefx"
            second_fixture = root / "sample-again.bnefx"
            state.write_bytes(state_stream())
            trace.write_text("diagnostic trace\n")
            state_validation = bne_fixture.validate_state_stream(state, 2)
            manifest = {
                "schema": 2,
                "fixture": {"schema": 1, "id": "fixture-test", "key": {}},
                "run": {
                    "cycle_limit": 2,
                    "commands": None,
                    "trace": {"name": trace.name, **identity(trace)},
                    "state": {
                        "name": state.name,
                        **identity(state),
                        "validation": state_validation,
                    },
                },
            }
            manifest_path.write_text(
                json.dumps(manifest, sort_keys=True) + "\n", encoding="utf-8")
            result = bne_fixture.seal_fixture(
                fixture, manifest_path, trace, state)
            bne_fixture.seal_fixture(
                second_fixture, manifest_path, trace, state)
            again = bne_fixture.validate_fixture(fixture)
            self.assertEqual(fixture.read_bytes(), second_fixture.read_bytes())
        self.assertEqual("fixture-test", result["fixture_id"])
        self.assertEqual(result, again)
        self.assertEqual(3, result["members"])


if __name__ == "__main__":
    unittest.main()
