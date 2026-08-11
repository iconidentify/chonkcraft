package net.chonkbase.chonkcraft.engine.unit;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A kind of unit: the stats and presentation shared by every instance.
 *
 * <p>Implements {@code CUnitType} from {@code src/include/unittype.h}, holding
 * what {@code DefineUnitType} sets. Mutable during loading and effectively
 * frozen afterwards, matching how the engine treats it.
 */
public final class UnitType {

    /**
     * Which of the three domains a type moves in.
     *
     * <p>{@code EMovement} in {@code src/include/unittype.h:662}, set from the
     * {@code Type} key of {@code DefineUnitType}
     * and the sole input to the movement
     * mask {@code UpdateUnitStats} works out.
     */
    public enum Movement {
        LAND, FLY, NAVAL;

        /** Resolves a script's {@code Type} value, or {@code null}. */
        public static Movement byName(String name) {
            return switch (name) {
                case "land" -> LAND;
                case "fly" -> FLY;
                case "naval" -> NAVAL;
                default -> null;
            };
        }
    }

    /** The resources a cost or storage table can name. */
    public enum Resource {
        TIME, GOLD, WOOD, OIL;

        /** Resolves a script's resource name, or {@code null}. */
        public static Resource byName(String name) {
            return switch (name) {
                case "time" -> TIME;
                case "gold" -> GOLD;
                case "wood" -> WOOD;
                case "oil" -> OIL;
                default -> null;
            };
        }
    }

    private final String ident;

    // Presentation.
    private String name = "";
    private String imageFile = "";
    private int imageWidth;
    private int imageHeight;
    private String animations = "";

    /**
     * How many facings the sprite has, or zero if the script did not say.
     *
     * <p>Eight for a Warcraft II unit and one for a building, which is the
     * guess makes for a type that omits it:
     * {@code type->NumDirections = type->Building ? 1 : 8}. Defaulting to
     * eight for everything, as this did, makes a building's sheet index its
     * animation frame <em>plus its heading</em> -- and every building is built
     * facing south, which is heading four. That is invisible for the
     * fifty-three buildings whose sheets hold two frames, because four modulo
     * two is zero, and wrong for the two oil platforms, which hold three: the
     * animation asks for frame zero and the renderer draws frame one.
     */
    private int numDirections;

    /** The animation set {@link #animations} names, resolved after loading. */
    private net.chonkbase.chonkcraft.engine.animation.AnimationSet animationSet;
    private String icon = "";
    private String construction = "";
    private String corpse = "";
    private int drawLevel;
    private int tileWidth = 1;
    private int tileHeight = 1;
    private int boxWidth;
    private int boxHeight;
    private boolean elevated;
    private String shadowFile = "";

    // Simulation.
    private int hitPoints;
    private int mana;
    private int manaStart = -1;
    private int armor;
    private int basicDamage;
    private int piercingDamage;
    private int sightRange;
    private int maxAttackRange;
    private int minAttackRange;
    private int speed;
    private int priority;
    private int points;
    private int demand;
    private int supply;
    private int level;
    private int repairHp;

    /**
     * How far away this unit can repair, zero meaning it cannot.
     *
     * <p>Kept separate from {@link #repairHp} because the two answer different
     * questions: how much a tick of repair restores, and whether the unit is a
     * repairer at all. Only workers carry a repair range, and it is what
     * upstream tests to decide whether the repair button appears.
     */
    private int repairRange;

    /**
     * How far this type looks for a fight when it has nothing else to do.
     *
     * <p>Two values, because Warcraft II gives the computer a longer leash
     * than the player: a footman owned by a person reacts at four tiles and
     * the same footman owned by the computer reacts at six. The scripts write
     * these as {@code PersonReactionRange} and {@code ComputerReactionRange},
     * and 61 of the unit types carry them.
     *
     * <p>Zero means the type never starts a fight on its own, which is right
     * for peasants and for every building that is not a tower.
     */
    /**
     * The projectile this type fires, or {@code missile-none} for melee.
     *
     * <p>Sixty-six of the shipped types name {@code missile-none} and strike
     * directly; the rest name something that has to cross the ground first.
     */
    private String missile = net.chonkbase.chonkcraft.engine.missile.MissileType.NONE;

    private int reactRangePerson;
    private int reactRangeComputer;
    private int randomMovementProbability;
    private int randomMovementDistance = 1;
    private String unitTypeClass = "";

    /** How many units this can carry. Zero means it is not a transport. */
    private int maxOnBoard;

    /** Consecutive dismiss clicks required to detonate this type; zero disables it. */
    private int clicksToExplode;

    /** Packed {@code 0xRRGGBB} minimap colour for a neutral instance, or -1. */
    private int neutralMinimapColour = -1;

    /** Which kinds it may carry, as the script's flag names. */
    private final java.util.List<String> canTransport = new java.util.ArrayList<>();

    // Behaviour flags.
    private boolean building;

    /**
     * Whether the player keeps seeing this where it was after looking away.
     *
     * <p>{@code VisibleUnderFog} in {@code units.legacy-declaration}, and the flag upstream's
     * {@code UnitGoesUnderFog} tests before it takes a snapshot. Every
     * building in the shipped data sets it and nothing else does, which is
     * what makes a scouted enemy town stay on your map while the units that
     * garrison it do not.
     */
    private boolean visibleUnderFog;
    private boolean landUnit;
    private boolean seaUnit;
    private boolean airUnit;
    private boolean shoreBuilding;
    private boolean builderOutside;
    private boolean canAttack;
    private boolean canTargetLand;
    private boolean canTargetSea;
    private boolean canTargetAir;
    private boolean selectableByRectangle;
    /** The missile a dying unit leaves; empty for the types that leave none. */
    private String explosion = "";
    private boolean coward;
    private boolean detectCloak;
    /** Whether enemies need cloak detection to see this unit. */
    private boolean permanentCloak;
    /** Ephemeral scenery and revealers do not occupy map squares. */
    private boolean vanishes;
    /** An invisible helper whose only purpose is to provide sight. */
    private boolean revealer;
    /** Whether other units may share this unit's footprint. */
    private boolean nonSolid;
    private boolean groundAttack;
    private boolean sideAttack;
    private String rightMouseAction = "";

    /**
     * Sprite file per tileset name, for types that look different on
     * different terrain.
     *
     * <p>Buildings and neutral objects declare {@code Image = {"size", {...}}}
     * with no file, because a farm in the winter tileset is a different
     * picture from a farm in the forest one. The script publishes the real
     * paths through the global {@code UnitTypeFiles} table instead, keyed by
     * tileset. Populated by {@code UnitTypeScript.applyUnitTypeFiles}.
     */
    private final Map<String, String> tilesetImageFiles = new LinkedHashMap<>();

    /** How this type gathers each resource it can, if any. */
    private final Map<Resource, ResourceInfo> gathering = new EnumMap<>(Resource.class);

    /** Resources this type accepts as a drop-off point. */
    private final java.util.EnumSet<Resource> stores = java.util.EnumSet.noneOf(Resource.class);

    private final Map<Resource, Integer> costs = new EnumMap<>(Resource.class);
    private final Map<Resource, Integer> repairCosts = new EnumMap<>(Resource.class);

    /**
     * How much better this building makes a resource pay, in points over the
     * default hundred.
     *
     * <p>{@code ImproveProduction} in the unit definitions, stored upstream
     * as {@code DefaultStat.ImproveIncomes[res] = DefaultIncomes[res] +
     * value}. Eight shipped types
     * carry it: each race's lumber mill pays wood at 125, its refinery oil
     * at 125, its keep-tier hall gold at 110 and its castle tier at 120.
     */
    private final Map<Resource, Integer> improveProduction = new EnumMap<>(Resource.class);
    private final Map<String, String> sounds = new LinkedHashMap<>();

    /**
     * Every key the script set, including ones this class has no field for.
     *
     * <p>Kept so that loading a definition is lossless while the implementation is
     * incomplete: a key that is not modelled yet is still recoverable, and a
     * test can assert nothing was silently dropped.
     */
    private final Map<String, Object> rawProperties = new LinkedHashMap<>();

    public UnitType(String ident) {
        this.ident = ident;
    }

    /** The engine identifier, for example {@code unit-footman}. */
    public String ident() {
        return ident;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String imageFile() {
        return imageFile;
    }

    public void setImageFile(String imageFile) {
        this.imageFile = imageFile;
    }

    public int imageWidth() {
        return imageWidth;
    }

    public int imageHeight() {
        return imageHeight;
    }

    public void setImageSize(int width, int height) {
        this.imageWidth = width;
        this.imageHeight = height;
    }

    public String animations() {
        return animations;
    }

    public void setAnimations(String animations) {
        this.animations = animations;
    }

    /** @see #numDirections */
    public int numDirections() {
        if (numDirections > 0) {
            return numDirections;
        }
        return building ? 1 : 8;
    }

    public void setNumDirections(int numDirections) {
        this.numDirections = numDirections;
    }

    /** The resolved animation set, or {@code null} if it was not found. */
    public net.chonkbase.chonkcraft.engine.animation.AnimationSet animationSet() {
        return animationSet;
    }

    public void setAnimationSet(net.chonkbase.chonkcraft.engine.animation.AnimationSet animationSet) {
        this.animationSet = animationSet;
    }

    public String icon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = icon;
    }

    public String construction() {
        return construction;
    }

    public void setConstruction(String construction) {
        this.construction = construction;
    }

    public String corpse() {
        return corpse;
    }

    public void setCorpse(String corpse) {
        this.corpse = corpse;
    }

    public int drawLevel() {
        return drawLevel;
    }

    public void setDrawLevel(int drawLevel) {
        this.drawLevel = drawLevel;
    }

    public int tileWidth() {
        return tileWidth;
    }

    public int tileHeight() {
        return tileHeight;
    }

    public void setTileSize(int width, int height) {
        this.tileWidth = width;
        this.tileHeight = height;
    }

    public int boxWidth() {
        return boxWidth;
    }

    public int boxHeight() {
        return boxHeight;
    }

    public void setBoxSize(int width, int height) {
        this.boxWidth = width;
        this.boxHeight = height;
    }

    public boolean elevated() {
        return elevated;
    }

    public void setElevated(boolean elevated) {
        this.elevated = elevated;
    }

    public String shadowFile() {
        return shadowFile;
    }

    public void setShadowFile(String shadowFile) {
        this.shadowFile = shadowFile;
    }

    /**
     * The shadow's cell size, offset and frame, beside {@link #shadowFile()}.
     *
     * <p>{@code ShadowWidth}, {@code ShadowHeight}, {@code ShadowOffset} and
     * {@code ShadowSpriteFrame} in {@code src/include/unittype.h}, filled by
     * the {@code Shadow} parse. Every
     * shipped shadow is a cell of one shared 32 by 32 sheet, offset
     * south-west of the flyer that casts it, and which cell is the
     * {@code sprite-frame} -- so a file with no frame and no offset is a
     * shadow that cannot be drawn, which is what this implementation had.
     */
    private int shadowWidth;

    private int shadowHeight;

    private int shadowOffsetX;

    private int shadowOffsetY;

    private int shadowSpriteFrame;

    public int shadowWidth() {
        return shadowWidth;
    }

    public int shadowHeight() {
        return shadowHeight;
    }

    public void setShadowSize(int width, int height) {
        this.shadowWidth = width;
        this.shadowHeight = height;
    }

    public int shadowOffsetX() {
        return shadowOffsetX;
    }

    public int shadowOffsetY() {
        return shadowOffsetY;
    }

    public void setShadowOffset(int x, int y) {
        this.shadowOffsetX = x;
        this.shadowOffsetY = y;
    }

    public int shadowSpriteFrame() {
        return shadowSpriteFrame;
    }

    public void setShadowSpriteFrame(int frame) {
        this.shadowSpriteFrame = frame;
    }

    public int hitPoints() {
        return hitPoints;
    }

    public void setHitPoints(int hitPoints) {
        this.hitPoints = hitPoints;
    }

    public int mana() {
        return mana;
    }

    public void setMana(int mana) {
        this.mana = mana;
    }

    /**
     * The mana a newly made unit of this type holds.
     *
     * <p>{@code Variables[MANA_INDEX].Value} as opposed to {@code .Max}.
     * LegacyEngine's variables carry both, and copies the
     * whole variable onto a new unit, so a mage is born on the {@code Value}
     * the scripts declare -- 84 of 255 -- and has to regenerate the rest.
     * Reading only the maximum is what let a mage leave the tower with
     * Polymorph paid for.
     *
     * <p>Falls back to the maximum when nothing declared a starting value.
     * That is the case for a type assembled in code rather than read from
     * {@code spells.legacy-declaration}: it has one number for its pool and no second one to
     * start below, so it starts full.
     */
    public int manaStart() {
        return manaStart < 0 ? mana : Math.min(manaStart, mana);
    }

    public void setManaStart(int manaStart) {
        this.manaStart = manaStart;
    }

    public int armor() {
        return armor;
    }

    public void setArmor(int armor) {
        this.armor = armor;
    }

    public int basicDamage() {
        return basicDamage;
    }

    public void setBasicDamage(int basicDamage) {
        this.basicDamage = basicDamage;
    }

    public int piercingDamage() {
        return piercingDamage;
    }

    public void setPiercingDamage(int piercingDamage) {
        this.piercingDamage = piercingDamage;
    }

    public int sightRange() {
        return sightRange;
    }

    public void setSightRange(int sightRange) {
        this.sightRange = sightRange;
    }

    public int maxAttackRange() {
        return maxAttackRange;
    }

    public void setMaxAttackRange(int maxAttackRange) {
        this.maxAttackRange = maxAttackRange;
    }

    public int minAttackRange() {
        return minAttackRange;
    }

    public void setMinAttackRange(int minAttackRange) {
        this.minAttackRange = minAttackRange;
    }

    public int speed() {
        return speed;
    }

    public void setSpeed(int speed) {
        this.speed = speed;
    }

    public int priority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public int points() {
        return points;
    }

    public void setPoints(int points) {
        this.points = points;
    }

    public int demand() {
        return demand;
    }

    public void setDemand(int demand) {
        this.demand = demand;
    }

    public int supply() {
        return supply;
    }

    public void setSupply(int supply) {
        this.supply = supply;
    }

    public int level() {
        return level;
    }

    public void setLevel(int level) {
        this.level = level;
    }

    public String missile() {
        return missile;
    }

    public void setMissile(String missile) {
        this.missile = missile;
    }

    /** Whether this type throws something rather than striking directly. */
    public boolean firesMissile() {
        return missile != null
                && !net.chonkbase.chonkcraft.engine.missile.MissileType.NONE.equals(missile);
    }

    public int reactRangePerson() {
        return reactRangePerson;
    }

    public void setReactRangePerson(int range) {
        this.reactRangePerson = range;
    }

    public int reactRangeComputer() {
        return reactRangeComputer;
    }

    public void setReactRangeComputer(int range) {
        this.reactRangeComputer = range;
    }

    /** The reaction range for an owner, person or computer. */
    public int reactRange(boolean person) {
        return person ? reactRangePerson : reactRangeComputer;
    }

    /**
     * How far this type may turn in one cycle, in 256ths of a circle.
     *
     * <p>{@code RotationSpeed}, and the default is 128 -- half a circle a
     * cycle, which is "instantly" for anything with eight facings
     * ({@code include/unittype.h:668}). Two types in the shipped data say
     * otherwise, and they are the two siege engines: the ballista and the
     * catapult are both {@code RotationSpeed = 4}
     * ({@code scripts/human/units.legacy-declaration:200}, {@code orc/units.legacy-declaration:209}), which
     * is sixteen cycles to swing a quarter turn.
     *
     * <p>It matters because those two types are also the only ones whose Move
     * animation asks about it: it opens {@code "if-var R >= 60 turn"}, and the
     * {@code turn} label is {@code "wait 30"}. A catapult that turns more than
     * a right angle stands still for thirty cycles before it starts walking.
     */
    public int rotationSpeed() {
        return rotationSpeed;
    }

    public void setRotationSpeed(int rotationSpeed) {
        this.rotationSpeed = rotationSpeed;
    }

    private int rotationSpeed = 128;

    public int randomMovementProbability() {
        return randomMovementProbability;
    }

    public void setRandomMovementProbability(int probability) {
        this.randomMovementProbability = probability;
    }

    public int randomMovementDistance() {
        return randomMovementDistance;
    }

    public void setRandomMovementDistance(int distance) {
        this.randomMovementDistance = distance;
    }

    public int repairRange() {
        return repairRange;
    }

    public void setRepairRange(int repairRange) {
        this.repairRange = repairRange;
    }

    public int repairHp() {
        return repairHp;
    }

    public void setRepairHp(int repairHp) {
        this.repairHp = repairHp;
    }

    /** The {@code Type} key: {@code land}, {@code fly}, or {@code naval}. */
    public String unitTypeClass() {
        return unitTypeClass;
    }

    public void setUnitTypeClass(String unitTypeClass) {
        this.unitTypeClass = unitTypeClass;
    }

    /**
     * Which domain this type moves in, as upstream's {@code MoveType}.
     *
     * <p>The {@code Type} key decides, and nothing else does.
     * {@code UpdateUnitStats} switches on {@code type.MoveType} alone to build
     * the movement mask, and the separate
     * {@code LandUnit}, {@code SeaUnit} and {@code AirUnit} keys are boolean
     * flags used for targeting and for what a transport will carry -- they are
     * copied straight into {@code BoolFlag} and never consulted about terrain.
     *
     * <p>The implementation had no reader for this key at all and derived the domain
     * from those three flags instead, which is right for 133 of the 143
     * shipped types and wrong for ten. The ten that matter are the buildings
     * that stand in the sea: {@code unit-oil-patch},
     * {@code unit-human-oil-platform}, {@code unit-orc-oil-platform} and
     * {@code unit-destroyed-3x3-place-water} all declare {@code Type = "naval"}
     * and none of them declares {@code SeaUnit}, so every one of them was
     * handed the land mask and could only have been placed on dry ground.
     *
     * <p>Falls back to the flags when the key is absent, which upstream does
     * not -- it defaults {@code MoveType} to {@code Land}. The difference is
     * confined to types built in code rather than read from the scripts: every
     * shipped type names its {@code Type}, and a fixture that sets
     * {@code SeaUnit} and no class would otherwise be quietly grounded.
     */
    public Movement moveType() {
        Movement declared = Movement.byName(unitTypeClass);
        if (declared != null) {
            return declared;
        }
        return airUnit ? Movement.FLY : seaUnit ? Movement.NAVAL : Movement.LAND;
    }

    public boolean building() {
        return building;
    }

    public void setBuilding(boolean building) {
        this.building = building;
    }

    /** @see #visibleUnderFog */
    public boolean visibleUnderFog() {
        return visibleUnderFog;
    }

    public void setVisibleUnderFog(boolean visibleUnderFog) {
        this.visibleUnderFog = visibleUnderFog;
    }

    public boolean landUnit() {
        return landUnit;
    }

    public void setLandUnit(boolean landUnit) {
        this.landUnit = landUnit;
    }

    public boolean seaUnit() {
        return seaUnit;
    }

    public void setSeaUnit(boolean seaUnit) {
        this.seaUnit = seaUnit;
    }

    public boolean airUnit() {
        return airUnit;
    }

    public void setAirUnit(boolean airUnit) {
        this.airUnit = airUnit;
    }

    /**
     * Whether the builder stays outside this building rather than going into
     * it. It decides how close the builder has to get: {@code COrder_Build::
     * UpdatePathFinderData} asks for a minimum range of one square when the
     * type is BuilderOutside and the builder can move, and nought otherwise
     * so a builder that will be swallowed
     * has to stand on the site's own ground.
     *
     * <p>ChonkCraft sets it on almost nothing -- the runestone and the dead body
     * -- so for every building a peasant puts up the answer is no.
     */
    public boolean builderOutside() {
        return builderOutside;
    }

    public void setBuilderOutside(boolean builderOutside) {
        this.builderOutside = builderOutside;
    }

    public boolean shoreBuilding() {
        return shoreBuilding;
    }

    public void setShoreBuilding(boolean shoreBuilding) {
        this.shoreBuilding = shoreBuilding;
    }

    public boolean canAttack() {
        return canAttack;
    }

    public void setCanAttack(boolean canAttack) {
        this.canAttack = canAttack;
    }

    public boolean canTargetLand() {
        return canTargetLand;
    }

    public void setCanTargetLand(boolean canTargetLand) {
        this.canTargetLand = canTargetLand;
    }

    public boolean canTargetSea() {
        return canTargetSea;
    }

    public void setCanTargetSea(boolean canTargetSea) {
        this.canTargetSea = canTargetSea;
    }

    public boolean canTargetAir() {
        return canTargetAir;
    }

    public void setCanTargetAir(boolean canTargetAir) {
        this.canTargetAir = canTargetAir;
    }

    public boolean selectableByRectangle() {
        return selectableByRectangle;
    }

    public void setSelectableByRectangle(boolean selectableByRectangle) {
        this.selectableByRectangle = selectableByRectangle;
    }

    /**
     * Whether this type goes up when it dies.
     *
     * <p>Cosmetic, and worth saying so. {@code LetUnitDie} fires the explosion
     * with {@code MakeMissile(*type->Explosion.Missile, pixelPos, pixelPos)}
     * and never sets the missile's {@code SourceUnit}, and
     * {@code Missile::MissileHit} returns before its splash loop when there is
     * no source ("no owner - green-cross ..."). The explosion the data names
     * declares no damage of its own either. A demolition squad kills with the
     * attack it makes, not with the crater it leaves.
     */
    public boolean explodeWhenKilled() {
        return !explosion.isEmpty();
    }

    /**
     * The missile it leaves behind, or empty.
     *
     * <p>{@code ExplodeWhenKilled} in the data is the missile's name, not a
     * flag: reads it with {@code DefinitionToString}
     * into {@code Explosion.Name}. The implementation coerced it to a bare true and
     * threw the name away, so even the picture could not be drawn.
     */
    public String explosion() {
        return explosion;
    }

    public void setExplosion(String explosion) {
        this.explosion = explosion == null ? "" : explosion;
    }

    /**
     * Whether nothing can hurt this at all.
     *
     * <p>{@code HitUnit} returns on the spot for
     * {@code BoolFlag[INDESTRUCTIBLE_INDEX]}. The flag
     * was parsed into the unmodelled pile, so a Circle of Power, an oil patch,
     * "The Pile", both start-location markers and every generated
     * {@code unit-dead-vision-*} could all be shot down. The Circle of Power is
     * a scripted objective in the Dark Portal missions, which made a stray
     * catapult shot enough to make one unwinnable.
     */
    public boolean indestructible() {
        return indestructible;
    }

    public void setIndestructible(boolean indestructible) {
        this.indestructible = indestructible;
    }

    private boolean indestructible;

    /**
     * Whether this type is a wall.
     *
     * <p>Read by {@code HitUnit_LastAttack}, which never cries for help over a
     * wall: a wall is struck constantly by whatever is breaking through it and
     * has no voice of its own.
     */
    public boolean wall() {
        return wall;
    }

    public void setWall(boolean wall) {
        this.wall = wall;
    }

    private boolean wall;

    public boolean coward() {
        return coward;
    }

    public void setCoward(boolean coward) {
        this.coward = coward;
    }

    public boolean detectCloak() {
        return detectCloak;
    }

    public void setDetectCloak(boolean detectCloak) {
        this.detectCloak = detectCloak;
    }

    public boolean permanentCloak() {
        return permanentCloak;
    }

    public void setPermanentCloak(boolean permanentCloak) {
        this.permanentCloak = permanentCloak;
    }

    public boolean vanishes() {
        return vanishes;
    }

    public void setVanishes(boolean vanishes) {
        this.vanishes = vanishes;
    }

    public boolean revealer() {
        return revealer;
    }

    public void setRevealer(boolean revealer) {
        this.revealer = revealer;
    }

    public boolean nonSolid() {
        return nonSolid;
    }

    public void setNonSolid(boolean nonSolid) {
        this.nonSolid = nonSolid;
    }

    /**
     * Whether a finished unit of this type goes exploring by itself.
     *
     * <p>{@code OnReady} is a per-type retired scripting language callback
     * The game fired for every unit on the map
     * at game creation, for a trained unit
     * and for a finished building
     * The shipped data sets it on exactly two
     * types -- the gnomish flying machine ({@code human/units.legacy-declaration:701}) and
     * the goblin zeppelin ({@code orc/units.legacy-declaration:739}) -- and always to
     * {@code AiExploreUnit} ({@code units.legacy-declaration:601}), which orders any
     * AI-owned one to explore: "send those balloons flying". This flag is
     * that one shipped meaning; a mod's different callback lands with the
     * unmodelled keys instead of quietly not running.
     */
    public boolean onReadyExplores() {
        return onReadyExplores;
    }

    public void setOnReadyExplores(boolean explores) {
        this.onReadyExplores = explores;
    }

    private boolean onReadyExplores;

    public boolean groundAttack() {
        return groundAttack;
    }

    public void setGroundAttack(boolean groundAttack) {
        this.groundAttack = groundAttack;
    }

    public boolean sideAttack() {
        return sideAttack;
    }

    public void setSideAttack(boolean sideAttack) {
        this.sideAttack = sideAttack;
    }

    public String rightMouseAction() {
        return rightMouseAction;
    }

    public void setRightMouseAction(String rightMouseAction) {
        this.rightMouseAction = rightMouseAction;
    }

    /** Sprite file per tileset name; empty for types with one fixed sprite. */
    public Map<String, String> tilesetImageFiles() {
        return tilesetImageFiles;
    }

    /**
     * The sprite to draw on a given tileset.
     *
     * @param tilesetName lower-case tileset name such as {@code summer}
     * @return the per-tileset file if there is one, otherwise the fixed
     *         {@link #imageFile()}, which may be empty for a type whose
     *         graphics have not been resolved
     */
    public String imageFileFor(String tilesetName) {
        String perTileset = tilesetImageFiles.get(tilesetName);
        return perTileset != null ? perTileset : imageFile;
    }

    /**
     * The sheet a worker draws from while it is working a resource.
     *
     * <p>{@code CUnit::Draw}: a harvester with a
     * current resource draws that resource's own {@code SpriteWhenLoaded} or
     * {@code SpriteWhenEmpty} in place of the type's, and falls back to the
     * type's when the resource names neither. It is a whole alternate sheet
     * with the same geometry, not a badge added to the usual one, which is why
     * the data carries a file name rather than a flag.
     *
     * <p>The implementation used to guess the name by sticking {@code _with_gold} or
     * {@code _with_wood} on the end of the type's own sprite. That is right for
     * a peasant by luck and wrong for the two oil tankers, whose data says
     * {@code oil_tanker_full} and {@code oil_tanker_empty} -- so a laden tanker
     * looked exactly like an empty one, and an empty one never used the empty
     * sheet the archive ships for it.
     *
     * @param resource what the worker is working, or {@code null} for none
     * @param loaded   whether it is holding anything
     */
    public String imageFileFor(String tilesetName, Resource resource, boolean loaded) {
        ResourceInfo info = resource == null ? null : gathering.get(resource);
        if (info != null) {
            String named = loaded ? info.fileWhenLoaded() : info.fileWhenEmpty();
            if (named != null && !named.isBlank()) {
                return named;
            }
        }
        return imageFileFor(tilesetName);
    }

    /** Whether this type has a sprite from either source. */
    public boolean hasGraphics() {
        return !imageFile.isEmpty() || !tilesetImageFiles.isEmpty();
    }

    /** Transport capacity; zero for anything that is not a transport. */
    public int maxOnBoard() {
        return maxOnBoard;
    }

    public void setMaxOnBoard(int maxOnBoard) {
        this.maxOnBoard = maxOnBoard;
    }

    public int clicksToExplode() {
        return clicksToExplode;
    }

    public void setClicksToExplode(int clicksToExplode) {
        this.clicksToExplode = clicksToExplode;
    }

    public int neutralMinimapColour() {
        return neutralMinimapColour;
    }

    public void setNeutralMinimapColour(int colour) {
        this.neutralMinimapColour = colour;
    }

    /**
     * Whether this type ferries other units.
     *
     * <p>Mirrors {@code CUnitType::CanTransport}: capacity alone is not
     * enough, because a resource-bearing unit uses the same field for how much
     * it holds.
     */
    public boolean canTransport() {
        return maxOnBoard > 0 && gathering().isEmpty();
    }

    /** The flag names this type may carry, such as {@code LandUnit}. */
    public java.util.List<String> canTransport_() {
        return canTransport;
    }

    /** Whether this transport will take a given passenger. */
    public boolean canCarry(UnitType passenger) {
        if (!canTransport() || passenger.building()) {
            return false;
        }
        if (canTransport.isEmpty()) {
            return true;
        }
        for (String flag : canTransport) {
            boolean matches = switch (flag) {
                case "LandUnit" -> passenger.landUnit();
                case "SeaUnit" -> passenger.seaUnit();
                case "AirUnit" -> passenger.airUnit();
                default -> false;
            };
            if (matches) {
                return true;
            }
        }
        return false;
    }

    /**
     * What this type is a source of, or {@code null}.
     *
     * <p>{@code CUnitType::GivesResource}. Four shipped types carry it: the
     * gold mine, the oil patch, and both oil platforms.
     *
     * <p>The implementation used to decide what a thing was a source of by looking at
     * its identifier -- {@code ident.contains("gold-mine")} and
     * {@code ident.contains("oil-platform")} -- which happens to give the
     * right answer on this data and stops being true the moment a map or a mod
     * declares a source of its own. The data says so directly, so this reads
     * what it says.
     */
    public Resource givesResource() {
        return givesResource;
    }

    public void setGivesResource(Resource givesResource) {
        this.givesResource = givesResource;
    }

    private Resource givesResource;

    /**
     * Whether a worker may take the resource out of this, rather than only
     * build over it.
     *
     * <p>{@code BoolFlag[CANHARVEST_INDEX]}, and the difference between an oil
     * patch and an oil platform. Both declare {@code GivesResource = "oil"};
     * only the platforms declare {@code CanHarvest}, which is why a tanker
     * sent at a bare patch is being told to build and one sent at a platform
     * is being told to load. Upstream pairs the two everywhere it asks the
     * question -- is
     * {@code mine.Type->BoolFlag[CANHARVEST_INDEX].value && mine.ResourcesHeld}.
     */
    public boolean canHarvest() {
        return canHarvest;
    }

    public void setCanHarvest(boolean canHarvest) {
        this.canHarvest = canHarvest;
    }

    private boolean canHarvest;

    /**
     * Where this type may be founded, as {@code BuildingRules} declares it.
     *
     * <p>{@code CUnitType::BuildingRules}, a list of and-lists:
     * {@code CanBuildHere} walks them in order and the
     * site is legal as soon as one whole and-list passes, so the outer list is
     * an or and each inner list is an and. That shape is not decoration -- the
     * oil patch declares four separate and-lists and the oil platform one.
     *
     * <p>An empty list means the type has no rules and may go wherever the
     * terrain allows, which is the case for every building in both tech trees
     * except the halls, the keeps, the castles, the shipyards, the refineries
     * and the two oil platforms.
     */
    public java.util.List<java.util.List<BuildRestriction>> buildingRules() {
        return buildingRules;
    }

    private final java.util.List<java.util.List<BuildRestriction>> buildingRules =
            new java.util.ArrayList<>();

    /**
     * The on-top rule this type is founded by, or {@code null}.
     *
     * <p>Implements {@code OnTopDetails}, which walks
     * the same lists looking for the one restriction that says what has to be
     * underneath. Upstream takes the first one it finds when it is not told
     * which parent to match -- "Guess this is right" -- and so does this.
     */
    public BuildRestriction.OnTop onTopRule() {
        for (java.util.List<BuildRestriction> andList : buildingRules) {
            for (BuildRestriction rule : andList) {
                if (rule instanceof BuildRestriction.OnTop onTop) {
                    return onTop;
                }
            }
        }
        return null;
    }

    /** How this type gathers each resource it can. */
    public Map<Resource, ResourceInfo> gathering() {
        return gathering;
    }

    /** Whether this type can gather anything at all. */
    public boolean canGather() {
        return !gathering.isEmpty();
    }

    /** Resources this type accepts as a drop-off point. */
    public java.util.EnumSet<Resource> stores() {
        return stores;
    }

    /** Whether workers can unload a resource here. */
    public boolean storesResource(Resource resource) {
        return stores.contains(resource);
    }

    /** Build cost by resource. */
    public Map<Resource, Integer> costs() {
        return costs;
    }

    /** Points over the default hundred a resource pays while this stands. */
    public Map<Resource, Integer> improveProduction() {
        return improveProduction;
    }

    /** Cost per repair tick by resource. */
    public Map<Resource, Integer> repairCosts() {
        return repairCosts;
    }

    /** Sound event name to sound identifier. */
    public Map<String, String> sounds() {
        return sounds;
    }

    /** Every key the script set, modelled or not. */
    public Map<String, Object> rawProperties() {
        return rawProperties;
    }

    @Override
    public String toString() {
        return ident;
    }
}
