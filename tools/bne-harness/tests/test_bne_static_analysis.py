from pathlib import Path
import sys
import unittest


SCRIPTS = Path(__file__).parents[1] / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import bne_static_analysis as static


class StaticAnalysisParserTest(unittest.TestCase):

    def test_objdump_slice_names_calls_branches_and_returns(self):
        text = """
00401000 <.text>:
  4379e0: 83 ec 08                      subl $0x8, %esp
  4379e3: 74 05                         je 0x4379ea <.text+0x369ea>
  4379e5: e8 c3 6d 00 00                calll 0x43e7ad <.text+0x3d7ad>
  4379ea: c3                            retl
"""
        rows = static.parse_objdump(text)
        self.assertEqual(["instruction", "branch", "call", "return"],
                         [row["kind"] for row in rows])
        self.assertEqual(0x4379EA, rows[1]["target"])
        self.assertEqual(0x43E7AD, rows[2]["target"])

    def test_ghidra_export_has_the_same_contract(self):
        rows = static.parse_ghidra_tsv(
            "# header\n0x4379e0\t83ec08\tSUB\tESP,0x8\t-\n"
            "0x4379e3\t7405\tJZ\t0x4379ea\t0x4379ea\n")
        self.assertEqual(0x4379E0, rows[0]["address"])
        self.assertEqual("branch", rows[1]["kind"])
        self.assertEqual(0x4379EA, rows[1]["target"])

    def test_analyzer_identity_names_the_exact_binary_and_runtime(self):
        identity = static.analyzer_identity(backend="objdump")
        self.assertEqual("objdump", identity["backend"])
        self.assertEqual(64, len(identity["executable"]["sha256"]))
        self.assertTrue(identity["version"])
        self.assertEqual(64, len(identity["python"]["executable"]["sha256"]))


if __name__ == "__main__":
    unittest.main()
