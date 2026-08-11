from pathlib import Path
import io
import sys
import tempfile
import unittest


SCRIPTS = Path(__file__).parents[1] / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import bne_semantic_v2
from bne_fixture import (
    AUX_HEADER, BULLET_DELTA_HEADER, CHUNK_HEADER, CYCLE_HEADER, MAP_DELTA,
    PLAYER_RECORD, PLAYER_SIM_RECORD, STATE_HEADER, UNIT_DELTA_HEADER,
)


UNIT_BYTES, UNIT_LIMIT, PLAYERS = 152, 1600, 16
MAP_SIZE = 4


def unit(x=3, y=4, hp=60):
    raw = bytearray(UNIT_BYTES)
    values = {0: 96, 2: 128, 7: 5, 9: 2, 10: 3, 24: x, 26: y,
              34: hp, 44: 0, 132: 9, 134: 10}
    for offset, value in values.items():
        size = 1 if offset in (7, 9, 10, 44) else 2
        raw[offset:offset + size] = value.to_bytes(size, "little")
    return bytes(raw)


def bullet(x=110, y=120):
    raw = bytearray(64)
    for offset, value, size in ((0, x, 2), (2, y, 2), (9, 4, 1),
                                (10, 6, 1), (40, 140, 2), (42, 150, 2)):
        raw[offset:offset + size] = value.to_bytes(size, "little")
    return bytes(raw)


def stream(second_hp=60):
    out = io.BytesIO()
    out.write(STATE_HEADER.pack(b"BNESTATE", 1, 1, STATE_HEADER.size,
                                UNIT_BYTES, UNIT_LIMIT, PLAYERS, 15))
    for cycle in (1, 2):
        body = io.BytesIO()
        body.write(CYCLE_HEADER.pack(cycle, 7, 1, 1))
        for player in range(PLAYERS):
            body.write(PLAYER_RECORD.pack(0 if player == 0 else 3, 100, 200, 300))
        body.write(UNIT_DELTA_HEADER.pack(0, 1))
        body.write(unit(hp=60 if cycle == 1 else second_hp))
        payload = body.getvalue()
        out.write(CHUNK_HEADER.pack(b"CYCL", len(payload)))
        out.write(payload)

        aux = io.BytesIO()
        changed_tiles = MAP_SIZE * MAP_SIZE if cycle == 1 else 1
        aux.write(AUX_HEADER.pack(cycle, 1, 1 if cycle == 1 else 0,
                                  MAP_SIZE, changed_tiles))
        for player in range(PLAYERS):
            values = [13, 1, 0, 0, 0, 0, 0, 0] + [0] * 10 + [0] * 4
            aux.write(PLAYER_SIM_RECORD.pack(*values))
        if cycle == 1:
            aux.write(BULLET_DELTA_HEADER.pack(0, 1))
            aux.write(bullet())
            for index in range(MAP_SIZE * MAP_SIZE):
                aux.write(MAP_DELTA.pack(index, 10, 20))
        else:
            aux.write(MAP_DELTA.pack(5, 11, 21))
        payload = aux.getvalue()
        out.write(CHUNK_HEADER.pack(b"AUXL", len(payload)))
        out.write(payload)
    return out.getvalue()


def java_trace(second_hp=60):
    lines = []
    for cycle in (1, 2):
        lines.extend([
            f"v2w cycle={cycle} sync_seed=00000007 async_seed=00000001 "
            "async_draws=0 units=1 missiles=1 terrain=0",
            f"v2p cycle={cycle} player=0 supply=13 demand=0 units=1 "
            "buildings=0 score=0 kills=0 razings=0 arrows=0 swords=0 "
            "shields=0 ship_attack=0 ship_armor=0 catapult_damage=0 "
            "ranger_berserker=0 marksmanship=0 longbow=0 scouting=0 "
            "researched=-",
            f"v2u cycle={cycle} unit=100 type=unit-footman player=0 x=3 y=4 "
            f"px=96 py=128 ox=0 oy=0 hp={60 if cycle == 1 else second_hp} "
            "mana=0 frame=2 face=3 timer=5 seqoff=0 order=STILL saved=STILL "
            "orderx=9 ordery=10 target=-1 wait=0 collision=0 refusals=0 route=-",
            f"v2m cycle={cycle} slot=0 type=missile-arrow source=100 target=-1 "
            "x=110 y=120 fromx=100 fromy=110 tox=140 toy=150 frame=4 face=6 "
            "delay=0 ttl=-1 damage=0 remaining=0 flags=0 error=0 major=0 "
            "minor=0 pending=0 impact_wait=0",
        ])
        if cycle == 2:
            lines.append("v2t cycle=2 x=1 y=1 tile=0 graphic=0 flags=0 value=0")
    return ("\n".join(lines) + "\n").encode()


def written(data, suffix):
    handle = tempfile.NamedTemporaryFile(suffix=suffix, delete=False)
    handle.write(data)
    handle.close()
    return Path(handle.name)


class SemanticV2Test(unittest.TestCase):

    def test_common_player_unit_projectile_and_terrain_state_passes(self):
        result = bne_semantic_v2.compare(
            written(stream(), ".state.bin"), written(java_trace(), ".txt"))
        self.assertEqual("PASS", result["status"], result["mismatches"])
        self.assertGreater(result["comparisons"]["player"]["compared"], 0)
        self.assertGreater(result["comparisons"]["unit"]["compared"], 0)
        self.assertGreater(result["comparisons"]["projectile"]["compared"], 0)
        self.assertGreater(result["comparisons"]["terrain"]["compared"], 0)

    def test_a_hidden_subtile_mismatch_fails_the_tier(self):
        trace = java_trace().decode().replace("px=96 py=128", "px=95 py=128", 1)
        result = bne_semantic_v2.compare(
            written(stream(), ".state.bin"), written(trace.encode(), ".txt"))
        self.assertEqual("DIVERGED", result["status"])
        self.assertEqual("px", result["mismatches"][0]["field"])

    def test_player_only_tier_ignores_unit_and_projectile_rows(self):
        trace = java_trace().decode().replace("px=96 py=128", "px=1 py=2")
        trace = trace.replace("x=110 y=120", "x=1 y=2")
        result = bne_semantic_v2.compare(
            written(stream(), ".state.bin"), written(trace.encode(), ".txt"),
            families={"player"})
        self.assertEqual("PASS", result["status"], result["mismatches"])
        self.assertEqual(["player"], result["families"])
        self.assertEqual(0, result["comparisons"]["unit"]["compared"])

    def test_missing_v2_rows_fail_closed(self):
        with self.assertRaisesRegex(ValueError, "no semantic-v2 rows"):
            bne_semantic_v2.compare(
                written(stream(), ".state.bin"), written(b"cycle 1 seed 1\n", ".txt"))


if __name__ == "__main__":
    unittest.main()
