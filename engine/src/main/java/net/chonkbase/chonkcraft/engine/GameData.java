package net.chonkbase.chonkcraft.engine;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.chonkbase.chonkcraft.data.GraphicsIndex;
import net.chonkbase.chonkcraft.data.NameTable;
import net.chonkbase.chonkcraft.data.source.ArchiveIds;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.data.source.EntryArchive;
import net.chonkbase.chonkcraft.data.graphic.GraphicDecoder;
import net.chonkbase.chonkcraft.data.graphic.ImageDecoder;
import net.chonkbase.chonkcraft.data.graphic.IndexedImage;
import net.chonkbase.chonkcraft.data.graphic.Palette;
import net.chonkbase.chonkcraft.data.graphic.TilesetDecoder;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.map.PudUnitTypes;
import net.chonkbase.chonkcraft.data.map.PudReader;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.campaign.Campaign;
import net.chonkbase.chonkcraft.engine.campaign.CampaignCatalog;
import net.chonkbase.chonkcraft.engine.construction.ConstructionCatalog;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.trigger.TriggerSystem;
import net.chonkbase.chonkcraft.engine.upgrade.AllowState;
import net.chonkbase.chonkcraft.engine.animation.AnimationCatalog;
import net.chonkbase.chonkcraft.engine.spell.SpellCatalog;
import net.chonkbase.chonkcraft.engine.sound.MusicPlayer;
import net.chonkbase.chonkcraft.engine.sound.SoundBank;
import net.chonkbase.chonkcraft.engine.unit.UnitTypeCatalog;
import net.chonkbase.chonkcraft.engine.unit.UnitRenderer;
import net.chonkbase.chonkcraft.engine.unit.UnitType;

/**
 * Loads the complete native game definition and the player's authenticated
 * Warcraft II assets.
 */
public final class GameData {

    /**
     * Which archive entries hold each tileset's palette, megatiles, and
     * minitiles, and which script defines its tile-code table. Entry indices
     * come from the {@code Todo} table at {@code wartool.h:280}.
     */
    private record TilesetSource(int palette, int megatiles, int minitiles) {}

    private static final Map<PudMap.Tileset, TilesetSource> TILESET_SOURCES =
            new EnumMap<>(PudMap.Tileset.class);

    static {
        TILESET_SOURCES.put(PudMap.Tileset.FOREST,
                new TilesetSource(2, 3, 4));
        TILESET_SOURCES.put(PudMap.Tileset.WASTELAND,
                new TilesetSource(10, 11, 12));
        TILESET_SOURCES.put(PudMap.Tileset.WINTER,
                new TilesetSource(18, 19, 20));
        // Swamp ships only with Beyond the Dark Portal, in a different entry
        // range; wired when the expansion path lands.
        TILESET_SOURCES.put(PudMap.Tileset.SWAMP,
                new TilesetSource(438, 439, 440));
    }

    /** A tileset ready to draw with: the code table plus its decoded sheet. */
    public record LoadedTileset(Tileset tileset, IndexedImage sheet, Palette palette,
            java.util.List<int[]> cyclingRanges) {

        /** Whether this tileset animates part of its palette. */
        public boolean cycles() {
            return cyclingRanges != null && !cyclingRanges.isEmpty();
        }
    }

    private final AssetSource source;

    private final EntryArchive main;
    private final NameTable names;
    private final GraphicsIndex graphics;
    private final Map<String, IndexedImage> spriteCache = new java.util.HashMap<>();
    private UnitTypeCatalog unitTypes;
    private AnimationCatalog animationSets;
    private net.chonkbase.chonkcraft.engine.upgrade.UpgradeCatalog upgradeCatalog;
    private SpellCatalog spellCatalog;
    private MusicPlayer music;
    private SoundBank sounds;

    /** Retail BNE settings owned directly by the native engine. */
    private volatile String damageMissile;
    private volatile int forestRegeneration;
    private volatile int speedFactor = 1;
    private volatile boolean trainingQueueEnabled = true;
    private volatile boolean deferredSettingsApplied = true;

    public String damageMissile() {
        return damageMissile;
    }

    public int forestRegeneration() {
        return forestRegeneration;
    }

    public int speedFactor() {
        return speedFactor;
    }

    public boolean trainingQueueEnabled() {
        return trainingQueueEnabled;
    }

    public boolean deferredSettingsApplied() {
        return deferredSettingsApplied;
    }

    /** Creates a game from one authenticated asset source. */
    public GameData(AssetSource source) {
        this.source = source;
        EntryArchive archive = source.archive(ArchiveIds.MAINDAT);
        if (archive == null) {
            throw new IllegalStateException("no maindat.war in " + source.describe());
        }
        this.main = archive;

        // Asset paths in the conversion table reference the game's string
        // table, so it has to be read before the index can be resolved.
        EntryArchive strings = source.archive(ArchiveIds.STRDAT);
        if (strings == null) {
            throw new IllegalStateException("no strdat.war in " + source.describe());
        }
        this.names = NameTable.from(strings.entry(1));
        this.graphics = GraphicsIndex.load(source.hasExpansion());
    }

    /** Where this game's data is being read from. */
    public AssetSource source() {
        return source;
    }

    /** Whether this installation has Beyond the Dark Portal. */
    public boolean hasExpansion() {
        return source.hasExpansion();
    }

    /** The game's string table. */
    public NameTable names() {
        return names;
    }

    /** The path-to-archive-entry index. */
    public GraphicsIndex graphics() {
        return graphics;
    }

    /**
     * Decodes the sprite sheet at an asset path, or {@code null} if the path
     * is not in the index.
     *
     * <p>Cached: a sheet costs a decompress plus a run-length decode, and the
     * same sprite is asked for once per unit on the map.
     */
    /**
     * Paths the game asked for and did not get.
     *
     * <p>The point of recording these is that a missing asset says nothing on
     * its own. Every resolver here answers null, every caller checks for null
     * and quietly draws nothing, and the result is a blank screen with no
     * error anywhere -- which is exactly how the campaigns came to open on
     * nothing for months. An image that cannot be found and a mission that has
     * no picture are indistinguishable at the call site; they are not
     * indistinguishable here.
     *
     * <p>Deliberately not a thrown exception. Some callers probe: they try a
     * path, take null for an answer and fall back, and that is legitimate. So
     * this is a record to be read rather than a failure to be handled, and it
     * is the record that makes the difference between "nothing was drawn" and
     * "nothing could be found to draw".
     */
    private final java.util.Set<String> unresolved =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Notes a path that resolved to nothing, and answers null for convenience. */
    private IndexedImage missing(String kind, String path) {
        unresolved.add(kind + " " + path);
        return null;
    }

    /** Every path asked for and not found, in the order they were first asked. */
    public java.util.List<String> unresolvedPaths() {
        java.util.List<String> sorted = new java.util.ArrayList<>(unresolved);
        java.util.Collections.sort(sorted);
        return sorted;
    }

    /** Forgets what has been asked for, so a test can measure one stretch. */
    public void clearUnresolvedPaths() {
        unresolved.clear();
    }

    public IndexedImage sprite(String path) {
        GraphicsIndex.Asset asset = graphics.find(path);
        if (asset == null) {
            return missing("sprite", path);
        }
        return spriteCache.computeIfAbsent(asset.path(), ignored -> {
            EntryArchive from = archiveFor(asset.archive());
            GraphicDecoder.Kind kind = asset.kind() == GraphicsIndex.Kind.GFU
                    ? GraphicDecoder.Kind.GFU
                    : GraphicDecoder.Kind.GFX;
            // A second entry continues the frame numbering from a given frame.
            // Only the worker sprites use it, for their carrying animations.
            byte[] secondEntry = asset.second() > 0 ? from.entry(asset.second()) : null;
            IndexedImage sheet =
                    GraphicDecoder.decode(kind, from.entry(asset.entry()), secondEntry, asset.fourth());

            // wartool folds index 0 into the transparent index when it writes
            // a sprite out (SavePNG with transparent set). A sprite's run-length
            // data uses both: explicit transparent runs, and palette-zero
            // pixels inside the frame that are meant to read as holes. Skipping
            // this leaves black blocks where a building's cut-outs should be.
            sheet.foldIndexZeroIntoTransparent();
            return sheet;
        });
    }

    /**
     * Decodes a flat interface image, or {@code null} if the path is unknown.
     *
     * <p>Separate from {@link #sprite} because these are not sprite sheets:
     * no frames, no transparency folding, and a different decoder.
     */
    public IndexedImage image(String path) {
        GraphicsIndex.Asset asset = graphics.find(path);
        if (asset == null
                || (asset.kind() != GraphicsIndex.Kind.IMAGE
                    && asset.kind() != GraphicsIndex.Kind.CURSOR)) {
            return missing("image", path);
        }
        return spriteCache.computeIfAbsent("image:" + asset.path(), ignored ->
                ImageDecoder.decode(archiveFor(asset.archive()).entry(asset.entry())));
    }

    /** Decoded widget pieces, by the path of the group they came from. */
    private final java.util.Map<String, java.util.Map<String,
            net.chonkbase.chonkcraft.data.graphic.WidgetSheet.Piece>> widgetCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * One interface widget, or null when the archive has no such piece.
     *
     * <p>The path is the group's path with the piece's name on the end, which
     * is how the scripts write it: {@code ui/human/widgets/button-large-normal}
     * is the sixteenth piece of the {@code ui/human/widgets} group. The implementation
     * used to answer nothing to any of these, because the conversion table's
     * {@code D} rows were parsed and then never decoded.
     */
    public IndexedImage widget(String path) {
        int cut = path.lastIndexOf('/');
        if (cut <= 0) {
            return missing("widget", path);
        }
        String group = path.substring(0, cut);
        String name = path.substring(cut + 1);
        GraphicsIndex.Asset asset = graphics.find(group);
        if (asset == null || asset.kind() != GraphicsIndex.Kind.WIDGETS) {
            return null;
        }
        return (IndexedImage) spriteCache.computeIfAbsent("widget:" + path, ignored -> {
            var pieces = net.chonkbase.chonkcraft.data.graphic.WidgetSheet.cut(widgetSheet(asset));
            return pieces.get(name);
        });
    }

    /**
     * The palette a widget is drawn with.
     *
     * <p>Not the group's own palette for most of them.
     * {@code ConvertGroupedGfu} swaps palettes once past the greyscale buttons
     * at the top of the sheet, so which palette a piece wants depends on how
     * far down the sheet it sits.
     */
    public Palette widgetPalette(String path) {
        int cut = path.lastIndexOf('/');
        if (cut <= 0) {
            return null;
        }
        String group = path.substring(0, cut);
        String name = path.substring(cut + 1);
        GraphicsIndex.Asset asset = graphics.find(group);
        if (asset == null || asset.kind() != GraphicsIndex.Kind.WIDGETS) {
            return null;
        }
        var list = net.chonkbase.chonkcraft.data.graphic.WidgetSheet.WIDGETS;
        int index = 0;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).name().equals(name)) {
                index = i;
                break;
            }
        }
        EntryArchive from = archiveFor(asset.archive());
        int entry = index >= net.chonkbase.chonkcraft.data.graphic.WidgetSheet.SECOND_PALETTE_FROM
                ? net.chonkbase.chonkcraft.data.graphic.WidgetSheet.SECOND_PALETTE_ENTRY
                : asset.palette();
        return Palette.fromVga(from.entry(entry));
    }

    /** The whole widget sheet, decoded once. */
    private IndexedImage widgetSheet(GraphicsIndex.Asset asset) {
        EntryArchive from = archiveFor(asset.archive());
        return GraphicDecoder.decode(GraphicDecoder.Kind.GFU,
                from.entry(asset.entry()), null, 0);
    }

    /** The palette an asset is drawn with. */
    public Palette paletteFor(String path) {
        GraphicsIndex.Asset asset = graphics.find(path);
        return asset == null ? null : Palette.fromVga(archiveFor(asset.archive()).entry(asset.palette()));
    }

    /**
     * An archive by id, opened on first use.
     *
     * <p>A handful of interface graphics live in {@code rezdat.war} rather
     * than {@code maindat.war}, so the archive an entry belongs to has to be
     * read from the conversion table rather than assumed.
     */
    private synchronized EntryArchive archiveFor(int archiveId) {
        EntryArchive open = source.archive(archiveId);
        if (open != null) {
            return open;
        }
        // Fall back to the main archive rather than failing: a release that
        // lacks an archive should lose those assets, not refuse to start.
        //
        // What that means in practice, since the caller goes on to ask for an
        // entry number: a DOS install missing snddat.war does not lose the
        // sound, it reads maindat's entry of the same number and gets a sprite
        // sheet where a wave file was asked for. Wrong, but it degrades to a
        // decoder shrugging rather than to a game that will not start, which is
        // what this implementation has always done and what every caller here is written
        // against. SoundBank is the one place that does not take this path: it
        // is handed the archives it may read and answers null for the rest.
        return main;
    }

    /** The main archive, for callers needing entries directly. */
    public EntryArchive mainArchive() {
        return main;
    }

    /** The complete native roster and animation bank committed in the JAR. */
    public synchronized UnitTypeCatalog unitTypes() {
        if (unitTypes != null) {
            return unitTypes;
        }
        animationSets = AnimationCatalog.generated();
        unitTypes = UnitTypeCatalog.generated(animationSets);
        return unitTypes;
    }

    /**
     * The sound bank, with every shipped name already bound.
     *
     * <p>Opens {@code sfxdat.sud} if this release has one. The DOS release
     * keeps its effects there and its music on the CD as audio tracks; later
     * releases use {@code snddat.war} instead, which is wired when that path
     * lands.
     *
     * <p>The names come from {@link SoundBindings}, not from
     * {@code scripts/sound.legacy-declaration}. This is the first subsystem taken off the
     * ChonkCraft checkout the engine could not previously boot without. The
     * migration differential compared every ordered binding before the legacy
     * loader was removed; the playing path now has no sound-script fallback.
     */
    public synchronized SoundBank sounds() {
        if (sounds != null) {
            return sounds;
        }
        sounds = new SoundBank(soundArchives(), graphics);
        net.chonkbase.chonkcraft.engine.sound.SoundBindings.install(
                sounds, graphics, source.isExpansionRelease());
        return sounds;
    }

    /**
     * What the critter is on this ground, in voice and in name.
     *
     * <p>Implements the tileset block in {@code scripts/scripts.legacy-declaration:31-55},
     * which this implementation had taken half of. The line above it -- the
     * {@code UnitTypeFiles} walk that picks each type's sprite for the tileset
     * -- was ported and is what {@code applyUnitTypeFiles} does. The four
     * lines below it were not, so the animal kept the placeholder name
     * {@code units.legacy-declaration} gives it and none of its sounds.
     *
     * <p>The player saw all three. Clicking the animal put "1 Critter" in the
     * status line where Warcraft II says "1 Sheep", played no sound at all,
     * and killing it played {@code explosion}.
     *
     * <p>Upstream redefines the type rather than looking the name up, so this
     * does too. The name is presentation and is never read by the simulation,
     * which is why setting it per mission cannot desynchronise anything.
     */
    private void applyTilesetCritter(String tilesetName) {
        String ground = scriptTilesetName(tilesetName);
        net.chonkbase.chonkcraft.engine.sound.SoundBindings.installForTileset(
                sounds(), graphics, ground);
        String name = switch (ground) {
            case "summer" -> "Sheep";
            case "winter" -> "Seal";
            case "wasteland" -> "Pig";
            case "swamp" -> "Warthog";
            default -> null;
        };
        if (name == null) {
            return;
        }
        UnitType critter = unitTypes().types().get("unit-critter");
        if (critter != null) {
            critter.setName(name);
        }
    }

    /**
     * What the scripts call the tileset a {@code .PUD} names.
     *
     * <p>They disagree on exactly one of the four, and it is the commonest.
     * Blizzard's map format calls the summer tileset {@code FOREST}; every
     * script keys its per-tileset data by {@code summer}. The other three --
     * winter, wasteland, swamp -- are spelled the same on both sides, which is
     * what makes the one exception easy to forget.
     *
     * <p>Written down once because it had already been written twice: in
     * {@code drawUnits} and in {@code buttonRelation}, each as its own inline
     * conditional. A third copy was about to be added for the critter and
     * would have been the first one to get it wrong.
     *
     * @return the script spelling, lower case; empty for no tileset
     */
    private static String scriptTilesetName(String tilesetName) {
        if (tilesetName == null) {
            return "";
        }
        String name = tilesetName.toLowerCase(java.util.Locale.ROOT);
        return "forest".equals(name) ? "summer" : name;
    }

    /** The archives sounds can come out of in this release. */
    private Map<Integer, EntryArchive> soundArchives() {
        // Sounds are spread across archives: interface clicks in maindat,
        // effects and voices in sfxdat, more in snddat on CD releases. Open
        // whichever this installation has.
        Map<Integer, EntryArchive> soundArchives = new java.util.LinkedHashMap<>();
        soundArchives.put(ArchiveIds.MAINDAT, main);
        for (int archiveId : List.of(ArchiveIds.SFXDAT, ArchiveIds.SNDDAT)) {
            // Asked of the source rather than of the archiveFor fallback above,
            // and that is the point of the map: a release without snddat.war
            // must leave the id out so that the bank records "archive 2000 is
            // not in this release" against those paths. Handing it the main
            // archive instead would have it read maindat entry 47 for a voice
            // line and hand the decoder a sprite sheet.
            EntryArchive archive = source.archive(archiveId);
            if (archive != null) {
                soundArchives.put(archiveId, archive);
            }
        }
        return soundArchives;
    }

    /**
     * The upgrades the game scripts define.
     *
     * <p>Loaded from {@code scripts/upgrade.legacy-declaration}, which pulls in the two race
     * files itself.
     */
    public synchronized net.chonkbase.chonkcraft.engine.upgrade.UpgradeCatalog upgrades() {
        if (upgradeCatalog == null) {
            upgradeCatalog = net.chonkbase.chonkcraft.engine.upgrade.UpgradeCatalog.generated();
        }
        return upgradeCatalog;
    }

    /**
     * Loads a campaign mission: its map, its rules and its briefing.
     *
     * <p>The two halves come from different places. The map is a PUD inside
     * {@code maindat.war}; the rules are a {@code .sms} script shipped with
     * ChonkCraft. The script's first line loads a {@code _c2.sms} that does not
     * exist in the checkout, because {@code wartool} generates it from the
     * same PUD this reads directly. Loading the PUD is that step, done a
     * shorter way, so the missing file is expected rather than a failure.
     *
     * @param mapPath the campaign map, such as {@code campaigns/human/level01h}
     * @param player  which slot the human plays
     */
    /**
     * Loads a mission for the player the map itself says is the person.
     *
     * <p>Not slot zero. On the original two campaigns the human is slot zero
     * and assuming it works by accident; the expansion puts them elsewhere,
     * and a trigger system evaluating "have I any units left" for a slot that
     * owns nothing declares defeat on its first evaluation. Every expansion
     * mission ended one second after it began, and the two campaigns where the
     * assumption happened to hold played perfectly, which is what made it look
     * like a problem with the expansion rather than with the assumption.
     */
    public Mission loadMission(String mapPath) {
        PudMap source = campaignMap(mapPath);
        return source == null ? null : loadMission(mapPath, personIn(source));
    }

    /** The slot a map means a person to play, or zero if it names none. */
    public static int personIn(PudMap source) {
        for (int i = 0; i < source.players().length; i++) {
            if (source.players()[i] == PudMap.PlayerType.PERSON) {
                return i;
            }
        }
        return 0;
    }

    public Mission loadMission(String mapPath, int player) {
        return loadMission(mapPath, player, World.DEFAULT_RANDOM_SEED);
    }

    /**
     * Loads a mission under the retail BNE simulation rules.
     *
     * @param initializationSeed seed used for both the disposable load stream
     *                           and cycle one's synchronized stream
     */
    public Mission loadMission(String mapPath, int player,
            int initializationSeed) {
        return loadNativeMission(mapPath, player, initializationSeed);
    }

    /** Constructs a campaign mission from the sealed native catalog. */
    private Mission loadNativeMission(String mapPath, int player,
            int initializationSeed) {
        net.chonkbase.chonkcraft.engine.campaign.MissionDefinition definition =
                net.chonkbase.chonkcraft.engine.campaign.MissionDefinitionCatalog.find(mapPath);
        if (definition == null) {
            throw new IllegalArgumentException("no native mission definition for " + mapPath);
        }
        PudMap source = campaignMap(mapPath);
        if (source == null) {
            return null;
        }
        applyBattleNetUnitTypeProfile(source, mapPath);
        GameMap map = GameMap.from(source, loadTileset(source.tileset()).tileset());
        applyTilesetCritter(map.tileset().name());
        Player[] players = Player.from(source);
        World world = new World(map, players, initializationSeed);
        configureWorld(world, source);

        populate(world, source);
        applyBattleNetCampaignRoster(world, mapPath);
        world.recalculateSupply();

        AllowState allowed = new AllowState();
        definition.allowFlags().forEach(allowed::define);
        world.setAllowed(allowed);
        world.applyResearchedAllows();

        net.chonkbase.chonkcraft.engine.trigger.TriggerSystem triggers =
                new net.chonkbase.chonkcraft.engine.trigger.TriggerSystem(
                        world, player, definition.triggers());
        world.enableAiForComputerPlayers();
        List<net.chonkbase.chonkcraft.engine.ai.AiAssignment> assignments =
                attachRetailAi(world, source, definition.requestedAi(),
                        definition.aiOrigins());
        world.fireBattleNetReadyForAll();

        net.chonkbase.chonkcraft.data.text.CampaignText.Mission text =
                missionTextFor(mapPath);
        String title = text == null ? null : text.title();
        String objectives = text == null ? null : String.join("\n", text.objectives());
        return new Mission(source, world, triggers, upgrades().dependencies(), allowed,
                title, objectives, briefingText(mapPath + ".txt"), definition.background(),
                definition.voices(), assignments);
    }

    /**
     * Applies the retail unit statistics carried by this particular PUD.
     *
     * <p>The oil patch and circle are indestructible in both engines, but
     * ChonkCraft expresses that with zero hit points while BNE stores one. ChonkCraft
     * gives some campaign heroes different values. BNE reads the complete
     * 110-entry hit-point array in {@code UDTA}, including mission-specific
     * overrides such as Orc X9's 180-HP daemon. Applying the table before
     * placement gives every instance the correct maximum and current value
     * during {@code CUnit::Init}. BNE also leaves the Human 13 wise-man at its
     * stock 90 hit points; the ChonkCraft script's later reduction is ignored
     * above in this profile.
     */
    private void applyBattleNetUnitTypeProfile(PudMap source, String mapPath) {
        Map<String, UnitType> types = unitTypes().types();
        if (source.unitData() != null && !source.unitData().useDefaults()) {
            for (int code = 0; code < source.unitData().hitPoints().length; code++) {
                int hitPoints = source.unitData().hitPoints(code);
                String ident = net.chonkbase.chonkcraft.data.map.PudUnitTypes.name(code);
                // Zero denotes an indestructible/special type, not a Java
                // unit whose current HP should begin dead.
                if (hitPoints > 0 && !ident.isEmpty()) {
                    setHitPoints(types, ident, hitPoints);
                }
            }
        }
        setHitPoints(types, "unit-oil-patch", 1);
        setHitPoints(types, "unit-circle-of-power", 1);
        // ChonkCraft's extended hero definition gives Korgath Bladefist 120 HP.
        // Retail BNE's stock table gives the sharp-axe slot 40; Orc 2 places
        // the hero without a UDTA override, making the stock distinction part
        // of the first authoritative frame.
        setHitPoints(types, "unit-sharp-axe", 40);
        // Beyond the Dark Portal's Human 9 campaign setup deliberately
        // starts its otherwise stock 5,000-HP runestones at 400 HP. This is
        // post-PUD campaign state, not a UDTA override.
        if ("campaigns/human-exp/levelx09h".equals(mapPath)) {
            setHitPoints(types, "unit-runestone", 400);
        }
    }

    /** Applies roster substitutions performed by BNE's campaign setup. */
    private void applyBattleNetCampaignRoster(World world, String mapPath) {
        switch (mapPath) {
            case "campaigns/human/level08h",
                    "campaigns/human/level10h" ->
                    transformPlacedUnits(world, 4, "unit-peasant", "unit-attack-peasant");
            case "campaigns/human-exp/levelx11h",
                    "campaigns/orc-exp/levelx09o" ->
                    transformPlacedUnits(world, 1, "unit-knight", "unit-paladin");
            default -> {
                // Most campaign PUD rosters enter play verbatim.
            }
        }
    }

    private void transformPlacedUnits(World world, int player,
            String fromIdent, String toIdent) {
        UnitType target = unitTypes().types().get(toIdent);
        if (target == null) {
            return;
        }
        for (net.chonkbase.chonkcraft.engine.unit.Unit unit : world.unitsSnapshot()) {
            if (unit.player() == player && unit.type() != null
                    && fromIdent.equals(unit.type().ident())) {
                world.transformInto(unit, target);
            }
        }
    }

    private static void setHitPoints(Map<String, UnitType> types,
            String ident, int hitPoints) {
        UnitType type = types.get(ident);
        if (type != null) {
            type.setHitPoints(hitPoints);
        }
    }

    /**
     * The prose behind a path a script names, or null.
     *
     * <p>Both the mission briefings and the campaign endings name their text
     * this way -- {@code campaigns/human/victory.txt} -- so the translation
     * from the script's spelling to the table's belongs somewhere both can
     * reach rather than inside the mission loader.
     */
    public String briefingText(String scriptPath) {
        return scriptPath == null ? null : text(briefingKey(scriptPath));
    }

    /**
     * The player kind a script names, or null if it names none.
     *
     * <p>The spellings are {@code PlayerTypeStrings},
     * which is what writes them.
     */
    public net.chonkbase.chonkcraft.data.text.CampaignText.Mission missionTextFor(String mapPath) {
        if (mapPath == null) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                // Retail expansion maps insert an x before the number
                // (levelx01h). Accepting only level01h made all 24 expansion
                // missions miss their own BNE text table and fall back to a
                // generic objective in the in-game menu.
                .compile("campaigns/(human|orc)(-exp)?/levelx?(\\d+)")
                .matcher(mapPath);
        if (!matcher.find()) {
            return null;
        }
        String race = matcher.group(1);
        boolean expansionCampaign = matcher.group(2) != null;
        int number = Integer.parseInt(matcher.group(3));
        return campaignText().mission(race, expansionCampaign ? number + 14 : number);
    }

    /**
     * The conversion table's name for a briefing the script names by file.
     *
     * <p>The script says {@code campaigns/human/level01h.txt}; the table says
     * {@code human/level01h}. Same text, two naming conventions, because one
     * describes where wartool would have written it and the other where it
     * came from.
     */
    private static String briefingKey(String path) {
        String key = path;
        if (key.endsWith(".txt")) {
            key = key.substring(0, key.length() - 4);
        }
        if (key.startsWith("campaigns/")) {
            key = key.substring("campaigns/".length());
        }
        return key;
    }

    /**
     * Native construction-stage presentation for the selected retail tileset.
     *
     * <p>A building going up is a sequence of stages rather than its own
     * sprite dimmed, and the stages are declared per tileset because the
     * scaffolding differs on snow.
     *
     * @param tilesetName which tileset's construction sprite to name
     */
    public synchronized ConstructionCatalog constructions(String tilesetName) {
        String key = tilesetName == null ? "summer" : tilesetName;
        return constructionCatalogs.computeIfAbsent(key, ConstructionCatalog::generated);
    }

    private final Map<String, ConstructionCatalog> constructionCatalogs = new LinkedHashMap<>();

    /**
     * A mouse pointer from the archive, with the hotspot the file gives.
     *
     * <p>Separate from {@link #image} because a cursor's header is not an
     * image's: it carries the hotspot before the dimensions, so reading one as
     * the other gets the size wrong and the picture with it.
     *
     * @param path the cursor's path, such as {@code human/cursors/human_gauntlet}
     * @return the pointer, or null if the table does not name it
     */
    public net.chonkbase.chonkcraft.data.graphic.CursorDecoder.Cursor cursor(String path) {
        GraphicsIndex.Asset asset = graphics().find(path);
        if (asset == null || asset.kind() != GraphicsIndex.Kind.CURSOR) {
            return null;
        }
        byte[] raw = archiveFor(asset.archive()).entry(asset.entry());
        return net.chonkbase.chonkcraft.data.graphic.CursorDecoder.looksLikeCursor(raw)
                ? net.chonkbase.chonkcraft.data.graphic.CursorDecoder.decode(raw)
                : null;
    }

    /**
     * A bitmap font from the archive.
     *
     * <p>The game ships five: {@code game} for the interface, {@code large}
     * and {@code small} for menus, and two for the episode titles.
     *
     * @param name the font's name, such as {@code game}
     * @return the decoded font, or null if the table does not name it
     */
    public synchronized net.chonkbase.chonkcraft.data.graphic.FontDecoder.Font font(String name) {
        net.chonkbase.chonkcraft.data.graphic.FontDecoder.Font cached = fonts.get(name);
        if (cached != null) {
            return cached;
        }
        GraphicsIndex.Asset asset = graphics().find(name);
        if (asset == null || asset.kind() != GraphicsIndex.Kind.FONT) {
            return null;
        }
        byte[] raw = archiveFor(asset.archive()).entry(asset.contentEntry());
        if (!net.chonkbase.chonkcraft.data.graphic.FontDecoder.looksLikeFont(raw)) {
            return null;
        }
        net.chonkbase.chonkcraft.data.graphic.FontDecoder.Font font =
                net.chonkbase.chonkcraft.data.graphic.FontDecoder.decode(raw);
        fonts.put(name, font);
        return font;
    }

    private final java.util.Map<String, net.chonkbase.chonkcraft.data.graphic.FontDecoder.Font>
            fonts = new java.util.HashMap<>();

    /**
     * The palette the fonts are drawn in.
     *
     * <p>Entry 2 of the main archive, which is what {@code ConvertFont} passes
     * and not the same palette the interface art uses. Drawing the glyphs
     * through the wrong one gives readable shapes in unreadable colours.
     */
    public Palette fontPalette() {
        return Palette.fromVga(main.entry(2));
    }

    /**
     * The projectile types, from {@code scripts/missiles.legacy-declaration}.
     *
     * <p>Thirty-five of them, and nothing read them until combat needed
     * travel time and splash.
     */
    public synchronized net.chonkbase.chonkcraft.engine.missile.MissileCatalog missiles() {
        if (missileCatalog == null) {
            missileCatalog = net.chonkbase.chonkcraft.engine.missile.MissileCatalog.generated();
        }
        return missileCatalog;
    }

    private net.chonkbase.chonkcraft.engine.missile.MissileCatalog missileCatalog;

    /**
     * The four campaigns the game ships.
     *
     * <p>The complete ordered declarations are committed in the JAR; no
     * campaign-menu script is evaluated at runtime.
     */
    public synchronized List<Campaign> campaigns() {
        if (campaigns != null) {
            return campaigns;
        }
        campaigns = CampaignCatalog.generated();
        return campaigns;
    }

    private List<Campaign> campaigns;

    /**
     * A campaign map, read straight out of the archive.
     *
     * <p>The 84 campaign levels live in {@code maindat.war} as PUDs, exactly
     * like the skirmish maps on disk, so nothing needs extracting to a file
     * first. {@code wartool} writes them out because LegacyEngine loads maps from
     * the filesystem; this implementation reads the archive directly and skips the step.
     *
     * @param path the campaign path, such as {@code campaigns/human/level01h}
     * @return the map, or null if the table does not name it
     */
    public PudMap campaignMap(String path) {
        GraphicsIndex.Asset asset = graphics().find(path);
        if (asset == null || asset.kind() != GraphicsIndex.Kind.MAP) {
            return null;
        }
        return PudReader.read(archiveFor(asset.archive()).entry(asset.contentEntry()));
    }

    /** Every campaign map the table names, in table order. */
    public List<String> campaignMapPaths() {
        List<String> paths = new java.util.ArrayList<>();
        for (GraphicsIndex.Asset asset : graphics().assets()) {
            if (asset.kind() == GraphicsIndex.Kind.MAP) {
                paths.add(asset.path());
            }
        }
        return paths;
    }

    /**
     * A cutscene, decoded from the disc.
     *
     * <p>The scripts name these with an {@code .ogv} suffix, because that is
     * what ChonkCraft converts them to. The conversion table names the same thing
     * without one, and what is actually in the archive is Smacker.
     *
     * @param path the video's path, such as {@code videos/human-1}
     * @return the video, or null if the table does not name it or the disc is
     *         not available
     */
    public net.chonkbase.chonkcraft.data.video.SmackerVideo video(String path) {
        String key = path;
        int dot = key.lastIndexOf('.');
        if (dot > key.lastIndexOf('/')) {
            key = key.substring(0, dot);
        }
        GraphicsIndex.Asset asset = graphics().find(key);
        if (asset == null || asset.kind() != GraphicsIndex.Kind.VIDEO) {
            missing("video", path);
            return null;
        }
        EntryArchive archive = archiveFor(asset.archive());
        if (archive == null) {
            missing("video", path);
            return null;
        }
        byte[] raw = archive.entry(asset.contentEntry());
        return net.chonkbase.chonkcraft.data.video.SmackerVideo.looksLikeSmacker(raw)
                ? net.chonkbase.chonkcraft.data.video.SmackerVideo.read(raw)
                : null;
    }

    /** Reads a briefing, credits, or victory text from its retail archive entry. */
    public String text(String path) {
        GraphicsIndex.Asset asset = graphics().find(path);
        if (asset == null || asset.kind() != GraphicsIndex.Kind.TEXT) {
            return null;
        }
        byte[] raw = archiveFor(asset.archive()).entry(asset.contentEntry());
        int offset = Math.min(Math.max(0, asset.entry()), raw.length);
        int end = offset;
        while (end < raw.length && raw[end] != 0) {
            end++;
        }
        return new String(raw, offset, end - offset,
                java.nio.charset.Charset.forName("IBM437"));
    }

    private boolean isExpansionRelease() {
        return source.isExpansionRelease();
    }

    /**
     * Whether this is the Battle.net edition.
     *
     * <p>Not the expansion question, and not derivable from it: the Battle.net
     * edition carries the expansion, so {@link #isExpansionRelease} folds it in
     * and cannot be asked to tell the two apart again. Three interface layout
     * scripts branch on it and use different menu art.
     *
     * <p>The probe that answers it -- three spellings of
     * {@code support/tomes/tome.1} -- lives in {@code InstallSource}, because a
     * pack has no directory to look in and has to have been told. It was here
     * until packs existed, and while it was, a packed Battle.net installation
     * quietly reported false and got the wrong art on three screens.
     */
    private boolean hasBattleNetTomes() {
        return source.isBattleNetEdition();
    }

    /**
     * The cell width of the game font.
     *
     * <p>{@code game_font_width = w / 15}, where w is the decoded sheet's
     * width and fifteen is how many glyphs a row holds. {@code fonts.legacy-declaration}
     * builds the font from it, so a wrong value cuts every letter in half.
     */
    private int gameFontWidth() {
        var decoded = font("game");
        if (decoded == null) {
            return 0;
        }
        return decoded.sheet().width() / net.chonkbase.chonkcraft.data.graphic.FontDecoder.PER_ROW;
    }

    /**
     * The command-panel buttons and the icons they draw.
     *
     * <p>Loaded from the two race button files and {@code scripts/icons.legacy-declaration}.
     * They come back together because neither is much use alone: a button
     * names an icon, and an icon on its own has nothing to sit in.
     *
     * @param tileset which tileset's icon sheet the frames index. The script
     *                branches on this, and left unset it skips the frame
     *                assignments entirely.
     */
    public synchronized Interface userInterface(String tileset) {
        if (userInterface != null) {
            return userInterface;
        }
        userInterface = new Interface(
                net.chonkbase.chonkcraft.engine.ui.GeneratedInterface.buttons(),
                net.chonkbase.chonkcraft.engine.ui.IconCatalog.generated());
        return userInterface;
    }

    /**
     * What plays before the menu.
     *
     * <p>The media are retail BNE pack assets. The first black frame is fixed
     * application presentation: ChonkCraft added {@code ui/black_title.png} in
     * 2019 as an OpenGL workaround, so asking the retail pack for it was both
     * an unresolved request and false provenance.
     */
    public synchronized java.util.List<
            net.chonkbase.chonkcraft.engine.ui.TitleSequence.Screen> titleScreens() {
        return net.chonkbase.chonkcraft.engine.ui.TitleSequence.battleNet();
    }

    /** Native field/minimap fog opacity policy. */
    public net.chonkbase.chonkcraft.engine.ui.FogOfWarSettings fogOfWar() {
        return net.chonkbase.chonkcraft.engine.ui.FogOfWarSettings.battleNet();
    }

    /**
     * The colour ramps each player's units are painted in.
     *
     * <p>Read from the prelude, which is where the game states them. Warcraft
     * II draws one sprite per unit type and swaps a short run of palette
     * entries for the owning player's ramp as it draws; a port that does not do
     * this draws every side in the same colour, which it did.
     */
    public net.chonkbase.chonkcraft.engine.ui.PlayerColours playerColours() {
        return net.chonkbase.chonkcraft.engine.ui.PlayerColours.battleNet();
    }

    private net.chonkbase.chonkcraft.data.text.CampaignText campaignText;

    /**
     * The mission titles and objectives.
     *
     * <p>Read from the archive rather than from the scripts, because the
     * scripts do not have them: {@code level01h_c.sms} passes two globals that
     * a file the extractor writes is supposed to have set.
     */
    public synchronized net.chonkbase.chonkcraft.data.text.CampaignText campaignText() {
        if (campaignText != null) {
            return campaignText;
        }
        GraphicsIndex.Asset asset = graphics.find("objectives");
        if (asset == null || asset.kind() != GraphicsIndex.Kind.CAMPAIGN_TEXT) {
            campaignText = net.chonkbase.chonkcraft.data.text.CampaignText.read(null, 0, false);
            return campaignText;
        }
        byte[] raw = archiveFor(asset.archive()).entry(asset.palette());
        // CampaignsCreate overrides the offset the table carries: 236 for the
        // expansion, 172 for the Spanish disc and 140 for everything else.
        // Getting it wrong does not fail, it shifts: the first mission's
        // objectives come back as a fragment of the binary before them and
        // every mission after it shows the previous one's text.
        boolean expansion = isExpansionRelease();
        int offset = source.campaignTextOffset();
        campaignText = net.chonkbase.chonkcraft.data.text.CampaignText.read(raw, offset, expansion);
        return campaignText;
    }

    /** The command panel's buttons and icon frames. */
    public record Interface(net.chonkbase.chonkcraft.engine.ui.ButtonSet buttons,
            net.chonkbase.chonkcraft.engine.ui.IconCatalog icons) {}

    /**
     * Which worker types may raise which buildings, read off the buttons.
     *
     * <p>{@code InitAiHelper}'s {@code ButtonCmd::Build} arm,
     * The game the building is the button's value and
     * the workers allowed to raise it are its {@code ForUnit} mask. Upstream
     * derives the relation this way and keeps it nowhere else, so this does
     * too, and hands the result to {@link World#setBuilders} rather than
     * leaving the engine to trust that the panel hid the button. A tanker has
     * one build button, for an oil platform, and that is the whole of what it
     * may build.
     *
     * @param tileset which tileset's button set to read; the files are the
     *                same either way, and this only picks the icon frames
     */
    public java.util.Map<String, java.util.Set<String>> buildRelation(PudMap.Tileset tileset) {
        return buttonRelation(tileset, "build");
    }

    /**
     * What trains what, from the same button table.
     *
     * <p>Upstream's {@code AiHelpers.Train()} is built from the
     * {@code train-unit} buttons exactly as its Build table is built from the
     * build ones, and {@code AiTrainUnit} only ever offers a training to a
     * trainer that table names. This implementation's AI walked its buildings and took
     * the first that would accept, and {@code World.orderTrain} asked nothing
     * about the pair -- so on {@code campaigns/human/level05h} the enemy's
     * oil tanker was trained at a pig farm while its shipyard stood idle.
     */
    public java.util.Map<String, java.util.Set<String>> trainRelation(PudMap.Tileset tileset) {
        return buttonRelation(tileset, "train-unit");
    }

    /**
     * What researches what, from the same button table.
     *
     * <p>{@code AiHelpers.Research()} is built from the {@code research}
     * buttons -- {@code upgrade-sword1}'s button carries
     * {@code ForUnit = {"unit-human-blacksmith"}} and that is the whole of
     * how upstream knows swords are a blacksmith's business. This implementation's AI
     * walked its buildings and took the first that would accept, so player
     * 2 on campaigns/orc-exp/levelx04o researched its weapon upgrades at
     * two pig farms while owning no blacksmith at all, where upstream's
     * request stands unpayable until the blacksmith exists.
     */
    public java.util.Map<String, java.util.Set<String>> researchRelation(PudMap.Tileset tileset) {
        return buttonRelation(tileset, "research");
    }

    private java.util.Map<String, java.util.Set<String>> buttonRelation(
            PudMap.Tileset tileset, String action) {
        java.util.Map<String, java.util.Set<String>> relation = new java.util.LinkedHashMap<>();
        Interface ui = userInterface(
                tileset == null ? "summer" : scriptTilesetName(tileset.name()));
        if (ui == null) {
            return relation;
        }
        for (net.chonkbase.chonkcraft.engine.ui.UnitButton button : ui.buttons().all()) {
            if (!action.equals(button.action()) || button.value() == null) {
                continue;
            }
            relation.computeIfAbsent(button.value(), ignored -> new java.util.LinkedHashSet<>())
                    .addAll(button.forUnits());
        }
        return relation;
    }

    private final java.util.Map<String, net.chonkbase.chonkcraft.engine.ui.UiLayout.Layout>
            layouts = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The sidebar layout for a race, read from the game's own interface script.
     *
     * <p>{@code scripts/<race>/ui.legacy-declaration} loads {@code ui_pandora.legacy-declaration}, which is
     * where every coordinate in the sidebar lives. Reading it rather than
     * repeating it is the difference between the implementation having the game's layout
     * and having a copy of it.
     *
     * @param race   human or orc
     * @param width  the screen size the layout is being computed for; several
     *               positions are measured back from the right edge
     * @param height likewise
     * @return the layout, or null when the scripts are not available
     */
    public net.chonkbase.chonkcraft.engine.ui.UiLayout.Layout uiLayout(
            String race, int width, int height) {
        String side = "orc".equalsIgnoreCase(race) ? "orc" : "human";
        return layouts.computeIfAbsent(side + ":" + width + "x" + height,
                ignored -> net.chonkbase.chonkcraft.engine.ui.UiLayout.battleNet(
                        side, width, height, this::hasArt));
    }

    /**
     * Whether a piece of interface art exists, for the script's
     * {@code CanAccessFile}.
     *
     * <p>The script names files; the index knows paths. The only difference is
     * the extension the extractor would have written.
     */
    private boolean hasArt(String file) {
        String path = file.endsWith(".png") ? file.substring(0, file.length() - 4) : file;
        return graphics.find(path) != null;
    }

    private Interface userInterface;

    /**
     * Installs the authenticated retail {@code ai.bin} profile for each active
     * computer slot. Script personality names are kept only as provenance for
     * diagnostics; no ChonkCraft AI program executes.
     */
    public List<net.chonkbase.chonkcraft.engine.ai.AiAssignment> attachRetailAi(
            net.chonkbase.chonkcraft.engine.World world, PudMap source,
            Map<Integer, String> fromMission) {
        return attachRetailAi(world, source, fromMission, Map.of());
    }

    private List<net.chonkbase.chonkcraft.engine.ai.AiAssignment> attachRetailAi(
            net.chonkbase.chonkcraft.engine.World world, PudMap source,
            Map<Integer, String> fromMission,
            Map<Integer, net.chonkbase.chonkcraft.engine.ai.AiAssignment.Origin> origins) {
        List<net.chonkbase.chonkcraft.engine.ai.AiAssignment> assignments =
                new java.util.ArrayList<>();
        byte[] bytecode = main.entry(277);
        for (var entry : new java.util.TreeMap<>(world.ais()).entrySet()) {
            int slot = entry.getKey();
            String requested;
            net.chonkbase.chonkcraft.engine.ai.AiAssignment.Origin origin;
            if (fromMission.containsKey(slot)) {
                requested = fromMission.get(slot);
                origin = origins.getOrDefault(slot,
                        net.chonkbase.chonkcraft.engine.ai.AiAssignment.Origin.MISSION_SCRIPT);
            } else if (source != null && slot < source.aiTypes().length) {
                requested = net.chonkbase.chonkcraft.engine.ai.AiTypeNames.name(
                        source.aiTypes()[slot]);
                origin = net.chonkbase.chonkcraft.engine.ai.AiAssignment.Origin.MAP;
            } else {
                requested = null;
                origin = net.chonkbase.chonkcraft.engine.ai.AiAssignment.Origin.MAP;
            }
            int profile = source != null && slot >= 0 && slot < source.aiTypes().length
                    ? source.aiTypes()[slot] : 0;
            entry.getValue().setUsePlan(false);
            entry.getValue().setBattleNetBuildProfile(bytecode, profile);
            entry.getValue().battleNetBootstrapBytecode(world);
            assignments.add(new net.chonkbase.chonkcraft.engine.ai.AiAssignment(
                    slot, requested, "retail-ai.bin:" + profile, origin));
        }
        return List.copyOf(assignments);
    }

    /** The music player, with the game's own tracks available. */
    public synchronized MusicPlayer music() {
        if (music == null) {
            music = new MusicPlayer(main, graphics);
        }
        return music;
    }

    /** The native spell catalog committed in the game JAR. */
    public synchronized SpellCatalog spells() {
        if (spellCatalog == null) {
            spellCatalog = SpellCatalog.generated();
        }
        return spellCatalog;
    }

    /** The animation sets the game scripts define. Loads the roster if needed. */
    public AnimationCatalog animationSets() {
        unitTypes();
        return animationSets;
    }

    /**
     * Places a map's units through the script's own {@code CreateUnit}.
     *
     * <p>Upstream a campaign map is three files. {@code <map>.sms} is generated
     * from the PUD by wartool and is nothing but a list of
     * {@code CreateUnit("unit-peasant", 3, {41, 15})} lines;
     * {@code <map>_c.sms} is written by hand and checked into ChonkCraft, and it
     * ends by {@code Load}ing the generated one. That ordering is not
     * incidental. It is a seam: the campaign script gets to redefine
     * {@code CreateUnit} before the units are placed and put it back
     * afterwards.
     *
     * <p>One mission in the game uses the seam, and it is the last of the
     * fifty-two that decided itself. {@code campaigns/human/level10h_c.sms}
     * ends:
     *
     * <pre>
     * local origCreateUnit = CreateUnit
     * function CreateUnit(type, player, pos)
     * -- Make the player 4 units attack peasants
     *   if player == 4 and type == "unit-peasant" then type = "unit-attack-peasant" end
     *   return origCreateUnit(type, player, pos)
     * end
     * Load("campaigns/human/level10h.sms")
     * CreateUnit = origCreateUnit
     * </pre>
     *
     * <p>The prisoners the mission is about are plain peasants in the PUD and
     * become Minutemen on the way in. Both of the mission's own triggers count
     * {@code unit-attack-peasant} -- rescue four of them near the circle of
     * power to win, let the count fall below four to lose -- so with the
     * substitution missing the count is zero, the defeat condition is true on
     * its first evaluation, and the mission is over before the player has
     * moved.
     *
     * <p>This implementation reads the PUD in Java rather than running a generated
     * script, so there is no file to load and nothing for the wrapper to wrap.
     * Rather than special-casing the one map, the {@code Load} of the
     * generated script is honoured as what it is: the moment the units appear.
     * Each PUD entry is handed to whatever {@code CreateUnit} is a global at
     * that instant, which is the wrapper if the script installed one and the
     * engine's own binding otherwise. Any future map that reaches for the same
     * seam gets it for free, which is the point of reproducing the mechanism
     * instead of the outcome.
     */
    /**
     * The human and orc equivalents of every convertible type, hand-carried
     * from the table {@code scripts/wc2.legacy-declaration:47-89} builds for its own
     * {@code CreateUnit} wrapper. The wrapper converts every load-time unit
     * to its owner's race, which is how a map drawn with one race's pieces
     * plays for whoever owns the slot; hand-ported the way the behavioural
     * scripts are generally, and worth re-checking against the file if the
     * upstream scripts ever move.
     */
    private static final String[][] RACE_EQUIVALENTS = {
        {"unit-town-hall", "unit-great-hall"},
        {"unit-keep", "unit-stronghold"},
        {"unit-castle", "unit-fortress"},
        {"unit-peasant", "unit-peon"},
        {"unit-elven-lumber-mill", "unit-troll-lumber-mill"},
        {"unit-human-blacksmith", "unit-orc-blacksmith"},
        {"unit-inventor", "unit-alchemist"},
        {"unit-stables", "unit-ogre-mound"},
        {"unit-church", "unit-altar-of-storms"},
        {"unit-mage-tower", "unit-temple-of-the-damned"},
        {"unit-gryphon-aviary", "unit-dragon-roost"},
        {"unit-human-barracks", "unit-orc-barracks"},
        {"unit-farm", "unit-pig-farm"},
        {"unit-yeoman", "unit-nomad"},
        {"unit-footman", "unit-grunt"},
        {"unit-archer", "unit-axethrower"},
        {"unit-ranger", "unit-berserker"},
        {"unit-knight", "unit-ogre"},
        {"unit-paladin", "unit-ogre-mage"},
        {"unit-mage", "unit-death-knight"},
        {"unit-dwarves", "unit-goblin-sappers"},
        {"unit-ballista", "unit-catapult"},
        {"unit-ballista-super", "unit-catapult-super"},
        {"unit-balloon", "unit-zeppelin"},
        {"unit-gryphon-rider", "unit-dragon"},
        {"unit-human-watch-tower", "unit-orc-watch-tower"},
        {"unit-human-guard-tower", "unit-orc-guard-tower"},
        {"unit-human-cannon-tower", "unit-orc-cannon-tower"},
        {"unit-human-guard-tower-super", "unit-orc-guard-tower-super"},
        {"unit-human-cannon-tower-super", "unit-orc-cannon-tower-super"},
        {"unit-caanoo-wiseman", "unit-caanoo-wiseskeleton"},
        {"unit-human-shipyard", "unit-orc-shipyard"},
        {"unit-human-refinery", "unit-orc-refinery"},
        {"unit-human-foundry", "unit-orc-foundry"},
        {"unit-human-oil-platform", "unit-orc-oil-platform"},
        {"unit-human-oil-tanker", "unit-orc-oil-tanker"},
        {"unit-human-submarine", "unit-orc-submarine"},
        {"unit-human-destroyer", "unit-orc-destroyer"},
        {"unit-battleship", "unit-ogre-juggernaught"},
        {"unit-human-transport", "unit-orc-transport"},
        {"unit-attack-peasant", "unit-skeleton"},
    };

    /** Wires {@code ConvertUnitType}'s two directions into a world. */
    public void applyRaceEquivalents(World world) {
        java.util.Map<String, String> toHuman = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> toOrc = new java.util.LinkedHashMap<>();
        for (String[] pair : RACE_EQUIVALENTS) {
            toHuman.put(pair[1], pair[0]);
            toOrc.put(pair[0], pair[1]);
        }
        world.setRaceEquivalents(toHuman, toOrc);
    }

    /**
     * {@code AiHelpers.Equiv()}, hand-carried from the two
     * {@code "unit-equiv"} {@code DefineAiHelper} blocks in
     * {@code scripts/legacyEngine.legacy-declaration:575-603} the way the race table above is
     * carried from {@code wc2.legacy-declaration}. Base type first, then everything the AI
     * accepts in its place: the upgraded halls answer for the plain ones and
     * the upgraded soldiers for their base types, which is what keeps a
     * script's {@code AiNeed(AiCityCenter())} from building a town hall in
     * the shadow of the player's own castle.
     */
    private static final java.util.Map<String, java.util.List<String>> AI_EQUIVALENTS =
            java.util.Map.of(
                    "unit-town-hall", java.util.List.of("unit-keep", "unit-castle"),
                    "unit-keep", java.util.List.of("unit-castle"),
                    "unit-archer", java.util.List.of("unit-ranger"),
                    "unit-knight", java.util.List.of("unit-paladin"),
                    "unit-great-hall", java.util.List.of("unit-stronghold", "unit-fortress"),
                    "unit-stronghold", java.util.List.of("unit-fortress"),
                    "unit-axethrower", java.util.List.of("unit-berserker"),
                    "unit-ogre", java.util.List.of("unit-ogre-mage"));

    /** Wires the AI's unit-type equivalences into a world. */
    public void applyAiEquivalents(World world) {
        world.setAiEquivalents(AI_EQUIVALENTS);
    }

    /**
     * Installs the complete BNE rules catalog on a newly created world.
     *
     * <p>Every game mode must cross this boundary before units are populated
     * or a save is restored. Keeping the catalog in one operation prevents a
     * screen from advertising a button whose simulation-side relationship or
     * price table was never installed.
     */
    public void configureWorld(World world, PudMap source) {
        if (source.unitData() != null) {
            world.setBattleNetUnitPriorities(source.unitData().priorities());
        }
        configureWorld(world, source.tileset());
    }

    /** Installs the map-independent catalog plus tileset-specific command relations. */
    public void configureWorld(World world, PudMap.Tileset tileset) {
        if (main.isValid(278)) {
            world.setBattleNetSequenceData(main.entry(278));
        }
        world.setUpgrades(upgrades().upgrades());
        world.setSpells(spells().spells());
        world.setMissileTypes(missiles().types());
        world.setDamageMissile(damageMissile());
        world.setTrainingQueueEnabled(trainingQueueEnabled());
        world.setUnitTypes(unitTypes().types());
        world.setBuilders(buildRelation(tileset));
        world.setTrainers(trainRelation(tileset));
        world.setResearchers(researchRelation(tileset));
        applyRaceEquivalents(world);
        applyAiEquivalents(world);
        world.setDependencies(upgrades().dependencies());
    }

    /** Installs every name-based command roster on a new command boundary. */
    public void configureCommands(
            net.chonkbase.chonkcraft.engine.network.CommandApplier commands) {
        commands.setUpgrades(upgrades().upgrades().all().keySet());
        commands.setSpells(spells().spells().all().keySet());
    }

    public int populate(World world, PudMap source) {
        UnitTypeCatalog roster = unitTypes();
        int placed = 0;
        for (PudMap.PudUnit entry : source.units()) {
            if (net.chonkbase.chonkcraft.data.map.PudUnitTypes.isStartLocation(entry.type())) {
                continue;
            }
            UnitType type = roster.types().get(entry.typeName());
            if (type == null) {
                continue;
            }
            // Beside the asked-for square when that square will not take it,
            // which is what CclCreateUnit does with every unit a map places.
            // Placing regardless put an oil platform on top of an oil tanker
            // on the second demo map and moved every ship the map placed
            // after it; skipping instead -- which is what this did when
            // createUnit answered null -- loses the unit altogether, so a map
            // that puts two things on one square keeps both upstream and one
            // here.
            net.chonkbase.chonkcraft.engine.unit.Unit made =
                    world.createUnitForMap(type, entry.player(), entry.x(), entry.y());
            if (made == null) {
                continue;
            }
            placed++;

            applyPudPlacementMetadata(made, entry);
        }
        return placed;
    }

    /** Applies the non-positional fields wartool emits after CreateUnit. */
    private static void applyPudPlacementMetadata(
            net.chonkbase.chonkcraft.engine.unit.Unit unit, PudMap.PudUnit entry) {
        // BNE's PUD loader: non-zero UNIT.Data subtracts the unit from the
        // AI-accounted family census (FUN_004175e0) and sets unit+0x5f bit 2.
        // The ready-pass uses the same marker on movable units to keep map
        // guard AI. Buildings store the raw word for the census filter even
        // when they do not take the ready-suppressed arm.
        unit.setBattleNetPudData(entry.data());
        if (entry.data() != 0 && unit.canMove()) {
            unit.setBattleNetReadySuppressed(true);
        }

        // How much the map says this mine or patch holds. The engine keeps a
        // deposit's remaining ore in its hit points, and wartool writes the
        // same arithmetic into generated scripts: SetResourcesHeld(unit,
        // Data * 2500).
        if (net.chonkbase.chonkcraft.data.map.PudUnitTypes.holdsResources(entry.type())
                && entry.resourcesHeld() > 0) {
            unit.setResourcesHeld(entry.resourcesHeld());
        }
    }

    /**
     * Draws every unit a map places onto a rendered scene.
     *
     * <p>Buildings go down before mobile units so a unit standing in a
     * doorway is not hidden behind it, which is the ordering the engine gets
     * from unit draw levels.
     *
     * @return how many units were drawn
     */
    public int drawUnits(IndexedImage scene, PudMap map) {
        UnitTypeCatalog roster = unitTypes();
        String tilesetName = scriptTilesetName(map.tileset().name());
        UnitRenderer renderer = new UnitRenderer(null);

        int drawn = 0;
        for (boolean buildings : new boolean[] {true, false}) {
            for (PudMap.PudUnit placed : map.units()) {
                UnitType type = roster.types().get(placed.typeName());
                if (type == null || type.building() != buildings) {
                    continue;
                }
                IndexedImage sheet = sprite(type.imageFileFor(tilesetName));
                if (sheet == null) {
                    continue;
                }
                renderer.draw(scene, sheet, type, placed.x(), placed.y(), 0);
                drawn++;
            }
        }
        return drawn;
    }

    /** Loads a tileset: evaluates its script and decodes its tile sheet. */
    public LoadedTileset loadTileset(PudMap.Tileset which) {
        TilesetSource source = TILESET_SOURCES.get(which);
        if (source == null) {
            throw new IllegalArgumentException("no source known for tileset " + which);
        }
        Palette palette = Palette.fromVga(main.entry(source.palette()));
        IndexedImage sheet = TilesetDecoder.decode(main.entry(source.minitiles()), main.entry(source.megatiles()));
        var nativeData = net.chonkbase.chonkcraft.engine.map.TilesetCatalog.create(which);
        return new LoadedTileset(nativeData.tileset(), sheet, palette, nativeData.cyclingRanges());
    }
}
