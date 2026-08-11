#!/usr/bin/env python3

from pathlib import Path
import sys
import tempfile
import unittest


SCRIPTS = Path(__file__).resolve().parents[1] / "scripts"
sys.path.insert(0, str(SCRIPTS))

import bne_doctor


class DoctorTest(unittest.TestCase):
    def layout(self, root):
        repository = root / "repo"
        (repository / "tools/bne-harness").mkdir(parents=True)
        (repository / "tools/bne-harness/PARITY.md").write_text("parity\n")
        asset = root / "assets.chonkpack"
        asset.write_bytes(b"asset")
        executable = root / "Warcraft-II-BNE.exe"
        executable.write_bytes(b"pinned")
        return repository, asset, executable

    def test_remote_capabilities_produce_an_immediate_route(self):
        with tempfile.TemporaryDirectory() as temporary:
            repository, asset, executable = self.layout(Path(temporary))

            def probe(command, timeout):
                if "ssh" in Path(command[0]).name:
                    return {"available": True, "returncode": 0,
                            "output": "root=1\ncorpus=1\ndocker=1\n"
                                      "branch_image=1\nbranch_capture=1"}
                return {"available": False, "returncode": 1, "output": "off"}

            report = bne_doctor.build_report(
                repository=repository, asset_pack=asset,
                executable=executable, local_oracle_root=None,
                remote_host="oracle-host",
                remote_root="$HOME/.local/share/chonkcraft-bne-oracle",
                need="capture",
                probe=probe,
            )

        self.assertTrue(report["ready"])
        self.assertTrue(report["remote"]["corpus"])
        self.assertEqual("remote-branch-witness", report["recommended"]["id"])

    def test_image_without_the_capture_subcommand_is_not_a_capture_route(self):
        with tempfile.TemporaryDirectory() as temporary:
            repository, asset, executable = self.layout(Path(temporary))

            def probe(command, timeout):
                if "ssh" in Path(command[0]).name:
                    return {"available": True, "returncode": 0,
                            "output": "root=1\ncorpus=1\ndocker=1\n"
                                      "branch_image=1\nbranch_capture=0"}
                return {"available": False, "returncode": 1, "output": "off"}

            report = bne_doctor.build_report(
                repository=repository, asset_pack=asset,
                executable=executable, local_oracle_root=None,
                remote_host="oracle-host",
                remote_root="$HOME/.local/share/chonkcraft-bne-oracle",
                need="capture", probe=probe,
            )

        self.assertFalse(report["ready"])
        self.assertFalse(report["remote"]["branch_capture_command"])
        self.assertIsNone(report["recommended"])

    def test_missing_routes_are_reported_without_guessing(self):
        with tempfile.TemporaryDirectory() as temporary:
            repository, asset, executable = self.layout(Path(temporary))
            report = bne_doctor.build_report(
                repository=repository, asset_pack=asset,
                executable=executable, local_oracle_root=None,
                remote_host=None,
                remote_root="$HOME/.local/share/chonkcraft-bne-oracle",
                docker="definitely-missing-docker",
            )
        self.assertFalse(report["ready"])
        self.assertIsNone(report["recommended"])

    def test_rejects_an_unsafe_remote_host(self):
        with tempfile.TemporaryDirectory() as temporary:
            repository, asset, executable = self.layout(Path(temporary))
            with self.assertRaisesRegex(ValueError, "remote host"):
                bne_doctor.build_report(
                    repository=repository, asset_pack=asset,
                    executable=executable, local_oracle_root=None,
                    remote_host="oracle-host; reboot",
                    remote_root="$HOME/.local/share/chonkcraft-bne-oracle",
                )


if __name__ == "__main__":
    unittest.main()
