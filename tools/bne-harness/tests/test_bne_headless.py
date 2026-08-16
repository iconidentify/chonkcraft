from pathlib import Path
import argparse
import sys
import tempfile
import unittest
from unittest import mock


SCRIPTS = Path(__file__).parents[1] / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import bne_headless


class HeadlessOutputOwnershipTest(unittest.TestCase):

    def test_sealed_capture_is_returned_to_unprivileged_operator(self):
        args = argparse.Namespace(docker="docker", image="oracle:test")
        with mock.patch.object(bne_headless.os, "getuid", return_value=1000), \
                mock.patch.object(bne_headless.os, "getgid", return_value=1001):
            command = bne_headless.output_ownership_command(
                args, Path("/srv/oracle"),
                Path("/oracle/output/ai-cycle/case"))
        self.assertEqual("docker", command[0])
        self.assertIn("/srv/oracle:/oracle", command)
        self.assertIn("--entrypoint", command)
        self.assertEqual("sh", command[command.index("--entrypoint") + 1])
        self.assertEqual("1000", command[-3])
        self.assertEqual("1001", command[-2])
        self.assertEqual("/oracle/output/ai-cycle/case", command[-1])
        self.assertIn("chown -R", command[-5])
        self.assertIn("chmod -R u+rwX,go-rwx", command[-5])

    def test_host_inode_ownership_is_checked_not_assumed(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            evidence = root / "snapshot.json"
            evidence.write_text("{}", encoding="utf-8")
            status = evidence.stat()
            bne_headless.verify_output_ownership(
                root, uid=status.st_uid, gid=status.st_gid)
            with self.assertRaises(RuntimeError) as raised:
                bne_headless.verify_output_ownership(
                    root, uid=status.st_uid + 1, gid=status.st_gid)
            self.assertIn("snapshot.json", str(raised.exception))

    def test_output_tree_refuses_symbolic_links(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            evidence = root / "snapshot.json"
            evidence.write_text("{}", encoding="utf-8")
            link = root / "outside"
            link.symlink_to(evidence)
            status = root.stat()
            with self.assertRaisesRegex(RuntimeError, "symbolic links"):
                bne_headless.verify_output_ownership(
                    root, uid=status.st_uid, gid=status.st_gid)


if __name__ == "__main__":
    unittest.main()
