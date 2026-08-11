from pathlib import Path
import json
import sys
import tempfile
import unittest


SCRIPTS = Path(__file__).parents[1] / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import bne_java
import bne_state_machine as miner
import bne_triage

SLOT = 1448
#: The real record width, so the packet decoder's named offsets land where a
#: fixture would put them: tile position at 24 and 26, hit points at 34.
RECORD_BYTES = 152
JAVA_UNIT = 152


def record(**offsets) -> bytes:
    """A full-width unit record with a few offsets written."""
    raw = bytearray(RECORD_BYTES)
    for offset, value in offsets.items():
        position = int(offset.lstrip("o"))
        width = 2 if value > 0xff else 1
        raw[position:position + width] = value.to_bytes(width, "little")
    return bytes(raw)


def sequence():
    """The motivating shape: settle, climb, clear the route, arm a timer.

    Written as offsets rather than as names because that is what the miner
    sees. Position holds still throughout, which is the whole difficulty: the
    unit is doing nothing visible while its record decides something.
    """
    return [
        # counter, route, timer, x
        (1, 22, 0, 25), (2, 22, 0, 25), (3, 22, 0, 25),
        (4, 0, 15, 25), (4, 0, 14, 25), (4, 0, 13, 25),
    ]


def packet(rows=None, *, case="case-a", slot=SLOT, first_cycle=35,
        java_rows=None):
    rows = rows or sequence()
    native_state = {}
    semantic = {}
    for index, (counter, route, timer, x) in enumerate(rows):
        cycle = first_cycle + index
        native_state[str(cycle)] = {"units": {str(slot): {
            "raw_hex": record(o60=counter, o64=route, o68=timer,
                              o24=x, o26=60, o34=47).hex(),
            "changed_this_cycle": True, "generation": 10 + index,
        }}}
        java = (java_rows[index] if java_rows
                else {"x": x, "y": 60, "hp": 47, "order": "move"})
        semantic[str(cycle)] = {"cycle": cycle, "focus": [{
            "native_slot": slot, "java_id": JAVA_UNIT,
            "oracle": {"x": x, "y": 60, "hp": 47}, "java": java,
        }]}
    return {
        "schema": 1,
        "case": {"id": case, "scenario": "Campaign\\Human\\Human12.pud",
                 "seed": 1},
        "divergence": {"cycle": first_cycle + len(rows) - 1, "findings": []},
        "semantic": semantic,
        "native_state": native_state,
        "identities": {}, "indexed_fixture": {},
        "native_diagnostic_events": [], "java_process_output": {},
        "java_diagnostic_highlights": [],
    }


def causal(rows, *, first_cycle=33, unit=JAVA_UNIT):
    """A followed unit's per-cycle causal state, as the engine writes it."""
    lines = []
    for index, fields in enumerate(rows):
        lines.append(json.dumps({
            "schema": 1, "side": "java", "ordinal": index,
            "cycle": first_cycle + index, "kind": "state.unit",
            "subject": f"unit:{unit}", "fields": fields,
        }, sort_keys=True))
    return "\n".join(lines) + "\n"


class BneStateReportTest(unittest.TestCase):

    def test_a_whole_window_becomes_one_report(self):
        report = miner.analyse(packet(), SLOT)
        self.assertEqual("mined", report["status"])
        self.assertEqual([35, 36, 37, 38, 39, 40], report["window"]["cycles"])
        self.assertIn("byte:0x3c",
                      [item["key"] for item in report["trajectories"]],
                      "the counter at offset 0x3c is not among the "
                      "observables the report found")

    def test_the_report_says_why_the_window_was_worth_mining(self):
        report = miner.analyse(packet(), SLOT)
        fired = {signal["signal"] for signal in report["signals"]}
        self.assertIn("accumulation-before-an-action", fired,
                      "a counter climbing for three cycles before a field was "
                      "cleared did not register as a reason to mine at all")
        self.assertIn("timer-armed-after-a-transition", fired)

    def test_a_quiet_window_raises_no_signal_at_all(self):
        quiet = packet([(0, 22, 0, 25 + index) for index in range(6)])
        report = miner.analyse(quiet, SLOT)
        fired = {signal["signal"] for signal in report["signals"]}
        self.assertNotIn("accumulation-before-an-action", fired,
                         "a unit that simply walked was reported as running a "
                         "multi-cycle state machine, which is how this tool "
                         "would end up attached to every divergence")

    def test_a_window_too_short_to_mine_produces_a_capture_plan(self):
        report = miner.analyse(packet(sequence()[:2]), SLOT)
        self.assertEqual("insufficient-window", report["status"])
        self.assertIn("state-machine", report["next_experiment"]["then"])
        self.assertIn("--before 12",
                      report["next_experiment"]["capture_command"],
                      "the plan for a window too short does not ask for a "
                      "wider one")

    def test_a_slot_absent_from_the_packet_is_not_invented(self):
        report = miner.analyse(packet(), SLOT + 1)
        self.assertEqual("insufficient-window", report["status"])
        self.assertEqual([], report["cycles_available"])

    def test_the_port_side_comes_from_causal_state_when_it_is_supplied(self):
        from bne_causal import parse_causal_jsonl

        events = parse_causal_jsonl(causal([
            {"collision": value, "x": 25, "order": "MOVE", "path_length": 4}
            for value in [1, 2, 3, 4, 4, 4]
        ]), expected_side="java")
        report = miner.analyse(packet(), SLOT, java_events=events,
                               java_unit=JAVA_UNIT)
        self.assertEqual("causal state.unit events", report["java"]["source"])
        pairing = next(item for item in report["correlations"]
                       if item["native"] == "byte:0x3c")
        self.assertEqual("strong-temporal-correlation", pairing["confidence"],
                         "the port's own per-cycle counter was not matched to "
                         "the native byte that moves exactly with it")
        self.assertEqual("collision", pairing["java_counterpart"])
        self.assertEqual(2, report["alignment"]["cycle_offset"]["offset"],
                         "the two-cycle difference between the fixture's "
                         "numbering and the engine's was not measured")

    def test_the_packet_alone_is_enough_to_produce_a_report(self):
        report = miner.analyse(packet(), SLOT)
        self.assertEqual("packet semantic window", report["java"]["source"],
                         "a packet with no rerun behind it produced no port "
                         "side at all, so the tool would need a rerun before "
                         "it could say anything")

    def test_the_markdown_leads_with_the_informative_fields(self):
        text = miner.format_report(miner.analyse(packet(), SLOT))
        self.assertIn("## What moved, most informative first", text)
        self.assertIn("Ramp and consequence", text)
        self.assertIn("Timing is not meaning", text,
                      "the report puts a native offset beside a port field "
                      "without saying what that does and does not mean")
        self.assertLess(len(text.splitlines()), 90,
                        "the report dumps enough of the record to bury the "
                        "finding in it")

    def test_the_report_says_what_it_does_not_know(self):
        report = miner.analyse(packet(), SLOT)
        unknowns = " ".join(report["unknowns"])
        self.assertIn("semantic window", unknowns,
                      "a report built without the port's per-cycle state did "
                      "not say so, and a table of confident-looking pairings "
                      "with its gaps unstated reads as though it had none")
        self.assertIn("rewritten", unknowns,
                      "the record-level limit on write evidence is not stated "
                      "in the report that relies on it")
        self.assertIn("What this report does not know",
                      miner.format_report(report))

    def test_a_state_graph_is_emitted_for_the_leading_observables(self):
        report = miner.analyse(packet(), SLOT)
        graph = next(item for item in report["state_graphs"]
                     if item["observable"] == "byte:0x3c")
        self.assertEqual([1, 2, 3, 4], graph["nodes"])
        self.assertEqual({(1, 2), (2, 3), (3, 4)},
                         {(edge["from"], edge["to"]) for edge in graph["edges"]})

    def test_the_same_packet_twice_produces_the_same_report(self):
        first = miner.analyse(packet(), SLOT)
        second = miner.analyse(packet(), SLOT)
        self.assertEqual(json.dumps(first, sort_keys=True),
                         json.dumps(second, sort_keys=True),
                         "the same window produced two different reports, so "
                         "a content-addressed run could never cache")


class BneStateMachineRunTest(unittest.TestCase):

    def write_packet(self, directory: Path, name: str, **kwargs) -> Path:
        path = directory / f"{name}.json"
        path.write_text(json.dumps(packet(**kwargs), indent=2, sort_keys=True)
                        + "\n")
        return path

    def test_a_durable_run_is_content_addressed_and_authenticated(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            path = self.write_packet(directory, "packet")

            status, run_root = miner.run_state_machine(
                path, SLOT, directory / "artifacts")

            self.assertEqual(1, status)
            manifest = json.loads((run_root / "manifest.json").read_text())
            for relative, expected in manifest["artifacts"].items():
                self.assertEqual(expected,
                                 bne_triage.file_identity(run_root / relative),
                                 f"the manifest does not describe {relative}")
            self.assertIn("accumulation-before-an-action",
                          manifest["pointer"]["signals"])
            repeated = miner.run_state_machine(
                path, SLOT, directory / "artifacts")
            self.assertEqual((status, run_root), repeated,
                             "the same evidence produced a second run rather "
                             "than the same content-addressed one")

    def test_a_packet_whose_fixture_changed_underneath_it_is_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            fixture = directory / "case.bnefx"
            fixture.write_bytes(b"sealed fixture bytes")
            body = packet()
            body["identities"] = {"fixture": {
                "path": str(fixture), **bne_triage.file_identity(fixture)}}
            path = directory / "packet.json"
            path.write_text(json.dumps(body, indent=2, sort_keys=True) + "\n")
            fixture.write_bytes(b"sealed fixture bytes, edited")

            with self.assertRaises(ValueError,
                    msg="a packet was mined although the sealed fixture it "
                        "was built from had been edited since"):
                miner.run_state_machine(path, SLOT, directory / "artifacts")

    def test_a_file_that_is_not_a_packet_is_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            path = directory / "not-a-packet.json"
            path.write_text('{"schema": 1}\n')
            with self.assertRaises(ValueError,
                    msg="a JSON file with no native state in it was mined "
                        "anyway"):
                miner.run_state_machine(path, SLOT, directory / "artifacts")

    def test_another_window_supplied_for_comparison_tests_the_rule(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            focus = self.write_packet(directory, "focus")
            other = self.write_packet(
                directory, "other", case="case-b",
                rows=[(1, 22, 0, 25), (2, 0, 15, 25), (2, 0, 14, 25),
                      (2, 0, 13, 25), (2, 0, 12, 25), (2, 0, 11, 25)])

            status, run_root = miner.run_state_machine(
                focus, SLOT, directory / "artifacts",
                compare=[(other, SLOT)])

            report = json.loads((run_root / "STATE-MACHINE.json").read_text())
            counter = report["counterexamples"]
            self.assertEqual(1, counter["contradicts"],
                             "a window that cleared the route two cycles "
                             "earlier did not break the mined threshold")
            self.assertEqual("case-b",
                             counter["nearest_counterexample"]["case"])
            self.assertEqual(1, status)

    def test_a_proposed_state_is_audited_against_the_same_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            path = self.write_packet(directory, "packet")
            proposal = directory / "proposal.json"
            proposal.write_text(json.dumps({
                "name": "xhuman12KeepRoute", "java_field": "keepRoute",
                "lifetime_cycles": 40,
            }) + "\n")

            _status, run_root = miner.run_state_machine(
                path, SLOT, directory / "artifacts", proposal_path=proposal)

            audit = json.loads(
                (run_root / "STATE-MACHINE.json").read_text()
            )["proposed_state_audit"]
            codes = [warning["code"] for warning in audit["warnings"]]
            self.assertIn("no-native-correlate", codes)
            self.assertIn("named-after-a-fixture", codes)
            self.assertTrue(audit["changes_no_source"],
                            "the audit reports itself as something that "
                            "could change source")
            self.assertIn("Proposed state audit",
                          (run_root / "STATE-MACHINE.md").read_text())

    def test_the_parser_offers_the_miner_as_a_supported_command(self):
        arguments = bne_java.parser().parse_args([
            "state-machine", "--packet", "packet.json", "--slot", "1448",
            "--compare", "other.json=1553",
        ])
        self.assertEqual(bne_java.state_machine_command, arguments.func)
        self.assertEqual(1448, arguments.slot)
        self.assertEqual(["other.json=1553"], arguments.compare)


if __name__ == "__main__":
    unittest.main()
