package net.chonkbase.chonkcraft.data.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Unit tests for the PUD parser, over hand-assembled map files. */
class PudReaderTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    /** Appends a section: four-character tag, little-endian length, payload. */
    private void section(String tag, byte[] payload) {
        out.writeBytes(tag.getBytes(StandardCharsets.US_ASCII));
        writeLe32(payload.length);
        out.writeBytes(payload);
    }

    private void writeLe32(int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
    }

    private static byte[] le16(int... values) {
        byte[] data = new byte[values.length * 2];
        for (int i = 0; i < values.length; i++) {
            data[i * 2] = (byte) (values[i] & 0xFF);
            data[i * 2 + 1] = (byte) ((values[i] >>> 8) & 0xFF);
        }
        return data;
    }

    private static byte[] sixteen(int value) {
        byte[] data = new byte[16];
        java.util.Arrays.fill(data, (byte) value);
        return data;
    }

    /** The mandatory opening section. */
    private void type() {
        section("TYPE", "WAR2 MAP\0".getBytes(StandardCharsets.US_ASCII));
    }

    /** A minimal valid 2x2 map. */
    private void minimalBody() {
        section("DIM ", le16(2, 2));
        section("MTXM", le16(0x0050, 0x0051, 0x0052, 0x0053));
    }

    private PudMap read() {
        return PudReader.read(out.toByteArray());
    }

    // ---------------------------------------------------------------- header

    @Test
    void readsAMinimalMap() {
        type();
        minimalBody();
        PudMap map = read();

        assertEquals(2, map.width());
        assertEquals(2, map.height());
        assertArrayEquals(new int[] {0x0050, 0x0051, 0x0052, 0x0053}, map.tiles());
        assertEquals(0x0053, map.tileAt(1, 1));
    }

    @Test
    void rejectsAFileThatDoesNotStartWithType() {
        section("DIM ", le16(2, 2));
        PudFormatException error = assertThrows(PudFormatException.class, this::read);
        assertTrue(error.getMessage().contains("expected 'TYPE'"));
    }

    @Test
    void rejectsAWrongMagicString() {
        section("TYPE", "WAR1 MAP\0".getBytes(StandardCharsets.US_ASCII));
        minimalBody();
        PudFormatException error = assertThrows(PudFormatException.class, this::read);
        assertTrue(error.getMessage().contains("WAR2 MAP"));
    }

    @Test
    void rejectsAnUnknownSection() {
        type();
        minimalBody();
        section("XXXX", new byte[4]);
        PudFormatException error = assertThrows(PudFormatException.class, this::read);
        assertTrue(error.getMessage().contains("unknown section 'XXXX'"));
    }

    @Test
    void rejectsAMapWithNoTerrain() {
        type();
        section("DIM ", le16(2, 2));
        assertThrows(PudFormatException.class, this::read);
    }

    @Test
    void rejectsTerrainThatArrivesBeforeTheDimensions() {
        type();
        section("MTXM", le16(1, 2, 3, 4));
        PudFormatException error = assertThrows(PudFormatException.class, this::read);
        assertTrue(error.getMessage().contains("before DIM"));
    }

    // -------------------------------------------------------------- sections

    @Test
    void readsTheDescription() {
        type();
        section("DESC", "Garden of War\0".getBytes(StandardCharsets.US_ASCII));
        minimalBody();
        assertEquals("Garden of War", read().description());
    }

    @Test
    void anEmptyDescriptionBecomesUnnamed() {
        type();
        section("DESC", new byte[32]);
        minimalBody();
        assertEquals("(unnamed)", read().description());
    }

    @Test
    void readsPlayerTypesAndRaces() {
        type();
        byte[] owners = sixteen(3);
        owners[0] = 5; // person
        owners[1] = 4; // computer
        owners[15] = 2; // neutral
        section("OWNR", owners);
        byte[] sides = sixteen(2);
        sides[0] = 0; // human
        sides[1] = 1; // orc
        section("SIDE", sides);
        minimalBody();

        PudMap map = read();
        assertEquals(PudMap.PlayerType.PERSON, map.players()[0]);
        assertEquals(PudMap.PlayerType.COMPUTER, map.players()[1]);
        assertEquals(PudMap.PlayerType.NEUTRAL, map.players()[15]);
        assertEquals(PudMap.Race.HUMAN, map.races()[0]);
        assertEquals(PudMap.Race.ORC, map.races()[1]);
        assertEquals(2, map.playableSlots());
    }

    @Test
    void readsStartingResources() {
        type();
        int[] gold = new int[16];
        java.util.Arrays.fill(gold, 2000);
        gold[3] = 15000;
        section("SGLD", le16(gold));
        section("SLBR", le16(new int[16]));
        section("SOIL", le16(new int[16]));
        minimalBody();

        PudMap map = read();
        assertEquals(2000, map.startGold()[0]);
        assertEquals(15000, map.startGold()[3]);
    }

    @Test
    void readsBothSpellingsOfTheTilesetSection() {
        type();
        section("ERA ", new byte[] {2});
        minimalBody();
        assertEquals(PudMap.Tileset.WASTELAND, read().tileset());

        out.reset();
        type();
        // ERAX is the expansion's spelling and must be read identically.
        section("ERAX", new byte[] {1});
        minimalBody();
        assertEquals(PudMap.Tileset.WINTER, read().tileset());
    }

    @Test
    void readsPlacedUnits() {
        type();
        minimalBody();
        ByteArrayOutputStream units = new ByteArrayOutputStream();
        // x, y, type, player, data
        units.writeBytes(le16(12, 34));
        units.write(0x5C); // gold mine
        units.write(15);
        units.writeBytes(le16(20));
        units.writeBytes(le16(5, 6));
        units.write(0x00); // footman
        units.write(0);
        units.writeBytes(le16(0));
        section("UNIT", units.toByteArray());

        PudMap map = read();
        assertEquals(2, map.units().size());

        PudMap.PudUnit mine = map.units().get(0);
        assertEquals(12, mine.x());
        assertEquals(34, mine.y());
        assertEquals("unit-gold-mine", mine.typeName());
        assertEquals(15, mine.player());
        assertEquals(50_000, mine.resourcesHeld());
        assertTrue(PudUnitTypes.holdsResources(mine.type()));

        assertEquals("unit-footman", map.units().get(1).typeName());
    }

    @Test
    void findsStartLocations() {
        type();
        minimalBody();
        ByteArrayOutputStream units = new ByteArrayOutputStream();
        units.writeBytes(le16(40, 50));
        units.write(0x5E); // human start location
        units.write(1);
        units.writeBytes(le16(0));
        section("UNIT", units.toByteArray());

        PudMap map = read();
        assertArrayEquals(new int[] {40, 50}, map.startLocation(1));
        assertEquals(null, map.startLocation(2));
    }

    @Test
    void ignoresTheSectionsChonkCraftDoesNotUse() {
        type();
        minimalBody();
        for (String tag : new String[] {"VER ", "UDTA", "ALOW", "UGRD", "SQM ", "OILM", "REGM", "SIGN"}) {
            section(tag, new byte[8]);
        }
        assertEquals(2, read().width());
    }

    @Test
    void readsCombatStatsFromUnitData() {
        type();
        minimalBody();
        byte[] unitData = new byte[3988];
        unitData[0] = 1;
        int at = 1678 + 56 * 2; // daemon
        unitData[at] = (byte) 180;
        unitData[3878 + 98] = 40; // human cannon tower
        section("UDTA", unitData);

        PudMap map = read();
        assertTrue(map.unitData().useDefaults());
        assertEquals(180, map.unitData().hitPoints(56));
        assertEquals(0, map.unitData().hitPoints(110));
        assertEquals(40, map.unitData().priority(98));
        assertEquals(0, map.unitData().priority(110));
        assertEquals(0, map.unitData().gold(0),
                "a table shorter than the gold column must not invent a cost");
    }

    @Test
    void readsGoldAndLumberCostsFromUnitData() {
        type();
        minimalBody();
        byte[] unitData = new byte[3988];
        unitData[0] = 0;
        unitData[2008] = 60;       // footman time
        unitData[2008 + 4] = (byte) 250; // ballista time
        unitData[2118] = 60;       // footman gold tens
        unitData[2118 + 2] = 40;   // peasant gold tens
        unitData[2228 + 58] = 25;  // farm lumber tens
        unitData[2338 + 30] = 70;  // destroyer oil tens
        unitData[3658] = 2;        // footman armor
        unitData[3328] = 1;        // footman range
        unitData[3548] = 4;        // footman sight
        section("UDTA", unitData);

        PudMap map = read();
        assertTrue(!map.unitData().useDefaults(),
                "clearing the leading word marks a custom unit table");
        assertEquals(60, map.unitData().time(0),
                "footman time is the raw byte at UDTA 2008");
        assertEquals(250, map.unitData().time(4),
                "ballista time is the raw byte at UDTA 2008");
        assertEquals(600, map.unitData().gold(0),
                "footman gold is stored as tens at UDTA 2118");
        assertEquals(400, map.unitData().gold(2),
                "peasant gold is stored as tens at UDTA 2118");
        assertEquals(250, map.unitData().lumber(58),
                "farm lumber is stored as tens at UDTA 2228");
        assertEquals(0, map.unitData().oil(0),
                "a land unit stores no oil tens");
        assertEquals(700, map.unitData().oil(30),
                "destroyer oil is stored as tens at UDTA 2338");
        assertEquals(2, map.unitData().armor(0),
                "footman armor sits at UDTA 3658");
        assertEquals(1, map.unitData().attackRange(0),
                "footman range sits at UDTA 3328");
        assertEquals(4, map.unitData().sight(0),
                "footman sight sits at UDTA 3548");
        assertEquals(0, map.unitData().basicDamage(0),
                "a 3988-byte table ends before the basic-damage column");
    }

    @Test
    void readsCustomUpgradeGoldAndTimeFromUgrd() {
        type();
        minimalBody();
        byte[] upgrades = new byte[782];
        upgrades[0] = 0;
        // time[2] is one byte at offset 2 + 2
        upgrades[4] = 100;
        // gold[2] is a word at offset 54 + 2*2
        upgrades[58] = (byte) 500;
        upgrades[59] = 1;
        section("UGRD", upgrades);

        PudMap map = read();
        assertTrue(map.upgradeData() != null, "a 782-byte UGRD must be kept");
        assertTrue(!map.upgradeData().useDefaults(),
                "Great Wall-style maps clear the use-defaults word");
        assertEquals(100, map.upgradeData().time(2),
                "custom research time must come from the UGRD time column");
        assertEquals(500, map.upgradeData().gold(2),
                "custom research gold must come from the UGRD gold column");
    }

    // ----------------------------------------------------------------- walls

    @Test
    void derivesWallHitPointsFromTileCodes() {
        // Walls are terrain in Warcraft II, not units, so the engine has to
        // recover them from the tile code.
        assertEquals(40, PudMap.wallValue(0x00A0)); // orc open wall
        assertEquals(40, PudMap.wallValue(0x00C5)); // orc closed wall
        assertEquals(40, PudMap.wallValue(0x0912)); // orc wall boundary
        assertEquals(40, PudMap.wallValue(0x0090)); // human open wall
        assertEquals(40, PudMap.wallValue(0x00B3)); // human closed wall
        assertEquals(40, PudMap.wallValue(0x0834)); // human wall boundary
        assertEquals(0, PudMap.wallValue(0x0050));  // light grass
        assertEquals(0, PudMap.wallValue(0x0070));  // forest
    }

    @Test
    void exposesWallValuePerTile() {
        type();
        section("DIM ", le16(2, 1));
        section("MTXM", le16(0x0050, 0x00A0));
        PudMap map = read();
        assertEquals(0, map.wallValueAt(0, 0));
        assertEquals(40, map.wallValueAt(1, 0));
    }

    // ------------------------------------------------------------ unit table

    @Test
    void unitTypeTableMatchesTheKnownCodes() {
        assertEquals("unit-footman", PudUnitTypes.name(0));
        assertEquals("unit-grunt", PudUnitTypes.name(1));
        assertEquals("unit-human-oil-platform", PudUnitTypes.name(0x56));
        assertEquals("unit-orc-oil-platform", PudUnitTypes.name(0x57));
        assertEquals("unit-gold-mine", PudUnitTypes.name(0x5C));
        assertEquals("unit-oil-patch", PudUnitTypes.name(0x5D));
        assertEquals("unit-human-start-location", PudUnitTypes.name(0x5E));
        assertEquals("unit-orc-start-location", PudUnitTypes.name(0x5F));
        assertEquals("unit-orc-wall", PudUnitTypes.name(PudUnitTypes.count() - 1));
        // Unassigned codes exist in the original table and stay empty.
        assertEquals("", PudUnitTypes.name(34));
        assertEquals("", PudUnitTypes.name(9999));
    }
}
