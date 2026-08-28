"""End-to-end tests for the content-addressed AI evidence conductor."""

from __future__ import annotations

import hashlib
import io
import json
from contextlib import redirect_stdout
from pathlib import Path
import shutil
import stat
import struct
import sys
import tempfile
import unittest
from unittest import mock
import zipfile


SCRIPTS = Path(__file__).parents[1] / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import bne_ai_conductor as conductor
import bne_ai_decision_ledger as ledger
import bne_fixture as fixture


AI_BASE = 0x00B00000


def ai_bytes() -> bytes:
    value = bytearray(0x800)
    value[0:2] = (0x200).to_bytes(2, "little")
    value[0x200:0x202] = (0x400).to_bytes(2, "little")
    value[0x202:0x204] = (0x480).to_bytes(2, "little")
    return bytes(value)


def raw_state(wait: int = 1) -> bytes:
    raw = bytearray(48)
    raw[0:4] = wait.to_bytes(4, "little")
    raw[0x04:0x08] = (AI_BASE + 0x220).to_bytes(4, "little")
    raw[0x23:0x27] = (AI_BASE + 0x400).to_bytes(4, "little")
    raw[0x27:0x2b] = (AI_BASE + 0x480).to_bytes(4, "little")
    return bytes(raw)


def state_text(raw: bytes) -> str:
    return ",".join(f"{value:02x}" for value in raw)


def native_trace(cycles: int = 2, players: tuple[int, ...] = (1,)) -> bytes:
    lines = []
    for cycle in range(1, cycles + 1):
        for player in players:
            lines.append(
                "event=ai-build-boundary phase=game-after "
                f"index={cycle} player={player} profile=0 "
                f"state={state_text(raw_state(cycle))}")
    return ("\n".join(lines) + "\n").encode()


def native_state(cycles: int = 2, computers: tuple[int, ...] = (1,)) -> bytes:
    out = bytearray(fixture.STATE_HEADER.pack(
        fixture.STATE_MAGIC, fixture.STATE_MAJOR, fixture.STATE_MINOR,
        fixture.STATE_HEADER.size, 152, 1600, 16, fixture.STATE_FLAGS))
    for cycle in range(1, cycles + 1):
        players = []
        for player in range(16):
            controller = 1 if player in computers else (2 if player == 0 else 3)
            players.append(fixture.PLAYER_RECORD.pack(controller, 0, 0, 0))
        payload = fixture.CYCLE_HEADER.pack(cycle, cycle, 0, 0) + b"".join(players)
        out += fixture.CHUNK_HEADER.pack(b"CYCL", len(payload)) + payload
        sim = b"\0" * (16 * fixture.PLAYER_SIM_RECORD.size)
        changed = 1 if cycle == 1 else 0
        aux = fixture.AUX_HEADER.pack(cycle, 0, 0, 1, changed) + sim
        if changed:
            aux += fixture.MAP_DELTA.pack(0, 0, 0)
        out += fixture.CHUNK_HEADER.pack(b"AUXL", len(aux)) + aux
    done = fixture.DONE_RECORD.pack(cycles)
    out += fixture.CHUNK_HEADER.pack(b"DONE", len(done)) + done
    return bytes(out)


def manifest(trace: bytes, state: bytes | None = None, *, cycles: int = 2,
             state_validation: dict | None = None,
             scenario: str = r"Campaign\Orc\Orc01.pud") -> dict:
    state = native_state(cycles) if state is None else state
    trace_id = {"name": "case.trace.txt", "bytes": len(trace),
                "sha256": hashlib.sha256(trace).hexdigest()}
    state_validation = (fixture.validate_state_source(io.BytesIO(state), cycles)
                        if state_validation is None else state_validation)
    state_id = {"name": "case.state.bin", "bytes": len(state),
                "sha256": hashlib.sha256(state).hexdigest(),
                "validation": state_validation}
    simulation = hashlib.sha256((scenario + str(cycles)).encode()).hexdigest()
    data = {"War2Dat.mpq": {
        "bytes": 1, "sha256": hashlib.sha256(b"data").hexdigest()}}
    tracer = hashlib.sha256(b"tracer").hexdigest()
    key = {
        "schema": 1, "state_schema": "1.1",
        "oracle_executable": conductor.PINNED,
        "oracle_data": {name: item["sha256"] for name, item in data.items()},
        "tracer": tracer, "scenario": scenario, "cycle_limit": cycles,
        "initialization_seed": 1, "commands": None, "replay": None,
        "simulation": simulation,
    }
    value = {
        "schema": 2,
        "oracle": {"executable": {"sha256": conductor.PINNED},
                   "data": data},
        "harness": {"tracer": {"sha256": tracer}, "state_schema": "1.1"},
        "runtime": {"network_disabled": True},
        "fixture": {"schema": 1, "id": "", "key": key},
        "run": {
            "cycle_limit": cycles, "initialization_seed": 1,
            "requested_scenario": scenario, "commands": None, "replay": None,
            "trace": trace_id, "state": state_id,
            "validation": {"cycles": cycles, "initialization_seed": 1,
                           "scenario": scenario, "commands_applied": 0,
                           "commands_rejected": 0,
                           "simulation_sha256": simulation}},
    }
    value["fixture"]["id"] = hashlib.sha256(json.dumps(
        key, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    return value


def native_ledger(trace: bytes, cycles: int = 2) -> dict:
    ai = ai_bytes()
    text = trace.decode()
    value = ledger.ledger_from_native_trace(
        text, ai_base=ledger.derive_ai_base(text, ai), ai_size=len(ai),
        ai_bin=ai)
    value["ai_bin_sha256"] = hashlib.sha256(ai).hexdigest()
    value["ai_bin_bytes"] = len(ai)
    return value


def java_ledger(trace: bytes, cycles: int = 2,
                scenario: str = r"Campaign\Orc\Orc01.pud",
                computers: tuple[int, ...] = (1,), person: int = 0) -> dict:
    value = native_ledger(trace, cycles)
    value.update({
        "map": conductor.scenario_to_java_map(scenario),
        "seed": 1, "cycles": cycles, "person_player": person,
        "computer_players": list(computers),
    })
    return value


class FakeOracle:
    def __init__(self, root: Path, *, cycles: int = 2,
                 case: str = "case") -> None:
        self.root = root
        self.run = root / "output" / "ai-cycle" / case
        self.run.mkdir(parents=True)
        self.trace = native_trace(cycles)
        self.state = native_state(cycles)
        (self.run / "case.trace.txt").write_bytes(self.trace)
        (self.run / "case.state.bin").write_bytes(self.state)
        self.manifest = manifest(self.trace, self.state, cycles=cycles)
        self.manifest_path = self.run / f"{case}.manifest.json"
        self.manifest_path.write_text(json.dumps(self.manifest), encoding="utf-8")
        self.ssh = root / "fake-ssh.py"
        self.ssh.write_text(
            "#!/usr/bin/env python3\n"
            "import subprocess, sys\n"
            "value=subprocess.run(sys.argv[-1], shell=True, "
            "input=sys.stdin.buffer.read(), stdout=subprocess.PIPE, "
            "stderr=subprocess.PIPE)\n"
            "sys.stdout.buffer.write(value.stdout)\n"
            "sys.stderr.buffer.write(value.stderr)\n"
            "raise SystemExit(value.returncode)\n", encoding="utf-8")
        self.ssh.chmod(self.ssh.stat().st_mode | stat.S_IXUSR)
        self.backend = conductor.SshBackend("fake", str(self.ssh))

    def pack(self, path: Path) -> Path:
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("assets/archives/maindat/0277.bin", ai_bytes())
        return path


class ManifestValidationTest(unittest.TestCase):

    def test_accepts_consistent_pinned_manifest(self):
        trace = native_trace()
        raw = json.dumps(manifest(trace)).encode()
        self.assertEqual(2, conductor.validate_manifest(raw, "/sealed/m.json")["schema"])

    def test_rejects_wrong_executable(self):
        value = manifest(native_trace())
        value["oracle"]["executable"]["sha256"] = "0" * 64
        with self.assertRaisesRegex(conductor.EvidenceError, "not pinned"):
            conductor.validate_manifest(json.dumps(value).encode(), "/bad")

    def test_rejects_forged_fixture_identity(self):
        value = manifest(native_trace())
        value["fixture"]["id"] = "0" * 64
        with self.assertRaisesRegex(conductor.EvidenceError, "fixture key"):
            conductor.validate_manifest(json.dumps(value).encode(), "/bad")

    def test_rejects_online_or_commanded_capture(self):
        value = manifest(native_trace())
        value["runtime"]["network_disabled"] = False
        with self.assertRaisesRegex(conductor.EvidenceError, "offline"):
            conductor.validate_manifest(json.dumps(value).encode(), "/bad")
        value = manifest(native_trace())
        value["run"]["validation"]["commands_applied"] = 1
        with self.assertRaisesRegex(conductor.EvidenceError, "idle capture"):
            conductor.validate_manifest(json.dumps(value).encode(), "/bad")

    def test_rejects_scenario_identity_disagreement(self):
        value = manifest(native_trace())
        value["run"]["requested_scenario"] = "other"
        with self.assertRaisesRegex(conductor.EvidenceError, "scenario"):
            conductor.validate_manifest(json.dumps(value).encode(), "/bad")

    def test_rejects_trace_path_escape(self):
        value = manifest(native_trace())
        value["run"]["trace"]["name"] = "../trace.txt"
        with self.assertRaisesRegex(conductor.EvidenceError, "trace identity"):
            conductor.validate_manifest(json.dumps(value).encode(), "/bad")


class RemoteTransportTest(unittest.TestCase):

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.oracle = FakeOracle(self.root / "oracle")

    def tearDown(self):
        self.temp.cleanup()

    def test_fake_ssh_discovers_exact_manifest_bytes(self):
        artifacts = conductor.discover_remote(
            self.oracle.backend, str(self.oracle.root))
        self.assertEqual(1, len(artifacts))
        self.assertEqual(self.oracle.manifest_path.read_bytes(),
                         artifacts[0].manifest_bytes)

    def test_cli_defaults_to_discovery_without_writing_a_store(self):
        store = self.root / "must-not-exist"
        output = io.StringIO()
        with redirect_stdout(output):
            status = conductor.main([
                "--host", "fake", "--ssh", str(self.oracle.ssh),
                "--remote-root", str(self.oracle.root),
                "--artifact-root", str(store), "--limit", "1"])
        self.assertEqual(0, status)
        self.assertEqual("read-only-discovery",
                         json.loads(output.getvalue())["mode"])
        self.assertFalse(store.exists())

    def test_remote_normalizer_imports_ledger_not_trace(self):
        artifact = conductor.discover_remote(
            self.oracle.backend, str(self.oracle.root))[0]
        value, proof = conductor.normalize_remote(
            self.oracle.backend, artifact, ai_bytes())
        self.assertEqual(2, len(value["rows"]))
        self.assertTrue(proof["coverage"]["complete"])
        self.assertEqual([1], proof["players"])

    def test_remote_normalizer_fails_if_trace_changed_after_manifest(self):
        artifact = conductor.discover_remote(
            self.oracle.backend, str(self.oracle.root))[0]
        (self.oracle.run / "case.trace.txt").write_bytes(b"tampered\n")
        with self.assertRaisesRegex(conductor.EvidenceError,
                                    "remote trace identity"):
            conductor.normalize_remote(self.oracle.backend, artifact, ai_bytes())

    def test_state_controller_roster_not_trace_rows_sets_denominator(self):
        state = native_state(2, computers=(1, 2))
        (self.oracle.run / "case.state.bin").write_bytes(state)
        value = manifest(self.oracle.trace, state, cycles=2)
        self.oracle.manifest_path.write_text(json.dumps(value), encoding="utf-8")
        artifact = conductor.discover_remote(
            self.oracle.backend, str(self.oracle.root))[0]
        with self.assertRaisesRegex(conductor.EvidenceError,
                                    "state-declared fixed-denominator"):
            conductor.normalize_remote(self.oracle.backend, artifact, ai_bytes())


class SelectionAndComparisonTest(unittest.TestCase):

    def artifact(self, case: str, cycles: int, scenario: str) \
            -> conductor.RemoteArtifact:
        trace = native_trace(cycles)
        raw = json.dumps(manifest(trace, cycles=cycles,
                                  scenario=scenario)).encode()
        return conductor.RemoteArtifact(
            f"/remote/{case}.manifest.json", raw,
            conductor.validate_manifest(raw, case))

    def test_selects_longest_capture_per_fixture(self):
        short = self.artifact("short", 2, r"Campaign\Orc\Orc01.pud")
        long = self.artifact("long", 3, r"Campaign\Orc\Orc01.pud")
        other = self.artifact("other", 2, r"Campaign\Orc\Orc02.pud")
        selected = conductor.select_strongest([short, long, other])
        self.assertEqual({"long", "other"}, {item.case_id for item in selected})

    def test_fixed_denominator_catches_missing_java_cycle(self):
        native = native_ledger(native_trace())
        java = json.loads(json.dumps(native))
        java["rows"].pop()
        result = conductor.compare_fixed(native, java,
                                         players=[1], cycles=[1, 2])
        self.assertEqual(2, result["denominator"])
        self.assertEqual(1, result["state_exact"])
        self.assertEqual("missing-java-row",
                         result["first_difference"]["field"])

    def test_extra_java_player_is_material_even_with_full_denominator(self):
        native = native_ledger(native_trace())
        java = json.loads(json.dumps(native))
        extra = dict(java["rows"][0])
        extra["player"] = 2
        java["rows"].append(extra)
        result = conductor.compare_fixed(native, java,
                                         players=[1], cycles=[1, 2])
        self.assertEqual(2, result["state_exact"])
        self.assertFalse(result["state_identical"])
        self.assertEqual("extra-java-row", result["first_difference"]["field"])

    def test_state_debt_ranks_before_telemetry_debt(self):
        runs = [
            {"case_id": "telemetry", "artifact_id": "a", "scenario": "s",
             "comparison": {"denominator": 2, "state_exact": 2,
                            "first_difference": {"cycle": 1, "player": 1,
                              "field": "writes", "kind": "telemetry"}}},
            {"case_id": "state", "artifact_id": "b", "scenario": "s",
             "comparison": {"denominator": 2, "state_exact": 0,
                            "first_difference": {"cycle": 2, "player": 1,
                              "field": "pc_offset", "kind": "state"}}},
        ]
        ranked = conductor.ranked_findings(runs)
        self.assertEqual(["state", "telemetry"],
                         [item["case_id"] for item in ranked])


class FleetPlanTest(unittest.TestCase):

    def test_manifest_is_exactly_all_52_campaigns(self):
        rows = conductor.load_fleet_requirements()
        self.assertEqual(52, len(rows))
        counts = {family: sum(row["id"].startswith(family + "-")
                              for row in rows)
                  for family in ("human", "orc", "xhuman", "xorc")}
        self.assertEqual({"human": 14, "orc": 14,
                          "xhuman": 12, "xorc": 12}, counts)
        self.assertTrue(all(row["cycles"] == 1800 for row in rows))

    def test_fleet_contract_rejects_a_substituted_scenario(self):
        value = json.loads(conductor.FLEET_REQUIREMENTS.read_text())
        value["required_scenarios"][0]["java_map"] = "campaigns/orc/level01o"
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "fleet.json"
            path.write_text(json.dumps(value), encoding="utf-8")
            with self.assertRaisesRegex(conductor.EvidenceError,
                                        "canonical 52-campaign"):
                conductor.load_fleet_requirements(path)

    def test_known_45_capture_fleet_reports_exact_seven_missing(self):
        missing_ids = {"xhuman-10", "xhuman-11", "xhuman-12",
                       "xorc-09", "xorc-10", "xorc-11", "xorc-12"}
        artifacts = []
        for row in conductor.load_fleet_requirements():
            if row["id"] in missing_ids:
                continue
            raw = json.dumps(manifest(
                b"trace", b"state", cycles=1800,
                state_validation={"schema": "1.1", "cycles": 1800,
                                  "player_count": 16},
                scenario=row["native_scenario"])).encode()
            artifacts.append(conductor.RemoteArtifact(
                f"/remote/{row['id']}.manifest.json", raw,
                conductor.validate_manifest(raw, row["id"])))
        plan = conductor.fleet_plan(
            artifacts, host="i9beef", remote_root=conductor.DEFAULT_REMOTE_ROOT)
        self.assertEqual((52, 45, 7),
                         (plan["required"], plan["existing"], plan["missing"]))
        actual = {row["id"] for row in plan["scenarios"]
                  if row["status"] == "missing"}
        self.assertEqual(missing_ids, actual)
        self.assertEqual(7, len(plan["capture_jobs"]))

    def test_capture_jobs_share_remote_lease_and_never_manage_containers(self):
        plan = conductor.fleet_plan(
            [], host="i9beef", remote_root=conductor.DEFAULT_REMOTE_ROOT)
        job = plan["capture_jobs"][0]
        self.assertIn(".ai-cycle-capture.lock", job)
        self.assertIn("flock -w 7200", job)
        self.assertIn("bne_headless.py", job)
        self.assertNotIn("docker kill", job)
        self.assertNotIn("docker rm", job)
        self.assertEqual(1, plan["execution"]["recommended_parallel_jobs"])
        self.assertEqual(2, plan["execution"]["maximum_parallel_jobs"])

    def test_local_materializer_lease_fails_closed(self):
        with tempfile.TemporaryDirectory() as temporary:
            store = Path(temporary)
            with conductor.local_lease(store):
                with self.assertRaisesRegex(conductor.EvidenceError,
                                            "owns the local lease"):
                    with conductor.local_lease(store):
                        pass

    def test_java_ledger_must_bind_the_pack_ai_bin(self):
        ai = ai_bytes()
        ledger = {"schema": "chonkcraft-bne-ai-decision-ledger-2"}
        bound = conductor._bind_java_ai_bin(ledger, ai)
        self.assertEqual(hashlib.sha256(ai).hexdigest(), bound["ai_bin_sha256"])
        self.assertEqual(len(ai), bound["ai_bin_bytes"])
        with self.assertRaisesRegex(conductor.EvidenceError, "different ai.bin"):
            conductor._bind_java_ai_bin(
                {**bound, "ai_bin_sha256": "0" * 64}, ai)

    def test_certification_requires_all_52_and_full_telemetry(self):
        runs = []
        for row in conductor.load_fleet_requirements():
            runs.append({
                "scenario": row["native_scenario"], "seed": 1,
                "cycles": 1800, "artifact_id": row["id"],
                "comparison": {"state_identical": True, "identical": True},
            })
        result = conductor._certification(runs, certifiable_proof=True)
        self.assertTrue(result["complete"])
        self.assertEqual((52, 52, 52), (
            result["materialized_scenarios"], result["state_exact_scenarios"],
            result["telemetry_exact_scenarios"]))
        runs[-1]["comparison"]["identical"] = False
        result = conductor._certification(runs, certifiable_proof=True)
        self.assertFalse(result["complete"])
        self.assertEqual(51, result["telemetry_exact_scenarios"])
        self.assertFalse(conductor._certification(
            runs[:-1], certifiable_proof=True)["complete"])
        self.assertFalse(conductor._certification(
            runs, certifiable_proof=False)["complete"])


class JavaProofIdentityTest(unittest.TestCase):

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.repository = Path(self.temp.name)
        for relative, data in {
            ("desktop/src/main/java/net/chonkbase/chonkcraft/desktop/"
             "BneAiDecisionAdapter.java"): b"adapter-v1",
            "desktop/pom.xml": b"pom-v1",
            "scripts/jbr/with-jbr-25.sh": b"jbr-v1",
        }.items():
            path = self.repository / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        self.jar = self.repository / "desktop/target/" \
            "chonkcraft-desktop-0.1.0-SNAPSHOT-app.jar"
        self.jar.parent.mkdir(parents=True, exist_ok=True)
        self.jar.write_bytes(b"jar-v1")
        self.engine = {"schema": 2, "policy": "test",
                       "engine_input_sha256": "e" * 64}

    def tearDown(self):
        self.temp.cleanup()

    def receipt(self):
        with mock.patch.object(conductor, "_engine_identity",
                               return_value=self.engine):
            inputs = conductor._java_build_inputs(self.repository)
        value = {"schema": conductor.BUILD_RECEIPT_SCHEMA,
                 "build_inputs": inputs,
                 "jar": conductor._path_identity(self.jar)}
        conductor._build_receipt_path(self.repository).write_bytes(
            conductor._json_bytes(value))
        return value

    def test_skip_build_accepts_only_exact_source_and_jar_receipt(self):
        receipt = self.receipt()
        with mock.patch.object(conductor, "_engine_identity",
                               return_value=self.engine):
            jar, actual = conductor._verified_app(
                self.repository, build=False)
        self.assertEqual(self.jar, jar)
        self.assertEqual(receipt, actual)
        self.jar.write_bytes(b"stale-jar")
        with mock.patch.object(conductor, "_engine_identity",
                               return_value=self.engine):
            with self.assertRaisesRegex(conductor.EvidenceError, "JAR bytes"):
                conductor._verified_app(self.repository, build=False)

    def test_skip_build_rejects_adapter_pom_or_jbr_change(self):
        for relative in (
                "desktop/src/main/java/net/chonkbase/chonkcraft/desktop/"
                "BneAiDecisionAdapter.java",
                "desktop/pom.xml", "scripts/jbr/with-jbr-25.sh"):
            with self.subTest(relative=relative):
                self.receipt()
                path = self.repository / relative
                before = path.read_bytes()
                path.write_bytes(before + b"-changed")
                with mock.patch.object(conductor, "_engine_identity",
                                       return_value=self.engine):
                    with self.assertRaisesRegex(
                            conductor.EvidenceError, "stale"):
                        conductor._verified_app(self.repository, build=False)
                path.write_bytes(before)

    def test_java_choices_must_match_state_declared_computers(self):
        trace = native_trace()
        raw = json.dumps(manifest(trace)).encode()
        artifact = conductor.RemoteArtifact(
            "/remote/case.manifest.json", raw,
            conductor.validate_manifest(raw, "case"))
        value = java_ledger(trace, computers=(2,))
        with self.assertRaisesRegex(conductor.EvidenceError,
                                    "disagree with native state"):
            conductor._java_choices(value, artifact, [1])

    def test_certifiable_pack_requires_the_pinned_bne_ai_program(self):
        pack = self.repository / "wrong.chonkpack"
        with zipfile.ZipFile(pack, "w") as archive:
            archive.writestr("assets/archives/maindat/0277.bin", b"not-bne")
        with self.assertRaisesRegex(conductor.EvidenceError, "pinned"):
            conductor._extract_ai_bin(pack)
        self.assertEqual(b"not-bne", conductor._extract_ai_bin(
            pack, require_pinned=False))

    def test_current_java_proof_recomputes_receipt_jar_pack_and_ai_program(self):
        self.receipt()
        pack = self.repository / "pack.chonkpack"
        with zipfile.ZipFile(pack, "w") as archive:
            archive.writestr("assets/archives/maindat/0277.bin", ai_bytes())
        with mock.patch.object(conductor, "_engine_identity",
                               return_value=self.engine), \
                mock.patch.object(conductor, "PINNED_AI_BIN_SHA256",
                                  hashlib.sha256(ai_bytes()).hexdigest()), \
                mock.patch.object(conductor, "PINNED_AI_BIN_BYTES",
                                  len(ai_bytes())):
            proof, engine = conductor._current_java_proof(
                self.repository, pack)
        self.assertEqual(self.engine, engine)
        self.assertEqual(conductor._path_identity(self.jar),
                         proof["build_receipt"]["jar"])
        self.assertEqual(conductor._path_identity(pack), proof["pack"])
        self.assertEqual({"bytes": len(ai_bytes()),
                          "sha256": hashlib.sha256(ai_bytes()).hexdigest()},
                         proof["ai_bin"])


class ConductorIntegrationTest(unittest.TestCase):

    def setUp(self):
        self.temp = None
        self.reset_fixture()

    def reset_fixture(self):
        if self.temp is not None:
            self.temp.cleanup()
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.oracle = FakeOracle(self.root / "oracle")
        self.pack = self.oracle.pack(self.root / "pack.chonkpack")
        self.store = self.root / "store"

    def tearDown(self):
        self.temp.cleanup()

    def materialize(self):
        expected = java_ledger(self.oracle.trace)
        identity = {"schema": 2, "policy": "test", "head": "a" * 40,
                    "dirty": False, "engine_input_sha256": "e" * 64,
                    "input_count": 1}
        with mock.patch.object(conductor, "_engine_identity",
                               return_value=identity):
            _status, report = conductor.run_conductor(
                repository=Path(__file__).parents[3], store=self.store,
                backend=self.oracle.backend, pack=self.pack,
                remote_root=str(self.oracle.root),
                emitter=lambda _artifact, _output, _players: expected)
        return report, identity

    def validate_graph(self, report, identity):
        return conductor._validate_report_graph(
            report, store=self.store, java_proof=report["java_proof"],
            engine_identity=identity, certifiable_proof=False)

    def promote_certifiable(self, report, identity):
        old_proof_id = report["java_proof_id"]
        proof = json.loads(json.dumps(report["java_proof"]))
        proof["build_receipt"] = {
            "schema": conductor.BUILD_RECEIPT_SCHEMA,
            "build_inputs": {"engine": identity, "files": {}},
            "jar": {"bytes": 3, "sha256": hashlib.sha256(b"jar").hexdigest()},
        }
        proof_id = hashlib.sha256(conductor._canonical(proof)).hexdigest()
        old_root = self.store / "twins" / old_proof_id
        new_root = self.store / "twins" / proof_id
        old_root.rename(new_root)
        artifact_id = report["runs"][0]["artifact_id"]
        twin_root = new_root / artifact_id
        twin = json.loads((twin_root / "TWIN.json").read_text())
        twin["java_proof_id"] = proof_id
        twin_bytes = conductor._json_bytes(twin)
        (twin_root / "TWIN.json").write_bytes(twin_bytes)
        run = json.loads((twin_root / "RUN.json").read_text())
        run["java_proof_id"] = proof_id
        run["twin"] = f"twins/{proof_id}/{artifact_id}"
        run["twin_identity"] = conductor._identity(twin_bytes)
        (twin_root / "RUN.json").write_bytes(conductor._json_bytes(run))
        report = json.loads(json.dumps(report))
        report["java_proof"] = proof
        report["java_proof_id"] = proof_id
        report["selected_runs"] = [run]
        report["runs"] = [run]
        report["certification"] = conductor._certification(
            [run], certifiable_proof=True)
        report["next"] = conductor.ranked_findings([run])
        (self.store / "CATALOG.json").write_bytes(conductor._json_bytes(
            conductor._catalog_document(self.store, proof_id, [run])))
        (self.store / "NEXT.json").write_bytes(conductor._json_bytes(report))
        return report, proof

    def test_end_to_end_retains_only_manifest_and_normalized_ledgers(self):
        expected = java_ledger(self.oracle.trace)

        def emit(_artifact, _output, _players):
            return json.loads(json.dumps(expected))

        identity = {"schema": 2, "policy": "test", "head": "a" * 40,
                    "dirty": False, "engine_input_sha256": "e" * 64,
                    "input_count": 1}
        with mock.patch.object(conductor, "_engine_identity",
                               return_value=identity):
            status, report = conductor.run_conductor(
                repository=Path(__file__).parents[3], store=self.store,
                backend=self.oracle.backend, pack=self.pack,
                remote_root=str(self.oracle.root), emitter=emit)
        self.assertEqual(1, status)
        self.assertEqual(2, report["summary"]["denominator"])
        self.assertEqual(2, report["summary"]["state_exact"])
        self.assertTrue((self.store / "NEXT.json").is_file())
        self.assertTrue((self.store / "NEXT.md").is_file())
        retained = [path.name for path in (self.store / "objects").glob("*/*")]
        self.assertNotIn("case.trace.txt", retained)
        self.assertNotIn("state.bin", retained)
        self.assertIn("native-ledger.json", retained)
        self.assertIn("manifest.json", retained)

    def test_same_bytes_are_content_deduplicated(self):
        expected = java_ledger(self.oracle.trace)
        identity = {"schema": 2, "policy": "test", "head": "a" * 40,
                    "dirty": False, "engine_input_sha256": "e" * 64,
                    "input_count": 1}
        with mock.patch.object(conductor, "_engine_identity",
                               return_value=identity):
            for _ in range(2):
                conductor.run_conductor(
                    repository=Path(__file__).parents[3], store=self.store,
                    backend=self.oracle.backend, pack=self.pack,
                    remote_root=str(self.oracle.root),
                    emitter=lambda _artifact, _output, _players: expected)
        self.assertEqual(1, len(list((self.store / "objects").iterdir())))
        proof = json.loads((self.store / "NEXT.json").read_text())["java_proof_id"]
        twins = list((self.store / "twins" / proof).iterdir())
        self.assertEqual(1, len(twins))

    def test_pack_bytes_are_part_of_the_java_proof_namespace(self):
        expected = java_ledger(self.oracle.trace)
        identity = {"schema": 2, "policy": "test", "head": "a" * 40,
                    "dirty": False, "engine_input_sha256": "e" * 64,
                    "input_count": 1}
        with mock.patch.object(conductor, "_engine_identity",
                               return_value=identity):
            _, first = conductor.run_conductor(
                repository=Path(__file__).parents[3], store=self.store,
                backend=self.oracle.backend, pack=self.pack,
                remote_root=str(self.oracle.root),
                emitter=lambda _artifact, _output, _players: expected)
            second_pack = self.oracle.pack(self.root / "second.chonkpack")
            with zipfile.ZipFile(second_pack, "a") as archive:
                archive.writestr("proof-marker", "different-pack-bytes")
            _, second = conductor.run_conductor(
                repository=Path(__file__).parents[3], store=self.store,
                backend=self.oracle.backend, pack=second_pack,
                remote_root=str(self.oracle.root),
                emitter=lambda _artifact, _output, _players: expected)
        self.assertNotEqual(first["java_proof_id"], second["java_proof_id"])

    def test_gc_is_permanently_dry_run(self):
        orphan = self.store / "objects" / ("a" * 64)
        orphan.mkdir(parents=True)
        report = conductor.gc_dry_run(self.store)
        self.assertEqual(["a" * 64], report["would_remove_objects"])
        self.assertEqual([], report["removed"])
        self.assertTrue(orphan.is_dir())

    def test_content_addressed_members_refuse_in_place_rewrite(self):
        path = self.store / "objects" / ("a" * 64) / "manifest.json"
        conductor._write_immutable(path, b"first")
        conductor._write_immutable(path, b"first")
        with self.assertRaisesRegex(conductor.EvidenceError,
                                    "changed in place"):
            conductor._write_immutable(path, b"second")

    def test_retained_validator_rejects_run_only_evidence(self):
        report, identity = self.materialize()
        run = report["runs"][0]
        object_root = self.store / run["object"]
        twin_root = self.store / run["twin"]
        shutil.rmtree(object_root)
        for name in ("TWIN.json", "java-ledger.json", "comparison.json"):
            (twin_root / name).unlink()
        with self.assertRaisesRegex(conductor.EvidenceError,
                                    "object|proof member"):
            self.validate_graph(report, identity)

    def test_retained_validator_recomputes_comparison_and_ledgers(self):
        report, identity = self.materialize()
        twin_root = self.store / report["runs"][0]["twin"]
        comparison = json.loads((twin_root / "comparison.json").read_text())
        comparison["identical"] = not comparison["identical"]
        (twin_root / "comparison.json").write_bytes(
            conductor._json_bytes(comparison))
        with self.assertRaisesRegex(conductor.EvidenceError, "comparison"):
            self.validate_graph(report, identity)

        self.reset_fixture()
        report, identity = self.materialize()
        twin_root = self.store / report["runs"][0]["twin"]
        java = json.loads((twin_root / "java-ledger.json").read_text())
        java["rows"][0]["wait"] += 1
        (twin_root / "java-ledger.json").write_bytes(conductor._json_bytes(java))
        with self.assertRaisesRegex(conductor.EvidenceError,
                                    "comparison|TWIN|RUN"):
            self.validate_graph(report, identity)

    def test_retained_validator_rejects_wrong_parent_artifact_and_twin(self):
        report, identity = self.materialize()
        run_path = self.store / report["runs"][0]["twin"] / "RUN.json"
        run = json.loads(run_path.read_text())
        run["twin"] = "twins/" + "0" * 64 + "/" + run["artifact_id"]
        run_path.write_bytes(conductor._json_bytes(run))
        with self.assertRaisesRegex(conductor.EvidenceError, "RUN"):
            self.validate_graph(report, identity)

        self.reset_fixture()
        report, identity = self.materialize()
        twin_path = self.store / report["runs"][0]["twin"] / "TWIN.json"
        twin = json.loads(twin_path.read_text())
        twin["artifact_id"] = "0" * 64
        twin_path.write_bytes(conductor._json_bytes(twin))
        with self.assertRaisesRegex(conductor.EvidenceError, "TWIN"):
            self.validate_graph(report, identity)

        self.reset_fixture()
        report, identity = self.materialize()
        old = self.store / report["runs"][0]["twin"]
        wrong_proof = self.store / "twins" / ("0" * 64) / old.name
        wrong_proof.parent.mkdir()
        old.rename(wrong_proof)
        with self.assertRaisesRegex(conductor.EvidenceError,
                                    "runs differ|proof"):
            self.validate_graph(report, identity)

    def test_retained_validator_closes_native_object_and_catalog(self):
        report, identity = self.materialize()
        object_root = self.store / report["runs"][0]["object"]
        source = json.loads((object_root / "SOURCE.json").read_text())
        source["artifact_id"] = "0" * 64
        (object_root / "SOURCE.json").write_bytes(conductor._json_bytes(source))
        with self.assertRaisesRegex(conductor.EvidenceError, "SOURCE|identity"):
            self.validate_graph(report, identity)

        self.reset_fixture()
        report, identity = self.materialize()
        object_root = self.store / report["runs"][0]["object"]
        native = json.loads((object_root / "native-ledger.json").read_text())
        native["rows"][0]["wait"] += 1
        (object_root / "native-ledger.json").write_bytes(
            conductor._json_bytes(native))
        with self.assertRaisesRegex(conductor.EvidenceError,
                                    "object identity|native"):
            self.validate_graph(report, identity)

        self.reset_fixture()
        report, identity = self.materialize()
        catalog = json.loads((self.store / "CATALOG.json").read_text())
        catalog["twins"][0]["artifact_id"] = "0" * 64
        (self.store / "CATALOG.json").write_bytes(
            conductor._json_bytes(catalog))
        with self.assertRaisesRegex(conductor.EvidenceError, "catalog"):
            self.validate_graph(report, identity)

    def test_retained_validator_recomputes_report_summary(self):
        report, identity = self.materialize()
        forged = json.loads(json.dumps(report))
        forged["summary"]["telemetry_exact"] += 1
        with self.assertRaisesRegex(conductor.EvidenceError,
                                    "derived report"):
            self.validate_graph(forged, identity)

    def test_public_store_validator_closes_current_proof_and_disk_report(self):
        report, identity = self.materialize()
        report, proof = self.promote_certifiable(report, identity)
        with mock.patch.object(conductor, "_current_java_proof",
                               return_value=(proof, identity)):
            self.assertEqual(report, conductor.validate_retained_store(
                self.store, repository=self.root, pack=self.pack))
        stale = json.loads(json.dumps(proof))
        stale["build_receipt"]["jar"]["sha256"] = "0" * 64
        with mock.patch.object(conductor, "_current_java_proof",
                               return_value=(stale, identity)):
            with self.assertRaisesRegex(conductor.EvidenceError,
                                        "current Java proof"):
                conductor.validate_retained_store(
                    self.store, repository=self.root, pack=self.pack)
        detached = json.loads(json.dumps(report))
        detached["summary"]["denominator"] += 1
        with mock.patch.object(conductor, "_current_java_proof",
                               return_value=(proof, identity)):
            with self.assertRaisesRegex(conductor.EvidenceError, "detached"):
                conductor.validate_retained_report(
                    detached, store=self.store, repository=self.root,
                    pack=self.pack)


if __name__ == "__main__":
    unittest.main()
