# Automatic native decision lab

`bne_divergence_compiler.py` turns one structured first mismatch into the
smallest honest work order an unattended parity agent can consume. It is the
handoff between differential comparison and an engine change; it is not an
alternative acceptance gate.

## Input

The input schema is `chonkcraft-bne-normalized-mismatch-1`. It requires one
complete evidence identity: case, fixture SHA-256, normalized retail scenario,
initialization seed, first mismatching cycle, subject native slot and native
program counter. The top-level case and cycle must agree with that tuple. It
also requires proof that the immediately preceding cycle is clean and one
structured finding. A scenario may carry
cycle-indexed commands/events; anything after the mismatch is removed from the
causal prefix. This is an exact **time** minimization. Entity or map reduction
must have its own native/Java receipt and is never inferred by the compiler.

Optional `evidence` entries name sealed JSON artifacts. A native capture only
counts when another supplied evidence entry is its matching manifest; the
snapshot, reviewed capture specification and retained oracle-run manifest all
carry the same complete identity; the run pins the 2.02b executable, fixture,
scenario, seed and cycle; and every artifact hash is intact. Only that evidence
may supply the PC used for static slicing. Loose probe JSON is retained as an
input but cannot make the native, PC or focused-proof lanes green.

Example:

```json
{
  "schema": "chonkcraft-bne-normalized-mismatch-1",
  "case": "retail-human-06-commanded",
  "cycle": 41,
  "clean_through": 40,
  "identity": {
    "case": "retail-human-06-commanded",
    "fixture_id": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    "scenario": "Campaign\\Human\\Human06.pud",
    "seed": 1,
    "cycle": 41,
    "subject": {"native_slot": 1490},
    "pc": 4422112
  },
  "family": "combat",
  "finding": {
    "kind": "unit",
    "unit": 1490,
    "unit_type": "unit-knight",
    "field": "target",
    "oracle": 1500,
    "java": -1
  },
  "scenario": {"commands": []},
  "evidence": {
    "snapshot": "/oracle/run/snapshot/snapshot.json",
    "snapshot_manifest": "/oracle/run/snapshot/manifest.json",
    "focused": "/proof/focused-proof.json",
    "focused_manifest": "/proof/manifest.json"
  },
  "focused_proof": "focused",
  "witnesses": []
}
```

Compile it:

```sh
python3 tools/bne-harness/scripts/bne_divergence_compiler.py MISMATCH.json \
  --native-executable "tools/bne-harness/work/target-smoke/Warcraft II BNE.exe"
```

## Output and cache identity

Runs live below
`tools/bne-harness/work/decision-lab/runs/<request-sha256>/`. The request hash
includes the normalized mismatch, every evidence identity, the pinned binary
identity, both global requirement inventories, the transitive compiler,
capture, replay and routing sources, and the resolved analyzer and runtime.
Analyzer identity includes its backend, executable bytes and version; the
runtime includes the Python executable bytes/version plus Capstone and Unicorn
versions. A repeated compile recomputes the request hash and authenticates the
manifest inventory before it returns a cache hit. Runs are immutable: there is
no force-replace mode. `latest.json` is replaced atomically and binds the
request, manifest, work order, case, cycle and complete evidence tuple.

Each run contains:

- `causal-prefix.json`: the exact prefix through the first mismatch;
- copied inputs and evidence, with no source mutation;
- native PC, branch and changed-memory inventory from authenticated evidence;
- `static-slice.json`: objdump now, or Ghidra headless when installed;
- a snapshot capture draft or `micro-oracle-result.json`;
- witness requirements and routed combat/campaign proof cells;
- placeholder-free focused and global proof commands;
- `work-order.json`, `NEXT.md` and an artifact manifest.

Missing evidence is a state, not a warning. The work order remains
`evidence-open`, and `engine_edit_allowed` remains false, until it has an
authenticated native capture, static slice, exact replay (or the required
witness family), and a **sealed focused proof**. A focused proof is not an
arbitrary command string: it is a `chonkcraft-bne-focused-proof-1` receipt with
the exact identity tuple, an argument-vector command and a passing exit result,
paired with a `chonkcraft-bne-focused-proof-manifest-1` that authenticates the
receipt and its producer. Legacy `proof_commands` may be displayed for an
operator, but never satisfy acceptance.

## Static analysis

`bne_static_analysis.py` emits one backend-neutral instruction, call, branch
and return slice. `/usr/bin/objdump` supports the pinned COFF-i386 executable
on macOS and GNU objdump does on `i9beef`, so static localization has no GUI
dependency. If `analyzeHeadless` is present, the bundled
`ghidra_scripts/ExportFunctionSlice.java` exports the same contract. Ghidra is
optional enrichment, never a reason the lab cannot compile. The resolved
backend, binary hash, analyzer version, exporter hash and runtime are part of
the request identity, so a cached slice cannot silently survive a tool change.

## Release gates

A local rule is accepted only after its focused witness and held-out controls
pass and both global commands pass:

```sh
python3 scripts/run-bne-playability-gate.py
scripts/check-bne-next-level-gate.sh --require-certified
```

The certified gate is intentionally stricter than ordinary unit tests. Combat
GREEN requires native/Java observation, exact outcome and causal order for all
**185** lifecycle cells. Campaign GREEN requires exact native/Java action and
decision cycle for all **137** trigger programs, plus exact save/resume forks
for mutable actions. Inventory generation alone is always RED.
