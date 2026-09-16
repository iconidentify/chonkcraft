#!/usr/bin/env python3
"""Require both test jobs on the exact master revision before OTA publication."""
import argparse
import json
import os
import re
import time
import urllib.parse
import urllib.request

REQUIRED_JOBS = {'Native Java, no game data', 'Authenticated game data'}


def release_run(runs, repository, revision):
    eligible = [run for run in runs
                if run.get('head_sha') == revision
                and run.get('head_branch') == 'master'
                and run.get('head_repository', {}).get('full_name') == repository
                and run.get('event') in {'push', 'workflow_dispatch'}]
    return max(eligible, key=lambda run: run['id'], default=None)


def check_run(run, jobs):
    if run is None or run.get('status') != 'completed':
        return False
    if run.get('conclusion') != 'success':
        raise RuntimeError(f"Tests run {run['id']} concluded {run.get('conclusion')}")
    for name in REQUIRED_JOBS:
        matches = [job for job in jobs if job.get('name') == name]
        if len(matches) != 1 or matches[0].get('conclusion') != 'success':
            raise RuntimeError(f"Required test job did not pass: {name}")
    return True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repository', required=True)
    parser.add_argument('--revision', required=True)
    parser.add_argument('--timeout-seconds', type=int, default=2700)
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', args.repository):
        parser.error('invalid repository')
    if not re.fullmatch(r'[0-9a-f]{40}', args.revision):
        parser.error('revision must be a full commit SHA')
    token = os.environ.get('GH_TOKEN', '')

    def get(path):
        request = urllib.request.Request('https://api.github.com/repos/' + args.repository + path,
                headers={'Accept': 'application/vnd.github+json',
                         'Authorization': 'Bearer ' + token,
                         'X-GitHub-Api-Version': '2022-11-28'})
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)

    deadline = time.monotonic() + args.timeout_seconds
    while True:
        runs = get('/actions/workflows/tests.yml/runs?' + urllib.parse.urlencode({
            'head_sha': args.revision, 'per_page': 100}))['workflow_runs']
        run = release_run(runs, args.repository, args.revision)
        jobs = []
        if run is not None and run.get('status') == 'completed':
            page = 1
            while True:
                batch = get(f"/actions/runs/{run['id']}/jobs?filter=latest&per_page=100&page={page}")['jobs']
                jobs.extend(batch)
                if len(batch) < 100:
                    break
                page += 1
        if check_run(run, jobs):
            current = get('/git/ref/heads/master')['object']['sha']
            if current != args.revision:
                raise RuntimeError('master advanced while tests ran; the newer revision must publish')
            print(f"Both test jobs passed for {args.revision}: {run['html_url']}")
            return
        if time.monotonic() >= deadline:
            raise RuntimeError('Timed out waiting for both test jobs on the release revision')
        print(f"Waiting for Tests on {args.revision}", flush=True)
        time.sleep(15)


if __name__ == '__main__':
    main()
