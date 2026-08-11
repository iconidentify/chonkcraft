from pathlib import Path
import json
import sys
import tempfile
import unittest


SCRIPTS = Path(__file__).parents[1] / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import bne_experiments
import bne_lab
import bne_triage

from test_bne_state_report import SLOT, packet, sequence


FINDING = {"kind": "unit", "unit": SLOT, "unit_type": "unit-grunt",
           "field": "order", "oracle": "attack", "java": "move"}


def triage_run(directory: Path, body: dict) -> Path:
    root = directory / "triage"
    packet_dir = root / "packets" / "case-a"
    packet_dir.mkdir(parents=True)
    packet_path = packet_dir / "packet.json"
    packet_path.write_text(json.dumps(body, indent=2, sort_keys=True) + "\n")
    survey = root / "survey.json"
    survey.write_text(json.dumps({"cases": [{
        "id": "case-a", "state": "divergent",
        "first_divergence_cycle": body["divergence"]["cycle"],
        "findings": body["divergence"]["findings"],
    }]}) + "\n")
    request = {"engine": {"head": "abc"}}
    (root / "manifest.json").write_text(json.dumps({
        "schema": 1, "request_sha256": bne_triage.canonical_digest(request),
        "created_at": "2026-08-03T00:00:00+00:00", "request": request,
        "candidate": {"survey": "survey.json", "counts": {
            "clean": 51, "divergent": 1, "failed": 0}},
        "gate": {"passed": True},
        "frontier": {"common_clean_through": 34,
                     "earliest_divergence_cycle": body["divergence"]["cycle"]},
        "clusters": [],
        "packets": [{"case": "case-a",
                     "cycle": body["divergence"]["cycle"],
                     "packet": "packets/case-a/packet.json"}],
        "artifacts": bne_triage.inventory_files(root, [packet_path, survey]),
    }, indent=2, sort_keys=True) + "\n")
    return root


class BneStateMachineLabTest(unittest.TestCase):

    def test_a_window_that_looks_like_a_state_machine_is_mined_by_the_lab(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            body = packet()
            body["divergence"]["findings"] = [FINDING]
            root = triage_run(directory, body)

            status, run_root = bne_lab.build_lab(root, directory / "lab")

            self.assertEqual(0, status)
            manifest = json.loads((run_root / "manifest.json").read_text())
            case = manifest["cases"][0]
            self.assertIn("accumulation-before-an-action",
                          case["state_machine_signals"],
                          "a counter climbing before a field was cleared did "
                          "not reach the lab's handoff at all")
            self.assertEqual(SLOT, case["focus_native_slot"])
            report = (run_root / manifest["attempt"]
                      / case["state_machine_report"]).read_text()
            self.assertIn("Ramp and consequence", report)
            self.assertIn("Native state machine",
                          (run_root / "NEXT.md").read_text(),
                          "the handoff packet does not name the state report "
                          "it generated, so nobody reading it would open one")

    def test_an_ordinary_divergence_is_not_mined_for_a_state_machine(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            # A unit walking east: its position climbs and nothing hidden
            # moves, which is the common divergence and not this shape.
            body = packet([(0, 22, 0, 25 + index) for index in range(6)])
            body["divergence"]["findings"] = [FINDING]
            root = triage_run(directory, body)

            _status, run_root = bne_lab.build_lab(root, directory / "lab")

            manifest = json.loads((run_root / "manifest.json").read_text())
            case = manifest["cases"][0]
            self.assertEqual([], case["state_machine_signals"],
                             "a unit that simply walked was mined for a "
                             "hidden state machine, which is how this tool "
                             "ends up attached to every divergence")
            self.assertIsNone(case["state_machine"])
            self.assertNotIn("Native state machine",
                             (run_root / "NEXT.md").read_text())

    def test_the_ranked_plan_offers_the_miner_when_the_signals_fire(self):
        plan = bne_experiments.default_investigation_plan(
            "case-a", 40, [FINDING],
            evidence={"state_machine_signals": [
                {"signal": "accumulation-before-an-action"}]})
        experiments = {item["id"] for item in plan["experiments"]}
        self.assertIn("native-state-machine", experiments,
                      "a window whose evidence shows something accumulating "
                      "was never offered the experiment that reads it")
        self.assertIn("hidden-state-machine",
                      {item["id"] for item in plan["hypotheses"]})

    def test_the_ranked_plan_is_unchanged_when_no_signal_fired(self):
        plan = bne_experiments.default_investigation_plan(
            "case-a", 40, [FINDING], evidence={"state_machine_signals": []})
        self.assertNotIn("native-state-machine",
                         {item["id"] for item in plan["experiments"]},
                         "the miner was offered for a divergence whose "
                         "evidence shows no multi-cycle state at all")

    def test_a_short_window_reaches_the_lab_without_a_state_report(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            body = packet(sequence()[:2])
            body["divergence"]["findings"] = [FINDING]
            root = triage_run(directory, body)

            status, run_root = bne_lab.build_lab(root, directory / "lab")

            self.assertEqual(0, status,
                             "a two-cycle window aborted the whole lab "
                             "compose rather than declining to mine")
            manifest = json.loads((run_root / "manifest.json").read_text())
            self.assertIsNone(manifest["cases"][0]["state_machine"])


if __name__ == "__main__":
    unittest.main()
