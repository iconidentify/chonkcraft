import json
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile


SCRIPTS = Path(__file__).parents[1] / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import bne_fixture
import bne_packet


class BneDivergencePacketTest(unittest.TestCase):

    def test_primary_java_diagnostic_matches_the_primary_native_slot(self):
        pairings = {268: {1359: 241, 1386: 214, 1481: 119}}
        self.assertEqual(
            (1359, 241),
            bne_packet._primary_focus_pair(
                {1359, 1386, 1481}, pairings, 268),
        )

    def test_summarizes_java_path_and_step_cycles_in_fixture_numbering(self):
        summary = bne_packet.summarize_java_diagnostics("""\
JBNEPATH cycle=8 unit=242 from=10,90 goal=26,87 stride=1 result=FOUND path=201 near=large
JBNESTEP cycle=24 unit=242 type=unit-grunt from=11,90 to=11,89 stride=1
""")
        self.assertEqual([
            "path internal=8 fixture=6 unit=242 from=10,90 goal=26,87 "
            "stride=1 result=FOUND path=201",
            "step internal=24 fixture=22 unit=242 type=unit-grunt "
            "from=11,90 to=11,89 stride=1",
        ], summary)

    @staticmethod
    def _chunk(tag, payload):
        return bne_fixture.CHUNK_HEADER.pack(tag, len(payload)) + payload

    @staticmethod
    def _unit(x, route_index, pixel_x):
        raw = bytearray(152)
        raw[0:2] = pixel_x.to_bytes(2, "little", signed=True)
        raw[2:4] = (321).to_bytes(2, "little", signed=True)
        raw[4:6] = (100).to_bytes(2, "little")
        raw[7] = 1
        raw[8] = 3
        raw[9] = 5
        raw[10] = 2
        raw[24:26] = x.to_bytes(2, "little")
        raw[26:28] = (10).to_bytes(2, "little")
        raw[34:36] = (60).to_bytes(2, "little")
        raw[39] = 1
        raw[44] = 1
        raw[46] = 3
        raw[47] = 60
        raw[48:68] = bytes((2, 2) + (255,) * 18)
        raw[88:90] = (8).to_bytes(2, "little")
        raw[90:92] = (8).to_bytes(2, "little")
        raw[126] = route_index
        raw[132:134] = (14).to_bytes(2, "little")
        raw[134:136] = (10).to_bytes(2, "little")
        return bytes(raw)

    def _fixture_state(self):
        players = b"".join(
            bne_fixture.PLAYER_RECORD.pack(
                0 if player == 1 else 3,
                1000 if player == 1 else 0,
                500 if player == 1 else 0,
                0,
            )
            for player in range(16)
        )
        player_sim = b"".join(
            bne_fixture.PLAYER_SIM_RECORD.pack(*([0] * 22))
            for _ in range(16)
        )
        map_deltas = b"".join(
            bne_fixture.MAP_DELTA.pack(index, index, 1)
            for index in range(16 * 16)
        )
        cycle_one = b"".join((
            bne_fixture.CYCLE_HEADER.pack(1, 1, 1, 1),
            players,
            bne_fixture.UNIT_DELTA_HEADER.pack(0, 1),
            self._unit(10, 0, 321),
        ))
        aux_one = b"".join((
            bne_fixture.AUX_HEADER.pack(1, 0, 0, 16, 16 * 16),
            player_sim,
            map_deltas,
        ))
        cycle_two = b"".join((
            bne_fixture.CYCLE_HEADER.pack(2, 1, 1, 1),
            players,
            bne_fixture.UNIT_DELTA_HEADER.pack(0, 1),
            self._unit(11, 1, 353),
        ))
        aux_two = b"".join((
            bne_fixture.AUX_HEADER.pack(2, 0, 0, 16, 0),
            player_sim,
        ))
        return b"".join((
            bne_fixture.STATE_HEADER.pack(
                b"BNESTATE", 1, 1, bne_fixture.STATE_HEADER.size,
                152, 1600, 16, 15,
            ),
            self._chunk(b"CYCL", cycle_one),
            self._chunk(b"AUXL", aux_one),
            self._chunk(b"CYCL", cycle_two),
            self._chunk(b"AUXL", aux_two),
            self._chunk(b"DONE", bne_fixture.DONE_RECORD.pack(2)),
        ))

    def test_builds_a_packet_with_pairing_raw_timeline_and_map_window(self):
        native_trace = """\
cycle 1 seed 00000001
p 1 gold 1000 wood 500 oil 0
u 0 unit-grunt p1 10 10 hp 60 o MOVE
cycle 2 seed 00000001
p 1 gold 1000 wood 500 oil 0
u 0 unit-grunt p1 11 10 hp 60 o MOVE
"""
        java_trace = """\
cycle 1 seed 00000001
p 1 gold 1000 wood 500 oil 0
u 7 unit-grunt p1 10 10 hp 60 o MOVE px 320 320
cycle 2 seed 00000001
p 1 gold 1000 wood 500 oil 0
u 7 unit-grunt p1 10 10 hp 60 o MOVE px 320 320
"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture = root / "case.bnefx"
            fixture_id = "fixture-id"
            with zipfile.ZipFile(fixture, "w") as archive:
                archive.writestr("manifest.json", json.dumps({
                    "fixture": {"id": fixture_id},
                }))
                archive.writestr("trace.txt", native_trace)
                archive.writestr("state.bin", self._fixture_state())
            fixture_identity = bne_packet.file_identity(fixture)
            java_path = root / "java.trace.txt"
            java_path.write_text(java_trace)
            java_identity = bne_packet.file_identity(java_path)
            index = root / "corpus-index.json"
            index.write_text(json.dumps({
                "schema": 1,
                "cases": [{
                    "id": "case",
                    "fixture_id": fixture_id,
                    "fixture": {"path": fixture.name, **fixture_identity},
                }],
            }))
            survey = root / "survey.json"
            survey.write_text(json.dumps({
                "schema": 1,
                "comparison_tier": "semantic-v1",
                "index": str(index),
                "engine": {"head": "abc", "dirty": False},
                "asset_source": {"kind": "test"},
                "cases": [{
                    "id": "case",
                    "fixture_id": fixture_id,
                    "scenario": r"Campaign\Orc\Orc01.pud",
                    "java_map": "campaigns/orc/level01o",
                    "seed": 1,
                    "comparison_tier": "semantic-v1",
                    "state": "divergent",
                    "compared_cycles": 2,
                    "first_divergence_cycle": 2,
                    "findings": [{
                        "cycle": 2,
                        "kind": "unit",
                        "unit": 0,
                        "unit_type": "unit-grunt",
                        "field": "x",
                        "oracle": 11,
                        "java": 10,
                        "message": "unit 0 (unit-grunt) x 11 vs 10",
                    }],
                    "comparison_output": "first divergence at cycle 2",
                    "java_trace": {"path": str(java_path), **java_identity},
                }],
            }))
            output = root / "packet"
            packet = bne_packet.generate_packet(
                survey, "case", output, before=1, radius=2
            )

            self.assertEqual(7, packet["semantic"]["2"]["focus"][0]["java_id"])
            raw = packet["native_state"]["2"]["units"]["0"]
            self.assertEqual(11, raw["x"])
            self.assertEqual(1, raw["route_index"])
            self.assertEqual([353, 321], [raw["pixel_x"], raw["pixel_y"]])
            self.assertIn("x", {
                change["field"]
                for change in raw["raw_changes_from_previous_packet_cycle"]
            })
            self.assertIn("unit-0-position",
                          packet["native_state"]["2"]["map"]["windows"])
            self.assertTrue((output / "packet.json").is_file())
            self.assertIn("Native raw-unit timeline",
                          (output / "README.md").read_text())
            first = packet["semantic"]["1"]["focus"][0]["subtile"]
            self.assertTrue(first["hidden_mismatch"])
            self.assertEqual([321, 321], first["oracle_pixel"])
            self.assertEqual([320, 320], first["java_pixel"])
            self.assertIn("Hidden sub-tile precursors",
                          (output / "README.md").read_text())
            with self.assertRaisesRegex(ValueError, "already exists"):
                bne_packet.generate_packet(survey, "case", output)


if __name__ == "__main__":
    unittest.main()
