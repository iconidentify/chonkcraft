from pathlib import Path
import json
import sys
import tempfile
import unittest


SCRIPTS = Path(__file__).parents[1] / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import bne_micro_oracle as mo
import bne_snapshot_capture as capture

from synthetic_decision import (
    CODE_BASE, DATA_BASE, OUTCOME_ADDRESS, REACH_ADDRESS, STACK_BASE,
    decision_code,
)


#: Where the capture pretends the caller is. Nothing executes here; it is the
#: address the invocation returns to, which the import replaces with the
#: replay sentinel.
CALLER = 0x00110000

CODE_BYTES = 64
DATA_BYTES = 64
STACK_LOW = STACK_BASE + 0x400
STACK_BYTES = 0x800
ENTRY_ESP = STACK_BASE + 0x800


def specification(**overrides):
    """The reviewed part of a capture of the synthetic decision."""
    document = {
        "schema": 1,
        "case": "synthetic-decide",
        "cycle": 23,
        "entry": CODE_BASE,
        "hit": 1,
        "executable_sha256": None,
        "regions": [
            {"label": "code", "access": "rx", "address": CODE_BASE,
             "bytes": CODE_BYTES},
            {"label": "stack", "access": "rw", "register": "esp",
             "offset": -0x400, "bytes": STACK_BYTES},
            {"label": "data", "access": "rw", "address": DATA_BASE,
             "bytes": DATA_BYTES},
        ],
        "inputs": [
            {"name": "counter", "kind": "register", "register": "ecx"},
            {"name": "limit", "kind": "register", "register": "edx"},
            {"name": "reach", "kind": "memory", "address": REACH_ADDRESS,
             "width": 4},
        ],
    }
    document.update(overrides)
    return document


def _dump(label, phase, address, data):
    lines = [f"BNESNAPSHOTMEM phase={phase} label={label} "
             f"address=0x{address:08x} bytes={len(data)}"]
    for offset in range(0, len(data), 8):
        row = data[offset:offset + 8]
        lines.append(f"0x{address + offset:x} <region+{offset}>:\t"
                     + "\t".join(f"0x{value:02x}" for value in row))
    return lines


def _registers(phase, values):
    return [f"BNESNAPSHOTREG phase={phase} name={name} value=0x{value:08x}"
            for name, value in values.items()]


def machine(counter=3, limit=9, reach=4, outcome=0xAAAAAAAA):
    """The bytes of the captured process, before the invocation runs."""
    code = decision_code().ljust(CODE_BYTES, b"\x00")
    data = bytearray(DATA_BYTES)
    data[0:4] = reach.to_bytes(4, "little")
    data[OUTCOME_ADDRESS - DATA_BASE:OUTCOME_ADDRESS - DATA_BASE + 4] = \
        outcome.to_bytes(4, "little")
    stack = bytearray(STACK_BYTES)
    stack[ENTRY_ESP - STACK_LOW:ENTRY_ESP - STACK_LOW + 4] = \
        CALLER.to_bytes(4, "little")
    return {"code": bytes(code), "data": bytes(data), "stack": bytes(stack),
            "counter": counter, "limit": limit, "reach": reach}


def capture_log(state=None, *, entry=CODE_BASE, hit=1, return_address=CALLER,
        return_esp=ENTRY_ESP + 4, instructions=None, data_after=None,
        stack_after=None, drop_return=False, short_code=False,
        hole_in_data=False):
    """A GDB capture log of one invocation of the synthetic decision.

    Written by hand rather than recorded, so the importer can be proved
    without a copy of the game, an oracle, or a debugger anywhere near the
    tests -- exactly as the branch recorder's importer is.
    """
    state = state or machine()
    counter, limit = state["counter"], state["limit"]
    taken = counter + state["reach"] <= limit
    result = 0 if taken else 1
    if data_after is None:
        data_after = bytearray(state["data"])
        data_after[OUTCOME_ADDRESS - DATA_BASE:OUTCOME_ADDRESS - DATA_BASE + 4] \
            = result.to_bytes(4, "little")
        data_after = bytes(data_after)
    stack_after = state["stack"] if stack_after is None else stack_after
    entry_registers = {
        "eax": 0, "ebx": 0, "ecx": counter, "edx": limit, "esi": 0, "edi": 0,
        "ebp": STACK_BASE + 0x900, "esp": ENTRY_ESP, "eip": entry,
        "eflags": 0x202,
    }
    return_registers = dict(entry_registers)
    return_registers.update({"eax": result, "esp": return_esp,
                             "eip": return_address, "eflags": 0x246})

    lines = [f"BNESNAPSHOT phase=entry entry=0x{entry:08x} hit={hit}"]
    lines.extend(_registers("entry", entry_registers))
    code = state["code"]
    code_lines = _dump("code", "entry", CODE_BASE, code)
    if short_code:
        # The debugger said how much it was about to print and then printed
        # less, which is what a region running off the end of a mapping does.
        code_lines = code_lines[:-2]
    lines.extend(code_lines)
    lines.extend(_dump("stack", "entry", STACK_LOW, state["stack"]))
    data_lines = _dump("data", "entry", DATA_BASE, state["data"])
    if hole_in_data:
        del data_lines[3]
    lines.extend(data_lines)
    if instructions is None:
        instructions = [
            (CODE_BASE + 0x00, "mov    0x200000,%eax"),
            (CODE_BASE + 0x06, "add    %ecx,%eax"),
            (CODE_BASE + 0x08, "cmp    %edx,%eax"),
            (CODE_BASE + 0x0a, f"jle    0x{CODE_BASE + 0x1c:08x}"),
        ]
        instructions.extend([
            (CODE_BASE + 0x1c, "movl   $0x0,0x200010"),
            (CODE_BASE + 0x26, "mov    $0x0,%eax"),
            (CODE_BASE + 0x2b, "ret"),
        ] if taken else [
            (CODE_BASE + 0x0c, "movl   $0x1,0x200010"),
            (CODE_BASE + 0x16, "mov    $0x1,%eax"),
            (CODE_BASE + 0x1b, "ret"),
        ])
        instructions.append((return_address, "nop"))
    if not drop_return:
        lines.append(f"BNESNAPSHOTRET address=0x{return_address:08x} "
                     f"esp=0x{return_esp:08x}")
    lines.extend(_registers("return", return_registers))
    lines.extend(_dump("code", "return", CODE_BASE, code))
    lines.extend(_dump("stack", "return", STACK_LOW, stack_after))
    lines.extend(_dump("data", "return", DATA_BASE, data_after))
    for index, (address, text) in enumerate(instructions, start=1):
        lines.append(f"{index:>6}  0x{address:08x} <decide+{index}>:\t{text}")
    return "\n".join(lines) + "\n"


class SnapshotCaptureScriptTest(unittest.TestCase):
    """What the debugger is told to do, and what it must not be told to do."""

    def test_the_script_stops_at_one_activation_of_one_address(self):
        text = capture.gdb_commands(
            specification(hit=3, focus={"native_slot": 12, "register": "esi"}),
            history_log=Path("/tmp/capture.log"),
            resume_marker=Path("/tmp/resume"),
        )
        self.assertIn("break *0x00100000 if (unsigned int)$esi", text,
                      "the capture stopped at every activation of the "
                      "address, so it could have saved another unit's decision")
        self.assertIn("ignore $bpnum 2", text,
                      "the third activation was asked for and the first was "
                      "captured")
        self.assertLess(text.index("shell touch /tmp/resume"),
                        text.index("BNESNAPSHOT phase=entry"),
                        "the paused oracle was never released, so the "
                        "breakpoint could not be reached")

    def test_the_machine_is_printed_before_and_after_the_invocation(self):
        text = capture.gdb_commands(
            specification(), history_log=Path("/tmp/capture.log"),
            resume_marker=Path("/tmp/resume"))
        for phase in ("entry", "return"):
            for name in ("eax", "esp", "eip", "eflags"):
                self.assertIn(f"BNESNAPSHOTREG phase={phase} name={name}", text,
                              f"the {phase} machine did not print {name}, so "
                              f"the invocation cannot be replayed or checked")
            self.assertIn(f"BNESNAPSHOTMEM phase={phase} label=data", text,
                          f"the {phase} phase did not dump the data region")
        self.assertIn("set $bne_return_pc = *(unsigned int*)$esp", text,
                      "the return PC was not read from the real entry stack")
        self.assertIn("tbreak *$bne_return_pc", text,
                      "the capture still depends on debugger frame unwind")
        self.assertNotIn("\nfinish\n", text,
                         "the default capture still uses GDB finish, which "
                         "fails on Wine's outermost frame")
        self.assertLess(text.index("record btrace bts"),
                        text.index("tbreak *$bne_return_pc"),
                        "the branch history was recorded after the "
                        "invocation had already run")

    def test_a_capture_that_reads_no_stack_is_refused(self):
        document = specification()
        document["regions"] = [document["regions"][0]]
        with self.assertRaises(capture.CaptureError) as raised:
            capture.load_specification(document)
        self.assertIn("stack", str(raised.exception),
                      "a capture with no stack was accepted, and a call or a "
                      "return in it would read memory nobody saved")

    def test_a_capture_that_reads_no_code_is_refused(self):
        document = specification()
        document["regions"] = document["regions"][1:]
        with self.assertRaises(capture.CaptureError) as raised:
            capture.load_specification(document)
        self.assertIn("code", str(raised.exception),
                      "a capture with no executable region was accepted")

    def test_a_capture_larger_than_a_bounded_decision_is_refused(self):
        document = specification()
        document["regions"][0]["bytes"] = 1024 * 1024
        with self.assertRaises(capture.CaptureError) as raised:
            capture.load_specification(document)
        self.assertIn("one region may carry", str(raised.exception),
                      "a capture asked for a megabyte of the process and was "
                      "accepted, which is a core dump and not evidence about "
                      "one decision")

    def test_a_native_entry_outside_the_pinned_text_is_refused(self):
        document = specification()
        document["executable_sha256"] = capture.BNE_202_SHA256
        with self.assertRaises(capture.CaptureError) as raised:
            capture.load_specification(document)
        self.assertIn("pinned BNE text", str(raised.exception),
                      "a capture claiming BNE was allowed to point at an "
                      "address the game's code never occupies")


class SnapshotDraftTest(unittest.TestCase):
    """Turning a localized branch into something a capture can be run from."""

    def artifact(self):
        return {
            "case": "retail-xhuman-12-idle", "cycle": 23,
            "fixture_id": "b" * 64,
            "scenario": "Campaign\\XHuman\\Human12.pud", "seed": 1,
            "top_branch": {"address": 0x00437646, "instruction": "jle",
                           "taken": True},
            "writer": {"address": 0x00402451, "field": "x"},
            "focus": {"native_slot": 12, "java_id": 5},
        }

    def test_a_draft_is_a_specification_the_capture_agent_accepts(self):
        draft = capture.specification_from_branch_witness(self.artifact())
        # The branch witness knows the unit slot but not which live register
        # carries its record.  This is the required human review step.
        draft["focus"] = {"native_slot": 12, "register": "esi"}
        loaded = capture.load_specification(draft)
        self.assertEqual(0x00437646, loaded["entry"],
                         "the draft captures somewhere other than the branch "
                         "the witness localized")
        self.assertEqual(23, loaded["cycle"],
                         "the draft does not carry the cycle the oracle has "
                         "to be paused at")
        self.assertEqual("b" * 64, loaded["identity"]["fixture_id"])

    def test_a_draft_says_what_the_evidence_could_not_decide(self):
        draft = capture.specification_from_branch_witness(self.artifact())
        self.assertEqual([], draft["inputs"],
                         "the draft invented reviewed inputs, and what a "
                         "register holds is the one thing this evidence "
                         "cannot say")
        self.assertTrue(
            any("slot 12" in item for item in draft["review_required"]),
            "the draft did not ask for the unit record the decision is about, "
            "which moves between runs and cannot be pinned here")

    def test_a_witness_that_localized_nothing_is_refused(self):
        artifact = self.artifact()
        artifact["top_branch"] = {}
        with self.assertRaises(capture.CaptureError) as raised:
            capture.specification_from_branch_witness(artifact)
        self.assertIn("localizes no branch", str(raised.exception),
                      "a capture was drafted from evidence that names no "
                      "decision, so there would be nothing to stop at")


class SnapshotImportTest(unittest.TestCase):
    """Rebuilding one replayable invocation from the log and nothing else."""

    def snapshot(self, **kwargs):
        return capture.snapshot_from_gdb_log(
            specification(), capture_log(**kwargs))

    def test_the_captured_machine_becomes_a_loadable_snapshot(self):
        document = self.snapshot()
        loaded = mo.load_snapshot(document, expected_executable=None)
        self.assertEqual(CODE_BASE, loaded.entry,
                         "the snapshot starts somewhere other than the "
                         "instruction the capture stopped at")
        self.assertEqual(3, loaded.registers["ecx"],
                         "the counter the decision was taken with did not "
                         "survive the import")
        self.assertEqual(ENTRY_ESP, loaded.registers["esp"],
                         "the stack pointer did not survive the import")
        self.assertEqual(
            {"counter", "limit", "reach"},
            {item.name for item in loaded.inputs},
            "the reviewed inputs were dropped, so exploration would have "
            "nothing it is allowed to vary")

    def test_the_return_address_is_replaced_by_the_sentinel_and_says_so(self):
        document = self.snapshot()
        loaded = mo.load_snapshot(document, expected_executable=None)
        slot = int.from_bytes(
            bytes(loaded.byte_at(ENTRY_ESP + step) for step in range(4)),
            "little")
        self.assertEqual(mo.RETURN_SENTINEL, slot,
                         "the captured return address was left on the stack, "
                         "so a replay would run on into the caller instead of "
                         "stopping where the invocation ended")
        self.assertEqual(CALLER, document["provenance"]["return_address"],
                         "the address that was replaced is not written down, "
                         "so the substitution is invisible")

    def test_the_outcome_the_capture_recorded_travels_with_it(self):
        expected = self.snapshot()["expected"]
        self.assertEqual(0, expected["registers"]["eax"],
                         "the value the invocation returned was not recorded")
        self.assertEqual([{"address": CODE_BASE + 0x0a, "taken": True}],
                         expected["branches"],
                         "the decision the capture exists for is not in its "
                         "own record of what happened")
        self.assertEqual(
            [{"address": OUTCOME_ADDRESS, "before_hex": "aaaaaaaa",
              "after_hex": "00000000"}],
            expected["memory_delta"],
            "the bytes the invocation changed were not recorded, so a replay "
            "could write anywhere and still look like a reproduction")

    def test_a_region_that_came_back_short_is_refused(self):
        with self.assertRaises(capture.CaptureError) as raised:
            self.snapshot(short_code=True)
        self.assertIn("came back with", str(raised.exception),
                      "half a code region was accepted, and the replay would "
                      "have run off the end of it")

    def test_a_region_with_a_hole_in_it_is_refused(self):
        with self.assertRaises(capture.CaptureError) as raised:
            self.snapshot(hole_in_data=True)
        self.assertIn("hole", str(raised.exception),
                      "a dump that skipped a line was accepted, so bytes the "
                      "game never held would have been shifted into place")

    def test_an_invocation_never_seen_to_finish_is_refused(self):
        with self.assertRaises(capture.CaptureError) as raised:
            self.snapshot(drop_return=True)
        self.assertIn("return marker", str(raised.exception),
                      "a capture with no end was accepted, so there would be "
                      "no outcome to check a reproduction against")

    def test_a_return_slot_that_holds_something_else_is_refused(self):
        with self.assertRaises(capture.CaptureError) as raised:
            self.snapshot(return_esp=ENTRY_ESP + 0x40)
        self.assertIn("return address", str(raised.exception),
                      "the sentinel was written over a stack slot that held "
                      "something else, which would corrupt the replay")

    def test_code_nobody_captured_is_refused_rather_than_replayed(self):
        instructions = [
            (CODE_BASE + 0x00, "mov    0x200000,%eax"),
            (CODE_BASE + 0x06, "call   0x00180000"),
            (0x00180000, "mov    $0x1,%eax"),
            (0x00180005, "ret"),
            (CODE_BASE + 0x2b, "ret"),
            (CALLER, "nop"),
        ]
        with self.assertRaises(capture.CaptureError) as raised:
            self.snapshot(instructions=instructions)
        self.assertIn("0x00180000", str(raised.exception),
                      "an invocation that ran into a function nobody captured "
                      "was imported, so the replay would answer with whatever "
                      "happened to be mapped there")

    def test_a_history_that_is_not_of_this_invocation_is_refused(self):
        instructions = [(CODE_BASE + 0x40, "nop"), (CALLER, "nop")]
        with self.assertRaises(capture.CaptureError) as raised:
            self.snapshot(instructions=instructions)
        self.assertIn("never reaches the entry", str(raised.exception),
                      "a branch history from some other moment was accepted "
                      "as the record of this decision")


class SnapshotReplayTest(unittest.TestCase):
    """The gate: what was captured has to happen again, instruction for
    instruction, before anything else this tool prints means anything."""

    def setUp(self):
        if not (mo.backend_available() or mo.backend_in_process()):
            self.skipTest("micro-oracle backend not built: run "
                          "tools/bne-harness/scripts/micro-oracle-venv.sh")

    def test_an_imported_capture_reproduces_the_invocation_exactly(self):
        document = capture.snapshot_from_gdb_log(
            specification(), capture_log())
        snapshot = mo.load_snapshot(document, expected_executable=None)
        result = mo.reproduce(snapshot)
        self.assertEqual("exact", result["status"],
                         f"the captured decision did not happen again under "
                         f"the emulator: {result['mismatches']}")

    def test_the_other_side_of_the_decision_reproduces_too(self):
        state = machine(counter=8, limit=9, reach=4)
        document = capture.snapshot_from_gdb_log(
            specification(), capture_log(state))
        snapshot = mo.load_snapshot(document, expected_executable=None)
        result = mo.reproduce(snapshot)
        self.assertEqual("exact", result["status"],
                         f"the rejected branch of the same decision did not "
                         f"reproduce: {result['mismatches']}")
        self.assertEqual([{"address": CODE_BASE + 0x0a, "taken": False}],
                         snapshot.expected["branches"],
                         "the branch that decided this invocation went the "
                         "same way as the one before it")

    def test_a_replay_that_writes_other_bytes_is_not_a_reproduction(self):
        document = capture.snapshot_from_gdb_log(
            specification(), capture_log())
        document["expected"]["memory_delta"][0]["after_hex"] = "01000000"
        snapshot = mo.load_snapshot(document, expected_executable=None)
        result = mo.reproduce(snapshot)
        self.assertEqual("divergent", result["status"],
                         "a replay that left different bytes behind was "
                         "reported as an exact reproduction")
        self.assertEqual("memory", result["mismatches"][0]["kind"],
                         "the memory the invocation changed is not what the "
                         "mismatch is about")

    def test_the_captured_decision_can_be_asked_what_if(self):
        document = capture.snapshot_from_gdb_log(
            specification(), capture_log())
        snapshot = mo.load_snapshot(document, expected_executable=None)
        results = mo.run_trials(snapshot, [
            mo.Trial(label="captured"),
            mo.Trial(label="counter-6", assignments={"counter": 6}),
        ])
        self.assertEqual(0, results[0].registers["eax"],
                         "the captured invocation returned something other "
                         "than what the capture recorded")
        self.assertEqual(1, results[1].registers["eax"],
                         "raising the counter past the limit did not change "
                         "the decision, so the capture is not answering "
                         "questions the oracle was never asked")


class SnapshotSealTest(unittest.TestCase):
    """Nothing is sealed that the replay's own loader will not accept."""

    def test_sealing_writes_a_snapshot_the_micro_oracle_loads(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            log = directory / "capture-log.txt"
            log.write_text(capture_log(), encoding="utf-8")
            snapshot_path, manifest_path = capture.seal_capture(
                specification(), log, directory / "run",
                gdb_version="GNU gdb 15.1", expect_pinned_executable=False)
            document = json.loads(snapshot_path.read_text(encoding="utf-8"))
            loaded = mo.load_snapshot(
                document, blob_root=snapshot_path.parent / "blobs",
                expected_executable=None)
            self.assertEqual(CODE_BASE, loaded.entry,
                             "the sealed snapshot does not start where the "
                             "capture stopped")
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            self.assertEqual(
                capture.file_identity(log)["sha256"],
                manifest["capture"]["log"]["sha256"],
                "the sealed run does not name the log it was imported from, "
                "so the import cannot be repeated and argued with")
            self.assertEqual(1, manifest["outcome"]["branches"],
                             "the sealed run does not say how many decisions "
                             "the invocation took")

    def test_the_remote_plan_names_the_agent_that_exists(self):
        plan = mo.remote_capture_plan(
            0x00437646, case="retail-xhuman-12-idle", scenario="xhuman-12",
            seed=1, cycles=40)
        commands = " ".join(plan["commands"])
        self.assertIn("bne_headless.py snapshot-capture", commands,
                      "the plan still names a capture agent that does not "
                      "exist, so following it cannot produce a snapshot")
        self.assertTrue(plan["dry_run"],
                        "the plan defaulted to running against a shared "
                        "oracle rather than printing itself")
        self.assertTrue(
            plan["isolation"]["directory_is_content_addressed"],
            "the capture would share a directory with whatever else is using "
            "the oracle")

    def test_sealing_refuses_an_executable_that_is_not_the_pinned_one(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            log = directory / "capture-log.txt"
            log.write_text(capture_log(), encoding="utf-8")
            other = directory / "other.exe"
            other.write_bytes(b"not the game")
            document = specification()
            document["executable_sha256"] = capture.BNE_202_SHA256
            document["entry"] = capture.BNE_TEXT_START
            document["focus"] = {"native_slot": 12, "register": "esi"}
            document["identity"] = {
                "case": "synthetic-decide", "fixture_id": "c" * 64,
                "scenario": "Campaign\\Human\\Human01.pud", "seed": 1,
                "cycle": 23, "subject": {"native_slot": 12},
                "pc": capture.BNE_TEXT_START,
            }
            with self.assertRaises(capture.CaptureError) as raised:
                capture.seal_capture(document, log, directory / "run",
                                     executable=other)
            self.assertIn("pinned", str(raised.exception),
                          "a capture was sealed against an executable that is "
                          "not the build the game runs")

    def test_native_spec_refuses_identity_that_names_another_pc(self):
        document = specification()
        document["executable_sha256"] = capture.BNE_202_SHA256
        document["entry"] = capture.BNE_TEXT_START
        document["focus"] = {"native_slot": 12, "register": "esi"}
        document["identity"] = {
            "case": "synthetic-decide", "fixture_id": "d" * 64,
            "scenario": "Campaign\\Human\\Human01.pud", "seed": 1,
            "cycle": 23, "subject": {"native_slot": 12},
            "pc": capture.BNE_TEXT_START + 1,
        }
        with self.assertRaisesRegex(capture.CaptureError, "entry"):
            capture.load_specification(document)

    def test_sealing_refuses_symbolic_link_capture_log(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            real = directory / "real.log"
            real.write_text(capture_log(), encoding="utf-8")
            linked = directory / "linked.log"
            linked.symlink_to(real)
            with self.assertRaisesRegex(capture.CaptureError, "symbolic link"):
                capture.seal_capture(
                    specification(), linked, directory / "run",
                    expect_pinned_executable=False)

    def test_sealing_refuses_a_symbolic_link_in_existing_output(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            log = directory / "capture-log.txt"
            log.write_text(capture_log(), encoding="utf-8")
            output = directory / "run"
            output.mkdir()
            outside = directory / "outside"
            outside.mkdir()
            (output / "blobs").symlink_to(outside, target_is_directory=True)
            with self.assertRaisesRegex(capture.CaptureError, "symbolic link"):
                capture.seal_capture(
                    specification(), log, output,
                    expect_pinned_executable=False)


if __name__ == "__main__":
    unittest.main()
