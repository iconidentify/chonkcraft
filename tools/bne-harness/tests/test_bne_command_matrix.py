from pathlib import Path
import io
import json
import sys
import tempfile
import unittest
import zipfile


SCRIPTS = Path(__file__).parents[1] / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import bne_command_matrix
import bne_corpus
from bne_fixture import (
    AUX_HEADER, CHUNK_HEADER, CYCLE_HEADER, MAP_DELTA, PLAYER_RECORD,
    PLAYER_SIM_RECORD, STATE_HEADER, UNIT_DELTA_HEADER,
)


def unit(x, y, movement):
    raw = bytearray(152)
    raw[24:26] = x.to_bytes(2, "little")
    raw[26:28] = y.to_bytes(2, "little")
    raw[42] = movement
    return bytes(raw)


def fixture():
    state = io.BytesIO()
    state.write(STATE_HEADER.pack(b"BNESTATE", 1, 1, STATE_HEADER.size,
                                  152, 1600, 16, 15))
    cycle = io.BytesIO()
    units = {0: unit(8, 8, 0), 1: unit(9, 8, 0),
             2: unit(8, 8, 1), 3: unit(8, 8, 2)}
    cycle.write(CYCLE_HEADER.pack(1, 7, 4, len(units)))
    for _ in range(16):
        cycle.write(PLAYER_RECORD.pack(0, 0, 0, 0))
    for slot, raw in units.items():
        cycle.write(UNIT_DELTA_HEADER.pack(slot, 1))
        cycle.write(raw)
    payload = cycle.getvalue()
    state.write(CHUNK_HEADER.pack(b"CYCL", len(payload)))
    state.write(payload)
    aux = io.BytesIO()
    aux.write(AUX_HEADER.pack(1, 0, 0, 16, 16 * 16))
    for _ in range(16):
        aux.write(PLAYER_SIM_RECORD.pack(*([0] * 8), *([0] * 10), *([0] * 4)))
    for index in range(16 * 16):
        aux.write(MAP_DELTA.pack(index, 0, 0))
    payload = aux.getvalue()
    state.write(CHUNK_HEADER.pack(b"AUXL", len(payload)))
    state.write(payload)

    handle = tempfile.NamedTemporaryFile(suffix=".bnefx", delete=False)
    handle.close()
    path = Path(handle.name)
    manifest = {"fixture": {"id": "fixture-test"}, "run": {
        "requested_scenario": r"Campaign\XHuman\2XHum10.pud",
        "initialization_seed": 7,
    }}
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("manifest.json", json.dumps(manifest))
        archive.writestr("state.bin", state.getvalue())
    return path


class CommandMatrixTest(unittest.TestCase):

    def test_compiles_every_ground_heading_and_all_movement_classes(self):
        plan, commands = bne_command_matrix.compile_matrix(fixture())
        ids = {case["id"] for case in plan["cases"]}
        self.assertEqual(17, len(ids))
        self.assertTrue(any("ground-nw" in case for case in ids))
        self.assertTrue(any("ground-occupied" in case for case in ids))
        self.assertEqual(4, sum("-air-" in case for case in ids))
        self.assertEqual(4, sum("-sea-" in case for case in ids))
        self.assertTrue(all(text.startswith("# bne-command-matrix-v1\ncycle 5 move")
                            for text in commands.values()))

    def test_written_plan_is_accepted_by_the_existing_corpus_contract(self):
        with tempfile.TemporaryDirectory() as directory:
            plan = bne_command_matrix.write_matrix(fixture(), Path(directory))
            _raw, cases = bne_corpus.load_plan(plan)
            self.assertEqual(17, len(cases))


if __name__ == "__main__":
    unittest.main()
