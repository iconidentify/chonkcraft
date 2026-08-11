#!/usr/bin/env python3
"""Reject stale historical source-file citations in Java comments.

Comments belong to the implementation that exists in this repository. They may
name authenticated BNE symbols, addresses, fixtures, and measured cycles, but a
path into an older C++ source tree is neither durable nor locally verifiable.
"""

from __future__ import annotations

import pathlib
import sys


REPO = pathlib.Path(__file__).resolve().parents[2]


def java_comments(source: str):
    """Yield ``(line, comment)`` while ignoring strings and character literals."""
    index = 0
    line = 1
    length = len(source)
    while index < length:
        if source.startswith("//", index):
            end = source.find("\n", index)
            if end < 0:
                end = length
            yield line, source[index:end]
            line += source[index:end].count("\n")
            index = end
            continue
        if source.startswith("/*", index):
            end = source.find("*/", index + 2)
            if end < 0:
                end = length - 2
            end += 2
            comment = source[index:end]
            yield line, comment
            line += comment.count("\n")
            index = end
            continue
        if source.startswith('"""', index):
            end = source.find('"""', index + 3)
            if end < 0:
                return
            end += 3
            line += source[index:end].count("\n")
            index = end
            continue
        if source[index] in {'"', "'"}:
            quote = source[index]
            index += 1
            while index < length:
                if source[index] == "\\":
                    index += 2
                    continue
                if source[index] == quote:
                    index += 1
                    break
                if source[index] == "\n":
                    line += 1
                index += 1
            continue
        if source[index] == "\n":
            line += 1
        index += 1


def main() -> int:
    problems: list[str] = []
    for path in sorted(REPO.rglob("*.java")):
        relative = path.relative_to(REPO)
        if any(part in {".git", "target"} for part in relative.parts):
            continue
        source = path.read_text(encoding="utf-8")
        for first_line, comment in java_comments(source):
            for offset, text in enumerate(comment.splitlines()):
                if ".cpp" in text.lower():
                    problems.append(
                        f"{relative}:{first_line + offset}: historical C++ path in comment")
    if problems:
        print("\n".join(problems), file=sys.stderr)
        print("Explain current behavior or cite authenticated BNE evidence instead.",
              file=sys.stderr)
        return 1
    print("source comments contain no historical C++ paths")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
