from pathlib import Path
import sys
import unittest


ROOT = Path(__file__).parents[3]
SCRIPTS = Path(__file__).parents[1] / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import bne_combat_lifecycle as combat


REQUIREMENTS = ROOT / "tools/bne-harness/combat-lifecycle-requirements.json"


class CombatLifecycleTest(unittest.TestCase):

    @staticmethod
    def proof(rows):
        item = {"path": "evidence.json", "bytes": 1, "sha256": "a" * 64}
        return combat.seal_proof(rows, {
            "fixture": item, "native_receipt": item,
            "java_receipt": item, "scenario": item,
        })

    def test_matrix_has_every_player_visible_combat_domain(self):
        required = combat.load_requirements(REQUIREMENTS)
        self.assertEqual(9, required["encounters"])
        self.assertGreater(required["required_cells"], 150)
        observed = {item["encounter"] for item in required["cells"]}
        self.assertEqual({
            "melee-infantry", "ranged-infantry", "siege", "tower",
            "naval", "air", "direct-spell", "persistent-spell",
            "building-destruction",
        }, observed)

    def test_coverage_merges_certified_rows_from_two_proofs(self):
        required = combat.load_requirements(REQUIREMENTS)
        melee = [{
            **{key: item[key] for key in ("encounter", "stance", "phase")},
            "native_observed": True, "java_observed": True,
            "exact": True, "causal_order_exact": True,
        } for item in required["cells"] if item["encounter"] == "melee-infantry"
            and item["stance"] == "attack"]
        ranged = [{
            **{key: item[key] for key in ("encounter", "stance", "phase")},
            "native_observed": True, "java_observed": True,
            "exact": True, "causal_order_exact": True,
        } for item in required["cells"] if item["encounter"] == "ranged-infantry"
            and item["stance"] == "attack"
            and item["phase"] in {"acquire", "swing", "projectile-create",
                                  "projectile-flight", "damage", "retaliation"}]
        report = combat.coverage(required, self.proof(melee), self.proof(ranged))
        self.assertEqual(len(melee) + len(ranged), report["exact"])
        self.assertFalse(report["complete"])

    def test_generated_matrix_is_not_native_proof(self):
        required = combat.load_requirements(REQUIREMENTS)
        report = combat.coverage(required, self.proof([]))
        self.assertFalse(report["complete"])
        self.assertEqual(0, report["exact"])
        self.assertEqual(required["required_cells"], len(report["debts"]))

    def test_every_cell_needs_exact_causal_order(self):
        required = combat.load_requirements(REQUIREMENTS)
        rows = [{
            **{key: item[key] for key in ("encounter", "stance", "phase")},
            "native_observed": True,
            "java_observed": True,
            "exact": True,
            "causal_order_exact": False,
        } for item in required["cells"]]
        report = combat.coverage(required, self.proof(rows))
        self.assertFalse(report["complete"])
        self.assertTrue(all("causal-order-not-exact" in item["reasons"]
                            for item in report["debts"]))

    def test_proof_identity_rejects_a_changed_checkbox(self):
        proof = self.proof([])
        proof["rows"].append({"encounter": "melee-infantry",
                              "stance": "attack", "phase": "acquire"})
        with self.assertRaisesRegex(ValueError, "identity changed"):
            combat.validate_proof(proof)

    def test_lifecycle_derives_only_observed_exact_causal_phases(self):
        def result(side, changed=False):
            events = []
            for cycle, x, order, hp in (
                    (4, 10, "STILL", 60),
                    (5, 10, "ATTACK", 60),
                    (6, 11, "ATTACK", 60),
                    (7, 11, "ATTACK", 55)):
                events.extend([
                    {"cycle": cycle, "kind": "combat-state", "unit_id": 10,
                     "present": True, "alive": True, "on_map": True,
                     "x": x, "y": 2, "offset_x": x * 32,
                     "offset_y": 64, "order": order, "hit_points": 60,
                     "target_id": None, "missile_count": 0},
                    {"cycle": cycle, "kind": "combat-state", "unit_id": 20,
                     "present": True, "alive": True, "on_map": True,
                     "x": 12, "y": 2, "offset_x": 384,
                     "offset_y": 64, "order": "STILL",
                     "hit_points": hp - (1 if changed and cycle == 7 else 0),
                     "target_id": None, "missile_count": 0},
                ])
            return {"side": side, "events": events}

        rows = combat.derive_rows(
            result("native"), result("java"), encounter="melee-infantry",
            stance="attack", attacker=10, defender=20, issue_cycle=5,
            evidence_sha256="b" * 64)
        by_phase = {row["phase"]: row for row in rows}
        self.assertTrue(by_phase["acquire"]["causal_order_exact"])
        self.assertTrue(by_phase["chase"]["causal_order_exact"])
        self.assertTrue(by_phase["damage"]["causal_order_exact"])
        self.assertNotIn("swing", by_phase)

        divergent = combat.derive_rows(
            result("native"), result("java", changed=True),
            encounter="melee-infantry", stance="attack", attacker=10,
            defender=20, issue_cycle=5, evidence_sha256="c" * 64)
        self.assertFalse({row["phase"]: row for row in divergent}[
            "damage"]["exact"])

    def test_native_dying_order_is_the_death_boundary(self):
        def result(side, dying_cycle):
            events = []
            for cycle in range(4, 9):
                events.extend([
                    {"cycle": cycle, "kind": "combat-state", "unit_id": 10,
                     "present": True, "alive": True, "on_map": True,
                     "x": 10, "y": 2, "offset_x": 320, "offset_y": 64,
                     "order": "ATTACK", "hit_points": 60,
                     "target_id": None, "missile_count": 0},
                    {"cycle": cycle, "kind": "combat-state", "unit_id": 20,
                     "present": True, "alive": True, "on_map": True,
                     "x": 11, "y": 2, "offset_x": 352, "offset_y": 64,
                     "order": "DYING" if cycle >= dying_cycle else "ATTACK",
                     "hit_points": 4, "target_id": None,
                     "missile_count": 0},
                ])
            return {"side": side, "events": events}

        rows = combat.derive_rows(
            result("native", 7), result("java", 8),
            encounter="melee-infantry", stance="attack",
            attacker=10, defender=20, issue_cycle=5,
            evidence_sha256="d" * 64)
        death = {row["phase"]: row for row in rows}["death"]
        self.assertTrue(death["native_observed"])
        self.assertTrue(death["java_observed"])
        self.assertEqual(7, death["native_cycle"])
        self.assertEqual(8, death["java_cycle"])
        self.assertFalse(death["exact"])

    def test_damage_rng_diagnosis_names_the_crossed_consumers(self):
        # Both traces contain the same two legal LCG transitions, but damage
        # and idle spend them in opposite order.
        native = "\n".join([
            "# bne-trace event=async-random cycle=214 caller=00418412 "
            "before=3157976727 after=2458500932 result=4745",
            "# bne-trace event=async-random cycle=214 caller=0040AD58 "
            "before=2458500932 after=3501412629 result=20659",
        ])
        java = "\n".join([
            '{"schema":1,"side":"java","kind":"rng.async.draw",'
            '"cycle":213,"fields":{"before":3157976727,'
            '"after":2458500932,"result":4745,'
            '"caller":"BattleNetIdleSystem.dispatchBattleNetIdleMarker"}}',
            '{"schema":1,"side":"java","kind":"rng.async.draw",'
            '"cycle":216,"fields":{"before":2458500932,'
            '"after":3501412629,"result":20659,'
            '"caller":"World.battleNetMeleeDamage"}}',
        ])
        report = combat.damage_rng_diagnosis(native, java)
        self.assertFalse(report["exact"])
        self.assertEqual("damage-consumer-order-mismatch",
                         report["classification"])
        self.assertIn("IdleSystem", report[
            "java_consumer_of_native_damage_seed"]["caller"])
        self.assertEqual("0x0040ad58", report[
            "native_consumer_of_java_damage_seed"]["caller"])

    def test_fixed_constructor_damage_return_is_a_damage_consumer(self):
        # Human 13's first projectile debit is a tower arrow: native
        # 0x0041834b, Java battleNetProjectileDamage. The mobile return
        # 0x00418412 is a later axe. Both spend the same half-band helper.
        native = "\n".join([
            "# bne-trace event=async-random cycle=7 caller=0041834B "
            "before=2884522246 after=3072498239 result=14114",
            "# bne-trace event=async-random cycle=13 caller=00418412 "
            "before=3161131417 after=2548013742 result=6111",
        ])
        java = "\n".join([
            '{"schema":1,"side":"java","kind":"rng.async.draw",'
            '"cycle":9,"fields":{"before":2884522246,'
            '"after":3072498239,"result":14114,'
            '"caller":"BattleNetProjectileSystem.battleNetProjectileDamage"}}',
            '{"schema":1,"side":"java","kind":"rng.async.draw",'
            '"cycle":15,"fields":{"before":3161131417,'
            '"after":2548013742,"result":6111,'
            '"caller":"BattleNetProjectileSystem.battleNetProjectileDamage"}}',
        ])
        report = combat.damage_rng_diagnosis(native, java)
        self.assertTrue(report["exact"],
                        "the first constructor debit is the same transition")
        self.assertEqual("exact-damage-consumer", report["classification"])
        self.assertEqual("0x0041834b", report["native_damage"]["caller"])

    def test_projectile_phases_require_the_commanded_attacker(self):
        def result(side, create=20, move=21, source=10):
            events = []
            for cycle in range(4, 24):
                events.extend([
                    {"cycle": cycle, "kind": "combat-state", "unit_id": 10,
                     "present": True, "alive": True, "on_map": True,
                     "x": 1, "y": 1, "offset_x": 32, "offset_y": 32,
                     "order": "ATTACK", "hit_points": 40,
                     "target_id": None, "missile_count": int(cycle >= create)},
                    {"cycle": cycle, "kind": "combat-state", "unit_id": 20,
                     "present": True, "alive": True, "on_map": True,
                     "x": 5, "y": 1, "offset_x": 160, "offset_y": 32,
                     "order": "STILL", "hit_points": 40,
                     "target_id": None, "missile_count": int(cycle >= create)},
                ])
            events.extend([
                {"cycle": create, "kind": "combat-projectile",
                 "projectile_id": "local", "present": True,
                 "source_id": source, "target_id": 20, "type_code": 15,
                 "x": 40, "y": 40, "frame": 0, "remaining": 96},
                {"cycle": move, "kind": "combat-projectile",
                 "projectile_id": "local", "present": True,
                 "source_id": source, "target_id": 20, "type_code": 15,
                 "x": 52, "y": 40, "frame": 0, "remaining": 84},
                {"cycle": 22, "kind": "combat-projectile",
                 "projectile_id": "local", "present": False,
                 "source_id": source, "target_id": 20, "type_code": 15,
                 "x": 52, "y": 40, "frame": 0, "remaining": -1},
            ])
            return {"side": side, "events": events}

        rows = combat.derive_rows(
            result("native"), result("java"), encounter="ranged-infantry",
            stance="attack", attacker=10, defender=20, issue_cycle=5,
            evidence_sha256="e" * 64)
        phases = {row["phase"]: row for row in rows}
        self.assertTrue(phases["projectile-create"]["causal_order_exact"])
        self.assertTrue(phases["projectile-flight"]["causal_order_exact"])
        self.assertTrue(phases["impact"]["causal_order_exact"])

        native_table = result("native")
        java_row = result("java")
        for event in native_table["events"]:
            if event.get("kind") == "combat-projectile":
                event["frame"] = 10
        framed = combat.derive_rows(
            native_table, java_row, encounter="ranged-infantry",
            stance="attack", attacker=10, defender=20, issue_cycle=5,
            evidence_sha256="e2" * 32)
        self.assertTrue({row["phase"]: row for row in framed}[
            "projectile-create"]["causal_order_exact"],
            "native +0x09 table values and Java animation rows are not a "
            "combat mismatch")

        absent = combat.derive_rows(
            result("native", source=99), result("java"),
            encounter="ranged-infantry", stance="attack", attacker=10,
            defender=20, issue_cycle=5, evidence_sha256="f" * 64)
        projectile = {row["phase"]: row for row in absent}[
            "projectile-create"]
        self.assertFalse(projectile["native_observed"])
        self.assertFalse(projectile["exact"])


if __name__ == "__main__":
    unittest.main()
