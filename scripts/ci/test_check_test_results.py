"""Integration checks for CI gate aggregation using synthetic Surefire reports."""
import os
from pathlib import Path
import runpy
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
PROFILE = runpy.run_path(str(HERE / 'check-test-skips.py'))['PROFILES']['data-free']


class ResultsTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        for module, (tests, skipped) in PROFILE.items():
            source = self.root / module / 'src/test/java/Fixture.java'
            source.parent.mkdir(parents=True)
            source.write_text('class Fixture {}')
            report = self.root / module / 'target/surefire-reports/TEST-Fixture.xml'
            report.parent.mkdir(parents=True)
            suite = ET.Element('testsuite', name='Fixture', tests=str(tests), skipped=str(skipped))
            if module == 'engine':
                case = ET.SubElement(suite, 'testcase', classname='Fixture', name='known')
                ET.SubElement(case, 'failure', message='known defect')
            ET.ElementTree(suite).write(report)
        (self.root / 'baseline.txt').write_text('unsorted\tneither\tFixture#known\n')

    def run_gate(self):
        summary = self.root / 'summary.md'
        result = subprocess.run([
            sys.executable, str(HERE / 'check-test-results.py'), '--profile', 'data-free',
            '--repo-root', str(self.root), '--baseline', 'baseline.txt'],
            env={**os.environ, 'GITHUB_STEP_SUMMARY': str(summary)},
            text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        return result, summary.read_text()

    def test_known_failure_is_executed_and_reported_separately(self):
        result, summary = self.run_gate()
        self.assertEqual(0, result.returncode, result.stdout)
        self.assertIn('| Known failures (still executed) | 1 |', summary)
        self.assertIn('| Unexpected failures | 0 |', summary)

    def test_coverage_failure_does_not_hide_regression(self):
        report = self.root / 'engine/target/surefire-reports/TEST-Fixture.xml'
        tree = ET.parse(report)
        tree.getroot().set('skipped', str(PROFILE['engine'][1] + 1))
        case = ET.SubElement(tree.getroot(), 'testcase', classname='Fixture', name='new')
        ET.SubElement(case, 'failure', message='new defect')
        tree.write(report)
        result, summary = self.run_gate()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Coverage inventory: **FAIL**', summary)
        self.assertIn('Failure inventory: **FAIL**', summary)
        self.assertIn('Fixture#new', summary)

    def test_missing_module_fails_coverage(self):
        (self.root / 'desktop/target/surefire-reports/TEST-Fixture.xml').unlink()
        result, summary = self.run_gate()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('desktop: no Surefire reports at all', result.stdout)
        self.assertIn('Coverage inventory: **FAIL**', summary)


if __name__ == '__main__':
    unittest.main()
