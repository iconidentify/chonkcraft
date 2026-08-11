package net.chonkbase.chonkcraft.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests for the string table and its path substitution. */
class NameTableTest {

    /**
     * Builds a table: a count, then one 16-bit offset per slot, then the
     * NUL-terminated strings. Slot 0 holds the count, so entries start at 1.
     */
    private static NameTable table(String... strings) {
        int count = strings.length + 1;
        int headerBytes = count * 2;

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int[] offsets = new int[count];
        for (int i = 0; i < strings.length; i++) {
            offsets[i + 1] = headerBytes + body.size();
            body.writeBytes(strings[i].getBytes(StandardCharsets.ISO_8859_1));
            body.write(0);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(count & 0xFF);
        out.write((count >>> 8) & 0xFF);
        for (int i = 1; i < count; i++) {
            out.write(offsets[i] & 0xFF);
            out.write((offsets[i] >>> 8) & 0xFF);
        }
        out.writeBytes(body.toByteArray());
        return NameTable.from(out.toByteArray());
    }

    @Test
    void readsStringsByIndex() {
        NameTable names = table("Footman", "Grunt", "Peasant");
        assertEquals("Footman", names.name(1));
        assertEquals("Grunt", names.name(2));
        assertEquals("Peasant", names.name(3));
        // Index 0 is the count slot, not a string.
        assertEquals("", names.name(0));
        assertEquals("", names.name(99));
    }

    @Test
    void expandsAReferenceIntoAPathSegment() {
        NameTable names = table("Footman");
        assertEquals("human/units/footman", names.expand("human/units/%1"));
    }

    @Test
    void lowerCasesAndReplacesSpacesAndHyphens() {
        // %16 in the real table is "Dwarven Demolition Squad", which the
        // conversion table expects to become a single path segment.
        NameTable names = table("Dwarven Demolition Squad", "Ogre-Mage");
        assertEquals("human/units/dwarven_demolition_squad", names.expand("human/units/%1"));
        assertEquals("orc/units/ogre_mage", names.expand("orc/units/%2"));
    }

    @Test
    void aLeadingMinusTakesOnlyThePartAfterTheFirstSpace() {
        NameTable names = table("Troll Destroyer");
        assertEquals("destroyer", names.expand("%-1"));
        assertEquals("troll_destroyer", names.expand("%1"));
    }

    @Test
    void expandsSeveralReferencesAndKeepsLiteralText() {
        NameTable names = table("Peasant", "Peon");
        assertEquals("human/units/peasant_with_gold", names.expand("human/units/%1_with_gold"));
        assertEquals("peasant-and-peon", names.expand("%1-and-%2"));
    }

    @Test
    void aTemplateWithNoReferencePassesThrough() {
        NameTable names = table("Footman");
        assertEquals("ui/human/infopanel", names.expand("ui/human/infopanel"));
        assertTrue(!NameTable.hasReference("ui/human/infopanel"));
        assertTrue(NameTable.hasReference("human/units/%1"));
    }

    @Test
    void aStrayPercentIsKept() {
        NameTable names = table("Footman");
        assertEquals("100%", names.expand("100%"));
    }

    @Test
    void graphicsIndexUsesCanonicalPathsIndependentOfLocalizedNames() {
        // A localized STRDAT table is display copy. It must not rename the
        // identifiers that packs and game scripts use to find sprites.
        GraphicsIndex index = GraphicsIndex.load(table("Localized Unit"));
        assertTrue(index.size() > 300, "expected the full conversion table, got " + index.size());

        GraphicsIndex.Asset footman = index.find("human/units/footman");
        assertEquals(GraphicsIndex.Kind.GFX, footman.kind());
        assertEquals(45, footman.entry());

        // Tileset rows carry no reference and so resolve without the table.
        GraphicsIndex.Asset summer = index.find("summer/terrain/summer");
        assertEquals(GraphicsIndex.Kind.TILESET, summer.kind());
        assertEquals(2, summer.palette());
        assertEquals(3, summer.megatiles());
        assertEquals(4, summer.minitiles());
    }

    @Test
    void graphicsIndexAcceptsAPathWithOrWithoutItsExtension() {
        GraphicsIndex index = GraphicsIndex.load(table("Footman"));
        assertEquals(index.find("summer/terrain/summer"), index.find("summer/terrain/summer.png"));
        assertEquals(null, index.find("no/such/asset"));
        assertEquals(null, index.find(""));
    }

    @Test
    void graphicsIndexCarriesTheSecondEntryForWorkerSprites() {
        // The worker sheets continue their frame numbering into a second
        // archive entry, starting at a named frame.
        GraphicsIndex index = GraphicsIndex.load(false);
        GraphicsIndex.Asset carrying = index.find("human/units/peasant_with_wood");
        assertEquals(122, carrying.entry());
        assertEquals(47, carrying.second());
        assertEquals(25, carrying.fourth());
    }

    @Test
    void graphicsIndexHoldsEveryKind() {
        GraphicsIndex index = GraphicsIndex.load(table("Footman"));
        List<GraphicsIndex.Kind> kinds = index.assets().stream().map(GraphicsIndex.Asset::kind).distinct().toList();
        assertTrue(kinds.contains(GraphicsIndex.Kind.GFX));
        assertTrue(kinds.contains(GraphicsIndex.Kind.GFU));
        assertTrue(kinds.contains(GraphicsIndex.Kind.TILESET));
    }
}
