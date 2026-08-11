from pathlib import Path
import sys
import unittest


SCRIPTS = Path(__file__).parents[1] / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import bne_causal


class BneCausalTest(unittest.TestCase):

    def test_native_zero_padded_values_are_decimal(self):
        fields = bne_causal.parse_fields("route=0040,0008 caller=00423555")
        self.assertEqual([40, 8], fields["route"])
        self.assertEqual("0x00423555", fields["caller"])

    def test_normalizes_native_and_java_path_events(self):
        native = bne_causal.parse_native_trace(
            "# bne-trace event=unit-route cycle=24 unit=17 result=FOUND path=10\n"
        )
        java = bne_causal.parse_java_trace(
            "JBNEPATH cycle=24 unit=17 result=FOUND path=10\n"
        )
        self.assertEqual("path.route", native[0].kind)
        self.assertEqual("path.route", java[0].kind)
        alignment = bne_causal.align_events(native, java)
        self.assertIsNone(alignment["first_divergence"])
        self.assertEqual(1, alignment["matched"])

    def test_finds_native_only_rng_draw_before_visible_state(self):
        native = bne_causal.parse_native_trace("""\
# bne-trace event=unit-route cycle=4 unit=17 result=FOUND path=10
# bne-trace event=sync-random cycle=23 caller=0x00400000 before=1 after=1103527590 result=16838
""")
        java = bne_causal.parse_java_trace(
            "JBNEPATH cycle=4 unit=17 result=FOUND path=10\n"
        )
        alignment = bne_causal.align_events(native, java)
        first = alignment["first_divergence"]
        self.assertEqual("native-only", first["op"])
        self.assertEqual("rng.sync.draw", first["native"]["kind"])
        self.assertEqual(23, first["native"]["cycle"])

    def test_distinguishes_sync_seed_from_sync_draw(self):
        events = bne_causal.parse_native_trace("""\
# bne-trace event=master-seed-call observed=1 applied=1 deterministic=true
# bne-trace event=sync-random cycle=1 caller=0x00400000 before=1 after=1103527590 result=16838
""")
        self.assertEqual(["rng.sync.seed", "rng.sync.draw"],
                         [event.kind for event in events])
        self.assertEqual(16838, events[1].fields["result"])

    def test_packet_observations_report_field_difference(self):
        packet = {"semantic": {"24": {"focus": [{
            "native_slot": 17, "java_id": 3,
            "oracle": {"x": 40, "y": 8, "order": "resource"},
            "java": {"x": 41, "y": 9, "order": "resource"},
        }]}}}
        native, java = bne_causal.events_from_packet(packet)
        first = bne_causal.align_events(native, java)["first_divergence"]
        self.assertEqual("mismatch", first["op"])
        self.assertEqual({"native": 40, "java": 41}, first["differences"]["x"])
        self.assertEqual({"native": 8, "java": 9}, first["differences"]["y"])


if __name__ == "__main__":
    unittest.main()
