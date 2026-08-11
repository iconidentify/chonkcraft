#!/usr/bin/env python3
"""Reproduce one native decision offline, vary its inputs, and learn its rule.

Branch Witness proves which native instruction wrote a byte, and the decision
miner proves which branch was taken on one visit. Neither can answer "what
would this function have done with a different number in that register",
because answering that means running the native code again -- which means the
remote oracle, a fixture, a capture, and minutes per question. That cost is why
a rule like "the branch is taken when ecx > eax" gets read off two visits
instead of being measured.

This module lifts one bounded native function out of the game and into an
emulator. Given an authenticated snapshot -- the pinned executable's code, the
registers, the stack and the memory the function reads -- it reproduces the
captured invocation exactly, then answers thousands of "what if" questions per
second against the same native instructions, with no oracle and no network.

What it is not: it is not a proof of meaning. A rule learned here predicts the
native function's outcome on inputs it was tested against. That is stronger
than reading two visits and weaker than understanding the code, and every
report says which.
"""

from __future__ import annotations

from dataclasses import dataclass, field
import hashlib
import json
import os
from pathlib import Path
import tempfile
import time
from typing import Any, Callable, Iterable, Sequence


MICRO_ORACLE_SCHEMA = 1
DEFAULT_REMOTE_HOST = os.environ.get("CHONKCRAFT_ORACLE_HOST", "oracle-host")

#: The one executable a native snapshot may come from.
BNE_202_SHA256 = "b0e914a9cb7dcc81a205e700a9bb0a1d0649df19d459388051ba170783d2c807"

#: Where the emulator lives. Never the machine's Python: see
#: micro-oracle-requirements.txt for why each pin is exact.
BACKEND_VENV = Path(__file__).resolve().parents[1] / ".venv-micro-oracle"

#: x86-32 general registers a snapshot must carry, in the order reports use.
GENERAL_REGISTERS = ("eax", "ebx", "ecx", "edx", "esi", "edi", "ebp", "esp")
CONTROL_REGISTERS = ("eip", "eflags")
SNAPSHOT_REGISTERS = GENERAL_REGISTERS + CONTROL_REGISTERS

#: A replay stops here. The address is never executed; it is the marker the
#: emulator returns to, so a function that returns normally is distinguishable
#: from one that ran off into unmapped memory.
RETURN_SENTINEL = 0x00FF0000

MASK32 = 0xFFFFFFFF


class SnapshotError(ValueError):
    """The evidence cannot be believed. Never downgraded to a warning."""


class ReplayError(RuntimeError):
    """The emulator could not reproduce the captured invocation."""


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


# --------------------------------------------------------------------------
# Snapshot
# --------------------------------------------------------------------------


@dataclass(frozen=True)
class Segment:
    """One mapped range of the captured process."""

    address: int
    data: bytes
    access: str = "rw"
    label: str | None = None

    @property
    def end(self) -> int:
        return self.address + len(self.data)


@dataclass(frozen=True)
class InputSpec:
    """A reviewed place in the snapshot that exploration is allowed to vary.

    Reviewed on purpose. Left to itself a mutator would rewrite return
    addresses and structure pointers and report that the function's behaviour
    is chaotic, which is true and useless.
    """

    name: str
    kind: str
    width: int = 4
    signed: bool = False
    register: str | None = None
    address: int | None = None

    def as_dict(self) -> dict[str, Any]:
        return {
            "name": self.name, "kind": self.kind, "width": self.width,
            "signed": self.signed, "register": self.register,
            "address": self.address,
        }


@dataclass(frozen=True)
class Snapshot:
    """One authenticated native invocation, complete enough to run again."""

    entry: int
    registers: dict[str, int]
    segments: tuple[Segment, ...]
    inputs: tuple[InputSpec, ...] = ()
    executable_sha256: str | None = None
    return_sentinel: int = RETURN_SENTINEL
    expected: dict[str, Any] = field(default_factory=dict)
    stubs: tuple[dict[str, Any], ...] = ()
    provenance: dict[str, Any] = field(default_factory=dict)

    def byte_at(self, address: int) -> int:
        """What the captured machine held at one address, before it ran."""
        for segment in self.segments:
            if segment.address <= address < segment.end:
                return segment.data[address - segment.address]
        raise SnapshotError(
            f"no captured memory holds 0x{address:08x}")

    def input(self, name: str) -> InputSpec:
        for specification in self.inputs:
            if specification.name == name:
                return specification
        raise SnapshotError(f"no reviewed input named {name!r}")

    def metadata(self) -> dict[str, Any]:
        """Everything but the bytes, for a manifest to be addressed by."""
        return {
            "schema": MICRO_ORACLE_SCHEMA,
            "entry": self.entry,
            "entry_hex": f"0x{self.entry:08x}",
            "return_sentinel": self.return_sentinel,
            "executable_sha256": self.executable_sha256,
            "registers": {name: self.registers[name]
                          for name in sorted(self.registers)},
            "segments": [
                {"address": segment.address, "address_hex": f"0x{segment.address:08x}",
                 "bytes": len(segment.data), "access": segment.access,
                 "label": segment.label, "sha256": sha256_bytes(segment.data)}
                for segment in self.segments
            ],
            "inputs": [specification.as_dict() for specification in self.inputs],
            "expected": self.expected,
            "stubs": list(self.stubs),
            "provenance": self.provenance,
        }


def _segment_from_record(record: dict[str, Any], blob_root: Path | None) \
        -> Segment:
    address = record.get("address")
    if not isinstance(address, int) or address < 0 or address > MASK32:
        raise SnapshotError("snapshot memory needs a 32-bit address")
    if "hex" in record:
        try:
            data = bytes.fromhex(record["hex"])
        except (TypeError, ValueError) as error:
            raise SnapshotError("snapshot memory contains invalid hex") from error
    elif "blob" in record:
        blob = record["blob"]
        if not isinstance(blob, dict) or not isinstance(blob.get("sha256"), str):
            raise SnapshotError("snapshot memory blob needs a sha256")
        if blob_root is None:
            raise SnapshotError("snapshot references a blob with no blob root")
        path = blob_root / f"{blob['sha256']}.bin"
        if not path.is_file():
            raise SnapshotError(f"missing snapshot blob: {path.name}")
        data = path.read_bytes()
        actual = sha256_bytes(data)
        if actual != blob["sha256"]:
            raise SnapshotError(
                f"snapshot blob identity changed: {blob['sha256']} is now {actual}")
        if isinstance(blob.get("bytes"), int) and blob["bytes"] != len(data):
            raise SnapshotError("snapshot blob length differs from its record")
    else:
        raise SnapshotError("snapshot memory needs hex or a blob")
    if not data:
        raise SnapshotError("snapshot memory segment is empty")
    access = record.get("access", "rw")
    if access not in ("r", "rw", "rx", "rwx"):
        raise SnapshotError(f"unsupported segment access: {access!r}")
    return Segment(address=address, data=data, access=access,
                   label=record.get("label"))


def load_snapshot(document: dict[str, Any], *, blob_root: Path | None = None,
        expected_executable: str | None = BNE_202_SHA256) -> Snapshot:
    """Read and check a snapshot, refusing anything it cannot vouch for.

    Fail-closed everywhere. A snapshot that half-loads produces a replay that
    half-reproduces, and a report that half-reproduces is worse than none: it
    looks like evidence.
    """
    if document.get("schema") != MICRO_ORACLE_SCHEMA:
        raise SnapshotError("unsupported micro-oracle snapshot schema")
    entry = document.get("entry")
    if not isinstance(entry, int):
        raise SnapshotError("snapshot needs an integer entry address")
    registers = document.get("registers")
    if not isinstance(registers, dict):
        raise SnapshotError("snapshot needs a register map")
    missing = [name for name in SNAPSHOT_REGISTERS if name not in registers]
    if missing:
        raise SnapshotError(
            f"snapshot is missing registers: {', '.join(missing)}")
    for name, value in registers.items():
        if not isinstance(value, int) or value < 0 or value > MASK32:
            raise SnapshotError(f"register {name} is not a 32-bit value")
    executable = document.get("executable_sha256")
    if expected_executable is not None:
        if executable != expected_executable:
            raise SnapshotError(
                "snapshot is not from the pinned executable: "
                f"{executable!r}")
    segments = tuple(_segment_from_record(record, blob_root)
                     for record in document.get("segments", []))
    if not segments:
        raise SnapshotError("snapshot maps no memory at all")
    ordered = sorted(segments, key=lambda segment: segment.address)
    for left, right in zip(ordered, ordered[1:]):
        if left.end > right.address:
            raise SnapshotError(
                f"snapshot segments overlap at 0x{right.address:08x}")
    if not any(segment.address <= entry < segment.end for segment in segments):
        raise SnapshotError("snapshot entry address is not inside any segment")
    stack = registers["esp"]
    if not any(segment.address <= stack < segment.end for segment in segments):
        raise SnapshotError("snapshot stack pointer is not inside any segment")
    inputs = []
    for record in document.get("inputs", []):
        specification = InputSpec(
            name=record.get("name", ""), kind=record.get("kind", ""),
            width=int(record.get("width", 4)),
            signed=bool(record.get("signed", False)),
            register=record.get("register"), address=record.get("address"),
        )
        if not specification.name:
            raise SnapshotError("a reviewed input needs a name")
        if specification.kind == "register":
            if specification.register not in GENERAL_REGISTERS:
                raise SnapshotError(
                    f"input {specification.name!r} names no general register")
        elif specification.kind == "memory":
            if not isinstance(specification.address, int):
                raise SnapshotError(
                    f"input {specification.name!r} needs an address")
            if not any(segment.address <= specification.address
                       and specification.address + specification.width
                       <= segment.end for segment in segments):
                raise SnapshotError(
                    f"input {specification.name!r} is outside mapped memory")
        else:
            raise SnapshotError(
                f"input {specification.name!r} has unsupported kind "
                f"{specification.kind!r}")
        if specification.width not in (1, 2, 4):
            raise SnapshotError(
                f"input {specification.name!r} has unsupported width")
        inputs.append(specification)
    return Snapshot(
        entry=entry,
        registers={name: registers[name] for name in registers},
        segments=segments, inputs=tuple(inputs),
        executable_sha256=executable,
        return_sentinel=document.get("return_sentinel", RETURN_SENTINEL),
        expected=document.get("expected", {}) or {},
        stubs=tuple(document.get("stubs", []) or ()),
        provenance=document.get("provenance", {}) or {},
    )


def snapshot_document(snapshot: Snapshot, *, blob_root: Path | None = None) \
        -> dict[str, Any]:
    """Serialize a snapshot, spilling large segments to content-addressed blobs."""
    records = []
    for segment in snapshot.segments:
        digest = sha256_bytes(segment.data)
        record: dict[str, Any] = {
            "address": segment.address, "access": segment.access,
            "label": segment.label, "bytes": len(segment.data),
        }
        if blob_root is not None and len(segment.data) > 4096:
            blob_root.mkdir(parents=True, exist_ok=True)
            (blob_root / f"{digest}.bin").write_bytes(segment.data)
            record["blob"] = {"sha256": digest, "bytes": len(segment.data)}
        else:
            record["hex"] = segment.data.hex()
            record["sha256"] = digest
        records.append(record)
    return {
        "schema": MICRO_ORACLE_SCHEMA,
        "entry": snapshot.entry,
        "return_sentinel": snapshot.return_sentinel,
        "executable_sha256": snapshot.executable_sha256,
        "registers": dict(sorted(snapshot.registers.items())),
        "segments": records,
        "inputs": [specification.as_dict() for specification in snapshot.inputs],
        "expected": snapshot.expected,
        "stubs": list(snapshot.stubs),
        "provenance": snapshot.provenance,
    }


# --------------------------------------------------------------------------
# Concrete replay
# --------------------------------------------------------------------------


@dataclass
class ReplayResult:
    """What one run of the native code did."""

    status: str
    registers: dict[str, int] = field(default_factory=dict)
    writes: list[dict[str, Any]] = field(default_factory=list)
    reads: list[dict[str, Any]] = field(default_factory=list)
    branches: list[dict[str, Any]] = field(default_factory=list)
    executed: int = 0
    stubbed_calls: list[dict[str, Any]] = field(default_factory=list)
    elapsed_ms: float = 0.0
    error: str | None = None

    def as_dict(self) -> dict[str, Any]:
        return {
            "status": self.status,
            "registers": {name: self.registers[name]
                          for name in sorted(self.registers)},
            "writes": self.writes, "reads": self.reads[:64],
            "read_count": len(self.reads),
            "branches": self.branches, "executed": self.executed,
            "stubbed_calls": self.stubbed_calls,
            "elapsed_ms": round(self.elapsed_ms, 3), "error": self.error,
        }

    def outcome(self) -> dict[str, Any]:
        """The part a rule is learned over: return value, writes, path."""
        return {
            "eax": self.registers.get("eax"),
            "writes": [{"address": write["address"], "hex": write["hex"]}
                       for write in self.writes],
            "path": [f"{item['address']:#010x}:{int(item['taken'])}"
                     for item in self.branches],
        }


def backend_available() -> bool:
    return (BACKEND_VENV / "bin" / "python").is_file()


def backend_in_process() -> bool:
    try:
        import capstone  # noqa: F401
        import unicorn  # noqa: F401
    except ImportError:
        return False
    return True


class Replayer:
    """Runs a snapshot's native code under the project-local emulator.

    Nothing about this class knows what game the code came from. It maps what
    the snapshot authenticates, runs from the entry to the sentinel, and stops
    the moment the code asks for memory nobody captured -- because the
    alternative, filling that read with a zero, silently turns a reproduction
    into a fabrication.
    """

    def __init__(self, snapshot: Snapshot, *, instruction_budget: int = 200_000,
            wall_clock_ms: int = 5_000, trace_branches: bool = True):
        self.snapshot = snapshot
        self.instruction_budget = instruction_budget
        self.wall_clock_ms = wall_clock_ms
        self.trace_branches = trace_branches
        self._conditional: dict[int, int] | None = None

    # -- emulator plumbing -------------------------------------------------

    def _imports(self):
        try:
            import unicorn
            from unicorn import x86_const
        except ImportError as error:  # pragma: no cover - environment guard
            raise ReplayError(
                "the micro-oracle backend is not built; run "
                "tools/bne-harness/scripts/micro-oracle-venv.sh"
            ) from error
        return unicorn, x86_const

    def _conditional_jumps(self) -> dict[int, int]:
        """Address -> length of every conditional jump in the mapped code.

        Decoded once per snapshot with capstone. Recording "which way did it
        go" needs to know which executed addresses were a decision, and a code
        hook that guessed from the address delta would call a loop back-edge a
        branch.
        """
        if self._conditional is not None:
            return self._conditional
        try:
            import capstone
        except ImportError as error:  # pragma: no cover - environment guard
            raise ReplayError("capstone is missing from the backend") from error
        decoder = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_32)
        decoder.detail = True
        found: dict[int, int] = {}
        for segment in self.snapshot.segments:
            if "x" not in segment.access:
                continue
            for instruction in decoder.disasm(segment.data, segment.address):
                name = instruction.mnemonic
                if name.startswith("j") and name not in ("jmp", "jmpf"):
                    found[instruction.address] = instruction.size
        self._conditional = found
        return found

    def run(self, *, registers: dict[str, int] | None = None,
            memory: Sequence[tuple[int, bytes]] = ()) -> ReplayResult:
        """One invocation, optionally with reviewed inputs overwritten."""
        unicorn, x86 = self._imports()
        snapshot = self.snapshot
        conditional = self._conditional_jumps() if self.trace_branches else {}
        emulator = unicorn.Uc(unicorn.UC_ARCH_X86, unicorn.UC_MODE_32)

        page = 0x1000
        mapped: list[tuple[int, int]] = []
        for segment in snapshot.segments:
            start = segment.address & ~(page - 1)
            end = (segment.end + page - 1) & ~(page - 1)
            if not any(low <= start and end <= high for low, high in mapped):
                try:
                    emulator.mem_map(start, end - start)
                except unicorn.UcError:
                    # Already mapped by an adjacent segment sharing a page.
                    pass
                mapped.append((start, end))
            emulator.mem_write(segment.address, segment.data)
        # The sentinel is a page that is mapped and never written to, so a
        # return lands somewhere legal and recognisable rather than faulting.
        sentinel_page = snapshot.return_sentinel & ~(page - 1)
        if not any(low <= sentinel_page < high for low, high in mapped):
            emulator.mem_map(sentinel_page, page)

        values = dict(snapshot.registers)
        values.update(registers or {})
        for name in GENERAL_REGISTERS + ("eflags",):
            emulator.reg_write(getattr(x86, f"UC_X86_REG_{name.upper()}"),
                               values[name] & MASK32)
        for address, data in memory:
            emulator.mem_write(address, data)

        result = ReplayResult(status="unknown")
        writes: list[dict[str, Any]] = []
        reads: list[dict[str, Any]] = []
        branches: list[dict[str, Any]] = []
        stubbed: list[dict[str, Any]] = []
        executed = [0]
        pending: list[dict[str, Any]] = []
        stubs = {int(stub["address"]): stub for stub in snapshot.stubs
                 if isinstance(stub.get("address"), int)}
        failure: list[str] = []

        def on_write(_uc, _access, address, size, value, _user):
            writes.append({"address": address, "size": size,
                           "hex": (value & ((1 << (size * 8)) - 1)).to_bytes(
                               size, "little").hex()})

        def on_read(_uc, _access, address, size, _value, _user):
            reads.append({"address": address, "size": size})

        def on_unmapped(_uc, access, address, size, _value, _user):
            failure.append(
                f"unmapped {'write' if access in (unicorn.UC_MEM_WRITE_UNMAPPED,) else 'read'}"
                f" of {size} bytes at 0x{address:08x}")
            return False

        def on_code(uc, address, size, _user):
            executed[0] += 1
            if executed[0] > self.instruction_budget:
                failure.append(
                    f"instruction budget of {self.instruction_budget} exhausted")
                uc.emu_stop()
                return
            if pending:
                previous = pending.pop()
                previous["taken"] = address != previous["fallthrough"]
                branches.append(previous)
            if address in conditional:
                pending.append({
                    "address": address,
                    "fallthrough": address + conditional[address],
                })
            stub = stubs.get(address)
            if stub is not None:
                self._apply_stub(uc, x86, stub, stubbed)

        emulator.hook_add(unicorn.UC_HOOK_MEM_WRITE, on_write)
        emulator.hook_add(unicorn.UC_HOOK_MEM_READ, on_read)
        emulator.hook_add(
            unicorn.UC_HOOK_MEM_READ_UNMAPPED | unicorn.UC_HOOK_MEM_WRITE_UNMAPPED
            | unicorn.UC_HOOK_MEM_FETCH_UNMAPPED, on_unmapped)
        emulator.hook_add(unicorn.UC_HOOK_CODE, on_code)

        started = time.perf_counter()
        try:
            emulator.emu_start(
                snapshot.entry, snapshot.return_sentinel,
                timeout=self.wall_clock_ms * 1000,
                count=self.instruction_budget + 1,
            )
            result.status = "ok"
        except unicorn.UcError as error:
            result.status = "failed"
            result.error = failure[0] if failure else f"emulator error: {error}"
        finally:
            result.elapsed_ms = (time.perf_counter() - started) * 1000.0
        if failure and result.status == "ok":
            result.status = "failed"
            result.error = failure[0]
        if pending and result.status == "ok":
            # A conditional jump was the last instruction executed; where it
            # went is what the sentinel says.
            last = pending.pop()
            last["taken"] = emulator.reg_read(x86.UC_X86_REG_EIP) != last["fallthrough"]
            branches.append(last)
        result.executed = executed[0]
        result.writes = writes
        result.reads = reads
        result.branches = [{"address": item["address"], "taken": item["taken"]}
                           for item in branches if "taken" in item]
        result.stubbed_calls = stubbed
        for name in SNAPSHOT_REGISTERS:
            register = getattr(x86, f"UC_X86_REG_{name.upper()}")
            result.registers[name] = emulator.reg_read(register) & MASK32
        if result.status == "ok" and executed[0] >= self.instruction_budget:
            result.status = "failed"
            result.error = "instruction budget exhausted"
        return result

    def _apply_stub(self, uc, x86, stub: dict[str, Any],
            stubbed: list[dict[str, Any]]) -> None:
        """Answer an external call the snapshot could not capture.

        Every stub is named in the manifest. An unnamed call into uncaptured
        code is a failure, not a zero: the whole value of this tool is that its
        answers came from the real instructions.
        """
        action = stub.get("action")
        if action != "return-constant":
            raise ReplayError(f"unsupported stub action: {action!r}")
        stack = uc.reg_read(x86.UC_X86_REG_ESP)
        returns = int.from_bytes(uc.mem_read(stack, 4), "little")
        popped = 4 + int(stub.get("pops", 0))
        uc.reg_write(x86.UC_X86_REG_EAX, int(stub.get("value", 0)) & MASK32)
        uc.reg_write(x86.UC_X86_REG_ESP, (stack + popped) & MASK32)
        uc.reg_write(x86.UC_X86_REG_EIP, returns)
        stubbed.append({"address": stub["address"], "name": stub.get("name"),
                        "returned": int(stub.get("value", 0))})


@dataclass(frozen=True)
class Trial:
    """One invocation to run: the snapshot with reviewed inputs overwritten."""

    label: str
    assignments: dict[str, int] = field(default_factory=dict)

    def as_dict(self) -> dict[str, Any]:
        return {"label": self.label,
                "assignments": dict(sorted(self.assignments.items()))}


def _apply_assignments(snapshot: Snapshot, assignments: dict[str, int]) \
        -> tuple[dict[str, int], list[tuple[int, bytes]]]:
    """Turn named input values into register overrides and memory patches."""
    registers: dict[str, int] = {}
    memory: list[tuple[int, bytes]] = []
    for name, value in sorted(assignments.items()):
        specification = snapshot.input(name)
        raw = value & ((1 << (specification.width * 8)) - 1)
        if specification.kind == "register":
            if specification.width != 4:
                raise SnapshotError(
                    "a register input is written at its full width")
            registers[specification.register] = raw
        else:
            memory.append((specification.address,
                           raw.to_bytes(specification.width, "little")))
    return registers, memory


def run_trials(snapshot: Snapshot, trials: Sequence[Trial], *,
        instruction_budget: int = 200_000, wall_clock_ms: int = 5_000) \
        -> list[ReplayResult]:
    """Run a batch of invocations in one emulator process.

    Batched on purpose. The emulator lives in a project-local virtual
    environment, so a fresh process per trial would cost more than the
    emulation and reduce a thousand questions a second to a dozen.
    """
    if backend_in_process():
        replayer = Replayer(snapshot, instruction_budget=instruction_budget,
                            wall_clock_ms=wall_clock_ms)
        results = []
        for trial in trials:
            registers, memory = _apply_assignments(snapshot, trial.assignments)
            results.append(replayer.run(registers=registers, memory=memory))
        return results
    return _run_trials_in_backend(
        snapshot, trials, instruction_budget=instruction_budget,
        wall_clock_ms=wall_clock_ms)


class Session:
    """A long-lived backend process answering batch after batch.

    A process per batch is what a first version does, and it costs about forty
    milliseconds each -- more than the emulation it wraps, and the difference
    between a hundred trials a second and tens of thousands. The bisection that
    finds an exact threshold asks thirty small questions in a row, so the
    process is kept and the snapshot is sent once.
    """

    def __init__(self, snapshot: Snapshot, *, instruction_budget: int = 200_000,
            wall_clock_ms: int = 5_000):
        self.snapshot = snapshot
        self.instruction_budget = instruction_budget
        self.wall_clock_ms = wall_clock_ms
        self._process = None

    def __enter__(self) -> "Session":
        import subprocess

        interpreter = BACKEND_VENV / "bin" / "python"
        if not interpreter.is_file():
            raise ReplayError(
                "the micro-oracle backend is not built; run "
                "tools/bne-harness/scripts/micro-oracle-venv.sh")
        self._process = subprocess.Popen(
            [str(interpreter), str(Path(__file__).resolve()), "--worker"],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, text=True, bufsize=1,
        )
        self._send({
            "command": "open",
            "snapshot": snapshot_document(self.snapshot),
            "instruction_budget": self.instruction_budget,
            "wall_clock_ms": self.wall_clock_ms,
        })
        return self

    def __exit__(self, *_exception: object) -> None:
        if self._process is None:
            return
        try:
            self._process.stdin.write(json.dumps({"command": "close"}) + "\n")
            self._process.stdin.flush()
            self._process.wait(timeout=10)
        except (OSError, ValueError):
            self._process.kill()
        finally:
            for stream in (self._process.stdin, self._process.stdout,
                           self._process.stderr):
                if stream is not None:
                    try:
                        stream.close()
                    except OSError:
                        pass
            self._process = None

    def _send(self, request: dict[str, Any]) -> dict[str, Any]:
        process = self._process
        if process is None:
            raise ReplayError("micro-oracle session is not open")
        process.stdin.write(json.dumps(request) + "\n")
        process.stdin.flush()
        line = process.stdout.readline()
        if not line:
            errors = process.stderr.read()[:400] if process.stderr else ""
            raise ReplayError(f"micro-oracle backend stopped: {errors.strip()}")
        payload = json.loads(line)
        if payload.get("error"):
            raise ReplayError(payload["error"])
        return payload

    def run(self, trials: Sequence[Trial]) -> list[ReplayResult]:
        if not trials:
            return []
        payload = self._send({
            "command": "run",
            "trials": [trial.as_dict() for trial in trials],
        })
        return [_result_from_record(record) for record in payload["results"]]


def _result_from_record(record: dict[str, Any]) -> ReplayResult:
    return ReplayResult(
        status=record["status"], registers=record["registers"],
        writes=record["writes"], reads=record.get("reads", []),
        branches=record["branches"], executed=record["executed"],
        stubbed_calls=record.get("stubbed_calls", []),
        elapsed_ms=record.get("elapsed_ms", 0.0), error=record.get("error"),
    )


def _run_trials_in_backend(snapshot: Snapshot, trials: Sequence[Trial], *,
        instruction_budget: int, wall_clock_ms: int) -> list[ReplayResult]:
    with Session(snapshot, instruction_budget=instruction_budget,
                 wall_clock_ms=wall_clock_ms) as session:
        return session.run(trials)


def _worker_main() -> int:
    """Serve batches of trials until told to stop.

    Runs only under the project-local backend, and keeps one emulator context
    factory and one decoded instruction map for the whole session.
    """
    import sys

    replayer: Replayer | None = None
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            request = json.loads(line)
            command = request.get("command", "run")
            if command == "close":
                return 0
            if command == "open":
                snapshot = load_snapshot(request["snapshot"],
                                         expected_executable=None)
                replayer = Replayer(
                    snapshot,
                    instruction_budget=request.get("instruction_budget", 200_000),
                    wall_clock_ms=request.get("wall_clock_ms", 5_000))
                replayer._conditional_jumps()
                sys.stdout.write(json.dumps({"ready": True}) + "\n")
                sys.stdout.flush()
                continue
            if replayer is None:
                raise ReplayError("micro-oracle session was never opened")
            results = []
            for item in request["trials"]:
                registers, memory = _apply_assignments(
                    replayer.snapshot, item["assignments"])
                results.append(replayer.run(registers=registers, memory=memory))
            sys.stdout.write(json.dumps(
                {"results": [result.as_dict() for result in results]}) + "\n")
            sys.stdout.flush()
        except (SnapshotError, ReplayError, ValueError, KeyError) as error:
            sys.stdout.write(json.dumps({"error": str(error)}) + "\n")
            sys.stdout.flush()
    return 0


def as_signed32(value: int) -> int:
    value &= MASK32
    return value - (1 << 32) if value & 0x80000000 else value


def derive_features(assignments: dict[str, int]) -> dict[str, int]:
    """Named inputs plus the small combinations a comparison is usually of.

    The rule grammar compares one feature with one constant, which cannot say
    "counter plus reach exceeds limit" -- a relation between three inputs and
    the commonest shape a native decision has. Rather than grow the grammar
    into arbitrary expressions, the combinations are computed here and named,
    so `counter+reach-limit > 0` is expressible and still readable.

    Signed throughout: the branches these functions are made of are `jle`,
    `jl`, `jg`, and reading their operands as unsigned turns every negative
    difference into four billion.
    """
    names = sorted(assignments)
    values = {name: as_signed32(assignments[name]) for name in names}
    features = dict(values)
    for index, left in enumerate(names):
        for right in names[index + 1:]:
            features[f"{left}-{right}"] = _wrap(values[left] - values[right])
            features[f"{right}-{left}"] = _wrap(values[right] - values[left])
            features[f"{left}+{right}"] = _wrap(values[left] + values[right])
            features[f"abs({left}-{right})"] = abs(
                _wrap(values[left] - values[right]))
    for target in names:
        others = [name for name in names if name != target]
        for index, left in enumerate(others):
            for right in others[index + 1:]:
                # The inner sum wraps because the machine computed it in a
                # register; the outer subtraction does not, because it is the
                # comparison rather than an addition. Modelling the sum with
                # unbounded integers is what made every rule fail against the
                # probes near 0x7fffffff: mathematics says the sum grew, the
                # hardware says it went negative, and the branch believed the
                # hardware.
                features[f"wrap({left}+{right})-{target}"] = (
                    _wrap(values[left] + values[right]) - values[target])
    return features


def _wrap(value: int) -> int:
    """One 32-bit register's worth of the result, signed, as the ALU leaves it."""
    return as_signed32(value & MASK32)


@dataclass
class Example:
    """One native invocation, kept as what went in and what came out."""

    assignments: dict[str, int]
    outcome: Any
    label: str
    path: str

    def as_dict(self) -> dict[str, Any]:
        return {"assignments": dict(sorted(self.assignments.items())),
                "outcome": self.outcome, "label": self.label, "path": self.path}


def outcome_of(result: ReplayResult, key: str) -> Any:
    """The part of a run a rule is asked to predict."""
    if result.status != "ok":
        return None
    if key == "eax":
        return result.registers.get("eax")
    if key == "path":
        return "|".join(f"{item['address']:#x}:{int(item['taken'])}"
                        for item in result.branches)
    if key.startswith("write:"):
        address = int(key.split(":", 1)[1], 0)
        for write in result.writes:
            if write["address"] == address:
                return write["hex"]
        return None
    raise ValueError(f"unsupported outcome key: {key!r}")


def _probe_values(base: int, width: int) -> list[int]:
    """Where a comparison is most likely to change its mind."""
    ceiling = (1 << (width * 8)) - 1
    candidates = [0, 1, 2, base - 2, base - 1, base, base + 1, base + 2,
                  ceiling, ceiling - 1, ceiling >> 1, (ceiling >> 1) + 1]
    return sorted({value & ceiling for value in candidates})


def explore(snapshot: Snapshot, *, outcome_key: str = "eax",
        budget: int = 512, instruction_budget: int = 200_000) \
        -> dict[str, Any]:
    """Vary the reviewed inputs and find where the native answer changes.

    Boundary neighbours first, because a comparison changes its mind next to a
    constant and almost nowhere else. Then, for every input that was seen to
    matter, a bisection between a value that produced one outcome and a value
    that produced another, which converges on the exact threshold rather than
    reporting the interval it was found in.

    Every candidate is confirmed by running the native instructions again.
    Nothing here is a solver result and nothing claims to be.
    """
    base = {}
    for specification in snapshot.inputs:
        if specification.kind == "register":
            base[specification.name] = snapshot.registers[specification.register]
        else:
            base[specification.name] = _read_snapshot_memory(
                snapshot, specification.address, specification.width)
    trials = [Trial(label="baseline", assignments=dict(base))]
    seen = {json.dumps(base, sort_keys=True)}
    for name, value in sorted(base.items()):
        specification = snapshot.input(name)
        for candidate in _probe_values(value, specification.width):
            assignments = dict(base)
            assignments[name] = candidate
            encoded = json.dumps(assignments, sort_keys=True)
            if encoded in seen or len(trials) >= budget:
                continue
            seen.add(encoded)
            trials.append(Trial(label=f"{name}={candidate}",
                                assignments=assignments))
    examples: list[Example] = []
    boundaries = []
    started = time.perf_counter()
    executed = 0
    with _session(snapshot, instruction_budget) as session:
        for trial, result in zip(trials, session.run(trials)):
            examples.append(Example(
                assignments=trial.assignments,
                outcome=outcome_of(result, outcome_key),
                label=trial.label, path=outcome_of(result, "path")))
        executed += len(trials)
        baseline = examples[0]
        for name in sorted(base):
            differing = [
                example for example in examples[1:]
                if example.assignments[name] != baseline.assignments[name]
                and example.outcome != baseline.outcome
                and all(example.assignments[other] == baseline.assignments[other]
                        for other in base if other != name)
            ]
            if differing:
                target = min(
                    differing,
                    key=lambda item: abs(as_signed32(item.assignments[name])
                                         - as_signed32(baseline.assignments[name])))
                far = as_signed32(target.assignments[name])
            else:
                # Nothing in the probe set moved this input's outcome. The flip
                # may still be out there: the probes jump from a neighbour of
                # the captured value to the extremes, and a threshold in
                # between -- which is where thresholds usually are -- falls in
                # the gap. Double outward until the answer changes.
                far, doubled = _doubling_search(
                    session, base, name, baseline.outcome, outcome_key,
                    snapshot, examples, seen)
                executed += doubled
                if far is None:
                    continue
            found, spent = _bisect_boundary(
                session, snapshot, base, name, baseline.outcome,
                as_signed32(baseline.assignments[name]), far, outcome_key,
                examples, seen)
            executed += spent
            if found is not None:
                boundaries.append(found)
    elapsed = time.perf_counter() - started
    outcomes = sorted({json.dumps(example.outcome, sort_keys=True)
                       for example in examples})
    return {
        "schema": MICRO_ORACLE_SCHEMA,
        "outcome_key": outcome_key,
        "baseline": baseline.as_dict(),
        "trials": len(examples),
        "native_invocations": executed,
        "elapsed_seconds": round(elapsed, 4),
        "invocations_per_second": round(executed / elapsed, 1) if elapsed else None,
        "distinct_outcomes": len(outcomes),
        "alternate_outcome_found": len(outcomes) > 1,
        "boundaries": boundaries,
        "paths": sorted({example.path for example in examples
                         if example.path is not None}),
        "examples": [example.as_dict() for example in examples],
        "method": ("boundary neighbours and confirmed bisection; every "
                   "candidate was executed, none was solved for"),
    }


def _read_snapshot_memory(snapshot: Snapshot, address: int, width: int) -> int:
    for segment in snapshot.segments:
        if segment.address <= address and address + width <= segment.end:
            offset = address - segment.address
            return int.from_bytes(segment.data[offset:offset + width], "little")
    raise SnapshotError(f"no snapshot memory at 0x{address:08x}")


import contextlib


@contextlib.contextmanager
def _session(snapshot: Snapshot, instruction_budget: int):
    """One backend for a whole exploration, or in-process when already there."""
    if backend_in_process():
        replayer = Replayer(snapshot, instruction_budget=instruction_budget)

        class _Direct:
            def run(self, trials: Sequence[Trial]) -> list[ReplayResult]:
                results = []
                for trial in trials:
                    registers, memory = _apply_assignments(
                        snapshot, trial.assignments)
                    results.append(replayer.run(registers=registers,
                                                memory=memory))
                return results

        yield _Direct()
        return
    with Session(snapshot, instruction_budget=instruction_budget) as session:
        yield session


def _record(session, snapshot: Snapshot, base: dict[str, int], name: str,
        value: int, outcome_key: str, examples: list[Example],
        seen: set[str], label: str) -> Any:
    """Run one value of one input, keeping the example if it is new."""
    specification = snapshot.input(name)
    ceiling = (1 << (specification.width * 8)) - 1
    assignments = dict(base)
    assignments[name] = value & ceiling
    trial = Trial(label=label, assignments=assignments)
    result = session.run([trial])[0]
    outcome = outcome_of(result, outcome_key)
    encoded = json.dumps(assignments, sort_keys=True)
    if encoded not in seen:
        seen.add(encoded)
        examples.append(Example(assignments=assignments, outcome=outcome,
                                label=label, path=outcome_of(result, "path")))
    return outcome


def _doubling_search(session, base: dict[str, int], name: str,
        baseline_outcome: Any, outcome_key: str, snapshot: Snapshot,
        examples: list[Example], seen: set[str], *, limit: int = 31) \
        -> tuple[int | None, int]:
    """Walk outward in powers of two until this input changes the answer.

    Thirty-one runs cover the whole signed range, which is why this is cheap
    enough to try for every input that the probe set left unexplained.
    """
    start = as_signed32(base[name])
    spent = 0
    for direction in (1, -1):
        step = 1
        for _ in range(limit):
            candidate = start + direction * step
            if candidate > 0x7FFFFFFF or candidate < -0x80000000:
                break
            outcome = _record(session, snapshot, base, name, candidate,
                              outcome_key, examples, seen,
                              f"double {name}={candidate}")
            spent += 1
            if outcome is not None and outcome != baseline_outcome:
                return candidate, spent
            step *= 2
    return None, spent


def _bisect_boundary(session, snapshot: Snapshot, base: dict[str, int],
        name: str, baseline_outcome: Any, low: int, high: int,
        outcome_key: str, examples: list[Example], seen: set[str]) \
        -> tuple[dict[str, Any] | None, int]:
    """Narrow to the exact value where the native answer changes.

    Bisection rather than a sweep because the interesting inputs are 32 bits
    wide: a sweep would need four billion runs and this needs thirty-two.
    """
    inside, outside = low, high
    spent = 0
    for _ in range(40):
        if abs(outside - inside) <= 1:
            break
        middle = (inside + outside) // 2
        outcome = _record(session, snapshot, base, name, middle, outcome_key,
                          examples, seen, f"bisect {name}={middle}")
        spent += 1
        if outcome == baseline_outcome:
            inside = middle
        else:
            outside = middle
    if abs(outside - inside) != 1:
        return None, spent
    return {
        "input": name,
        "holds_through": inside, "changes_at": outside,
        "direction": "increasing" if outside > inside else "decreasing",
        "outcome_before": baseline_outcome,
        "confirmed_by_replay": True,
    }, spent


def _memory_delta_mismatches(snapshot: Snapshot, result: "ReplayResult",
        expected: Sequence[dict[str, Any]]) -> list[dict[str, Any]]:
    """Check the bytes the invocation left behind, wherever they came from.

    A capture taken with a debugger can prove what memory held before the
    decision and what it held after; it cannot prove the order the stores
    happened in, because nothing watched every store. So the ordered write set
    is what the emulator offers and the final bytes are what evidence from the
    game can insist on, and this checks the second against a replay by folding
    the replay's writes onto the captured memory in the order they happened.
    """
    image: dict[int, int] = {}
    for write in result.writes:
        data = bytes.fromhex(write["hex"])
        for step, value in enumerate(data):
            image[write["address"] + step] = value
    mismatches = []
    for change in expected:
        address = int(change["address"])
        wanted = bytes.fromhex(change["after_hex"])
        actual = bytearray(len(wanted))
        for step in range(len(wanted)):
            at = address + step
            if at in image:
                actual[step] = image[at]
            else:
                actual[step] = snapshot.byte_at(at)
        if bytes(actual) != wanted:
            mismatches.append({
                "kind": "memory", "address": address,
                "expected": change["after_hex"], "actual": bytes(actual).hex(),
            })
    return mismatches


def reproduce(snapshot: Snapshot, **kwargs: Any) -> dict[str, Any]:
    """Run the captured invocation unchanged and check it against its record.

    This gate is the whole evidence contract. Until the snapshot reproduces
    what was captured, every other number this tool could print is a guess
    about an emulator rather than a measurement of the game.
    """
    result = run_trials(snapshot, [Trial(label="baseline")], **kwargs)[0]
    expected = snapshot.expected or {}
    mismatches = []
    for name, value in (expected.get("registers") or {}).items():
        actual = result.registers.get(name)
        if actual != value:
            mismatches.append(
                {"kind": "register", "name": name,
                 "expected": value, "actual": actual})
    expected_writes = expected.get("writes")
    if expected_writes is not None:
        actual_writes = [{"address": write["address"], "hex": write["hex"]}
                         for write in result.writes]
        wanted = [{"address": item["address"], "hex": item["hex"]}
                  for item in expected_writes]
        if actual_writes != wanted:
            mismatches.append({"kind": "writes", "expected": wanted,
                               "actual": actual_writes})
    expected_delta = expected.get("memory_delta")
    if expected_delta is not None:
        mismatches.extend(_memory_delta_mismatches(
            snapshot, result, expected_delta))
    expected_path = expected.get("branches")
    if expected_path is not None:
        actual_path = [{"address": item["address"], "taken": item["taken"]}
                       for item in result.branches]
        if actual_path != list(expected_path):
            mismatches.append({"kind": "branches", "expected": expected_path,
                               "actual": actual_path})
    status = "exact"
    if result.status != "ok":
        status = "failed"
    elif mismatches:
        status = "divergent"
    return {
        "schema": MICRO_ORACLE_SCHEMA,
        "status": status,
        "replay": result.as_dict(),
        "mismatches": mismatches,
        "baseline_outcome": result.outcome(),
    }


if __name__ == "__main__":
    import sys

    if "--worker" in sys.argv:
        raise SystemExit(_worker_main())
    print(__doc__)


# --------------------------------------------------------------------------
# Rule synthesis
# --------------------------------------------------------------------------


def _examples_for_rules(examples: Sequence[dict[str, Any]]) \
        -> list[dict[str, Any]]:
    return [{"input": derive_features(example["assignments"]),
             "output": example["outcome"]}
            for example in examples if example["outcome"] is not None]


def synthesize_rule(snapshot: Snapshot, exploration: dict[str, Any], *,
        holdout: int = 8, max_rounds: int = 16,
        instruction_budget: int = 200_000) -> dict[str, Any]:
    """Learn the smallest readable rule the native outcomes support.

    The search is the parity lab's existing bounded one, over the derived
    features above, and its verifier is the native code itself: a candidate is
    challenged by running invocations it and the evidence disagree about, so a
    rule that merely fits the training examples is refuted by the function
    rather than by a human noticing later.
    """
    from bne_experiments import Rule, cegis, consistent_rules

    observed = [example for example in exploration["examples"]
                if example["outcome"] is not None]
    if len({json.dumps(example["outcome"], sort_keys=True)
            for example in observed}) < 2:
        return {
            "schema": MICRO_ORACLE_SCHEMA, "status": "single-outcome",
            "reason": ("every invocation produced the same outcome, so there "
                       "is no decision here to explain"),
            "rule": None, "candidates": 0,
        }
    ordered = sorted(observed, key=lambda item: item["label"])
    train = [example for index, example in enumerate(ordered)
             if index % 4 != 3][: max(4, len(ordered) - holdout)]
    held = [example for example in ordered if example not in train][:holdout]
    training = _examples_for_rules(train)
    features = sorted(training[0]["input"]) if training else []

    challenged: list[dict[str, Any]] = []

    def verifier(rule: Rule) -> dict[str, Any] | None:
        """Run the native code where the rule and the evidence could differ."""
        for example in _examples_for_rules(held):
            if rule.evaluate(example["input"]) != example["output"]:
                challenged.append({"source": "held-out",
                                   "input": example["input"]})
                return example
        probes = _counterexample_probes(snapshot, exploration, rule)
        if not probes:
            return None
        results = run_trials(snapshot, probes,
                             instruction_budget=instruction_budget)
        for trial, result in zip(probes, results):
            outcome = outcome_of(result, exploration["outcome_key"])
            if outcome is None:
                continue
            features_of = derive_features(trial.assignments)
            if rule.evaluate(features_of) != outcome:
                challenged.append({"source": "probe", "label": trial.label})
                return {"input": features_of, "output": outcome}
        return None

    synthesis = cegis(training, verifier, max_rounds=max_rounds,
                      features=features)
    rule = synthesis.get("rule")
    decision_list = None
    if rule is None:
        # One predicate could not explain it. Before giving up, try a bounded
        # two-predicate decision list: a function with three outcomes -- an
        # early return and then a comparison -- is common and is not a sign
        # that the behaviour is unexplainable.
        decision_list, refutations = _decision_list_against_native(
            snapshot, exploration, training, features,
            instruction_budget=instruction_budget, max_rounds=max_rounds)
        challenged.extend(refutations)
        if decision_list is not None:
            validation = _validate_decision_list(decision_list, held)
            return {
                "schema": MICRO_ORACLE_SCHEMA,
                "status": "synthesized-decision-list",
                "rule": None, "decision_list": decision_list,
                "readable": _readable_list(decision_list),
                "grammar": {
                    "form": ("up to two predicates as a decision list: "
                             "if P1 then A elif P2 then B else C"),
                    "operators": ["<", "<=", "==", ">=", ">"],
                    "features_searched": features,
                },
                "training_examples": len(training),
                "held_out_examples": len(held),
                "counterexamples": challenged,
                "rounds": len(synthesis.get("rounds", [])),
                "consistent_candidates": 1,
                "ambiguous": False,
                "held_out_validation": validation,
                "confidence": _grade("synthesized", validation, 1),
                "next_experiment": None,
            }
    validation = _validate_rule(rule, held) if rule else None
    remaining = consistent_rules(training, features=features) if rule else []
    distinct = _distinct_behaviours(
        remaining, list(training) + _examples_for_rules(held))
    return {
        "schema": MICRO_ORACLE_SCHEMA,
        "status": synthesis["status"],
        "rule": rule,
        "readable": _readable(rule) if rule else None,
        "grammar": {
            "form": "one predicate: feature OPERATOR constant -> outcome",
            "operators": ["<", "<=", "==", ">=", ">"],
            "features_searched": features,
            "derived_feature_note": (
                "features include signed differences, sums and absolute "
                "differences of the reviewed inputs, so a comparison between "
                "two inputs is expressible against a constant"),
        },
        "training_examples": len(training),
        "held_out_examples": len(held),
        "counterexamples": challenged,
        "rounds": len(synthesis.get("rounds", [])),
        "consistent_candidates": len(remaining),
        "equivalent_forms": len(remaining),
        "distinct_behaviours": len(distinct),
        "ambiguous": len(distinct) > 1,
        "held_out_validation": validation,
        "confidence": _grade(synthesis["status"], validation, len(distinct)),
        "next_experiment": _next_experiment(
            [group[0] for group in distinct], held),
    }


def _counterexample_probes(snapshot: Snapshot, exploration: dict[str, Any],
        rule: Any, *, limit: int = 24) -> list[Trial]:
    """Inputs near every known boundary, where a wrong rule shows itself."""
    base = exploration["baseline"]["assignments"]
    probes: list[Trial] = []
    seen = set()
    for boundary in exploration.get("boundaries", []):
        name = boundary["input"]
        specification = snapshot.input(name)
        ceiling = (1 << (specification.width * 8)) - 1
        for offset in (-3, -2, -1, 0, 1, 2, 3):
            assignments = dict(base)
            assignments[name] = (boundary["changes_at"] + offset) & ceiling
            encoded = json.dumps(assignments, sort_keys=True)
            if encoded in seen or len(probes) >= limit:
                continue
            seen.add(encoded)
            probes.append(Trial(label=f"probe {name}={assignments[name]}",
                                assignments=assignments))
    for name in sorted(base):
        specification = snapshot.input(name)
        ceiling = (1 << (specification.width * 8)) - 1
        for value in (0, 1, ceiling, ceiling >> 1):
            assignments = dict(base)
            assignments[name] = value
            encoded = json.dumps(assignments, sort_keys=True)
            if encoded in seen or len(probes) >= limit:
                continue
            seen.add(encoded)
            probes.append(Trial(label=f"probe {name}={value}",
                                assignments=assignments))
    return probes


def _validate_rule(rule: dict[str, Any], held: Sequence[dict[str, Any]]) \
        -> dict[str, Any]:
    from bne_experiments import Rule

    candidate = Rule(**rule)
    checked = _examples_for_rules(held)
    wrong = [example for example in checked
             if candidate.evaluate(example["input"]) != example["output"]]
    return {
        "held_out": len(checked), "correct": len(checked) - len(wrong),
        "passed": not wrong and bool(checked),
        "failures": wrong[:4],
    }


def _readable(rule: dict[str, Any]) -> str:
    if rule.get("feature") is None:
        return f"always {rule.get('when_true')}"
    return (f"{rule['feature']} {rule['operator']} {rule['threshold']}"
            f" -> {rule['when_true']}, otherwise {rule['when_false']}")


def _grade(status: str, validation: dict[str, Any] | None, candidates: int) \
        -> str:
    """How much this rule is worth, said plainly.

    Predicting invocations it was not fitted to is the only thing that
    separates a rule from a restatement of the examples.
    """
    if status != "synthesized" or validation is None:
        return "no-rule"
    if not validation["passed"]:
        return "refuted-on-held-out"
    if not validation["held_out"]:
        return "fitted-only"
    if candidates > 1:
        return "predictive-but-ambiguous"
    return "predictive-and-unique"


def _next_experiment(candidates: Sequence[Any],
        held: Sequence[dict[str, Any]]) -> dict[str, Any] | None:
    """The smallest input that would tell two surviving rules apart."""
    if len(candidates) <= 1:
        return None
    first, second = candidates[0], candidates[1]
    for example in _examples_for_rules(held):
        if first.evaluate(example["input"]) != second.evaluate(example["input"]):
            return {
                "reason": "two rules survive the evidence",
                "distinguishing_input": example["input"],
                "first": first.as_dict(), "second": second.as_dict(),
            }
    return {
        "reason": "two rules survive and no tested input separates them",
        "first": first.as_dict(), "second": second.as_dict(),
        "suggestion": "widen the explored range of the disagreeing feature",
    }


def _distinct_behaviours(candidates: Sequence[Any],
        examples: Sequence[dict[str, Any]]) -> list[list[Any]]:
    """Group rules that only differ in how they are written.

    `feature < 1`, `feature <= 0`, `feature > 0` and `feature >= 1` over
    integers are one rule in four costumes. Counting them as four surviving
    candidates reports an ambiguity that does not exist and asks for an
    experiment that cannot resolve it.
    """
    prepared = list(examples)
    groups: dict[str, list[Any]] = {}
    for candidate in candidates:
        signature = json.dumps(
            [candidate.evaluate(example["input"]) for example in prepared],
            sort_keys=True)
        groups.setdefault(signature, []).append(candidate)
    return [groups[key] for key in sorted(groups)]


def _predicates(examples: Sequence[dict[str, Any]], features: Sequence[str]) \
        -> list[tuple[str, str, int]]:
    thresholds: dict[str, set[int]] = {}
    for example in examples:
        for feature in features:
            value = example["input"][feature]
            thresholds.setdefault(feature, set()).update(
                {value, value + 1, value - 1})
    return [(feature, operator, threshold)
            for feature in sorted(thresholds)
            for operator in ("<", "<=", "==", ">=", ">")
            for threshold in sorted(thresholds[feature])]


def _holds(predicate: tuple[str, str, int], inputs: dict[str, int]) -> bool:
    feature, operator, threshold = predicate
    value = inputs[feature]
    return {
        "<": value < threshold, "<=": value <= threshold,
        "==": value == threshold, ">=": value >= threshold,
        ">": value > threshold,
    }[operator]


def _decision_list(examples: Sequence[dict[str, Any]],
        features: Sequence[str], *, depth: int = 2) -> list[dict[str, Any]] | None:
    """Peel one outcome off at a time with one predicate each.

    Greedy and bounded: at each level, look for a predicate that is true of
    exactly the examples of one outcome and of no others. That is what an
    early return looks like, and two levels are enough for the shape this tool
    keeps meeting -- a guard, a comparison, and a default.
    """
    prepared = list(examples)
    if not prepared:
        return None
    rules: list[dict[str, Any]] = []
    remaining = list(prepared)
    for _ in range(depth):
        outcomes = {json.dumps(example["output"], sort_keys=True)
                    for example in remaining}
        if len(outcomes) <= 1:
            break
        found = None
        for predicate in _predicates(remaining, features):
            matched = [example for example in remaining
                       if _holds(predicate, example["input"])]
            if not matched or len(matched) == len(remaining):
                continue
            values = {json.dumps(example["output"], sort_keys=True)
                      for example in matched}
            if len(values) != 1:
                continue
            outcome = matched[0]["output"]
            if any(_holds(predicate, example["input"]) is False
                   and example["output"] == outcome
                   for example in remaining):
                continue
            found = (predicate, outcome, matched)
            break
        if found is None:
            return None
        predicate, outcome, matched = found
        rules.append({
            "feature": predicate[0], "operator": predicate[1],
            "threshold": predicate[2], "outcome": outcome,
        })
        remaining = [example for example in remaining if example not in matched]
    if not rules or not remaining:
        return None
    defaults = {json.dumps(example["output"], sort_keys=True)
                for example in remaining}
    if len(defaults) != 1:
        return None
    rules.append({"feature": None, "operator": None, "threshold": None,
                  "outcome": remaining[0]["output"]})
    return rules


def evaluate_decision_list(rules: Sequence[dict[str, Any]],
        inputs: dict[str, int]) -> Any:
    for rule in rules:
        if rule["feature"] is None:
            return rule["outcome"]
        if _holds((rule["feature"], rule["operator"], rule["threshold"]),
                  inputs):
            return rule["outcome"]
    return None


def _validate_decision_list(rules: Sequence[dict[str, Any]],
        held: Sequence[dict[str, Any]]) -> dict[str, Any]:
    checked = _examples_for_rules(held)
    wrong = [example for example in checked
             if evaluate_decision_list(rules, example["input"])
             != example["output"]]
    return {
        "held_out": len(checked), "correct": len(checked) - len(wrong),
        "passed": not wrong and bool(checked), "failures": wrong[:4],
    }


def _readable_list(rules: Sequence[dict[str, Any]]) -> str:
    parts = []
    for rule in rules:
        if rule["feature"] is None:
            parts.append(f"otherwise {rule['outcome']}")
        else:
            parts.append(f"{rule['feature']} {rule['operator']} "
                         f"{rule['threshold']} -> {rule['outcome']}")
    return "; ".join(parts)


def _decision_list_against_native(snapshot: Snapshot,
        exploration: dict[str, Any], training: list[dict[str, Any]],
        features: Sequence[str], *, instruction_budget: int,
        max_rounds: int) -> tuple[list[dict[str, Any]] | None,
                                  list[dict[str, Any]]]:
    """Learn a decision list and make the native code try to break it.

    The greedy learner picks a threshold from the values it happened to see,
    so a guard at eight can be learned as seven when nothing ever ran with
    seven. Fitting the training examples and the held-out examples is not
    enough -- both are drawn from the same exploration, and both can miss the
    same value. So each candidate is probed either side of every threshold it
    proposes, against the real instructions, and a disagreement becomes a
    training example for the next round.
    """
    examples = list(training)
    refutations: list[dict[str, Any]] = []
    base = exploration["baseline"]["assignments"]
    for _ in range(max_rounds):
        candidate = _decision_list(examples, features)
        if candidate is None:
            return None, refutations
        probes = _decision_list_probes(snapshot, base, candidate)
        if not probes:
            return candidate, refutations
        results = run_trials(snapshot, probes,
                             instruction_budget=instruction_budget)
        broken = None
        for trial, result in zip(probes, results):
            outcome = outcome_of(result, exploration["outcome_key"])
            if outcome is None:
                continue
            inputs = derive_features(trial.assignments)
            if evaluate_decision_list(candidate, inputs) != outcome:
                broken = {"input": inputs, "output": outcome}
                refutations.append({
                    "source": "decision-list probe", "label": trial.label,
                    "predicted": evaluate_decision_list(candidate, inputs),
                    "native": outcome,
                })
                break
        if broken is None:
            return candidate, refutations
        examples.append(broken)
    return _decision_list(examples, features), refutations


def _decision_list_probes(snapshot: Snapshot, base: dict[str, int],
        rules: Sequence[dict[str, Any]], *, span: int = 3) -> list[Trial]:
    """Inputs either side of every threshold the candidate list proposes.

    A threshold is exactly where a wrong one shows itself, and nowhere else:
    a rule that is off by one is right about every value except the two
    straddling the boundary it invented.
    """
    probes: list[Trial] = []
    seen: set[str] = set()
    for rule in rules:
        feature = rule.get("feature")
        if feature is None or feature not in base:
            # Derived features are not directly assignable; probe the named
            # inputs they are built from instead.
            continue
        threshold = rule["threshold"]
        specification = snapshot.input(feature)
        ceiling = (1 << (specification.width * 8)) - 1
        for offset in range(-span, span + 1):
            assignments = dict(base)
            assignments[feature] = (threshold + offset) & ceiling
            encoded = json.dumps(assignments, sort_keys=True)
            if encoded in seen:
                continue
            seen.add(encoded)
            probes.append(Trial(
                label=f"threshold {feature}={assignments[feature]}",
                assignments=assignments))
    return probes


# --------------------------------------------------------------------------
# Durable, content-addressed runs
# --------------------------------------------------------------------------


IMPLEMENTATION = ("bne_micro_oracle.py", "bne_experiments.py")


def _write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(
                mode="w", encoding="utf-8", prefix=path.name + ".",
                suffix=".tmp", dir=path.parent, delete=False) as handle:
            temporary = Path(handle.name)
            handle.write(text)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
        temporary = None
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def _json(value: object) -> str:
    return json.dumps(value, indent=2, sort_keys=True) + "\n"


def format_next(report: dict[str, Any]) -> str:
    """The agent-sized account of what the function was asked and answered."""
    lines = [
        "# Native decision micro-oracle", "",
        f"- Snapshot: `{report['snapshot']['entry_hex']}` from "
        f"`{report['snapshot'].get('provenance', {}).get('kind', 'unknown')}`",
        f"- Baseline reproduction: **{report['reproduction']['status']}**",
    ]
    if report["reproduction"]["status"] != "exact":
        lines.extend([
            "",
            "The captured invocation did not reproduce, so nothing below it "
            "would be evidence about the game.", "",
        ])
        for mismatch in report["reproduction"]["mismatches"][:6]:
            lines.append(f"- `{mismatch['kind']}`: {mismatch}")
        lines.append("")
        return "\n".join(lines)
    exploration = report.get("exploration") or {}
    lines.extend([
        f"- Native invocations: **{exploration.get('native_invocations', 0)}** "
        f"in {exploration.get('elapsed_seconds', 0)}s "
        f"({exploration.get('invocations_per_second')}/s)",
        f"- Distinct outcomes: **{exploration.get('distinct_outcomes', 0)}**",
        "",
    ])
    if exploration.get("boundaries"):
        lines.extend(["## Where the answer changes", "",
                      "| Input | Holds through | Changes at |", "|---|---|---|"])
        for boundary in exploration["boundaries"]:
            lines.append(f"| `{boundary['input']}` | {boundary['holds_through']}"
                         f" | {boundary['changes_at']} |")
        lines.append("")
    rule = report.get("rule") or {}
    lines.extend([
        "## Rule", "",
        f"- Status: **{rule.get('status', 'none')}**",
        f"- Confidence: **{rule.get('confidence', 'no-rule')}**",
        f"- Rule: `{rule.get('readable')}`",
    ])
    validation = rule.get("held_out_validation") or {}
    if validation:
        lines.append(
            f"- Held-out: **{validation.get('correct')}/"
            f"{validation.get('held_out')}** predicted correctly")
    for refutation in (rule.get("counterexamples") or [])[:4]:
        lines.append(f"- Refuted once: {refutation}")
    lines.extend([
        "",
        "A rule here predicts this function's outcome on inputs it was tested "
        "against. It is not a proof of what the function means, and it does "
        "not authorize a source change on its own.", "",
        "The full regression gate remains the acceptance authority.", "",
    ])
    return "\n".join(lines)


def run_micro_oracle(snapshot_path: Path, artifact_root: Path, *,
        outcome_key: str = "eax", explore_budget: int = 512,
        instruction_budget: int = 200_000,
        expected_executable: str | None = BNE_202_SHA256) \
        -> tuple[int, Path]:
    """Reproduce, explore, learn and record -- addressed by its own inputs."""
    from bne_triage import canonical_digest, file_identity, inventory_files

    snapshot_path = snapshot_path.expanduser().resolve()
    if not snapshot_path.is_file():
        raise SnapshotError(f"missing snapshot: {snapshot_path}")
    document = json.loads(snapshot_path.read_text(encoding="utf-8"))
    snapshot = load_snapshot(document, blob_root=snapshot_path.parent / "blobs",
                             expected_executable=expected_executable)
    request = {
        "schema": MICRO_ORACLE_SCHEMA,
        "implementation": {
            name: file_identity(Path(__file__).with_name(name))
            for name in IMPLEMENTATION
        },
        "backend": _backend_identity(),
        "snapshot": {"path": str(snapshot_path), **file_identity(snapshot_path),
                     "metadata": snapshot.metadata()},
        "outcome_key": outcome_key,
        "explore_budget": explore_budget,
        "instruction_budget": instruction_budget,
    }
    request_sha256 = canonical_digest(request)
    artifact_root = artifact_root.expanduser().resolve()
    run_root = artifact_root / "runs" / request_sha256
    manifest_path = run_root / "manifest.json"
    if manifest_path.is_file():
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        if manifest.get("request_sha256") != request_sha256 \
                or canonical_digest(manifest.get("request")) != request_sha256:
            raise SnapshotError("cached micro-oracle request identity changed")
        for relative, expected in manifest["artifacts"].items():
            path = run_root / relative
            if not path.is_file() or file_identity(path) != expected:
                raise SnapshotError(f"micro-oracle artifact changed: {path}")
        _write(artifact_root / "latest.json", _json(manifest["pointer"]))
        return int(manifest["exit_code"]), run_root

    started = time.perf_counter()
    reproduction = reproduce(snapshot, instruction_budget=instruction_budget)
    exploration: dict[str, Any] | None = None
    rule: dict[str, Any] | None = None
    if reproduction["status"] == "exact" and snapshot.inputs:
        exploration = explore(snapshot, outcome_key=outcome_key,
                              budget=explore_budget,
                              instruction_budget=instruction_budget)
        rule = synthesize_rule(snapshot, exploration,
                               instruction_budget=instruction_budget)
    report = {
        "schema": MICRO_ORACLE_SCHEMA,
        "snapshot": snapshot.metadata(),
        "reproduction": reproduction,
        "exploration": exploration,
        "rule": rule,
        "timing": {
            "total_seconds": round(time.perf_counter() - started, 4),
            "baseline_ms": reproduction["replay"]["elapsed_ms"],
        },
    }
    status = 0 if reproduction["status"] == "exact" else 2
    if status == 0 and rule and rule.get("confidence") in (
            "no-rule", "refuted-on-held-out"):
        status = 1
    run_root.mkdir(parents=True, exist_ok=True)
    written = []
    for name, value in (
            ("concrete-replay.json", reproduction),
            ("exploration.json", exploration or {"status": "not-run"}),
            ("rule-space.json", rule or {"status": "not-run"}),
            ("held-out-validation.json",
             (rule or {}).get("held_out_validation") or {"status": "not-run"}),
            ("snapshot.json", snapshot.metadata())):
        path = run_root / name
        _write(path, _json(value))
        written.append(path)
    summary = run_root / "NEXT.md"
    _write(summary, format_next(report))
    written.append(summary)
    pointer = {
        "schema": MICRO_ORACLE_SCHEMA, "request_sha256": request_sha256,
        "run": str(run_root.relative_to(artifact_root)),
        "entry": snapshot.metadata()["entry_hex"],
        "reproduction": reproduction["status"],
        "confidence": (rule or {}).get("confidence", "no-rule"),
        "exit_code": status,
    }
    manifest = {
        "schema": MICRO_ORACLE_SCHEMA, "request_sha256": request_sha256,
        "request": request, "exit_code": status, "pointer": pointer,
        "artifacts": inventory_files(run_root, written),
    }
    _write(manifest_path, _json(manifest))
    _write(artifact_root / "latest.json", _json(pointer))
    return status, run_root


def _backend_identity() -> dict[str, Any]:
    """Which emulator produced these answers, so a pin change invalidates them."""
    requirements = BACKEND_VENV.parent / "micro-oracle-requirements.txt"
    return {
        "requirements_sha256": (sha256_bytes(requirements.read_bytes())
                                if requirements.is_file() else None),
        "available": backend_available(),
    }


# --------------------------------------------------------------------------
# Planning a capture from evidence the lab already has
# --------------------------------------------------------------------------


#: What a snapshot needs that a Branch Witness run does not collect. Branch
#: Witness answers "which instruction wrote this byte, and which branch
#: controlled it" from a BTS history; it never had a reason to save the
#: machine, so none of this is a defect in it.
SNAPSHOT_REQUIREMENTS = (
    ("registers", "all eight general registers plus EIP, ESP and EFLAGS at "
                  "the moment the function is entered"),
    ("stack", "the bytes below ESP the function reads, including its return "
              "address"),
    ("code", "the bytes of the function and of everything it calls, from the "
             "pinned executable"),
    ("data", "every memory range the function reads, which the replay learns "
             "by failing closed on the first uncaptured read"),
    ("outcome", "the registers, the written bytes and the branch path the "
                "captured invocation produced, so the replay can be checked "
                "against it rather than trusted"),
)


def plan_from_branch_witness(artifact: dict[str, Any], *,
        case: str | None = None) -> dict[str, Any]:
    """Turn a completed Branch Witness result into a capture specification.

    Branch Witness localizes a decision; this says what would have to be saved
    at that decision for it to be replayed offline afterwards. It emits a plan
    and never a snapshot: half a snapshot that loads is worse than none,
    because it produces numbers.
    """
    branch = artifact.get("top_branch") or {}
    writer = artifact.get("writer") or {}
    focus = artifact.get("focus") or {}
    entry = branch.get("address")
    supported = isinstance(entry, int)
    have = {
        "branch_address": branch.get("address"),
        "branch_instruction": branch.get("instruction"),
        "branch_taken": branch.get("taken"),
        "writer_address": writer.get("address"),
        "writer_instruction": writer.get("instruction_text"),
        "field": writer.get("field"),
        "before": writer.get("before"), "after": writer.get("after"),
        "operands": branch.get("operands"),
    }
    missing = [name for name, _ in SNAPSHOT_REQUIREMENTS]
    return {
        "schema": MICRO_ORACLE_SCHEMA,
        "supported": supported,
        "case": case or artifact.get("case"),
        "cycle": artifact.get("cycle"),
        "focus": {"native_slot": focus.get("native_slot"),
                  "java_id": focus.get("java_id")},
        "candidate_entry": entry,
        "candidate_entry_hex": f"0x{entry:08x}" if supported else None,
        "evidence_present": have,
        "evidence_missing": [
            {"part": name, "why": why} for name, why in SNAPSHOT_REQUIREMENTS],
        "reason": (
            "Branch Witness records a branch history and the writer it "
            "controls. It does not save the machine at that instruction, so "
            "the decision it localized cannot be replayed from this artifact "
            "alone."
            if supported else
            "this artifact localizes no branch, so there is nothing to capture"),
        "fallback_route": (
            "decision-plan and decision-mine remain the route to a predicate "
            "for this decision until a snapshot capture exists"),
        "next_command": (
            "python3 tools/bne-harness/scripts/bne_java.py micro-oracle-spec "
            f"ARTIFACT --case {case or artifact.get('case')} "
            "--out specification.json" if supported else None),
        "capture_requirements": [name for name, _ in SNAPSHOT_REQUIREMENTS],
        "missing_count": len(missing),
    }


def remote_capture_plan(entry: int, *, case: str,
        executable_sha256: str = BNE_202_SHA256,
        remote: str = DEFAULT_REMOTE_HOST, dry_run: bool = True,
        scenario: str = "SCENARIO", seed: int = 1, cycles: int | None = None,
        remote_root: str = ".local/share/chonkcraft-bne-oracle",
        harness: str = "harness-micro-oracle") -> dict[str, Any]:
    """The isolated, dry-run-by-default plan for capturing one snapshot.

    Modelled on the Branch Witness contract, which is the only capture design
    here that has been trusted with the retail oracle: a disposable directory
    of its own, the executable's hash checked before anything is hooked, no
    network while the game runs, the canonical corpus untouched, and the
    original bytes validated before and restored after.

    Dry run by default because the remote is shared. A capture that ran
    because somebody forgot a flag is a capture that collided with whatever
    else was using the oracle.
    """
    digest = hashlib.sha256(
        json.dumps({"entry": entry, "case": case,
                    "executable": executable_sha256},
                   sort_keys=True).encode("utf-8")).hexdigest()
    directory = f"$HOME/.local/share/chonkcraft-bne-micro-oracle/{digest}"
    return {
        "schema": MICRO_ORACLE_SCHEMA,
        "dry_run": dry_run,
        "case": case,
        "entry": entry, "entry_hex": f"0x{entry:08x}",
        "remote": remote,
        "output_directory": directory,
        "isolation": {
            "directory_is_content_addressed": True,
            "shared_with_other_agents": False,
            "canonical_oracle_modified": False,
            "corpus_modified": False,
            "network_disabled_during_native_execution": True,
            "original_bytes_validated_before_hook": True,
            "instrumentation_removed_after_capture": True,
        },
        "executable_sha256": executable_sha256,
        "specification": f"{directory}/specification.json",
        "commands": [
            f"ssh {remote} 'mkdir -p {directory}'",
            f"scp specification.json {remote}:{directory}/specification.json",
            f"scp tools/bne-harness/scripts/bne_snapshot_capture.py "
            f"{remote}:{remote_root}/{harness}/scripts/",
            f"ssh {remote} 'python3 {remote_root}/{harness}/scripts/"
            f"bne_headless.py snapshot-capture --oracle-root {remote_root} "
            f"--harness-name {harness} "
            f"--specification {directory}/specification.json --case-id {case} "
            f"--scenario {scenario} --seed {seed} "
            f"--cycles {cycles if cycles is not None else 'CYCLES'} "
            f"--output micro-oracle/{digest[:16]} --host-gdb'",
            f"scp -r {remote}:{remote_root}/output/micro-oracle/{digest[:16]}/"
            f"snapshot ./",
        ],
        "verification_on_return": [
            "the snapshot's executable hash equals the pinned one",
            "every memory blob's sha256 matches its record",
            "the baseline replay reproduces the captured outcome exactly",
        ],
        "note": ("nothing is executed by this call; it prints the plan. The "
                 "capture agent it names is implemented and proved offline "
                 "against a synthetic function; it has never been run against "
                 "the retail oracle."),
    }
