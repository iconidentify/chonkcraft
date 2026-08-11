# runtime

`net.chonkbase.runtime` -- the shared desktop foundation, **vendored from
seven-days-to-tomorrow and kept source-identical to it**. Fixed-step timing,
Java2D pipeline selection, fullscreen, SDL controller input, and a PCM bus
mixer.

## The rule for changing anything in here

**Changes belong upstream and sync in; they do not start here.** This module is
shared with ChonkBlocker and Seven Days to Tomorrow, and a fix made only in this
copy is a fix the other two do not get and that the next sync silently reverts.
If the port needs behaviour this module does not have, the options in order of
preference are: make the change upstream and re-vendor, or build the
ChonkCraft-specific piece in `engine/` on top of the seam this module already
exposes.

`engine/sound/SoundServer.java` and `desktop/ScreenAudio.java` are what that
second option looks like in practice -- the game's mixing policy lives in the
game, and this module stays a general PCM bus.

## What this port actually uses

Nine types, and the count is worth knowing before changing any of them:

| Type | Call sites |
|---|---|
| `audio.PcmClip` | 11 -- every sound the game plays |
| `audio.AudioMixer` | 10 |
| `audio.PcmFormat` | 6 |
| `audio.AudioBus` | 3 |
| `PlatformFullscreen` | 2 |
| `Java2DPipeline` | 1 |
| `FixedStepLoop` | 1 |
| `audio.JavaSoundPcmSink` | 1 |
| `audio.AudioOutputDriver` | 1 |

`net.chonkbase.runtime.input` -- the SDL controller stack -- is **imported by
nothing in this repository**. Warcraft II is a mouse-and-keyboard game. It is
carried because the module is vendored whole, not because it is used, and it
should not be pruned here: pruning it would break the source-identity rule
above.

## The design notes are upstream, deliberately

The audio foundation's full design record -- the streaming contract, the
page-ring budgets, the failure and recovery behaviour, the hardware
qualification matrix, and the provenance table pinning each file's SHA-256
against the ChonkBlocker commit it was adapted from -- lives in the
seven-days-to-tomorrow repository, beside the code it describes.

It used to be copied into this file, and that was a mistake worth recording: it
was written entirely in terms of that game's audio content, referred to a
`docs/audio-streaming.md` that does not exist in this repository, and listed
integration work for a game this is not. A reader here could not tell which
parts applied.

What matters for this port is the contract those notes pin down, and it is
short: clips are immutable 48 kHz PCM; the mixer is render-thread isolated;
underruns fail soft and are reported rather than thrown; and a lost output
device is retried with exponential backoff and then abandoned rather than
retried forever.

The vendored copy also carries a licensing constraint. ChonkBlocker's
repository licence is still marked `TBD`; reuse here is authorised by its owner,
but this module must not be published as an independent shared artifact until
that source carries an explicit SPDX licence and distribution notices.
