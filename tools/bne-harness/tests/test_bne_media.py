import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest

SCRIPT = Path(__file__).parents[1] / "scripts" / "bne_media.py"
SPEC = importlib.util.spec_from_file_location("bne_media", SCRIPT)
bne_media = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(bne_media)


def raw_sector(payload: bytes, number: int = 0) -> bytes:
    assert len(payload) == 2048
    address = bytes((0, number // 75, number % 75))
    header = bne_media.CD_SYNC + address + b"\x01"
    return header + payload + bytes(2352 - len(header) - len(payload))


class Mode1ConversionTest(unittest.TestCase):

    def test_strips_raw_sector_headers_and_tails(self):
        first = bytes(index % 251 for index in range(2048))
        second = bytes((255 - index) % 251 for index in range(2048))
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "disc.iso"
            source = __import__("io").BytesIO(raw_sector(first) + raw_sector(second, 1))
            raw_sha, iso_sha, sectors = bne_media.convert_mode1(
                source, destination, "MODE1/2352")
            self.assertEqual(2, sectors)
            self.assertEqual(first + second, destination.read_bytes())
            self.assertEqual(hashlib.sha256(first + second).hexdigest(), iso_sha)
            self.assertEqual(
                hashlib.sha256(raw_sector(first) + raw_sector(second, 1)).hexdigest(),
                raw_sha,
            )

    def test_rejects_a_non_mode1_sector(self):
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "disc.iso"
            source = __import__("io").BytesIO(bytes(2352))
            with self.assertRaisesRegex(ValueError, "not a Mode-1"):
                bne_media.convert_mode1(source, destination, "MODE1/2352")


if __name__ == "__main__":
    unittest.main()
