"""Tests for the BNE projectile pool ledger.

Every fixture here is built in code. The point of the ledger is to stop the
projectile lifecycle being read by eye out of a hex dump, so these tests state
the lifecycle in the game's terms -- a shot was created, it landed, its slot
was taken by a later shot -- and check the ledger says that, rather than
checking that a field was parsed.
"""

import contextlib
import io
import json
from pathlib import Path
import shutil
import struct
import sys
import tempfile
import unittest
import zipfile

SCRIPTS = Path(__file__).parents[1] / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import bne_java
import bne_projectile_ledger as ledger


def raw_bullet(*, live: bool = False, x: int = 0, y: int = 0,
               kind: int = 0, remaining: int = 0, action: int = 0,
               source: int = 0, target: int = 0,
               frame: int = 0, facing: int = 0) -> bytes:
    raw = bytearray(64)
    raw[0:2] = x.to_bytes(2, "little")
    raw[2:4] = y.to_bytes(2, "little")
    raw[0x09] = frame
    raw[0x0a] = facing
    raw[0x20:0x22] = remaining.to_bytes(2, "little", signed=True)
    raw[0x2c:0x30] = source.to_bytes(4, "little")
    raw[0x30:0x34] = target.to_bytes(4, "little")
    raw[52] = kind
    raw[53] = 0 if live else 1
    raw[54] = action
    return bytes(raw)


def cycle_chunk(cycle: int, seed: int) -> bytes:
    players = [struct.pack("<IIII", 0 if p == 0 else 3, 0, 0, 0)
               for p in range(16)]
    payload = [struct.pack("<IIII", cycle, seed, 1600, 0), *players]
    body = b"".join(payload)
    return struct.pack("<4sI", b"CYCL", len(body)) + body


def aux_chunk(cycle: int, pool: int,
              bullets: list[tuple[int, int, bytes]]) -> bytes:
    records = [struct.pack("<8H10B2x4I", *([0] * 22)) for _ in range(16)]
    payload = [struct.pack("<IIIII", cycle, pool, len(bullets), 2, 0), *records]
    payload.extend(struct.pack("<II", slot, generation) + raw
                   for slot, generation, raw in bullets)
    body = b"".join(payload)
    return struct.pack("<4sI", b"AUXL", len(body)) + body


def state_stream(cycles: list[tuple[int, list[tuple[int, int, bytes]]]], *,
                 pool: int = 8) -> bytes:
    header = struct.pack("<8sHHIIIII", b"BNESTATE", 1, 1, 32, 152, 1600, 16, 15)
    body = [header]
    for cycle, bullets in cycles:
        body.append(cycle_chunk(cycle, 1))
        body.append(aux_chunk(cycle, pool, bullets))
    body.append(struct.pack("<4sII", b"DONE", 4, len(cycles)))
    return b"".join(body)


def checkpoint(pool: int, live: dict[int, bytes]) -> list[tuple[int, int, bytes]]:
    """Cycle 1 checkpoints every slot in the configured pool."""
    return [(slot, 1 if slot in live else 0, live.get(slot, raw_bullet()))
            for slot in range(pool)]


def sealed(path: Path, stream: bytes) -> Path:
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("state.bin", stream)
    return path


def read(stream: bytes, through: int) -> dict:
    return ledger.read_native_pool(io.BytesIO(stream), through=through)


class ProjectileLifetimeTest(unittest.TestCase):
    def test_structured_java_lifecycle_uses_fixture_cycle_and_creation_identity(self):
        """The engine's JSONL can be compared directly with native cycles."""
        records = [
            {"schema": 1, "side": "java", "ordinal": 40, "cycle": 14,
             "kind": "projectile.create", "subject": "unit:7",
             "fields": {"fixture_cycle": 12, "creation_ordinal": 3,
                        "pool_slot": 5, "type": 15,
                        "type_ident": "missile-arrow", "source": 7,
                        "target": 9, "remaining": 48}},
            {"schema": 1, "side": "java", "ordinal": 91, "cycle": 18,
             "kind": "projectile.free", "subject": "unit:7",
             "fields": {"fixture_cycle": 16, "creation_ordinal": 3,
                        "pool_slot": 5, "type": 15,
                        "type_ident": "missile-arrow", "source": 7,
                        "target": 9, "remaining": -1}},
        ]
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "causal.jsonl"
            path.write_text("\n".join(json.dumps(row) for row in records) + "\n")
            loaded = ledger.load_java_lifecycle(path)["lifecycle"]

        self.assertEqual([row["cycle"] for row in loaded], [12, 16])
        self.assertEqual([row["ordinal"] for row in loaded], [3, 3])
        self.assertEqual(loaded[0]["type"], 15)
        self.assertEqual(loaded[0]["pool_slot"], 5)

    def test_visual_frame_and_facing_are_named_evidence(self):
        """The renderer fields are decoded rather than read from raw hex."""
        decoded = ledger.decode_projectile(raw_bullet(
            live=True, kind=15, frame=2, facing=7))

        self.assertEqual(decoded["frame"], 2)
        self.assertEqual(decoded["facing"], 7)

    def test_a_shot_that_lands_frees_its_slot_for_a_later_shot(self):
        """An arrow lands, and the next arrow is built in the slot it left."""
        arrow = raw_bullet(live=True, x=10, y=10, kind=15, remaining=24)
        flying = raw_bullet(live=True, x=22, y=10, kind=15, remaining=12)
        landed = raw_bullet(live=False, x=34, y=10, kind=15, remaining=-1)
        second = raw_bullet(live=True, x=50, y=50, kind=15, remaining=36)
        native = read(state_stream([
            (1, checkpoint(8, {})),
            (2, [(3, 1, arrow)]),
            (3, [(3, 1, flying)]),
            (4, [(3, 1, landed)]),
            (5, [(3, 2, second)]),
        ]), through=5)

        lifetimes = native["lifetimes"]
        self.assertEqual(len(lifetimes), 2,
                         "slot 3 carried two shots, so it has two lifetimes")
        first, later = lifetimes
        self.assertEqual(first["creation_cycle"], 2,
                         "the first arrow was created on cycle 2")
        self.assertEqual(first["final_occupied_cycle"], 3,
                         "the first arrow was last in flight on cycle 3")
        self.assertEqual(first["free_cycle"], 4,
                         "the first arrow landed and freed its slot on cycle 4")
        self.assertEqual(first["reuse_cycle"], 5,
                         "slot 3 was taken again on cycle 5")
        self.assertEqual(first["generation"], 1,
                         "the first occupancy of slot 3 is generation 1")
        self.assertEqual(later["generation"], 2,
                         "reusing slot 3 starts a second generation, so one "
                         "slot number never silently names two shots")
        self.assertIsNone(later["free_cycle"],
                          "the second arrow is still flying at the window end")

    def test_a_slot_freed_this_cycle_is_not_given_to_this_cycle_s_new_shot(self):
        """Two shots fire on the cycle an older shot lands, and sit above it.

        This is the ordering that has been read wrongly by hand. The finding
        has to record it as a cycle-boundary state and stop there: a snapshot
        per cycle cannot show whether the free ran after the allocation or
        whether the low slot was used and released again in between.
        """
        old = raw_bullet(live=True, x=10, y=10, kind=15, remaining=12)
        landed = raw_bullet(live=False, x=20, y=10, kind=15, remaining=-1)
        new_a = raw_bullet(live=True, x=30, y=30, kind=15, remaining=48)
        new_b = raw_bullet(live=True, x=40, y=40, kind=16, remaining=60)
        native = read(state_stream([
            (1, checkpoint(8, {})),
            (2, [(3, 1, old)]),
            (3, [(3, 1, landed), (5, 1, new_a), (6, 1, new_b)]),
        ]), through=3)

        findings = ledger.allocation_findings(native)
        self.assertEqual(len(findings), 2,
                         "both new shots skipped the slot freed this cycle")
        self.assertEqual([f["slot"] for f in findings], [5, 6],
                         "the two new shots took slots 5 and 6")
        for finding in findings:
            self.assertEqual(finding["skipped_slots_freed_this_cycle"], [3],
                             "slot 3 ended this cycle free and below both "
                             "new shots")

    def test_the_same_cycle_finding_stops_short_of_claiming_the_order(self):
        """The finding must not turn a boundary state into a call order.

        One snapshot per cycle cannot see a slot freed, reallocated and freed
        again inside that cycle, and such a lifetime never crosses a boundary
        so it never increments a generation. The finding has to say that.
        """
        native = read(state_stream([
            (1, checkpoint(8, {})),
            (2, [(3, 1, raw_bullet(live=True, kind=15, remaining=12))]),
            (3, [(3, 1, raw_bullet(live=False, kind=15, remaining=-1)),
                 (5, 1, raw_bullet(live=True, kind=15, remaining=48))]),
        ]), through=3)

        finding = ledger.allocation_findings(native)[0]
        self.assertIn("consistent with", finding["conclusion"],
                      "the conclusion is a qualified reading, not a verdict")
        self.assertIn("assuming no hidden same-cycle lifetime",
                      finding["conclusion"],
                      "the assumption the reading rests on is stated in it")
        self.assertTrue(
            any("freed again inside" in item for item in finding["not_excluded"]),
            "a birth and death inside one cycle is named as unexcluded")
        self.assertTrue(
            any("does not scan from slot 0" in item
                for item in finding["not_excluded"]),
            "an allocator that does not start at slot 0 is named too")
        self.assertNotIn("conclusion", finding["observation"],
                         "the observation states the boundary state only")

    def test_the_captured_pool_count_is_not_called_an_allocator_scan_bound(self):
        """400 is what a global held, not how far the allocator looked."""
        native = read(state_stream([(1, checkpoint(8, {}))], pool=8), through=1)

        evidence = native["pool_count_evidence"]
        self.assertEqual(evidence["value"], 8,
                         "the reported value is the captured pool count")
        self.assertFalse(
            evidence["allocator_scan_bound_proved"],
            "nothing in the capture measures how far the allocator scans")
        self.assertIn("0x004ae268", evidence["measures"],
                      "the report names the global the value came from")

        report = ledger.build_ledger(native, None, case="pool-proof", through=1)
        published = report["native"]["pool_count_evidence"]
        self.assertEqual(published, evidence,
                         "the qualified evidence survives into the published ledger")
        self.assertFalse(published["allocator_scan_bound_proved"],
                         "the published artifact must not restore the overclaim")

    def test_the_creation_order_of_two_shots_is_not_their_slot_order(self):
        """A shot created second can occupy a lower slot than the first.

        Creation ordinal and slot number are separate facts. The ledger has to
        keep both, because pairing Java against native by slot when only the
        creation order is known is how a false correspondence gets made.
        """
        native = read(state_stream([
            (1, checkpoint(8, {(6): raw_bullet(live=True, kind=15,
                                               remaining=10)})),
            (2, [(2, 1, raw_bullet(live=True, x=5, y=5, kind=16,
                                   remaining=20))]),
        ]), through=2)

        by_creation = [(lt["creation_ordinal"], lt["slot"])
                       for lt in native["lifetimes"]]
        self.assertEqual(by_creation, [(0, 6), (1, 2)],
                         "the shot created first sits in the higher slot, so "
                         "creation order and slot order disagree")

    def test_the_scan_order_of_the_capture_is_not_offered_as_execution_order(self):
        """The ledger refuses to present ascending slot order as an ordering."""
        native = read(state_stream([(1, checkpoint(4, {}))]), through=1)
        order = native["record_order"]
        self.assertFalse(order["carries_execution_order"],
                         "the tracer scans the pool in ascending slot order, "
                         "so the record order proves nothing about the order "
                         "BNE updated the slots")


class SourceAndTargetTest(unittest.TestCase):
    def test_pointers_a_whole_unit_apart_are_reported_without_naming_a_slot(self):
        """Source and target index the unit pool, but the base is unknown."""
        native = read(state_stream([
            (1, checkpoint(8, {})),
            (2, [(3, 1, raw_bullet(live=True, kind=15, remaining=12,
                                   source=0x05066978, target=0x050668e0))]),
        ]), through=2)

        correspondence = native["unit_pointer_correspondence"]
        self.assertEqual(correspondence["state"], "unit-pointers-relative-only",
                         "two pointers exactly one 152-byte unit apart index "
                         "the unit pool")
        self.assertFalse(correspondence["absolute_slots_known"],
                         "the fixture never records where the unit pool "
                         "starts, so no absolute unit slot may be claimed")
        self.assertEqual(correspondence["relative_index_span"], 1,
                         "the shooter and its target are adjacent units")

    def test_dwords_that_are_not_a_unit_apart_are_refused_as_unit_pointers(self):
        """A pointer pair that does not share the unit stride names nothing."""
        native = read(state_stream([
            (1, checkpoint(8, {})),
            (2, [(3, 1, raw_bullet(live=True, kind=15, remaining=12,
                                   source=0x05066978, target=0x05066979))]),
        ]), through=2)

        self.assertEqual(
            native["unit_pointer_correspondence"]["state"], "not-unit-pointers",
            "dwords one byte apart cannot both address 152-byte unit records")


class JavaCorrespondenceTest(unittest.TestCase):
    def test_without_java_missile_evidence_the_ledger_says_so(self):
        """No Java lifecycle means no verdict, and a named gap instead."""
        native = read(state_stream([
            (1, checkpoint(8, {})),
            (2, [(3, 1, raw_bullet(live=True, kind=15, remaining=12))]),
        ]), through=2)
        report = ledger.build_ledger(native, None, case="synthetic", through=2)

        self.assertEqual(report["classification"], "unknown-correspondence",
                         "with nothing to compare, the ledger must not invent "
                         "a verdict")
        self.assertEqual(ledger.exit_code(report), 2,
                         "an unprovable comparison is its own exit code, not "
                         "a pass and not a failure")
        gaps = [gap["gap"] for gap in report["evidence_gaps"]]
        self.assertIn("java-projectile-lifecycle", gaps,
                      "the missing Java side is reported as a gap")
        gap = report["evidence_gaps"][0]
        self.assertTrue(gap["instrumentation_plan"],
                        "a gap is only useful with the smallest change that "
                        "would close it")
        self.assertTrue(
            any("Missile.java" in path for path in gap["blocked_on_files"]),
            "the plan names the files it would have to touch")

    def test_a_shot_java_frees_a_cycle_late_is_called_free_timing(self):
        """Java holds a landed shot one cycle longer than BNE does."""
        native = read(state_stream([
            (1, checkpoint(8, {})),
            (2, [(3, 1, raw_bullet(live=True, kind=15, remaining=12))]),
            (3, [(3, 1, raw_bullet(live=False, kind=15, remaining=-1))]),
        ]), through=3)
        java = {"lifecycle": [
            {"cycle": 2, "event": "create", "type": 15},
            {"cycle": 4, "event": "free", "type": 15},
        ], "source": "synthetic"}
        report = ledger.build_ledger(native, java, case="synthetic", through=3)

        first = report["first_disagreement"]
        self.assertIsNotNone(first, "the late free is a disagreement")
        self.assertEqual(first["class"], "free-timing",
                         "the shot was created in step but freed a cycle late")
        self.assertEqual(first["cycle"], 3,
                         "cycle 3 is where BNE freed the shot and Java did not")
        self.assertEqual(ledger.exit_code(report), 1,
                         "a named mismatch fails")

    def test_a_shot_java_never_fires_is_called_allocation_order(self):
        """Java creates fewer shots on a cycle than BNE does."""
        native = read(state_stream([
            (1, checkpoint(8, {})),
            (2, [(3, 1, raw_bullet(live=True, kind=15, remaining=12)),
                 (4, 1, raw_bullet(live=True, kind=16, remaining=20))]),
        ]), through=2)
        java = {"lifecycle": [
            {"cycle": 2, "event": "create", "type": 15},
        ], "source": "synthetic"}
        report = ledger.build_ledger(native, java, case="synthetic", through=2)

        self.assertEqual(report["first_disagreement"]["class"],
                         "allocation-order",
                         "BNE fired two shots on cycle 2 and Java fired one")

    def test_matching_lifecycles_agree(self):
        native = read(state_stream([
            (1, checkpoint(8, {})),
            (2, [(3, 1, raw_bullet(live=True, kind=15, remaining=12))]),
            (3, [(3, 1, raw_bullet(live=False, kind=15, remaining=-1))]),
        ]), through=3)
        java = {"lifecycle": [
            {"cycle": 2, "event": "create", "type": 15},
            {"cycle": 3, "event": "free", "type": 15},
        ], "source": "synthetic"}
        report = ledger.build_ledger(native, java, case="synthetic", through=3)

        self.assertEqual(report["classification"], "agreed",
                         "the same shot fired and landed on the same cycles")
        self.assertEqual(ledger.exit_code(report), 0, "agreement passes")


class PublicationTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp()).resolve()
        self.addCleanup(shutil.rmtree, self.root, ignore_errors=True)
        self.fixture = sealed(self.root / "case.bnefx", state_stream([
            (1, checkpoint(8, {})),
            (2, [(3, 1, raw_bullet(live=True, kind=15, remaining=12))]),
            (3, [(3, 1, raw_bullet(live=False, kind=15, remaining=-1))]),
        ]))
        self.artifacts = self.root / "artifacts"

    def test_a_run_publishes_every_named_output_and_an_atomic_pointer(self):
        status, run_root = ledger.run_projectile_ledger(
            self.fixture, self.artifacts, through=3, case="synthetic")

        self.assertEqual(status, 2, "no Java evidence was supplied")
        for name in ("PROJECTILE-LEDGER.json", "PROJECTILE-LEDGER.md",
                     "NEXT.md", "manifest.json"):
            self.assertTrue((run_root / name).is_file(),
                            f"the run publishes {name}")
        pointer = json.loads(
            (self.artifacts / "latest.json").read_text(encoding="utf-8"))
        self.assertEqual(pointer["run"], str(run_root.relative_to(self.artifacts)),
                         "the latest pointer names the run that was written")
        self.assertFalse(
            [p for p in self.artifacts.rglob("*.tmp")],
            "publication leaves no staging file behind")

    def test_the_next_note_names_the_unresolved_question(self):
        _status, run_root = ledger.run_projectile_ledger(
            self.fixture, self.artifacts, through=3, case="synthetic")
        text = (run_root / "NEXT.md").read_text(encoding="utf-8")
        self.assertIn("no projectile lifecycle evidence", text.lower(),
                      "NEXT.md says what is unresolved and why")

    def test_repeating_a_run_reuses_the_sealed_result(self):
        status, first = ledger.run_projectile_ledger(
            self.fixture, self.artifacts, through=3, case="synthetic")
        report = (first / "PROJECTILE-LEDGER.json").read_bytes()
        again, second = ledger.run_projectile_ledger(
            self.fixture, self.artifacts, through=3, case="synthetic")

        self.assertEqual(first, second,
                         "the same request is the same content-addressed run")
        self.assertEqual(status, again, "a cache hit reports the same verdict")
        self.assertEqual(report, (second / "PROJECTILE-LEDGER.json").read_bytes(),
                         "a cache hit does not rewrite the report")

    def test_a_rewritten_report_is_refused_rather_than_reused(self):
        _status, run_root = ledger.run_projectile_ledger(
            self.fixture, self.artifacts, through=3, case="synthetic")
        (run_root / "PROJECTILE-LEDGER.md").write_text("edited\n",
                                                       encoding="utf-8")

        with self.assertRaises(ValueError) as raised:
            ledger.run_projectile_ledger(
                self.fixture, self.artifacts, through=3, case="synthetic")
        self.assertIn("changed", str(raised.exception),
                      "an edited artifact cannot be served as a cache hit")

    def test_a_fixture_edited_after_a_run_produces_a_different_run(self):
        _status, first = ledger.run_projectile_ledger(
            self.fixture, self.artifacts, through=3, case="synthetic")
        sealed(self.fixture, state_stream([
            (1, checkpoint(8, {})),
            (2, [(4, 1, raw_bullet(live=True, kind=16, remaining=20))]),
        ]))
        _again, second = ledger.run_projectile_ledger(
            self.fixture, self.artifacts, through=3, case="synthetic")

        self.assertNotEqual(first, second,
                            "changing the evidence changes the run identity, "
                            "so an old verdict is never served for new bytes")


class EvidenceAuthenticationTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root, ignore_errors=True)
        self.stream = state_stream([
            (1, checkpoint(8, {})),
            (2, [(3, 1, raw_bullet(live=True, kind=15, remaining=12))]),
        ])

    def _survey(self, *, fixture: Path, case: str,
                sha256: str | None = None, fixture_id: str = "fid-1",
                indexed_id: str = "fid-1",
                relative: str | None = None) -> Path:
        import hashlib
        data = fixture.read_bytes()
        index = {"cases": [{
            "id": case,
            "fixture": {
                "path": relative or fixture.name,
                "bytes": len(data),
                "sha256": sha256 or hashlib.sha256(data).hexdigest(),
            },
            "fixture_id": indexed_id,
        }]}
        index_path = self.root / "corpus-index.json"
        index_path.write_text(json.dumps(index), encoding="utf-8")
        survey = {"schema": 1, "index": str(index_path),
                  "cases": [{"id": case, "fixture_id": fixture_id}]}
        survey_path = self.root / "survey.json"
        survey_path.write_text(json.dumps(survey), encoding="utf-8")
        return survey_path

    def test_a_fixture_matching_the_index_authenticates(self):
        fixture = sealed(self.root / "case.bnefx", self.stream)
        survey = self._survey(fixture=fixture, case="case-a")
        status, _run = ledger.run_projectile_ledger(
            fixture, self.root / "artifacts", through=2, case="case-a",
            survey=survey)
        self.assertEqual(status, 2, "the sealed fixture was accepted")

    def test_a_fixture_whose_bytes_changed_since_sealing_is_refused(self):
        fixture = sealed(self.root / "case.bnefx", self.stream)
        survey = self._survey(fixture=fixture, case="case-a")
        sealed(fixture, state_stream([(1, checkpoint(8, {}))]))

        with self.assertRaises(ValueError) as raised:
            ledger.run_projectile_ledger(
                fixture, self.root / "artifacts", through=2, case="case-a",
                survey=survey)
        self.assertIn("identity changed", str(raised.exception),
                      "a bundle edited after sealing is not evidence")

    def test_a_survey_and_index_naming_different_fixtures_are_refused(self):
        fixture = sealed(self.root / "case.bnefx", self.stream)
        survey = self._survey(fixture=fixture, case="case-a",
                              fixture_id="fid-1", indexed_id="fid-2")

        with self.assertRaises(ValueError) as raised:
            ledger.run_projectile_ledger(
                fixture, self.root / "artifacts", through=2, case="case-a",
                survey=survey)
        self.assertIn("different fixture identities", str(raised.exception),
                      "a survey that disagrees with the index is not evidence")

    def test_an_index_path_escaping_the_corpus_is_refused(self):
        outside = self.root / "outside"
        outside.mkdir()
        fixture = sealed(outside / "case.bnefx", self.stream)
        inside = self.root / "corpus"
        inside.mkdir()
        index_path = inside / "corpus-index.json"
        import hashlib
        data = fixture.read_bytes()
        index_path.write_text(json.dumps({"cases": [{
            "id": "case-a",
            "fixture": {"path": "../outside/case.bnefx", "bytes": len(data),
                        "sha256": hashlib.sha256(data).hexdigest()},
            "fixture_id": "fid-1",
        }]}), encoding="utf-8")
        survey_path = self.root / "survey.json"
        survey_path.write_text(json.dumps({
            "schema": 1, "index": str(index_path),
            "cases": [{"id": "case-a", "fixture_id": "fid-1"}]}),
            encoding="utf-8")

        with self.assertRaises(ValueError) as raised:
            ledger.run_projectile_ledger(
                fixture, self.root / "artifacts", through=2, case="case-a",
                survey=survey_path)
        self.assertIn("unsafe fixture path", str(raised.exception),
                      "an index entry may not reach outside the corpus it "
                      "belongs to")

    def test_a_symlinked_fixture_pointing_outside_the_corpus_is_refused(self):
        outside = self.root / "outside"
        outside.mkdir()
        real = sealed(outside / "real.bnefx", self.stream)
        inside = self.root / "corpus"
        inside.mkdir()
        link = inside / "case.bnefx"
        try:
            link.symlink_to(real)
        except (OSError, NotImplementedError):
            self.skipTest("this filesystem does not support symlinks")
        index_path = inside / "corpus-index.json"
        import hashlib
        data = real.read_bytes()
        index_path.write_text(json.dumps({"cases": [{
            "id": "case-a",
            "fixture": {"path": "case.bnefx", "bytes": len(data),
                        "sha256": hashlib.sha256(data).hexdigest()},
            "fixture_id": "fid-1",
        }]}), encoding="utf-8")
        survey_path = self.root / "survey.json"
        survey_path.write_text(json.dumps({
            "schema": 1, "index": str(index_path),
            "cases": [{"id": "case-a", "fixture_id": "fid-1"}]}),
            encoding="utf-8")

        with self.assertRaises(ValueError) as raised:
            ledger.run_projectile_ledger(
                link, self.root / "artifacts", through=2, case="case-a",
                survey=survey_path)
        self.assertIn("unsafe fixture path", str(raised.exception),
                      "an indexed name that resolves through a symlink to "
                      "outside the corpus is not the indexed fixture")


class StreamRejectionTest(unittest.TestCase):
    def test_a_capture_cut_short_inside_the_window_is_refused(self):
        """A capture that ends mid-chunk is incomplete, not merely short."""
        stream = state_stream([
            (1, checkpoint(8, {})),
            (2, [(3, 1, raw_bullet(live=True, kind=15, remaining=12))]),
        ])
        with self.assertRaises(ValueError) as raised:
            read(stream[:-40], through=2)
        self.assertIn("truncated", str(raised.exception),
                      "a capture missing the end of a cycle it was asked for "
                      "cannot be read as if it were whole")

    def test_the_unit_only_schema_cannot_supply_a_projectile_pool(self):
        header = struct.pack("<8sHHIIIII", b"BNESTATE", 1, 0, 32, 152, 1600,
                             16, 1)
        with self.assertRaises(ValueError) as raised:
            read(header, through=1)
        self.assertIn("schema 1.1", str(raised.exception),
                      "schema 1.0 has no projectile deltas to read")

    def test_an_aux_chunk_carrying_more_bytes_than_it_declares_is_refused(self):
        """Extra bytes inside a cycle mean the capture is not what it says."""
        header = struct.pack("<8sHHIIIII", b"BNESTATE", 1, 1, 32, 152, 1600,
                             16, 15)
        records = [struct.pack("<8H10B2x4I", *([0] * 22)) for _ in range(16)]
        body = b"".join([struct.pack("<IIIII", 1, 4, 0, 2, 0), *records,
                         b"JUNK"])
        stream = (header + cycle_chunk(1, 1)
                  + struct.pack("<4sI", b"AUXL", len(body)) + body)

        with self.assertRaises(ValueError) as raised:
            read(stream, through=1)
        self.assertIn("trailing payload", str(raised.exception),
                      "a cycle with unaccounted bytes is a broken capture")


class CommandSurfaceTest(unittest.TestCase):
    def test_the_parser_accepts_the_command_the_ledger_documents(self):
        """Every argument the ledger's own report suggests must really parse."""
        arguments = [
            "projectile-ledger",
            "--fixture", "work/corpus/campaign-1800/cases/case.bnefx",
            "--through", "50",
            "--case", "retail-xhuman-10-idle",
            "--survey", "work/survey.json",
            "--java-causal", "work/causal.jsonl",
            "--artifact-root", ".bne-projectile-ledger",
        ]
        parsed = bne_java.parser().parse_args(arguments)

        self.assertEqual(parsed.func, bne_java.projectile_ledger_command,
                         "the subcommand reaches the ledger")
        self.assertEqual(parsed.through, 50, "the cycle bound parses")
        self.assertEqual(parsed.case, "retail-xhuman-10-idle")

    def test_the_fixture_and_cycle_bound_are_required(self):
        # argparse writes its usage to stderr before exiting; the test only
        # cares that the command is refused, so the usage text is swallowed.
        with contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):
                bne_java.parser().parse_args(
                    ["projectile-ledger", "--through", "5"])
            with self.assertRaises(SystemExit):
                bne_java.parser().parse_args(
                    ["projectile-ledger", "--fixture", "case.bnefx"])


if __name__ == "__main__":
    unittest.main()
