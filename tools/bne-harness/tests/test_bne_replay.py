import importlib.util
import json
from pathlib import Path
import struct
import tempfile
import unittest
import zlib

SCRIPT = Path(__file__).parents[1] / "scripts" / "bne_replay.py"
SPEC = importlib.util.spec_from_file_location("bne_replay", SCRIPT)
bne_replay = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(bne_replay)


def replay_bytes(records=None, *, command_offset=5):
    if records is None:
        records = [
            (bytes((0, 0, 3, 3, 3, 3, 3, 3)), 1, b"\x10\x02"),
            (bytes((0, 3, 3, 3, 3, 3, 3, 3)), 0, b"\x18\x01\x02\x03"),
        ]
    header = bytearray(bne_replay.HEADER_BYTES)
    header[0] = len(bne_replay.MAGIC)
    header[1:1 + len(bne_replay.MAGIC)] = bne_replay.MAGIC
    version = 1 + len(bne_replay.MAGIC)
    header[version:version + 2] = bytes((1, 1))
    header[bne_replay.MAP_NAME_OFFSET:bne_replay.MAP_NAME_OFFSET + 9] = b"Test.pud\0"
    header[bne_replay.PLAYER_COUNT_OFFSET] = 2
    header[bne_replay.PLAYER_NAMES_OFFSET:bne_replay.PLAYER_NAMES_OFFSET + 6] = b"Alice\0"
    second_name = bne_replay.PLAYER_NAMES_OFFSET + bne_replay.PLAYER_NAME_BYTES
    header[second_name:second_name + 4] = b"Bob\0"
    header[bne_replay.PLAYER_RACES_OFFSET:bne_replay.PLAYER_RACES_OFFSET + 8] = bytes((0, 1, 2, 2, 2, 2, 2, 2))
    header[bne_replay.PLAYER_CONTROLLERS_OFFSET:bne_replay.PLAYER_CONTROLLERS_OFFSET + 8] = bytes((0, 0, 3, 3, 3, 3, 3, 3))
    header[bne_replay.GAME_TYPE_OFFSET] = 2
    struct.pack_into("<I", header, bne_replay.RECORD_COUNT_OFFSET, len(records))
    struct.pack_into("<I", header, bne_replay.SNAPSHOT_OFFSET_OFFSET, 0)
    struct.pack_into("<I", header, bne_replay.COMMAND_STREAM_OFFSET_OFFSET, command_offset)
    stream = bytearray(b"state"[:command_offset])
    for status, player, packet in records:
        stream.extend(bne_replay.RECORD_PREFIX.pack(status, player, len(packet)))
        stream.extend(packet)
    decoded = header + stream
    decoded[
        bne_replay.CHECKSUM_IGNORED_OFFSET:
        bne_replay.CHECKSUM_IGNORED_OFFSET + bne_replay.CHECKSUM_IGNORED_BYTES
    ] = bytes(bne_replay.CHECKSUM_IGNORED_BYTES)
    decoded[bne_replay.CHECKSUM_OFFSET:bne_replay.CHECKSUM_OFFSET + 4] = bytes(4)
    checksum = (~zlib.crc32(decoded)) & 0xffffffff
    struct.pack_into("<I", decoded, bne_replay.CHECKSUM_OFFSET, checksum)
    return zlib.compress(decoded)


class ReplayParserTest(unittest.TestCase):

    def test_validates_header_checksum_snapshot_and_records(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "match.wir"
            path.write_bytes(replay_bytes())
            replay = bne_replay.parse_replay(path)
            summary = bne_replay.replay_summary(replay)
            self.assertEqual("Test.pud", summary["map"])
            self.assertEqual(["Alice", "Bob"], summary["players"])
            self.assertEqual(b"state", replay.snapshot)
            self.assertEqual(2, summary["record_count"])
            self.assertEqual(
                {"0": 1, "1": 1},
                summary["records_by_network_player"],
            )
            self.assertEqual(1, summary["slot_status_transitions"])
            self.assertEqual(b"\x10\x02", replay.records[0].packet)

    def test_rejects_checksum_corruption(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "match.wir"
            raw = bytearray(replay_bytes())
            raw[-1] ^= 1
            path.write_bytes(raw)
            with self.assertRaises((ValueError, zlib.error)):
                bne_replay.parse_replay(path)

    def test_rejects_trailing_command_data_even_with_valid_checksum(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "match.wir"
            compressed = replay_bytes()
            decoded = bytearray(zlib.decompress(compressed))
            decoded.extend(b"extra")
            decoded[
                bne_replay.CHECKSUM_IGNORED_OFFSET:
                bne_replay.CHECKSUM_IGNORED_OFFSET
                + bne_replay.CHECKSUM_IGNORED_BYTES
            ] = bytes(bne_replay.CHECKSUM_IGNORED_BYTES)
            decoded[bne_replay.CHECKSUM_OFFSET:bne_replay.CHECKSUM_OFFSET + 4] = bytes(4)
            checksum = (~zlib.crc32(decoded)) & 0xffffffff
            struct.pack_into("<I", decoded, bne_replay.CHECKSUM_OFFSET, checksum)
            path.write_bytes(zlib.compress(decoded))
            with self.assertRaisesRegex(ValueError, "trailing command bytes"):
                bne_replay.parse_replay(path)

    def test_writes_reproducible_inventory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "match.wir").write_bytes(replay_bytes())
            first = root / "one.json"
            second = root / "two.json"
            base = {
                "sources": [root],
                "collection_id": "test-pack",
                "source_url": "https://example.invalid/replays.zip",
                "archive_sha256": "a" * 64,
            }
            bne_replay.inventory_command(type("Args", (), {**base, "output": first})())
            bne_replay.inventory_command(type("Args", (), {**base, "output": second})())
            self.assertEqual(first.read_bytes(), second.read_bytes())
            inventory = json.loads(first.read_text())
            self.assertEqual(1, inventory["replay_count"])
            self.assertEqual("match.wir", inventory["entries"][0]["path"])

    def test_decodes_ordered_selection_and_command_context(self):
        records = [
            (bytes((0, 3, 3, 3, 3, 3, 3, 3)), 0,
             bytes.fromhex("1801020308032a00070063001012001000ffff")),
            (bytes((0, 3, 3, 3, 3, 3, 3, 3)), 0,
             bytes.fromhex("18020203137d001c00ffff08")),
        ]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "commands.wir"
            path.write_bytes(replay_bytes(records))
            replay = bne_replay.parse_replay(path)
            commands = bne_replay.decode_commands(replay)
            self.assertEqual(["selection", "move", "attack"],
                    [command.name for command in commands])
            self.assertEqual((42, 7, 99), commands[0].selected_unit_ids)
            self.assertEqual((42, 7, 99), commands[1].selected_unit_ids)
            self.assertEqual((42, 7, 99), commands[2].selected_unit_ids)
            self.assertEqual(2, bne_replay.command_summary(replay)[
                    "commands_with_multi_unit_selection"])

    def test_selection_limit_and_truncated_command_are_rejected(self):
        too_many = bytes.fromhex("18010203080a") + bytes(20)
        truncated = bytes.fromhex("18010203100102")
        for name, packet, message in (
                ("many", too_many, "retail limit"),
                ("short", truncated, "truncates embedded opcode")):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / f"{name}.wir"
                path.write_bytes(replay_bytes([
                    (bytes((0, 3, 3, 3, 3, 3, 3, 3)), 0, packet)
                ]))
                replay = bne_replay.parse_replay(path)
                with self.assertRaisesRegex(ValueError, message):
                    bne_replay.decode_commands(replay)

    def test_corpus_identity_covers_logical_name_and_compressed_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "one.wir").write_bytes(replay_bytes([
                (bytes((0, 3, 3, 3, 3, 3, 3, 3)), 0,
                 bytes.fromhex("1801020308012a001012001000ffff"))
            ]))
            args = type("Args", (), {
                "sources": [root], "expect_corpus_sha256": None
            })()
            import contextlib
            import io
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                bne_replay.corpus_command(args)
            first = json.loads(output.getvalue())
            self.assertEqual(1, first["replay_count"])
            self.assertEqual(2, first["embedded_command_count"])
            self.assertEqual(64, len(first["corpus_sha256"]))

            (root / "one.wir").rename(root / "renamed.wir")
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                bne_replay.corpus_command(args)
            self.assertNotEqual(first["corpus_sha256"],
                    json.loads(output.getvalue())["corpus_sha256"])


if __name__ == "__main__":
    unittest.main()
