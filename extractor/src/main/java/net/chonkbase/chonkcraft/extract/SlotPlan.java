package net.chonkbase.chonkcraft.extract;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import net.chonkbase.assetpack.AssetKind;
import net.chonkbase.chonkcraft.data.GraphicsIndex;
import net.chonkbase.chonkcraft.data.source.EntryCodec;

/**
 * Decides what every archive entry is, before anything is read.
 *
 * <p>Warcraft II's archives are numbered and not named: entry 33 of
 * {@code maindat.war} is a sprite sheet and nothing in the file says whose,
 * or even that it is a sprite. The only record of what any of it means is the
 * conversion table in ChonkCraft's {@code wartool.h}, which this implementation ships as
 * {@code graphics-index.tsv}. This class turns that table inside out: the
 * table maps a name to an entry number, and a pack needs to go the other way,
 * from an entry number to what to call it and how to store it.
 *
 * <p>Two things make that harder than a loop.
 *
 * <p><b>One entry can be named twice.</b> The main menu music and the orc
 * briefing music are the same entry 429, and the swamp tileset has two rows,
 * one for the expansion and one stand-in for installations without it. The
 * first name wins, matching the order the table is processed in everywhere
 * else, and the losing names are recorded so the build can report them.
 *
 * <p><b>Most entries have no row at all.</b> Palettes are referenced by other
 * rows rather than named by their own, the second widget palette is a bare
 * constant in the engine, and a few hundred entries are simply not in the
 * table. Those get a name derived from where they sit, which is honest: the
 * number really is all that is known about them.
 */
public final class SlotPlan {

    /** What one entry becomes. */
    public record Slot(String id, AssetKind kind, EntryCodec.Form form, int paletteEntry) {

        /** Whether this entry is stored as-is because nothing better is known. */
        public boolean unnamed() {
            return form == EntryCodec.Form.RAW && kind == AssetKind.BINARY;
        }
    }

    /**
     * The second palette a widget sheet is drawn through.
     *
     * <p>{@code ConvertGroupedGfu} swaps palettes partway down the sheet, so
     * pieces from the fourth onward use {@code rezdat} entry 14 rather than
     * the one the row names. Nothing in the table records that, which is why
     * the number is here.
     */
    private static final int WIDGET_SECOND_PALETTE = 14;

    private final Map<Long, Slot> slots = new LinkedHashMap<>();
    private final Map<String, String> collisions = new LinkedHashMap<>();

    private SlotPlan() {
    }

    /** Plans every entry the conversion table names, plus the palettes they use. */
    public static SlotPlan from(GraphicsIndex index) {
        SlotPlan plan = new SlotPlan();
        for (GraphicsIndex.Asset asset : index.assets()) {
            plan.add(asset);
        }
        // The engine reads this one by number and the table never mentions it.
        plan.claim(3000, WIDGET_SECOND_PALETTE, "graphics/ui/widgets-palette",
                AssetKind.PALETTE, EntryCodec.Form.PALETTE, 0);
        return plan;
    }

    private void add(GraphicsIndex.Asset asset) {
        int archive = asset.archive();
        String path = asset.path();
        switch (asset.kind()) {
            case GFX -> {
                claim(archive, asset.entry(), "graphics/" + path,
                        AssetKind.SPRITE, EntryCodec.Form.SPRITE_GFX, asset.palette());
                if (asset.second() > 0) {
                    // The worker sprites carry their carrying-and-repairing
                    // animations in a second entry that continues the same
                    // frame numbering. It is a sheet in its own right and has
                    // to be stored as one, or the frames past the join are
                    // rebuilt from nothing.
                    claim(archive, asset.second(), "graphics/" + path + "-continued",
                            AssetKind.SPRITE, EntryCodec.Form.SPRITE_GFX, asset.palette());
                }
                palette(archive, asset.palette());
            }
            case GFU -> {
                claim(archive, asset.entry(), "graphics/" + path,
                        AssetKind.SPRITE, EntryCodec.Form.SPRITE_GFU, asset.palette());
                palette(archive, asset.palette());
            }
            case WIDGETS -> {
                claim(archive, asset.entry(), "graphics/" + path,
                        AssetKind.WIDGETS, EntryCodec.Form.SPRITE_GFU, asset.palette());
                palette(archive, asset.palette());
            }
            case IMAGE -> {
                claim(archive, asset.entry(), "graphics/" + path,
                        AssetKind.IMAGE, EntryCodec.Form.IMAGE, asset.palette());
                palette(archive, asset.palette());
            }
            case CURSOR -> {
                claim(archive, asset.entry(), "graphics/" + path,
                        AssetKind.CURSOR, EntryCodec.Form.CURSOR, asset.palette());
                palette(archive, asset.palette());
            }
            case FONT -> claim(archive, asset.contentEntry(), "fonts/" + path,
                    AssetKind.FONT, EntryCodec.Form.RAW, 0);
            case RGB -> palette(archive, asset.palette());
            case TILESET -> {
                palette(archive, asset.palette());
                claim(archive, asset.megatiles(), "graphics/tilesets/" + path + "/tiles",
                        AssetKind.TILE_TABLE, EntryCodec.Form.RAW, 0);
                claim(archive, asset.minitiles(), "graphics/tilesets/" + path + "/blocks",
                        AssetKind.TILE_ATLAS, EntryCodec.Form.TILE_ATLAS, asset.palette());
                if (asset.fourth() > 0) {
                    claim(archive, asset.fourth(), "graphics/tilesets/" + path + "/codes",
                            AssetKind.BINARY, EntryCodec.Form.RAW, 0);
                }
            }
            case SOUND -> claim(archive, asset.soundEntry(), "sounds/" + path,
                    AssetKind.SOUND, EntryCodec.Form.SOUND, 0);
            case MUSIC -> claim(archive, asset.musicEntry(), "music/xmi/" + path,
                    AssetKind.SEQUENCE, EntryCodec.Form.RAW, 0);
            case MAP -> claim(archive, asset.contentEntry(), "maps/campaign/" + path,
                    AssetKind.MAP, EntryCodec.Form.RAW, 0);
            case VIDEO -> claim(archive, asset.contentEntry(), path,
                    AssetKind.VIDEO, EntryCodec.Form.RAW, 0);
            case TEXT -> claim(archive, asset.contentEntry(), "text/" + path,
                    AssetKind.TEXT, EntryCodec.Form.RAW, 0);
            case CAMPAIGN_TEXT -> claim(archive, asset.palette(), "text/" + path,
                    AssetKind.TEXT, EntryCodec.Form.RAW, 0);
        }
    }

    private void palette(int archive, int entry) {
        if (entry <= 0) {
            return;
        }
        claim(archive, entry, "graphics/palettes/" + archive + "-" + entry,
                AssetKind.PALETTE, EntryCodec.Form.PALETTE, 0);
    }

    private void claim(int archive, int entry, String id, AssetKind kind,
            EntryCodec.Form form, int paletteEntry) {
        if (entry < 0) {
            return;
        }
        long key = key(archive, entry);
        Slot already = slots.get(key);
        if (already != null) {
            if (!already.id().equals(id)) {
                collisions.putIfAbsent(id, already.id());
            }
            return;
        }
        slots.put(key, new Slot(id, kind, form, paletteEntry));
    }

    /** What entry {@code entry} of archive {@code archive} is, or {@code null}. */
    public Slot slot(int archive, int entry) {
        return slots.get(key(archive, entry));
    }

    /**
     * A plan for an entry the table never mentions.
     *
     * <p>Named for where it sits, because the number really is all that is
     * known. Storing it raw rather than guessing is the whole point: a pack
     * that dropped what it could not identify would be missing the second
     * widget palette, the font palette and a few hundred other entries the
     * engine reads by number.
     */
    public static Slot unnamed(String archiveName, int entry) {
        return new Slot(String.format(Locale.ROOT, "archives/%s/%04d", archiveName, entry),
                AssetKind.BINARY, EntryCodec.Form.RAW, 0);
    }

    /** Names the table gives an entry that a different name got to first. */
    public Map<String, String> collisions() {
        return Map.copyOf(collisions);
    }

    /** How many entries the table accounts for. */
    public int size() {
        return slots.size();
    }

    private static long key(int archive, int entry) {
        return ((long) archive << 32) | (entry & 0xFFFFFFFFL);
    }
}
