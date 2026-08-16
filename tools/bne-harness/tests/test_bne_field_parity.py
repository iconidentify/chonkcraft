from pathlib import Path
import io
import sys
import tempfile
import unittest


SCRIPTS = Path(__file__).parents[1] / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import bne_field_parity
import bne_routes
from bne_fixture import (
    AUX_HEADER, CHUNK_HEADER, CYCLE_HEADER, PLAYER_RECORD, PLAYER_SIM_RECORD,
    STATE_HEADER, UNIT_DELTA_HEADER,
)


UNIT_BYTES, UNIT_LIMIT, PLAYERS = 152, 1600, 16
MAP_SIZE = 16


def unit(*, x, y, route=(), walked=0):
    """One 152-byte native record, only the fields this scorer reads."""
    raw = bytearray(UNIT_BYTES)
    raw[bne_routes.UNIT_X:bne_routes.UNIT_X + 2] = x.to_bytes(2, "little")
    raw[bne_routes.UNIT_Y:bne_routes.UNIT_Y + 2] = y.to_bytes(2, "little")
    for index in range(bne_routes.ROUTE_BYTES):
        raw[bne_routes.ROUTE_OFFSET + index] = 0xFF
    for index, step in enumerate(route):
        raw[bne_routes.ROUTE_OFFSET + index] = step
    raw[bne_routes.ROUTE_INDEX] = walked
    return bytes(raw)


def stream(cycles):
    """A sealed-shaped state stream, assembled for this test only."""
    out = io.BytesIO()
    out.write(STATE_HEADER.pack(b"BNESTATE", 1, 1, STATE_HEADER.size,
                                UNIT_BYTES, UNIT_LIMIT, PLAYERS, 15))
    for number, units in enumerate(cycles, start=1):
        body = io.BytesIO()
        body.write(CYCLE_HEADER.pack(number, 1, UNIT_LIMIT, len(units)))
        for _ in range(PLAYERS):
            body.write(PLAYER_RECORD.pack(0, 0, 0, 0))
        for slot, raw in sorted(units.items()):
            body.write(UNIT_DELTA_HEADER.pack(slot, 1))
            body.write(raw)
        payload = body.getvalue()
        out.write(CHUNK_HEADER.pack(b"CYCL", len(payload)))
        out.write(payload)

        aux = io.BytesIO()
        aux.write(AUX_HEADER.pack(number, 0, 0, MAP_SIZE, 0))
        for _ in range(PLAYERS):
            aux.write(PLAYER_SIM_RECORD.pack(*([0] * 8), *([0] * 10),
                                             *([0] * 4)))
        payload = aux.getvalue()
        out.write(CHUNK_HEADER.pack(b"AUXL", len(payload)))
        out.write(payload)
    return out.getvalue()


def written(data, suffix):
    handle = tempfile.NamedTemporaryFile(suffix=suffix, delete=False)
    handle.write(data)
    handle.close()
    return Path(handle.name)


def dumped(cycles):
    """The stderr the engine writes under CHONKCRAFT_TRACE_BNE_FIELDS."""
    lines = []
    for number, units in enumerate(cycles, start=1):
        for ident, (x, y, route) in sorted(units.items()):
            lines.append(
                f"JBNEFIELD cycle={number} unit={ident} x={x} y={y} "
                "moving=0 moveanim=0 seqoff=0 movestart=0 attackstart=0 "
                "pathn=0 spent=0 drained=0 holding=0 ox=0 oy=0 coll=0 "
                f"wait=0 delay=0 chasing=0 order=Still route={route}")
    return written(("\n".join(lines) + "\n").encode(), ".java.stderr.txt")


class FieldParityTest(unittest.TestCase):
    """Scoring a survey against the captures it was run beside."""

    def test_units_are_paired_by_where_they_stood_and_not_by_pool_order(self):
        # The two engines walk the unit pool in opposite directions, so
        # pairing by order matches about one unit per cycle by luck and every
        # score drawn from it is noise. Here the port lists the same two units
        # backwards and both stand still, so a scorer that pairs by position
        # sees perfect agreement and one that pairs by order sees none.
        state = written(stream([
            {7: unit(x=2, y=2), 9: unit(x=5, y=5)},
            {7: unit(x=2, y=2), 9: unit(x=5, y=5)},
        ]), ".state.bin")
        stderr = dumped([
            {100: (5, 5, "-"), 101: (2, 2, "-")},
            {100: (5, 5, "-"), 101: (2, 2, "-")},
        ])
        got = bne_field_parity.score_case(state, stderr, through=2)
        self.assertEqual(2, got["pairs"],
                         "the two units standing on distinct squares on cycle "
                         "one were not paired")
        self.assertEqual(got["paired"], got["in_place"],
                         "two units that never moved in either engine were "
                         "scored as standing somewhere different")

    def test_a_unit_that_parts_still_counts_for_the_rest_of_the_run(self):
        # An earlier scorer stopped counting a unit at its first
        # disagreement, so the denominator moved with the result and every
        # candidate that made a unit part sooner scored better by measuring
        # less of the run.
        state = written(stream([
            {7: unit(x=2, y=2)},
            {7: unit(x=2, y=2)},
            {7: unit(x=2, y=2)},
            {7: unit(x=2, y=2)},
        ]), ".state.bin")
        stderr = dumped([
            {100: (2, 2, "-")},
            {100: (3, 2, "-")},
            {100: (4, 2, "-")},
            {100: (5, 2, "-")},
        ])
        got = bne_field_parity.score_case(state, stderr, through=4)
        self.assertEqual(4, got["paired"],
                         "a unit that walked away on cycle two stopped being "
                         "measured, which is what lets a worse engine score "
                         "higher")
        self.assertEqual(1, got["in_place"],
                         "the port put the unit on a different square for "
                         "three of the four cycles and they were not counted")

    def test_a_java_unit_that_disappears_counts_as_missing_not_less_work(self):
        state = written(stream([
            {7: unit(x=2, y=2)},
            {7: unit(x=2, y=2)},
            {7: unit(x=2, y=2)},
        ]), ".state.bin")
        stderr = dumped([
            {100: (2, 2, "-")},
            {},
            {},
        ])
        got = bne_field_parity.score_case(
            state, stderr, through=3, frozen_pairs={7: 100})
        self.assertEqual(3, got["paired"])
        self.assertEqual(1, got["in_place"])
        self.assertEqual(2, got["missing_samples"])

    def test_frozen_pairing_cannot_be_recomputed_around_a_startup_regression(self):
        state = written(stream([{7: unit(x=2, y=2)}]), ".state.bin")
        stderr = dumped([{101: (2, 2, "-")}])
        got = bne_field_parity.score_case(
            state, stderr, through=1, frozen_pairs={7: 100})
        self.assertEqual(1, got["paired"])
        self.assertEqual(0, got["in_place"])
        self.assertEqual(1, got["missing_samples"])

    def test_two_units_on_one_square_are_left_unpaired(self):
        state = written(stream([
            {7: unit(x=2, y=2), 9: unit(x=2, y=2)},
        ]), ".state.bin")
        stderr = dumped([{100: (2, 2, "-"), 101: (2, 2, "-")}])
        got = bne_field_parity.score_case(state, stderr, through=1)
        self.assertEqual(0, got["pairs"],
                         "two units sharing a square on cycle one were paired "
                         "by a guess rather than left out")

    def test_a_parked_route_reads_as_holding_none(self):
        # 0x00450ad0 gives a route up by writing 20 to the cursor, one past
        # the twenty heading bytes, so the headings survive and the unit is
        # holding nothing. Reading the bytes without the cursor reports a
        # route retail has abandoned.
        state = written(stream([
            {7: unit(x=2, y=2, route=(2, 2, 2), walked=20)},
        ]), ".state.bin")
        stderr = dumped([{100: (2, 2, "-")}])
        got = bne_field_parity.score_case(state, stderr, through=1)
        self.assertEqual(1, got["in_place"],
                         "the paired unit was not scored at all")
        self.assertEqual(0, sum(got["decisions"].values()),
                         "a single cycle has no previous visit to compare")

    def test_decisions_stop_being_compared_once_the_unit_parts(self):
        # A wrong decision on cycle 19 shows as a wrong position on cycle 36,
        # by which time the unit's state is incomparable and counting it
        # inflates whichever bucket the drift happens to land in.
        state = written(stream([
            {7: unit(x=2, y=2, route=(2, 2), walked=0)},
            {7: unit(x=3, y=2, route=(2, 2), walked=1)},
            {7: unit(x=4, y=2, route=(2, 2), walked=2)},
        ]), ".state.bin")
        stderr = dumped([
            {100: (2, 2, "22")},
            {100: (2, 2, "22")},
            {100: (2, 2, "22")},
        ])
        got = bne_field_parity.score_case(state, stderr, through=3)
        self.assertEqual(1, sum(got["decisions"].values()),
                         "decisions were compared after the two engines had "
                         "the unit on different squares")
        self.assertEqual(1, got["decisions"]["a differing step"],
                         "native stepped east and the port stood still, and "
                         "that was not reported as a differing step")


if __name__ == "__main__":
    unittest.main()
