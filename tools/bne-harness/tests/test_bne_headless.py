from pathlib import Path
import argparse
import sys
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
        self.assertEqual("1000", command[-3])
        self.assertEqual("1001", command[-2])
        self.assertEqual("/oracle/output/ai-cycle/case", command[-1])
        self.assertIn("chown -R", command[-5])
        self.assertIn("chmod -R u+rwX,go-rwx", command[-5])


if __name__ == "__main__":
    unittest.main()
