package net.chonkbase.chonkcraft.engine.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.chonkbase.chonkcraft.data.map.PudUnitTypes;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.perf.SimulationProfile;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Test;

/** Checks semantic Java type flags against the captured retail type table. */
class BattleNetAiRetailDataTest {

    private static final Set<Integer> GROUND = Set.of(
            0x00, 0x01, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09,
            0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10, 0x11,
            0x12, 0x13, 0x14, 0x15, 0x17, 0x18, 0x19, 0x2c,
            0x2e, 0x2f, 0x31, 0x32, 0x33, 0x34, 0x35, 0x37);
    private static final Set<Integer> NAVAL = Set.of(
            0x1e, 0x1f, 0x20, 0x21, 0x26, 0x27);
    private static final Set<Integer> AIR = Set.of(
            0x16, 0x23, 0x2a, 0x2b, 0x38);

    @Test
    void semanticDomainsMatchTheCapturedRetailTypeFlags() {
        GameData data = SimulationProfile.load();
        assumeTrue(data != null, "BNE asset pack and ChonkCraft source are required");
        Map<String, UnitType> types = data.unitTypes().types();
        List<String> mismatches = new ArrayList<>();

        for (int code = 0; code < PudUnitTypes.count(); code++) {
            String ident = PudUnitTypes.name(code);
            if (ident.isEmpty()) {
                continue;
            }
            UnitType type = types.get(ident);
            assumeTrue(type != null, "retail type is absent from the loaded data: " + ident);
            for (int predicate = 4; predicate <= 6; predicate++) {
                Set<Integer> expected = switch (predicate) {
                    case 4 -> GROUND;
                    case 5 -> NAVAL;
                    default -> AIR;
                };
                boolean actual = AiPlayer.battleNetCountsForForce(type, predicate);
                if (expected.contains(code) != actual) {
                    mismatches.add(ident + " p" + predicate + " expected="
                            + expected.contains(code) + " actual=" + actual
                            + " attack=" + type.canAttack() + " gather="
                            + type.canGather() + " building=" + type.building()
                            + " land=" + type.landUnit() + " sea="
                            + type.seaUnit() + " air=" + type.airUnit());
                }
            }
        }
        assertEquals(List.of(), mismatches);
    }
}
