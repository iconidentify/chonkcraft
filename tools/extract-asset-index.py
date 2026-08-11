#!/usr/bin/env python3
"""Generate the graphics asset index from ChonkCraft's wartool.h.

The upstream Todo table is 3000 lines of C initialisers that say which
archive entry produces which extracted file. Rather than transcribe it by
hand or embed the header, this reads it and emits a tab-separated resource
the data module loads. Paths are resolved through wartool's built-in English
``Names`` table while generating the resource. Those names are identifiers,
not display copy: resolving them from a Japanese, German, or French game's
localized string table makes a different and unusable asset namespace.

Row shape, from `typedef struct _control_` in wartool.h:

    {Type, Version, File, Arg1, Arg2, Arg3, Arg4, MPQFile, ArcFile}

with two abbreviating macros:

    #define __ ,0,0,0,"",""
    #define _2 ,0,0,"",""

Only the graphic kinds are emitted. For G and U, Arg1 is the palette entry,
Arg2 the graphic entry, and for G additionally Arg3 a second entry that
continues the frame numbering and Arg4 the frame it starts at.

Usage:
    tools/extract-asset-index.py ../chonkcraft/wartool.h \
        data/src/main/resources/chonkcraft/graphics-index.tsv
"""

import re
import sys

# Kinds worth indexing. The extraction dispatch accepts more kinds than the
# produce a graphic: the campaign maps and mission briefings come from the
# same table and are addressed the same way.
#   G  ConvertGfx     run-length coded sprite sheet
#   U  ConvertGfu     uncompressed sprite sheet
#   T  ConvertTileset terrain, Arg1..Arg4 = palette, mega, mini, map
#   W  ConvertWav     a sound; Arg1 is the entry in the sound archive
#   I  ConvertImage   a flat image: u16 width, u16 height, then the pixels
#   C  ConvertCursor  a cursor, same shape as an image with a hotspot
#   M  ConvertXmi     an XMI music track; Arg1 is the entry
#   P  ConvertPud     a campaign map; Arg1 is the entry
#   X  ConvertText    a briefing or credits text; Arg1 is the entry
#   V  ConvertVideo   a Smacker cutscene; Arg1 is the entry
#   N  ConvertFont    a bitmap font; Arg1 is the entry, palette comes from F
#   R  ConvertRgb     a tileset's RGB palette
#   D  ConvertGroupedGfu  UI widgets, grouped uncompressed sprites
#   L  CampaignsCreate    the campaign title and objectives table; Arg1 is
#                         the entry and Arg2 the offset into it
WANTED = {"G", "U", "T", "W", "I", "C", "M", "P", "X", "V", "N", "R", "D", "L"}

# The table is stateful. An F row opens an archive, and every row after it
# indexes into that one until the next F row. Ignoring this silently points
# sound entries at the graphics archive, where the numbers are still in range
# and decode to nonsense.
ARCHIVE_IDS = {
    "maindat.war": 1000,
    "snddat.war": 2000,
    "rezdat.war": 3000,
    "strdat.war": 4000,
    "sfxdat.sud": 5000,
    "muddat.cud": 6000,
}

ROW = re.compile(r"^\{([A-Z]),\s*(\d+),\s*\"([^\"]*)\"\s*(.*)$")
NAME_BLOB = re.compile(
    r"unsigned\s+char\s+Names\[\]\s*=\s*\{(.*?)\};", re.DOTALL)
REFERENCE = re.compile(r"%(-?)(\d+)")


def canonical_names(source):
    """Read wartool's stable English identifier table from the C header."""
    match = NAME_BLOB.search(source)
    if not match:
        raise ValueError("wartool.h has no canonical Names array")
    blob = bytes(int(value, 16)
                 for value in re.findall(r"0x([0-9A-Fa-f]{1,2})", match.group(1)))
    if len(blob) < 2:
        raise ValueError("wartool.h has an empty canonical Names array")
    count = int.from_bytes(blob[:2], "little")
    names = [""] * count
    for index in range(1, count):
        at = int.from_bytes(blob[index * 2:index * 2 + 2], "little")
        end = blob.find(b"\0", at)
        if at <= 0 or at >= len(blob) or end < 0:
            continue
        names[index] = blob[at:end].decode("latin-1")
    return names


def canonical_path(template, names):
    """Resolve a Todo path exactly as wartool's ParseString does."""
    def replace(match):
        index = int(match.group(2))
        value = names[index] if 0 < index < len(names) else ""
        if match.group(1):
            _, separator, tail = value.partition(" ")
            value = tail if separator else value
        return value.lower().replace("-", "_").replace(" ", "_")

    return REFERENCE.sub(replace, template)


def parse_args(tail):
    """Expand the macros and return Arg1..Arg4 as ints."""
    tail = tail.strip()
    if tail.endswith("},"):
        tail = tail[:-2]
    elif tail.endswith("}"):
        tail = tail[:-1]

    # The macros stand in for trailing zero arguments.
    macro_zeros = 0
    if "__" in tail:
        tail = tail.replace("__", "")
        macro_zeros = 3
    elif "_2" in tail:
        tail = tail.replace("_2", "")
        macro_zeros = 2

    numbers = []
    for piece in tail.split(","):
        piece = piece.strip()
        if not piece or piece.startswith('"'):
            continue
        try:
            numbers.append(int(piece, 0))
        except ValueError:
            pass
    numbers.extend([0] * macro_zeros)
    numbers.extend([0] * (4 - len(numbers)))
    return numbers[:4]


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        return 1
    source, target = sys.argv[1], sys.argv[2]

    with open(source, encoding="latin-1") as handle:
        source_text = handle.read()
    lines = source_text.splitlines()
    names = canonical_names(source_text)

    rows = []
    current_archive = 0
    index = 0
    while index < len(lines):
        line = lines[index].rstrip("\n")
        index += 1
        if not line.startswith("{"):
            continue
        match = ROW.match(line)
        if not match:
            continue
        kind, version, path, tail = match.groups()

        if kind == "F":
            # Opens an archive for the rows that follow.
            current_archive = ARCHIVE_IDS.get(path.lower(), 0)
            continue
        if kind not in WANTED:
            continue
        # A long row wraps after the path; the arguments are on the next line.
        while "}" not in tail and index < len(lines):
            tail += " " + lines[index].strip()
            index += 1
        args = parse_args(tail)
        rows.append((kind, version, current_archive,
                     canonical_path(path, names), *args))

    with open(target, "w", encoding="utf-8") as handle:
        handle.write("# Generated by tools/extract-asset-index.py from chonkcraft/wartool.h\n")
        handle.write("# %N references are canonical English identifiers, resolved at generation time\n")
        handle.write("# kind\tversion\tarchive\tpath\targ1\targ2\targ3\targ4\n")
        for row in rows:
            handle.write("\t".join(str(field) for field in row) + "\n")

    counts = {}
    for row in rows:
        counts[row[0]] = counts.get(row[0], 0) + 1
    print(f"wrote {len(rows)} rows to {target}: " +
          ", ".join(f"{k}={v}" for k, v in sorted(counts.items())))
    return 0


if __name__ == "__main__":
    sys.exit(main())
