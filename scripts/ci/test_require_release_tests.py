"""Publication cannot borrow a passing result from another revision or skipped job."""
from pathlib import Path
import runpy
import unittest

GATE = runpy.run_path(str(Path(__file__).with_name('require-release-tests.py')))


class ReleaseTestsTest(unittest.TestCase):
    def run_record(self, **changes):
        return dict({'id': 12, 'head_sha': 'a' * 40, 'head_branch': 'master',
                     'head_repository': {'full_name': 'owner/game'}, 'event': 'push',
                     'status': 'completed', 'conclusion': 'success'}, **changes)

    def jobs(self):
        return [{'name': name, 'conclusion': 'success'} for name in GATE['REQUIRED_JOBS']]

    def test_only_same_revision_trusted_master_runs_qualify(self):
        good = self.run_record()
        for changes in ({'head_sha': 'b' * 40}, {'head_branch': 'feature'},
                        {'event': 'pull_request'},
                        {'head_repository': {'full_name': 'fork/game'}}):
            wrong = self.run_record(**changes)
            self.assertIsNone(GATE['release_run']([wrong], 'owner/game', 'a' * 40))
        self.assertEqual(good, GATE['release_run']([good], 'owner/game', 'a' * 40))

    def test_newer_failed_or_pending_run_cannot_borrow_earlier_success(self):
        for status, conclusion in [('completed', 'failure'), ('in_progress', None)]:
            newer = self.run_record(id=13, status=status, conclusion=conclusion)
            chosen = GATE['release_run']([self.run_record(), newer], 'owner/game', 'a' * 40)
            self.assertEqual(newer, chosen)
            if status == 'completed':
                with self.assertRaises(RuntimeError):
                    GATE['check_run'](chosen, self.jobs())
            else:
                self.assertFalse(GATE['check_run'](chosen, self.jobs()))

    def test_both_required_jobs_must_have_executed_successfully(self):
        self.assertTrue(GATE['check_run'](self.run_record(), self.jobs()))
        for conclusion in ['skipped', 'cancelled', 'failure', None]:
            jobs = self.jobs()
            jobs[0]['conclusion'] = conclusion
            with self.assertRaises(RuntimeError):
                GATE['check_run'](self.run_record(), jobs)
        with self.assertRaises(RuntimeError):
            GATE['check_run'](self.run_record(), self.jobs()[:1])
        with self.assertRaises(RuntimeError):
            GATE['check_run'](self.run_record(), self.jobs() + self.jobs())

    def test_absent_run_never_qualifies(self):
        self.assertFalse(GATE['check_run'](None, []))


if __name__ == '__main__':
    unittest.main()
