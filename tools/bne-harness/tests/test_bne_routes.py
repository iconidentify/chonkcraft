from pathlib import Path
import io
import struct
import sys
import tempfile
import unittest


SCRIPTS = Path(__file__).parents[1] / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import bne_routes
from bne_fixture import (
    AUX_HEADER, BULLET_DELTA_HEADER, BULLET_BYTES, CHUNK_HEADER, CYCLE_HEADER,
    MAP_DELTA, PLAYER_RECORD, PLAYER_SIM_RECORD, STATE_HEADER,
    UNIT_DELTA_HEADER,
)


UNIT_BYTES, UNIT_LIMIT, PLAYERS = 152, 1600, 16
MAP_SIZE = 16
FOREST, PLAIN = 0x81, 0x01


def unit(*, x, y, route=(), walked=0, order=23, order_point=(0, 0), timer=1,
        dead=False):
    """One 152-byte native unit record, only the fields this reader names."""
    raw = bytearray(UNIT_BYTES)
    raw[bne_routes.UNIT_TIMER] = timer
    raw[bne_routes.UNIT_X:bne_routes.UNIT_X + 2] = x.to_bytes(2, "little")
    raw[bne_routes.UNIT_Y:bne_routes.UNIT_Y + 2] = y.to_bytes(2, "little")
    raw[bne_routes.UNIT_FLAGS] = 0x05 if dead else 0x00
    raw[bne_routes.UNIT_ORDER] = order
    for index in range(bne_routes.ROUTE_BYTES):
        raw[bne_routes.ROUTE_OFFSET + index] = 0xFF
    for index, step in enumerate(route):
        raw[bne_routes.ROUTE_OFFSET + index] = step
    raw[bne_routes.ROUTE_INDEX] = walked
    raw[bne_routes.UNIT_ORDER_X:bne_routes.UNIT_ORDER_X + 2] = \
        order_point[0].to_bytes(2, "little")
    raw[bne_routes.UNIT_ORDER_Y:bne_routes.UNIT_ORDER_Y + 2] = \
        order_point[1].to_bytes(2, "little")
    return bytes(raw)


def stream(cycles, forest=()):
    """A whole sealed-shaped state stream, assembled for this test only."""
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
        tiles = [(index, 0, FOREST if (index % MAP_SIZE,
                                       index // MAP_SIZE) in forest else PLAIN)
                 for index in range(MAP_SIZE * MAP_SIZE)] if number == 1 else []
        aux.write(AUX_HEADER.pack(number, 0, 0, MAP_SIZE, len(tiles)))
        for _ in range(PLAYERS):
            aux.write(PLAYER_SIM_RECORD.pack(*([0] * 8), *([0] * 10),
                                             *([0] * 4)))
        for index, cell, square in tiles:
            aux.write(MAP_DELTA.pack(index, cell, square))
        payload = aux.getvalue()
        out.write(CHUNK_HEADER.pack(b"AUXL", len(payload)))
        out.write(payload)
    return out.getvalue()


def written(data):
    handle = tempfile.NamedTemporaryFile(suffix=".state.bin", delete=False)
    handle.write(data)
    handle.close()
    return Path(handle.name)


class RouteReaderTest(unittest.TestCase):
    """What a unit has decided to do, read out of the record it keeps."""

    def test_a_stored_route_says_where_the_unit_is_going(self):
        # North-east then five north, which is the route xhuman-08's peon 1511
        # stores, and it ends on the tree's west face rather than beside it.
        path = written(stream([
            {7: unit(x=2, y=67, route=(1, 0, 0, 0, 0, 0), order_point=(4, 61))},
        ]))
        plans = bne_routes.planned_routes(path)
        self.assertEqual(1, len(plans),
                         "the unit stored a route and the reader found none")
        self.assertEqual([3, 61], plans[0]["destination"],
                         "the route's own headings do not land where walking "
                         "them lands")
        self.assertEqual(["NE", "N", "N", "N", "N", "N"], plans[0]["steps"],
                         "the headings were not decoded as north-clockwise")

    def test_a_route_read_after_a_step_still_names_where_it_started(self):
        path = written(stream([
            {7: unit(x=3, y=66, route=(1, 0, 0, 0, 0, 0), walked=1,
                     order_point=(4, 61))},
        ]))
        plan = bne_routes.planned_routes(path)[0]
        self.assertEqual([2, 67], plan["start"],
                         "a route seen after its first step was taken read as "
                         "having been planned from the wrong square, which "
                         "moves every destination with it")
        self.assertEqual([3, 61], plan["destination"],
                         "the destination moved with the misread start")

    def test_a_buffer_with_no_terminator_is_not_a_route(self):
        raw = bytearray(unit(x=5, y=5))
        for index in range(bne_routes.ROUTE_BYTES):
            raw[bne_routes.ROUTE_OFFSET + index] = 0
        self.assertIsNone(bne_routes.route_of(bytes(raw)),
                          "twenty zero bytes with no end marker were read as "
                          "twenty steps due north, which invents a journey")

    def test_a_route_is_reported_once_on_the_cycle_it_was_decided(self):
        record = unit(x=2, y=67, route=(1, 0), order_point=(4, 61))
        moved = unit(x=3, y=66, route=(1, 0), walked=1, order_point=(4, 61))
        path = written(stream([{7: record}, {7: moved}, {7: moved}]))
        plans = bne_routes.planned_routes(path)
        self.assertEqual([1], [plan["cycle"] for plan in plans],
                         "one decision was reported as three, so any count of "
                         "how often a unit replans would be wrong")

    def test_a_dead_unit_leaves_the_pool(self):
        alive = unit(x=2, y=67, route=(1, 0), order_point=(4, 61))
        path = written(stream([{7: alive}, {7: unit(x=2, y=67, dead=True)}]))
        frames = list(bne_routes.read_state_stream(path))
        self.assertEqual({}, frames[1]["units"],
                         "a unit that died stayed in the pool, so a later "
                         "cycle would report a corpse's plans")


class WoodApproachTest(unittest.TestCase):
    """The squares beside a tree, and which one the worker walked to."""

    def approach(self):
        # A tree at 8,8 with the worker four squares west of it. Its west
        # face is three steps away; the route stored here -- north-east then
        # three east -- walks past that to the north face, four steps away,
        # which is the shape xhuman-08's peon 1511 shows against retail.
        return written(stream([
            {7: unit(x=4, y=8, route=(1, 2, 2, 2), order_point=(8, 8))},
        ], forest=((8, 8),)))

    def test_a_forest_goal_reports_the_face_and_what_it_cost(self):
        found = bne_routes.wood_approaches(self.approach())
        self.assertEqual(1, len(found),
                         "the worker planned at a forest square and the "
                         "reader did not report it")
        self.assertEqual("N", found[0]["face"],
                         "the square the route ends on is not read as the "
                         "face of the tree it lies against")
        ring = {item["face"]: item["steps"] for item in found[0]["ring"]}
        self.assertEqual(4, ring["N"],
                         "the cost of reaching the face it chose is wrong")
        self.assertEqual(3, ring["W"],
                         "the cost of the face it passed over is wrong, so a "
                         "probe could not tell it walked further than it had to")

    def test_a_route_that_does_not_end_beside_its_tree_is_not_an_approach(self):
        path = written(stream([
            {7: unit(x=4, y=8, route=(2,), order_point=(8, 8))},
        ], forest=((8, 8),)))
        self.assertEqual([], bne_routes.wood_approaches(path),
                         "a route that stops nowhere near the tree was "
                         "counted as a chop approach")

    def test_an_order_point_that_is_not_forest_is_not_an_approach(self):
        path = written(stream([
            {7: unit(x=4, y=8, route=(1, 2, 2, 2), order_point=(8, 8))},
        ]))
        self.assertEqual([], bne_routes.wood_approaches(path),
                         "a worker walking to open ground was counted as "
                         "chopping, and oil and gold goals would join it")

    def test_a_standing_unit_is_solid_to_the_walk(self):
        # An ally standing on the tree's west face. Measured against the
        # sealed captures, charging through allies fits worse than treating
        # them as solid, so that face is not available at all.
        path = written(stream([
            {7: unit(x=4, y=8, route=(1, 2, 2, 2), order_point=(8, 8)),
             9: unit(x=7, y=8, order=2)},
        ], forest=((8, 8),)))
        found = bne_routes.wood_approaches(path)
        ring = {item["face"]: item["steps"] for item in found[0]["ring"]}
        self.assertIsNone(ring["W"],
                          "a square an ally was standing on was offered as a "
                          "chop face, so a probe would report the worker "
                          "passed over a square it could not have had")


if __name__ == "__main__":
    unittest.main()


class RefuseMarkerTest(unittest.TestCase):
    """Twenty in the index is a refusal, not a step count."""

    def test_a_refused_record_is_not_reported_as_a_route(self):
        path = written(stream([
            {7: unit(x=2, y=67, route=(1, 0), walked=20, order_point=(4, 61))},
        ]))
        self.assertEqual([], bne_routes.planned_routes(path),
                         "a unit carrying the refuse marker was reported as "
                         "having walked twenty steps of a two-step route, so "
                         "its start and destination were both invented")

    def test_an_ordinary_index_still_reports(self):
        path = written(stream([
            {7: unit(x=3, y=66, route=(1, 0), walked=1, order_point=(4, 61))},
        ]))
        self.assertEqual(1, len(bne_routes.planned_routes(path)),
                         "guarding the refuse marker also dropped an ordinary "
                         "part-walked route")
