# The asset pack format

One file holds everything the game draws, plays and reads. This document
specifies that file: what is in it, how it is encoded, and what an artist has
to produce for the game to accept new art.

The format is called **chonkpack**. It is not specific to Warcraft II and
contains no Warcraft II vocabulary; the first pack happens to be built from a
1995 installation, and a later one will not be.

- [Why a pack at all](#why-a-pack-at-all)
- [The container](#the-container)
- [The manifest](#the-manifest)
- [The three ways in](#the-three-ways-in)
- [Codecs](#codecs)
- [Asset kinds: the data dictionary](#asset-kinds-the-data-dictionary)
- [Rules a pack must satisfy](#rules-a-pack-must-satisfy)
- [Producing a pack](#producing-a-pack)
- [Replacing art](#replacing-art)
- [What the format deliberately does not do](#what-the-format-deliberately-does-not-do)

## Why a pack at all

Before this, the game read a Warcraft II installation directly: six archives in
a `DATA` directory, two CD images beside them, and seventy-seven `.PUD` files
scattered across four folders. That works and it is what the port was built
against, but it has three problems that do not go away on their own.

**Nothing else can produce it.** The 1995 archives are a numbered container
with a Blizzard LZSS payload, sprites are run-length coded against a per-row
offset table, and the music is raw red book sectors whose boundaries live in a
`.cue` file. An artist cannot make any of that, so the game could only ever
draw art that shipped in 1995.

**It is 1,091,487,952 bytes, most of which is uncompressed.** Ninety minutes of
red book audio is 902.8 MB of raw PCM sectors -- 946,687,056 bytes, which is
exactly 402,503 sectors of 2,352 -- against 17.5 MB of `DATA` archives, 114.4
MB of `MUDDAT.CUD` and `SNDDAT.WAR` off the discs, and 6.2 MB of `.PUD` maps.
Nothing in a 1995 format is compressed by anything a modern encoder would
recognise.

**The facts that are not in any file are lost the moment you copy it.**
Whether an installation has the expansion is worked out by counting archive
entries, measuring `rezdat.war` to the byte, and probing for a Battle.net tome
file. Where a music track ends is in a text file next to the disc image. A
pack writes those answers down instead of leaving the evidence lying around.

## The container

A pack is a **zip**. `java.util.zip` reads it, so does every operating system's
file manager, and so does any language an artist's tooling might be written in.

```
chonkcraft.chonkpack
├── pack.json                       the manifest
├── dictionary.md                   what is in this pack, in prose
└── assets/
    ├── graphics/human/units/footman.png
    ├── graphics/tilesets/summer/minitiles.png
    ├── sounds/human/footman/selected1.flac
    ├── sounds/human/basic-voices/ready.opus
    ├── music/cd/WC2TOD/track-02.opus
    ├── music/xmi/human-battle-1.xmi
    ├── videos/gameintro.smk
    ├── maps/multi/3VS3.pud
    └── archives/maindat/0028.bin
```

Two rules about the zip itself:

- Entries whose payload is already compressed (PNG, FLAC, Opus, Smacker) are
  **STORED**, not deflated. Deflating a PNG returns a fraction of a percent and
  costs a pass over the data; across 900 MB of audio that is minutes of build
  time for kilobytes of pack.
- Every entry's timestamp is fixed at zero, so that building the same pack
  twice from the same source produces the same bytes. A build that is a
  different artefact every time cannot be cached, compared or verified.

Paths inside the zip are lower-case with forward slashes. Zip entry names are
case-sensitive and the filesystem the raw data came from is not, so a pack that
preserves the original spelling works on one machine and fails on another.

## The manifest

`pack.json` is the whole contents of the pack as a document. It is
pretty-printed on purpose: it is a file a person reads and a reviewer diffs,
and the whitespace disappears into the zip's deflate.

```json
{
  "format": "chonkpack",
  "formatVersion": 1,
  "pack": {
    "id": "wc2-dos-1995",
    "name": "Warcraft II: Tides of Darkness",
    "source": "Warcraft II DOS installation",
    "builtBy": "chonkcraft-extractor 0.1.0-SNAPSHOT",
    "builtAt": "2026-07-26T22:41:07Z",
    "properties": {
      "expansionEntries": true,
      "expansionRelease": true,
      "battleNetEdition": false,
      "campaignTextOffset": 236,
      "sourceVersion": "Beyond the Dark Portal",
      "sourceFormat": "Classic Warcraft II archives",
      "sourceOriginalName": "WAR2X.ISO",
      "sourceOriginalBytes": 681574400,
      "sourceOriginalSha256": "..."
    }
  },
  "archives": [
    { "id": 1000, "name": "maindat", "entryCount": 528, "slots": [0, 1, -1, 2, ...] }
  ],
  "discs": [
    { "name": "WC2TOD", "tracks": [881, 882, 883] }
  ],
  "maps": [1204, 1205, 1206],
  "assets": [
    {
      "id": "graphics/human/units/footman",
      "kind": "sprite",
      "codec": "png-indexed",
      "file": "assets/graphics/human/units/footman.png",
      "bytes": 24117,
      "sha256": "3f1c...",
      "meta": {
        "width": 360,
        "height": 720,
        "transparentIndex": 255,
        "encoding": "gfx",
        "cellWidth": 72,
        "cellHeight": 72,
        "framesPerRow": 5,
        "frames": [[0, 0, 45, 40], [1, 0, 44, 40], ...]
      }
    }
  ]
}
```

The source fields are provenance, not decoration. `sourceVersion` is the most
specific release the importer could prove, while `sourceFormat` records the
layout that proved it. When the player selected a file, the original filename,
byte length and complete SHA-256 remain in the pack even though the temporary
extraction directory is removed. Together with `builtBy` and `builtAt`, those
fields let a library distinguish two packs of the same edition made from
different discs, installers or community releases. Older version-1 packs that
do not carry the fields remain readable.

A reader that meets `formatVersion` higher than it knows **refuses the pack**.
It does not read what it recognises and ignore the rest: a format that silently
drops what it cannot parse loses assets quietly, and a missing asset in a game
is a blank square, not an error.

`meta` is an open map, not a fixed schema. A reader must tolerate a field it
has never heard of, and a writer must be able to add one without every reader
needing a new release.

## The three ways in

Different callers know different things about an asset, so a pack is indexed
three ways. All three point at the same bytes; nothing is stored twice.

**By name.** `graphics/human/units/footman`. What an artist uses, what a game
script uses, and the only spelling that survives a change of source data.

**By slot.** Archive 1000, entry 33. A port of a 1995 engine needs this,
because the original data is numbered rather than named and the engine holds
those numbers as constants: the font palette is entry 2, the second widget
palette is entry 14, each terrain tileset is a fixed triple of entries. The
`archives` array maps every original entry index to an asset, or to `-1` where
the original archive had nothing readable there.

The slot table is **dense and keeps its holes**. A Warcraft II `maindat.war`
has five junk entries at indices 28 to 32; they are unreadable and they still
occupy their indices, because closing the gap would renumber everything after
them and the numbers are what the engine holds. It matters twice: the port
decides an installation has the expansion by comparing the entry count against
437, and the sound bank bounds-checks every entry number against it.

**By collection.** `discs` lists each disc's music in playing order, because
order is the only identity recorded music has — the game asks for "track three
of whichever disc is in the drive". `maps` lists the loose playable maps.

### One file, two assets

An asset may declare `sameAs`, naming another asset whose file it reads, plus
`frameOffset` and `sampleFrames` for the window it takes out of it. It carries
no bytes of its own.

This is not a general symlink; it exists because of a specific and large piece
of duplication. A Warcraft II installation with both discs has thirty-three red
book tracks and nineteen recordings: fourteen of Beyond the Dark Portal's
tracks are Tides of Darkness's, pressed about a fifth of a second apart. The
audio is identical to the sample and the discs merely cut the track boundary in
different places, so a hash finds nothing and stored twice it costs 358 MB —
more than half the pack.

The extractor finds these by looking for an alignment rather than an equality,
and then **proves it by comparing every overlapping sample** before storing
anything once. The group's file holds the union of its members, and each member
is a window that reproduces its own samples exactly. Nothing here is
approximate; a pair that fails the proof stays stored twice.

## Codecs

The codec says how the bytes in the zip turn back into the asset. It is
orthogonal to the kind: a sprite is a sprite whether it arrives as a PNG or as
a raw index plane, and a consumer that switches on the kind rather than on the
codec keeps working when the encoding changes underneath it.

| Codec | Payload | Lossless | Used for |
|---|---|---|---|
| `png-indexed` | 8-bit palette-indexed PNG, colour type 3 | yes | every picture |
| `opus` | an Opus stream in an Ogg container | **no** | recorded music and sampled sound |
| `flac` | native FLAC stream | yes | audio kept at full precision |
| `wav` | RIFF WAVE, PCM | yes | audio too short for anything to pay for itself |
| `smacker` | a Smacker `.smk` file, unaltered | yes | cutscenes |
| `midi` | a standard MIDI file | yes | sequenced music |
| `store` | the bytes are the asset | yes | everything else |

**Every codec here is lossless except `opus`, and that exception was bought
deliberately.** The rule matters: a pack is the only copy of the art the game
will ever see, so an encoder allowed to approximate turns a build-time
convenience into a permanent loss that no later build can undo. It is worth
breaking exactly once, and only where the measurement justifies it.

Ninety minutes of red book music is 902 MB of raw sectors and 335 MB as FLAC —
three quarters of a lossless pack, for audio that plays under a game at -12 dB
through a mixer that already resamples it 44,100 to 48,000 with linear
interpolation. That resampling is a cruder operation than Opus transparency.
Trading it takes the music to 58 MB and the whole pack from 442 MB to 157 MB.

So the format carries both, per asset, and the pack records which:

| content | codec | why |
|---|---|---|
| recorded music | `opus` at 144 kbps | 902 MB of raw sectors to 58 MB |
| sound effects | `opus` at 64 kbps, where it wins | 8-bit mono at 11 and 22 kHz; 128k would make them **bigger** |
| anything else sampled | `flac` | lossless, and what a sound falls back to |

Battle.net Edition uses the same storage policy with better sources: its named
effects and campaign narration are 16-bit at 22.05 kHz, its twenty recorded
music files are 16-bit stereo at 22.05 kHz, and its main Smacker movies are
320x288 with 16-bit stereo sound. The older numbered graphics archives are
byte-identical; the improved media lives in `INSTALL.EXE` and its nested
`War2Dat.mpq`, so the importer overlays those named files rather than treating
the four visible TOMEs as the whole edition.

That second row is not a rounding of the first. The effects are 487 clips of
8-bit mono, mostly under a second, and Opus runs at 48 kHz in 20 ms frames, so
at 128 kbps a half-second grunt costs 8 KB regardless of what is in it.
Measured over all 487: raw 52.4 MB, FLAC 30.5 MB, **Opus 128k 45.8 MB**, Opus
64k 23.2 MB. A single global bitrate would have quietly made a third of the
audio worse and larger at the same time.

The music rate is 144 and not 128 for a reason about this encoder rather than
about Opus. It is CELT-only and constant-rate, measures level with the RFC 6716
reference encoder, and is ahead of libopus on these sound effects at 64k and
behind it on music at 128k. Buying the difference back with bits is the honest
fix and costs about six megabytes across ninety minutes.

**"Where it wins" is literal, and per clip.** An encoder that is allowed to
approximate must at least be smaller for it; a lossy encoding that comes out
larger than a lossless one is the worst of both, and at 64 kbps in 20 ms frames
that is what happens to a short clip. So the build encodes each sound both ways
and keeps whichever is smaller, and a pack ends up holding both codecs:

| | count | in the pack |
|---|---:|---:|
| `sound` stored `opus` | 132 | |
| `sound` stored `flac` | 355 | |
| all 487 together | | **20.8 MB** |

which is under all-Opus (23.2 MB) and well under all-FLAC (30.5 MB). The rule
costs nothing and is not a hedge: it is the only rule that cannot make an asset
both worse and bigger.

A clip also has to survive the encoding to be shipped lossily. The build decodes
what it just encoded, compares it with the entry it came from, and refuses
anything under **12 dB** of waveform signal-to-noise, falling back to lossless.
On the real sound bank that rejects 3 clips of 135 candidates — the kept ones
average 17.1 dB and the worst is 13.8 dB, and the best rejected one is 11.0 dB,
so the floor sits in a real gap rather than on top of the distribution. It is
there as a net, not as a policy: what actually decides the codec is size.

### Opus decodes at 48 kHz, and the pack does not

Nothing in an Opus bitstream says what rate to reconstruct at. RFC 6716 defines
the codec at 48,000 Hz and that is the only rate it produces. Warcraft II's
effects are 8-bit mono at 11,025 and 22,050 and its music is 16-bit stereo at
44,100, so every asset stored as `opus` is resampled on the way in and back
again on the way out.

**Back again is not optional, and this is the decision to understand before
changing anything here.** Handing the game 48 kHz audio instead would make the
engine's own resamplers no-ops: one resample instead of two, measurably better
audio. It would also mean a pack sounded different from the installation it was
built from, because the port's linear resample from 11,025 is part of how this
game sounds, and it would change every sample count that follows. So:

> Opus's 48 kHz is a storage detail. Every interface the pack offers — an
> archive entry, a music track, `AssetPack.audio` — is at the source's own rate,
> its own channel count and its own bit depth.

The frame count survives exactly, not approximately. The encoder writes a final
granule position of `preSkip + ceil(frames * 48000 / rate)` and the decoder
recovers `floor(count * rate / 48000)`; for any rate at or below 48 kHz that
composition is the identity. A rebuilt sound entry is the same length in frames
as the entry it replaces, and a music track is the same length as the disc's.

The cost is a third resample, at load, on a signal already band-limited well
below the rate it is going back to. It is the cheapest of the three places this
could have been paid.

Pictures are stored **palette-indexed, never as colour**. This is the single
most important constraint in the format. In this game a unit's team colour is
produced by swapping a band of palette indices at draw time, and the animated
water and lava are produced by rotating another band five times a second
against a shared raster. Both operate on the index, not on the pixel. A pack
that resolves indices to RGB — even losslessly, even at 32 bits — freezes every
player to the same colour and stops the water moving.

Audio carries its **own** sample rate, not the mixer's. Warcraft II's effects
are 8-bit at 11,025 and 22,050 and its music is 16-bit stereo at 44,100; the
mixer runs at 48,000 and the resampling between them is a specific, tested
piece of the game. A pack that helpfully resamples on the way in replaces that
with its own arithmetic.

## Asset kinds: the data dictionary

This is what an artist authors against. The kind decides which accessor the
game reaches for and which `meta` fields are meaningful.

### `sprite`

A sheet of animation frames on a grid.

| Field | Meaning |
|---|---|
| `width`, `height` | the sheet, in pixels |
| `transparentIndex` | the palette index that is a hole; 255 here |
| `encoding` | `gfx` (run-length) or `gfu` (raw rows) — how the game's own decoder wants it back |
| `cellWidth`, `cellHeight` | the grid cell every frame is drawn into |
| `framesPerRow` | 5, or 1 for a sheet with fewer than five frames |
| `frames` | `[x, y, width, height]` per frame, in animation order, relative to its cell |

A sheet is not self-describing: where one frame ends and the next begins is a
fact about the animation, not about the picture. `frames` is why an artist can
repaint a footman and have him still animate.

### `image`

One picture with no frame structure: an interface panel, a background, a title
card. `width`, `height`. Index 0 is a real colour here and is **not**
transparent — the same byte means a hole in a sprite and black in an image.

### `cursor`

A picture plus `hotspot`, `[x, y]`, which is where the point of the pointer is.
A cursor's hole is palette index 0 in the source and 255 once decoded.

### `font`

A sheet of glyphs, fifteen to a row, plus `glyphWidth`, `glyphHeight`, `count`
and `widths` — the true drawn width of each glyph, which is what spaces text.
Fifteen per row is not a convention that can be changed: the game divides the
sheet width by it to get the cell width.

### `widgets`

One sheet several named pieces are cut out of at recorded rectangles, listed in
`pieces` as `{name, x, y, width, height}`. Buttons, checkboxes, sliders and
arrows are one image in the source, not fifty-three.

### `palette`

768 bytes: 256 entries of red, green, blue. Stored `store`, because a palette
is a table and not a picture.

Warcraft II's palettes are 6-bit VGA values scaled to 8 bits by a **left shift
of two**, so a maximum component of 0x3F becomes 0xFC and not 0xFF. That is
preserved exactly. A pack that renormalises "correctly" shifts every colour in
the game, and every index-band operation lands on a slightly different colour
than it did in 1995.

### `tile-atlas` and `tile-table`

Terrain is stored twice-indirected. The **atlas** is a grid of 8x8 blocks; the
**table** says which four-by-four arrangement of blocks, in which of four
mirrorings, composes each 32x32 terrain tile. One stored block therefore serves
up to four orientations, which is how a whole tileset fits in a few tens of
kilobytes.

The atlas is a `png-indexed` picture laid out 32 blocks to a row, so an artist
can edit terrain. The mapping between the block index the table uses and the
block's position in the atlas is `(index % 32 * 8, index / 32 * 8)`, exactly
and always. The table is `store`, because it is data rather than art.

### `sound`

A short sound. `sampleRate`, `channels`, `bitsPerSample`, `sampleFrames`.

Every one of those four is the *original* file's, not the codec's, and every one
of them is needed. An Opus stream records the rate it was encoded from and
nothing else: not the bit depth, which Opus has no notion of, and not the frame
count in the source's own frames. A stream stored `opus` carries two more
fields, which a reader may ignore and a person reading a manifest should not
have to work out:

| Field | Meaning |
|---|---|
| `decodeSampleRate` | 48,000 — the rate the stream actually decodes at, before it is resampled back to `sampleRate` |
| `bitrateBps` | what it was encoded at |

Sounds are **never deduplicated**, even when two are byte-identical. Which
sound a unit plays is chosen by index into a group, and the group sizes decide
the choice; collapsing two identical clips changes which one a footman answers
with, and in a networked game both peers must choose the same one.

### `music`

A recorded track. Same fields as `sound`, and `sampleFrames` matters more here:
a red book track has no length of its own, it is a run of raw sectors, and a
pack that loses the count plays the beginning of the next track over the end of
this one.

`frameOffset` and `sampleFrames` are in the **recording's** frames, at
`sampleRate`, never in the codec's 48 kHz. That is what makes a window into a
shared stream mean the same thing whichever codec is underneath it.

A window into an `opus` stream is taken after the whole stream has been decoded,
and there is no other place it could be taken. A FLAC stream is
sample-addressable — its frames are independent — so a reader that wanted to
could decode only the part a window covers. An Opus stream is not: every packet
is predicted from the one before it through the MDCT overlap and the energy
envelope, so the only correct entry point is the start. It costs nothing here,
because a track is loaded whole anyway.

### `sequence`

Music as events for a synthesiser rather than as samples. Stored as the
original bytes; see [what the format does not do](#what-the-format-deliberately-does-not-do).

### `video`

A cutscene. `width`, `height`, `frames`, `fpsNum`, `fpsDen`, and the audio
track's `sampleRate` and `channels`. Stored as Smacker, unaltered — see below
for why that is the right answer and not laziness.

### `map`

A playable map, stored as the bytes the game's own map reader takes.

### `text`

Prose: a briefing, an objective list, the credits. Stored as the **exact**
original bytes, because the game reads text as a byte range starting at a
recorded offset inside a larger entry and stopping at the first NUL, and
because the encoding is IBM code page 437 rather than Latin-1 — the briefings
draw their borders out of box-drawing characters that do not survive a trip
through UTF-8.

### `binary`

Bytes with a structure the format does not model. Every pack has some. The
alternative to admitting that is a format that quietly drops whatever it has no
name for, which is how an asset goes missing without anything failing.

## Rules a pack must satisfy

A pack that breaks any of these is malformed even if it loads.

1. **Asset ids are unique.** The writer refuses a duplicate.
2. **Every slot points at a real asset or at `-1`.**
3. **`entryCount` equals the length of `slots`.**
4. **An invalid slot answers, and does not throw.** Reading an entry the
   original archive had nothing readable in returns the single byte `0x01`,
   which is what the 1995 extraction tool substitutes. Callers rely on it: the
   font and cursor loaders sniff the result and fall through to null, so an
   implementation that throws turns a graceful degradation into a crash.
5. **Decoding is exact, except where the codec says it is not.** For every asset
   stored in a lossless codec, decoding the pack's payload and re-encoding it to
   what the game's own reader expects must produce the same pixels or the same
   samples as the original did. For an asset stored in a lossy one — `opus`, the
   only one there is — the requirement is that the sample rate, the channel
   count and the bit depth are identical, the length agrees to within one frame,
   and the signal-to-noise against the original clears the floor for its kind:
   12 dB for a sound, 8 dB for music. Which question is asked is decided by the
   codec, not guessed. Both are checked for every asset at build time, not
   asserted; see below.
6. **`sha256` is over the stored payload**, in lower-case hex.

## Producing a pack

The extractor is a separate program that shares no code with the game:

```bash
scripts/build-asset-pack.sh                       # from $WC2_INSTALL_DIR
scripts/build-asset-pack.sh --out /tmp/wc2.chonkpack --verify
```

Verification is **on by default**. It re-reads every asset out of the finished
pack, rebuilds the archive entry from it, and compares that against what the
installation produced — pixels for a picture, samples for a sound, bytes for
anything stored raw, and for the lossy audio the four-part question in rule 5
above. It roughly doubles the build, and it is the reason the
format can be trusted: the guarantee is not "we were careful" but "every one of
the 1,355 assets was checked". `--no-verify` turns it off and makes the result
worth less.

Measured on a Warcraft II DOS installation with both discs:

```
  source data         1.0 GB
  pack              157.0 MB
  reduction            85.1%
  assets                1355 (864 converted, 381 stored raw)
  empty slots             29

  kind             count         source         packed of source
  music               19       902.8 MB        58.1 MB      6.4%
  video               17        68.1 MB        68.1 MB    100.0%
  sound              492        52.6 MB        20.8 MB     39.6%
  map                161        13.3 MB         2.1 MB     15.9%
  image               61        11.8 MB         4.1 MB     34.5%
  sprite             266         4.7 MB         2.5 MB     54.0%
  tile-atlas           4       917.5 KB       292.9 KB     31.9%
  binary             197       677.4 KB       306.4 KB     45.2%
  sequence            17       264.9 KB       139.3 KB     52.6%
  palette             20       132.5 KB        57.7 KB     43.6%
  font                 5       114.7 KB        33.9 KB     29.5%
  tile-table           4        47.1 KB        32.0 KB     67.9%
  text                56        44.5 KB        24.6 KB     55.3%
  cursor              22        18.3 KB        28.1 KB    154.0%

  verified: 1187 of 1355 assets read back out of the pack decode to exactly
  what the installation decodes to
  the other 168 are stored lossily and were checked for rate, channels,
  length and signal to noise instead: 135 sound effects, worst 13.8 dB
  against a floor of 12.0 dB; 33 music tracks, worst 21.5 dB against a
  floor of 8.0 dB

  built in 94.9 s
```

The separately measured Battle.net Edition 2.02b pack is not expected to have
the DOS counts. A fixed-epoch import from the pinned 662,253,608-byte USA ZIP
produces these byte-identical results twice:

```
  pack bytes       220,309,920
  pack SHA-256     662b14fb73d75d37bb9e64f6359aada0a7af7336cef6d5665c9b5b7da9f6cbac
  logical assets          1,412
  map assets                237 (153 BNE map identities)
  music                      20 (19 physical recordings; one proved alias)
  sounds                     491
  sprites                    266
  binary                     192
  videos                      13
  sequences                   17

  verified exactly           903
  checked lossily            509
  sound effects              489, worst 13.5 dB against 12.0 dB
  music                       20, worst 22.2 dB against 8.0 dB
```

The apparently missing 492nd sound is not an extraction failure. Comparing
the BNE pack with the 492-sound classic DOS packs leaves exactly
`sounds/spells/basic_spell_sound`, graphics-index SFXDAT slot 68. BNE marks
that classic placeholder invalid and has no named overlay for it. Adding a
sound to make the totals agree would invent media the BNE installation does
not contain.

To the byte, and against the two baselines a player would recognise:

| | bytes | |
|---|---:|---|
| the 1995 files the game reads | 1,091,487,952 | archives, discs, red book PCM, maps |
| the whole install directory | 1,468,486,021 | 172 files, both disc images included |
| **the pack** | **164,650,362** | 157.0 MB |

That is **15.08%** of what the game reads and **11.21%** of what has to be on
disk for it to run -- 84.92% and 88.79% off. The `source data` line the
extractor prints is a larger number, 1,106,758,689, because it counts every
asset *decompressed*, which is what the per-kind ratios above are against.

Two rows in that table deserve a word. **Video is 100%** because it is stored
unaltered, for the reasons measured below. **Cursors are 154%** because a
palette-indexed PNG always writes all 256 palette entries, which is 780 bytes,
and the largest cursor in the game is a few hundred pixels; the whole overhead
is ten kilobytes and it buys twenty-two pointers an artist can open and repaint.
Index identity is worth more than ten kilobytes.

## Replacing art

The pack is the interface, so replacing art means replacing a file in it and
nothing else.

1. Open the pack. Find the asset in `pack.json` by its `id`.
2. Edit the file it names. A `png-indexed` asset opens in any paint program.
3. Keep the palette. Your editor must not remap, quantise or reorder indices;
   export as an indexed PNG with the same 256-entry palette. If your tool
   cannot, edit the indices rather than the colours.
4. Keep the geometry, or update `meta` to match. Changing a sheet's
   `cellWidth` without changing `frames` moves every frame.
5. Put the file back in the zip. That is all.

Step 5 really is all. The manifest's `bytes` and `sha256` describe the pack as
it was built, and a replaced asset simply no longer matches them; the loader
does not care, and a verification pass will tell you which assets have been
changed since the build, which is usually the question you wanted answered
anyway. Nothing is lost by this: a zip carries a CRC per entry, so a truncated
or corrupted file still fails to load.

The parts of `meta` that are not pixels are the contract. An artist can change
what a footman looks like without touching anything else; changing how many
frames he has means changing `frames` too, and the animation scripts that name
those frames.

This is the one property of the format that is easy to break without anything
failing: the pack would still load, the game would still run, and it would
still be drawing the 1995 art. `extractor/ArtistWorkflowTest` does exactly the
above — decode a sprite's PNG, repaint a block of it, encode a PNG, put the
file back in the zip — and then reads it the way the engine does, through the
rebuilt archive entry and the game's own sprite decoder, and asserts that all
256 repainted pixels arrive and that repainting one pixel changes exactly one
pixel.

### Paint inside the frames

A sprite sheet is a grid of cells, and a frame occupies a **rectangle inside
its cell**, given by its `frames` entry as `[x, y, width, height]`. The pixels
around that rectangle belong to no frame. They are not stored, and they come
back transparent however you paint them.

So a footman can be repainted freely and cannot be made bigger by painting into
the margin: growing him means growing his `frames` entry, and that is a change
to the animation, not to the picture. `extractor/ArtistWorkflowTest` pins both
halves — a block painted inside frame 0's rectangle arrives on screen intact,
and a pixel painted one step outside it does not arrive at all.

### What is not yours to change

- **The palette indices.** Your editor must not remap, quantise or reorder
  them. If it wants to, edit the indices rather than the colours.
- **The sheet's dimensions**, unless you update `cellWidth`, `cellHeight` and
  `frames` to match. The encoder refuses a sheet whose size does not match the
  layout its frames imply rather than producing a corrupt entry.
- **A GFX frame wider than 255 pixels.** The 1995 format has nowhere to put the
  ninth bit of a frame width.

## What the format deliberately does not do

**It does not re-encode video.** This was measured twice, both ways, on
Warcraft II's own intro: 1,326 frames of 320x200 at 12 fps, 11.99 MB as
Smacker.

*Losslessly* there is nothing to gain. x265 lossless gives 47.0 MB and FFV1
73.8 MB — three to six times **larger**. The best scheme tried, LZMA over the
raw palette-indexed frames, came to 12.62 MB, still worse than the 1994 codec.
Smacker is keyframe-and-delta with Huffman coding over 4x4 blocks, and on
eight-bit palettised source it is very hard to beat.

*Lossily* there is plenty to gain and it costs too much to collect. Scored
against a lossless decode of the source:

| codec | size | of Smacker | PSNR | SSIM |
|---|---:|---:|---:|---:|
| MJPEG q=2 | 28.63 MB | 239% | 33.9 dB | 0.928 |
| MJPEG q=8 | 8.61 MB | 72% | 33.3 dB | 0.902 |
| AV1 crf 20 | 3.94 MB | 33% | 42.7 dB | 0.974 |
| HEVC crf 20 | 4.68 MB | 39% | 42.9 dB | 0.976 |

AV1 at crf 20 is a genuine three-fold win at a quality that is close to
transparent, and it is not taken, because **it needs a native video decoder**.
Pure-Java AV1 is an order of magnitude beyond the Opus decoder in this module
and would be too slow for playback regardless, so it would mean the first
native dependency in the asset path, on three platforms, through jpackage. That
is a large permanent cost for 46 MB.

MJPEG is the only format the JDK decodes on its own, through ImageIO, and it is
dominated: it plateaus near 33 dB while costing seven times what AV1 does,
because an 8x8 DCT has nothing to work with on dithered palette art.

So cutscenes are stored as they were authored, and the port's own Smacker
decoder plays them. If a native decoder ever lands for another reason, the
per-asset `codec` field means this is a rebuild and not a format change.

**It does not normalise, resample or dither audio.** See above.

**It does not resolve palettes to colour.** See above.

**It does not deduplicate.** Two identical sounds stay two sounds.

**It does not convert sequenced music to samples.** A `sequence` asset goes to
a synthesiser and the pack has no opinion about which one.

**It has no compression level to tune.** There is one encoding per kind and it
is lossless. A format with a quality dial has a wrong setting.

## Reproducible builds

Every asset in a pack is a pure function of the data it came from, so two
builds of the same installation produce byte-identical payloads without anyone
arranging it. The *file* was not identical, and the whole difference was one
field: `pack.builtAt`. `Instant` prints as many fractional digits as it
happens to have, so the timestamp changes length between builds and deflates
differently — two packs measured minutes apart, with all 1,341 asset payloads
byte-for-byte the same, came out one byte different in total size.

Set `SOURCE_DATE_EPOCH` and the build is a function of its inputs and nothing
else:

```bash
SOURCE_DATE_EPOCH=1700000000 scripts/build-asset-pack.sh --out a.chonkpack
SOURCE_DATE_EPOCH=1700000000 scripts/build-asset-pack.sh --out b.chonkpack
cmp a.chonkpack b.chonkpack        # identical
```

Unset, a pack records when it was made, which is what somebody looking at one
usually wants. A value that is not seconds since the epoch is refused with a
message naming it, rather than falling back to the clock — a caller who asked
for a reproducible build and quietly did not get one is worse off than one who
was told.
