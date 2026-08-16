#!/usr/bin/env python3
"""Verify and launch the exact Warcraft II BNE 2.02 oracle under Wine."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys

SCRIPT_DIRECTORY = Path(__file__).resolve().parent
if str(SCRIPT_DIRECTORY) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIRECTORY))

from bne_fixture import seal_fixture, validate_fixture, validate_state_stream

TARGET_EXE = "Warcraft II BNE.exe"
EXPECTED_TARGET_SHA256 = "b0e914a9cb7dcc81a205e700a9bb0a1d0649df19d459388051ba170783d2c807"
EXPECTED_SUPPORT = {
    "storm.dll": "dd2c6d7a645f375608750a8b345440db675760e50d20fe1c7bd6cc00ada6bbdb",
    "battle.snp": "f8b8689ff9f61b5343e03182b72e2a303c4c1184b9a3236074829849be49af86",
}
EXPECTED_DATA = {
    "War2Dat.mpq": "aac526b396d8493205c3cb1e9944b9d10733675d4eca1301899b60dfb330fb10",
    "War2Patch.mpq": "be2e4f85a5ebd2c1a58b0d00c7f67ff673cd9c25b069469c84db205006c6bc36",
}

CAMPAIGN_SCENARIO = re.compile(
    r"^campaign[\\/]"
    r"(human[\\/]human|orc[\\/]orc|xhuman[\\/]2xhum|xorc[\\/]2xorc)"
    r"(\d{1,2})\.pud$",
    re.IGNORECASE,
)
SCRIPT_COMMAND_MOVE = re.compile(
    r"^cycle ([1-9]\d*) (move|patrol|attack-ground|attack-move) unit (\d+) x (\d+) y (\d+)$"
)
SCRIPT_COMMAND_STANCE = re.compile(
    r"^cycle ([1-9]\d*) (stop|stand-ground|return-goods) unit (\d+)$"
)
SCRIPT_COMMAND_TRAIN = re.compile(
    r"^cycle ([1-9]\d*) train unit (\d+) type (\d+)$"
)
SCRIPT_COMMAND_TARGETED = re.compile(
    r"^cycle ([1-9]\d*) (attack|harvest|repair) unit (\d+) target (\d+)$"
)
GAME_RULE_REJECT_REASONS = {
    "unit-not-local",
    "unit-not-live",
    "unit-slot-out-of-range",
    "target-not-live",
    "target-slot-out-of-range",
    "target-is-self",
    "target-required",
    "not-a-worker",
    # 0x40e2a0 returned 0: the hall/barracks would not start that type.
    "refused",
}
COMMAND_REJECT_REASON = re.compile(r"\breason=([a-z0-9-]+)\b")


def canonical_campaign_scenario(value: str) -> str:
    match = CAMPAIGN_SCENARIO.fullmatch(value.strip())
    if match is None:
        raise ValueError(
            "--scenario must name a retail BNE campaign PUD, for example "
            r"Campaign\Orc\Orc01.pud"
        )
    family = match.group(1).lower().replace("/", "\\")
    mission = int(match.group(2))
    definitions = {
        r"human\human": ("Human", "Human", 14),
        r"orc\orc": ("Orc", "Orc", 14),
        r"xhuman\2xhum": ("XHuman", "2XHum", 12),
        r"xorc\2xorc": ("XOrc", "2XOrc", 12),
    }
    campaign, filename, maximum = definitions[family]
    if mission < 1 or mission > maximum:
        raise ValueError(
            f"--scenario mission must be between 1 and {maximum} for {campaign}"
        )
    return f"Campaign\\{campaign}\\{filename}{mission:02d}.pud"


def parse_command_script(path: Path) -> list[dict[str, int | str]]:
    commands: list[dict[str, int | str]] = []
    previous_cycle = 0
    with path.open(encoding="ascii") as source:
        for line_number, raw_line in enumerate(source, 1):
            line = raw_line.strip()
            if not line or line.startswith("#"):
                continue
            move = SCRIPT_COMMAND_MOVE.fullmatch(line)
            stance = SCRIPT_COMMAND_STANCE.fullmatch(line)
            train = SCRIPT_COMMAND_TRAIN.fullmatch(line)
            targeted = SCRIPT_COMMAND_TARGETED.fullmatch(line)
            target = None
            if move is not None:
                cycle = int(move.group(1))
                action = move.group(2)
                slot = int(move.group(3))
                x = int(move.group(4))
                y = int(move.group(5))
            elif stance is not None:
                cycle = int(stance.group(1))
                action = stance.group(2)
                slot = int(stance.group(3))
                x = 0
                y = 0
            elif train is not None:
                cycle = int(train.group(1))
                action = "train"
                slot = int(train.group(2))
                x = int(train.group(3))
                y = 0
            elif targeted is not None:
                cycle = int(targeted.group(1))
                action = targeted.group(2)
                slot = int(targeted.group(3))
                target = int(targeted.group(4))
                x = 0
                y = 0
            else:
                raise ValueError(
                    f"invalid command at {path}:{line_number}; expected "
                    "'cycle N move|patrol|attack-ground|attack-move unit SLOT x X y Y', "
                    "'cycle N stop|stand-ground|return-goods unit SLOT', "
                    "'cycle N train unit SLOT type T', or "
                    "'cycle N attack|harvest|repair unit SLOT target T'"
                )
            if cycle < previous_cycle:
                raise ValueError(f"commands are not cycle-sorted at {path}:{line_number}")
            if slot >= 1600:
                raise ValueError(f"unit slot is outside BNE's pool at {path}:{line_number}")
            if target is not None and target >= 1600:
                raise ValueError(f"target slot is outside BNE's pool at {path}:{line_number}")
            if x > 127 or y > 127:
                raise ValueError(f"tile is outside BNE's map bounds at {path}:{line_number}")
            parsed = {
                "cycle": cycle,
                "action": action,
                "unit": slot,
                "x": x,
                "y": y,
            }
            if target is not None:
                parsed["target"] = target
            commands.append(parsed)
            previous_cycle = cycle
    if len(commands) > 1024:
        raise ValueError("command file exceeds the 1,024-command harness limit")
    return commands


def hash_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def verify(game_dir: Path, require_data: bool) -> None:
    game_dir = game_dir.resolve()
    target = game_dir / TARGET_EXE
    if not target.is_file():
        raise ValueError(f"missing {target}")
    target_hash = hash_file(target)
    if target_hash != EXPECTED_TARGET_SHA256:
        raise ValueError(
            f"unsupported executable hash {target_hash}; expected retail BNE 2.02b "
            f"{EXPECTED_TARGET_SHA256}"
        )
    for name, expected_hash in EXPECTED_SUPPORT.items():
        path = game_dir / name
        if not path.is_file() or hash_file(path) != expected_hash:
            raise ValueError(f"{name} is not the official 2.02b patch payload")
    if require_data:
        for name, expected_hash in EXPECTED_DATA.items():
            path = game_dir / name
            if not path.is_file() or hash_file(path) != expected_hash:
                raise ValueError(f"{name} is not the verified English retail 2.02b data")


def validate_trace(trace: Path, expected_cycles: int | None,
        expected_scenario: str | None = None,
        expected_commands: int | None = None,
        expected_initialization_seed: int | None = None) -> dict[str, int | str]:
    cycle_pattern = re.compile(r"^cycle (\d+) seed [0-9a-fA-F]{8}$")
    player_pattern = re.compile(
        r"^p (\d+) gold (\d+) wood (\d+) oil (\d+)$")
    unit_pattern = re.compile(
        r"^u (\d+) \S+ p(\d+) (\d+) (\d+) hp (\d+) o \S+( removed)?$")
    initialization_seed_pattern = re.compile(
        r"^# bne-trace event=initialization-seed-applied seed=(\d+)$")
    cycles: list[int] = []
    player_records = 0
    unit_records = 0
    match_ready = 0
    cycle_limit = 0
    detach = 0
    scenarios: list[str] = []
    commands_applied = 0
    commands_rejected = 0
    rejected_reasons: list[str] = []
    initialization_seeds: list[int] = []
    simulation_digest = hashlib.sha256()
    simulation_records = 0
    seed_digest = hashlib.sha256()
    player_digest = hashlib.sha256()
    unit_digest = hashlib.sha256()
    current_cycle: int | None = None
    with trace.open(encoding="utf-8") as source:
        for raw_line in source:
            line = raw_line.rstrip("\r\n")
            if line.startswith(("cycle ", "p ", "u ")):
                simulation_digest.update(line.encode("utf-8") + b"\n")
                simulation_records += 1
            match = cycle_pattern.match(line)
            if match:
                current_cycle = int(match.group(1))
                cycles.append(current_cycle)
                gameplay_seed = int(line.rsplit(" ", 1)[1], 16)
                seed_digest.update(struct.pack(
                    "<II", current_cycle, gameplay_seed))
            elif line.startswith("p "):
                player_records += 1
                player_match = player_pattern.match(line)
                if player_match is None or current_cycle is None:
                    raise ValueError(f"malformed player trace record: {line!r}")
                player, gold, wood, oil = (
                    int(value) for value in player_match.groups())
                player_digest.update(struct.pack(
                    "<IIIII", current_cycle, player, gold, wood, oil))
            elif line.startswith("u "):
                unit_records += 1
                unit_match = unit_pattern.match(line)
                if unit_match is None or current_cycle is None:
                    raise ValueError(f"malformed unit trace record: {line!r}")
                slot, owner, x, y, hp = (
                    int(value) for value in unit_match.groups()[:5])
                removed = 1 if unit_match.group(6) else 0
                unit_digest.update(struct.pack(
                    "<IIIIIII", current_cycle, slot, owner, x, y, hp,
                    removed))
            elif "event=match-ready" in line:
                match_ready += 1
            elif "event=cycle-limit" in line:
                cycle_limit += 1
            elif "event=detach" in line:
                detach += 1
            elif "event=command-applied" in line:
                commands_applied += 1
            elif "event=command-rejected" in line:
                commands_rejected += 1
                reason_match = COMMAND_REJECT_REASON.search(line)
                rejected_reasons.append(
                    reason_match.group(1) if reason_match else "unspecified")
            seed_match = initialization_seed_pattern.match(line)
            if seed_match:
                initialization_seeds.append(int(seed_match.group(1)))
            if "event=storm-open-file" in line and ' path="' in line:
                escaped_path = line.split(' path="', 1)[1].split('"', 1)[0]
                if escaped_path.lower().endswith((".pud", ".smp")):
                    scenario = json.loads(f'"{escaped_path}"')
                    if not scenarios or scenarios[-1] != scenario:
                        scenarios.append(scenario)
    wanted = expected_cycles if expected_cycles is not None else len(cycles)
    if cycles != list(range(1, wanted + 1)):
        raise ValueError(f"trace has non-contiguous cycles {cycles!r}; expected 1..{wanted}")
    if match_ready != 1:
        raise ValueError(f"trace has {match_ready} match-ready markers; expected 1")
    if expected_cycles is not None and cycle_limit != 1:
        raise ValueError(f"trace has {cycle_limit} cycle-limit markers; expected 1")
    if detach != 1:
        raise ValueError(f"trace has {detach} detach markers; expected 1")
    if player_records == 0 or unit_records == 0:
        raise ValueError("trace did not capture both player banks and live units")
    if not scenarios:
        raise ValueError("trace did not identify a PUD or SMP scenario")
    if (expected_scenario is not None
            and scenarios[-1].replace("/", "\\").lower()
            != expected_scenario.replace("/", "\\").lower()):
        raise ValueError(
            f"trace loaded {scenarios[-1]!r}; expected {expected_scenario!r}"
        )
    processed = commands_applied + commands_rejected
    if expected_commands is not None and processed != expected_commands:
        raise ValueError(
            f"trace processed {processed} commands "
            f"({commands_applied} applied, {commands_rejected} rejected); "
            f"expected {expected_commands}"
        )
    illegal_rejects = [
        reason for reason in rejected_reasons
        if reason not in GAME_RULE_REJECT_REASONS
    ]
    if illegal_rejects:
        raise ValueError(
            "trace rejected scripted commands for harness failures "
            f"{illegal_rejects!r}"
        )
    if expected_initialization_seed is not None:
        if initialization_seeds != [expected_initialization_seed]:
            raise ValueError(
                "trace applied initialization seeds "
                f"{initialization_seeds!r}; expected "
                f"[{expected_initialization_seed}]"
            )
    return {
        "cycles": len(cycles),
        "player_records": player_records,
        "unit_records": unit_records,
        "scenario": scenarios[-1],
        "commands_applied": commands_applied,
        "commands_rejected": commands_rejected,
        "initialization_seed": (
            initialization_seeds[-1] if initialization_seeds else "uncontrolled"
        ),
        "simulation_records": simulation_records,
        "simulation_sha256": simulation_digest.hexdigest(),
        "cycle_seed_sha256": seed_digest.hexdigest(),
        "player_bank_sha256": player_digest.hexdigest(),
        "unit_core_sha256": unit_digest.hexdigest(),
    }


def cross_validate_trace_state(trace_validation: dict[str, int | str],
        state_validation: dict[str, int | str]) -> None:
    pairs = (
        ("cycles", "cycles"),
        ("player_records", "active_player_records"),
        ("unit_records", "live_unit_records"),
        ("cycle_seed_sha256", "cycle_seed_sha256"),
        ("player_bank_sha256", "player_bank_sha256"),
        ("unit_core_sha256", "unit_core_sha256"),
    )
    for trace_key, state_key in pairs:
        if trace_validation[trace_key] != state_validation[state_key]:
            raise ValueError(
                f"text trace {trace_key}={trace_validation[trace_key]!r} "
                f"does not match state {state_key}={state_validation[state_key]!r}"
            )


def identity(path: Path) -> dict[str, int | str]:
    return {"bytes": path.stat().st_size, "sha256": hash_file(path)}


def validate_replay_inputs(plan_path: Path, schedule_path: Path) -> dict:
    """Bind a native schedule byte-for-byte to its authenticated plan."""

    import bne_replay_outcome
    plan = json.loads(plan_path.read_text(encoding="utf-8"))
    bne_replay_outcome._validate_plan(plan, require_startup=True)
    expected = bne_replay_outcome.native_schedule_bytes(plan)
    if schedule_path.read_bytes() != expected:
        raise ValueError("native replay schedule does not match its plan")
    return plan


def write_run_manifest(args: argparse.Namespace, wine: Path, injector: Path,
        tracer: Path, trace: Path, state: Path,
        validation: dict[str, int | str],
        state_validation: dict[str, int | str]) -> tuple[Path, str]:
    game_dir = args.game_dir.resolve()
    source = None
    if args.source_manifest is not None:
        source_path = args.source_manifest.resolve()
        source_data = json.loads(source_path.read_text(encoding="utf-8"))
        source = {
            "manifest": identity(source_path),
            "authority": source_data.get("authority"),
            "archive": source_data.get("archive"),
            "iso": source_data.get("iso"),
            "track": source_data.get("track"),
        }
    try:
        wine_version = subprocess.run(
            [str(wine), "--version"], check=False, capture_output=True,
            text=True, timeout=10).stdout.strip()
    except (OSError, subprocess.TimeoutExpired):
        wine_version = "unknown"
    executable_identity = identity(game_dir / TARGET_EXE)
    tracer_identity = identity(tracer)
    data_identity = {
        name: identity(game_dir / name) for name in sorted(EXPECTED_DATA)
    }
    command_identity = (
        None if args.commands is None else identity(args.commands)
    )
    replay_identity = None
    if args.replay_plan is not None:
        replay_identity = {
            "plan": identity(args.replay_plan),
            "schedule": identity(args.replay_schedule),
            "startup_sha256": args.replay_plan_data["startup_sha256"],
            "packet_schedule_sha256":
                args.replay_plan_data["schedule_sha256"],
            "native_dispatch_proof": args.replay_dispatch_proof,
        }
    fixture_key = {
        "schema": 1,
        "state_schema": state_validation["schema"],
        "oracle_executable": executable_identity["sha256"],
        "oracle_data": {
            name: value["sha256"] for name, value in data_identity.items()
        },
        "tracer": tracer_identity["sha256"],
        "scenario": args.scenario,
        "cycle_limit": args.cycles,
        "initialization_seed": args.seed,
        "commands": None if command_identity is None
                    else command_identity["sha256"],
        "replay": None if replay_identity is None else {
            "startup_sha256": replay_identity["startup_sha256"],
            "packet_schedule_sha256":
                replay_identity["packet_schedule_sha256"],
        },
        "simulation": validation["simulation_sha256"],
    }
    fixture_id = hashlib.sha256(json.dumps(
        fixture_key, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")).hexdigest()
    manifest = {
        "schema": 2,
        "fixture": {
            "schema": 1,
            "id": fixture_id,
            "key": fixture_key,
        },
        "oracle": {
            "product": "Warcraft II Battle.net Edition 2.02b",
            "executable": executable_identity,
            "support": {
                name: identity(game_dir / name) for name in sorted(EXPECTED_SUPPORT)
            },
            "data": data_identity,
        },
        "harness": {
            "injector": identity(injector),
            "tracer": tracer_identity,
            "state_schema": state_validation["schema"],
        },
        "runtime": {
            "wine": identity(wine),
            "wine_version": wine_version,
            "wine_cd_fallback": True,
            "startup_tips_disabled": True,
            "execution_mode": os.environ.get(
                "CHONK_BNE_EXECUTION_MODE", "interactive-wine"),
            "container_image": os.environ.get("CHONK_BNE_CONTAINER_IMAGE"),
            "container_image_id": os.environ.get(
                "CHONK_BNE_CONTAINER_IMAGE_ID"),
            "display": os.environ.get("DISPLAY"),
            "display_depth": os.environ.get("CHONK_BNE_DISPLAY_DEPTH"),
            "network_disabled": (
                os.environ.get("CHONK_BNE_NETWORK_DISABLED") == "1"),
            "audio_device": os.environ.get(
                "CHONK_BNE_AUDIO_DEVICE", "host-default"),
            "branch_witness_pause_cycle": args.branch_pause_cycle,
        },
        "source": source,
        "run": {
            "cycle_limit": args.cycles,
            "initialization_seed": args.seed,
            "requested_scenario": args.scenario,
            "commands": (
                None if args.commands is None else {
                    "count": args.command_count,
                    "name": args.commands.name,
                    "file": command_identity,
                }
            ),
            "replay": replay_identity,
            "trace": {"name": trace.name, **identity(trace)},
            "state": {
                "name": state.name,
                **identity(state),
                "validation": state_validation,
            },
            "validation": validation,
        },
    }
    manifest_path = (args.manifest.resolve() if args.manifest is not None
                     else Path(str(trace) + ".manifest.json"))
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n",
                             encoding="utf-8")
    return manifest_path, fixture_id


def wine_path(path: Path) -> str:
    return "Z:" + str(path.resolve()).replace("/", "\\")


def find_wine(explicit: Path | None) -> Path:
    if explicit is not None:
        return explicit.expanduser().resolve()
    whisky = Path.home() / (
        "Library/Application Support/com.isaacmarovitz.Whisky/Libraries/Wine/bin/wine64"
    )
    if whisky.is_file():
        return whisky
    candidate = shutil.which("wine") or shutil.which("wine64")
    if candidate is None:
        raise RuntimeError("Wine was not found; pass --wine /path/to/wine64")
    return Path(candidate).resolve()


def ensure_symlink(link: Path, target: Path) -> None:
    target = target.resolve()
    if os.path.lexists(link):
        if link.is_symlink() and link.resolve() == target:
            return
        raise ValueError(f"refusing to replace existing Wine drive link: {link}")
    link.symlink_to(target)


def configure_media(args: argparse.Namespace) -> int:
    prefix = args.prefix.resolve()
    dosdevices = prefix / "dosdevices"
    if not dosdevices.is_dir():
        raise ValueError(
            f"Wine prefix is not initialized: {prefix}; create it with wineboot first")
    cd_dir = args.cd_dir.resolve()
    iso = args.iso.resolve()
    if not (cd_dir / "INSTALL.EXE").is_file():
        raise ValueError(f"prepared CD directory has no INSTALL.EXE: {cd_dir}")
    if not iso.is_file():
        raise ValueError(f"prepared ISO does not exist: {iso}")
    label = args.label.strip()
    if not label or any(char in label for char in "\r\n"):
        raise ValueError("--label must be one non-empty line")
    ensure_symlink(dosdevices / "i:", cd_dir)
    ensure_symlink(dosdevices / "i::", iso)
    (cd_dir / ".windows-label").write_text(label + "\n", encoding="ascii")

    environment = os.environ.copy()
    environment["WINEPREFIX"] = str(prefix)
    wine = find_wine(args.wine)
    registry_commands = [
        ["reg", "add", r"HKLM\Software\Wine\Drives", "/v", "i:",
         "/t", "REG_SZ", "/d", "cdrom", "/f"],
        ["reg", "add", r"HKCU\Software\Wine\Drives", "/v", "i:",
         "/t", "REG_SZ", "/d", "cdrom", "/f"],
        ["reg", "add",
         r"HKLM\Software\Blizzard Entertainment\Warcraft II BNE",
         "/v", "War2CD", "/t", "REG_SZ", "/d", "I:\\", "/f",
         "/reg:32"],
    ]
    for command in registry_commands:
        subprocess.run([str(wine), *command], env=environment,
                       check=True, capture_output=True, text=True)
    print(f"configured retail BNE media as I: ({label}) in {prefix}")
    return 0


def run(args: argparse.Namespace) -> int:
    game_dir = args.game_dir.resolve()
    verify(game_dir, require_data=True)
    if args.scenario is not None:
        args.scenario = canonical_campaign_scenario(args.scenario)
    args.command_count = 0
    args.replay_plan_data = None
    args.replay_dispatch_proof = None
    if (args.replay_plan is None) != (args.replay_schedule is None):
        raise ValueError("--replay-plan and --replay-schedule are required together")
    if args.replay_plan is not None:
        args.replay_plan = args.replay_plan.resolve()
        args.replay_schedule = args.replay_schedule.resolve()
        if not args.replay_plan.is_file() or not args.replay_schedule.is_file():
            raise ValueError("replay plan or native schedule does not exist")
        args.replay_plan_data = validate_replay_inputs(
            args.replay_plan, args.replay_schedule)
    if args.commands is not None:
        args.commands = args.commands.resolve()
        if not args.commands.is_file():
            raise ValueError(f"missing command file: {args.commands}")
        commands = parse_command_script(args.commands)
        args.command_count = len(commands)
        if (args.cycles is not None and commands
                and int(commands[-1]["cycle"]) > args.cycles):
            raise ValueError(
                f"last scripted command is at cycle {commands[-1]['cycle']}, "
                f"after --cycles {args.cycles}"
            )
    harness_dir = Path(__file__).resolve().parent.parent
    injector = harness_dir / "build/bne-inject.exe"
    tracer = harness_dir / "build/bne-trace.dll"
    for tool in (injector, tracer):
        if not tool.is_file():
            raise RuntimeError(f"missing {tool}; build the native harness first")

    trace = args.trace.resolve()
    trace.parent.mkdir(parents=True, exist_ok=True)
    state = (args.state.resolve() if args.state is not None
             else Path(str(trace) + ".state"))
    fixture = (args.fixture.resolve() if args.fixture is not None
               else Path(str(trace) + ".bnefx"))
    state.parent.mkdir(parents=True, exist_ok=True)
    fixture.parent.mkdir(parents=True, exist_ok=True)
    manifest = (args.manifest.resolve() if args.manifest is not None
                else Path(str(trace) + ".manifest.json"))
    for label, output in (
            ("trace", trace), ("state", state),
            ("manifest", manifest), ("fixture", fixture)):
        if output.exists():
            raise ValueError(
                f"{label} already exists: {output}; choose a new output path")
    if args.source_manifest is not None:
        source_manifest = args.source_manifest.resolve()
        if not source_manifest.is_file():
            raise ValueError(f"missing source manifest: {source_manifest}")
        json.loads(source_manifest.read_text(encoding="utf-8"))
    prefix = args.prefix.resolve()
    prefix.mkdir(parents=True, exist_ok=True)
    environment = os.environ.copy()
    environment["WINEPREFIX"] = str(prefix)
    environment["CHONK_BNE_TRACE"] = wine_path(trace)
    environment["CHONK_BNE_STATE"] = wine_path(state)
    # Blizzard Storm's physical-CD flag rejects Wine directory-backed CD-ROM
    # drives before reading the archive. The tracer retries only that exact
    # ERROR_INVALID_DRIVE case in ordinary file mode, against the same media.
    environment["CHONK_BNE_WINE_CD_FALLBACK"] = "1"
    # The stock Tip of the Day overlay grabs input under Wine's macOS driver
    # after the simulation has started. Disable that UI-only preference so a
    # bounded oracle run cannot stall between its first and second ticks.
    environment["CHONK_BNE_DISABLE_STARTUP_TIPS"] = "1"
    environment["CHONK_BNE_SEED"] = str(args.seed)
    if args.replay_schedule is not None:
        environment["CHONK_BNE_REPLAY_SCHEDULE"] = wine_path(
            args.replay_schedule)
    if args.branch_pause_cycle is not None:
        environment["CHONK_BNE_BRANCH_PAUSE_CYCLE"] = str(
            args.branch_pause_cycle)
        environment["CHONK_BNE_BRANCH_READY"] = wine_path(
            args.branch_ready.resolve())
        environment["CHONK_BNE_BRANCH_RESUME"] = wine_path(
            args.branch_resume.resolve())
    if args.cycles is not None:
        environment["CHONK_BNE_TRACE_CYCLES"] = str(args.cycles)
    if args.scenario is not None:
        environment["CHONK_BNE_SCENARIO"] = args.scenario
    if args.commands is not None:
        environment["CHONK_BNE_COMMANDS"] = wine_path(args.commands)
    wine = find_wine(args.wine)
    command = [
        str(wine),
        str(injector),
        str(tracer),
        str(game_dir / TARGET_EXE),
    ]
    result = subprocess.run(command, cwd=game_dir, env=environment, check=False)
    if result.returncode == 0:
        validation = validate_trace(
            trace, args.cycles, args.scenario,
            args.command_count if args.commands is not None else None,
            args.seed)
        state_validation = validate_state_stream(state, args.cycles)
        cross_validate_trace_state(validation, state_validation)
        if args.replay_plan_data is not None:
            import bne_replay_outcome
            args.replay_dispatch_proof = \
                bne_replay_outcome.verify_native_dispatch_trace(
                    args.replay_plan_data,
                    trace.read_text(encoding="utf-8", errors="replace").splitlines())
        manifest_path, fixture_id = write_run_manifest(
            args, wine, injector, tracer, trace, state,
            validation, state_validation)
        fixture_validation = seal_fixture(
            fixture, manifest_path, trace, state, args.commands)
        if fixture_validation["fixture_id"] != fixture_id:
            raise ValueError("sealed fixture id differs from its run manifest")
        print(f"verified trace: {trace}")
        print(f"run manifest: {manifest_path}")
        print(f"sealed fixture: {fixture} ({fixture_id})")
    return result.returncode


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    subcommands = result.add_subparsers(dest="command", required=True)

    verify_parser = subcommands.add_parser("verify", help="verify a patched install")
    verify_parser.add_argument("--game-dir", required=True, type=Path)
    verify_parser.add_argument(
        "--target-only", action="store_true", help="do not require installed game data"
    )

    media_parser = subcommands.add_parser(
        "configure-media", help="map prepared retail media as Wine CD-ROM I:")
    media_parser.add_argument("--prefix", required=True, type=Path)
    media_parser.add_argument("--cd-dir", required=True, type=Path)
    media_parser.add_argument("--iso", required=True, type=Path)
    media_parser.add_argument("--label", default="WAR2BNECD")
    media_parser.add_argument("--wine", type=Path)

    fixture_parser = subcommands.add_parser(
        "validate-fixture", help="validate a sealed .bnefx bundle")
    fixture_parser.add_argument("fixture", type=Path)

    run_parser = subcommands.add_parser("run", help="run BNE through the tracer")
    run_parser.add_argument("--game-dir", required=True, type=Path)
    run_parser.add_argument("--trace", required=True, type=Path)
    run_parser.add_argument(
        "--state", type=Path,
        help="raw state sidecar (default: TRACE.state)",
    )
    run_parser.add_argument(
        "--fixture", type=Path,
        help="sealed .bnefx bundle (default: TRACE.bnefx)",
    )
    run_parser.add_argument("--manifest", type=Path)
    run_parser.add_argument("--source-manifest", type=Path)
    run_parser.add_argument("--prefix", required=True, type=Path)
    run_parser.add_argument("--wine", type=Path)
    run_parser.add_argument("--cycles", type=int)
    run_parser.add_argument(
        "--seed", type=int, default=1,
        help="unsigned 32-bit seed for BNE's initialization RNG (default: 1)",
    )
    run_parser.add_argument(
        "--scenario",
        help=(r"retail campaign PUD to start without menus, such as "
              r"Campaign\Orc\Orc01.pud"),
    )
    run_parser.add_argument(
        "--commands", type=Path,
        help="cycle-sorted deterministic command script",
    )
    run_parser.add_argument(
        "--replay-plan", type=Path,
        help="authenticated replay plan whose startup recipe is already running",
    )
    run_parser.add_argument(
        "--replay-schedule", type=Path,
        help="exact native packet schedule compiled from --replay-plan",
    )
    run_parser.add_argument(
        "--branch-pause-cycle", type=int,
        help="diagnostic-only pre-tick pause for a Branch Witness recorder",
    )
    run_parser.add_argument("--branch-ready", type=Path)
    run_parser.add_argument("--branch-resume", type=Path)
    return result


def main() -> int:
    args = parser().parse_args()
    try:
        if args.command == "verify":
            verify(args.game_dir, require_data=not args.target_only)
            print(f"verified Warcraft II BNE 2.02b: {args.game_dir.resolve()}")
            return 0
        if args.command == "configure-media":
            return configure_media(args)
        if args.command == "validate-fixture":
            validation = validate_fixture(args.fixture.resolve())
            print(json.dumps(validation, indent=2, sort_keys=True))
            return 0
        if args.cycles is not None and args.cycles <= 0:
            raise ValueError("--cycles must be positive")
        branch_values = (
            args.branch_pause_cycle, args.branch_ready, args.branch_resume,
        )
        if any(value is not None for value in branch_values) \
                and not all(value is not None for value in branch_values):
            raise ValueError(
                "branch pause requires --branch-pause-cycle, --branch-ready, "
                "and --branch-resume together")
        if args.branch_pause_cycle is not None and (
                args.branch_pause_cycle <= 0
                or args.cycles is None
                or args.branch_pause_cycle > args.cycles):
            raise ValueError("branch pause cycle must lie within --cycles")
        if args.seed < 0 or args.seed > 0xffffffff:
            raise ValueError("--seed must be between 0 and 4294967295")
        return run(args)
    except (OSError, ValueError, RuntimeError) as error:
        print(f"bne-oracle: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
