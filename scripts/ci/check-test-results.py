#!/usr/bin/env python3
"""Evaluate both CI gates and summarize executed, skipped, and failing tests."""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import runpy
import subprocess
import sys


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--profile', required=True)
    parser.add_argument('--repo-root', type=Path, default=Path.cwd())
    parser.add_argument('--baseline', type=Path,
                        default=Path('scripts/ci/expected-failures.txt'))
    args = parser.parse_args()
    root = args.repo_root.resolve()
    here = Path(__file__).resolve().parent
    coverage = runpy.run_path(str(here / 'check-test-skips.py'))
    failures = runpy.run_path(str(here / 'check-test-failures.py'))

    # Always evaluate both: a coverage mismatch must not hide new failures.
    coverage_result = subprocess.run([
        sys.executable, str(here / 'check-test-skips.py'),
        '--profile', args.profile, '--repo-root', str(root)], check=False)
    failure_result = subprocess.run([
        sys.executable, str(here / 'check-test-failures.py'),
        '--baseline', str(args.baseline), '--repo-root', str(root)], check=False)

    reports, _ = coverage['read_reports'](root)
    total = skipped = 0
    for classes in reports.values():
        count, omitted = coverage['totals'](classes)
        total += count
        skipped += omitted
    baseline_path = args.baseline if args.baseline.is_absolute() else root / args.baseline
    expected = failures['read_baseline'](baseline_path)
    failing = failures['failing_tests'](root)
    unexpected = sorted(set(failing) - set(expected))
    absent = sorted(set(expected) - set(failing))
    known = len(set(failing) & set(expected))
    summary = [
        f'## Test results: {args.profile}', '',
        '| Result | Count |', '|---|---:|',
        f'| Passed | {total - skipped - len(failing)} |',
        f'| Known failures (still executed) | {known} |',
        f'| Unexpected failures | {len(unexpected)} |',
        f'| Skipped (not executed) | {skipped} |',
        f'| Total discovered | {total} |', '',
        f'Coverage inventory: **{"PASS" if coverage_result.returncode == 0 else "FAIL"}**.  ',
        f'Failure inventory: **{"PASS" if failure_result.returncode == 0 else "FAIL"}**.', '',
        'Green means the coverage and failure inventories match; it does not mean every test passed.',
        'The data-free lane intentionally omits tests needing game media. The authenticated lane runs those tests.', '',
    ]
    if unexpected:
        summary += ['Unexpected failures:', ''] + [f'- `{name}`' for name in unexpected] + ['']
    if absent:
        summary += ['Expected failures not observed (check whether fixed, skipped, or missing):', '']
        summary += [f'- `{name}`' for name in absent] + ['']
    text = '\n'.join(summary) + '\n'
    print(text, flush=True)
    if destination := os.environ.get('GITHUB_STEP_SUMMARY'):
        with Path(destination).open('a', encoding='utf-8') as stream:
            stream.write(text)
    return int(coverage_result.returncode != 0 or failure_result.returncode != 0)


if __name__ == '__main__':
    raise SystemExit(main())
