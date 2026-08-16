#ifndef CHONK_BNE_202_LAYOUT_H
#define CHONK_BNE_202_LAYOUT_H

#include <windows.h>

/*
 * English retail Warcraft II BNE 2.02b, extracted from Blizzard's official
 * War2Patch_202.exe. The executable is fixed-base PE32 (image base 0x400000)
 * and has no relocation table. Every patch is guarded by the original bytes.
 */
#define BNE_202_IMAGE_BASE ((BYTE *) 0x00400000)
#define BNE_202_TICK_CALL ((BYTE *) 0x00421238)
#define BNE_202_TICK_TARGET ((BYTE *) 0x00452110)
#define BNE_202_WARMUP_TICK_1_CALL ((BYTE *) 0x00420bc7)
#define BNE_202_WARMUP_TICK_2_CALL ((BYTE *) 0x00420ca6)
#define BNE_202_MAIN_STATE_CALL ((BYTE *) 0x0042a348)
#define BNE_202_MAIN_STATE_TARGET ((BYTE *) 0x00420480)
#define BNE_202_NEW_GAME_CALL ((BYTE *) 0x0042a4a1)
#define BNE_202_NEW_GAME_TARGET ((BYTE *) 0x0041f6e0)
#define BNE_202_LOAD_SCENARIO_CALL ((BYTE *) 0x0041face)
#define BNE_202_LOAD_SCENARIO_TARGET ((BYTE *) 0x0042c6f0)
#define BNE_202_MASTER_SEED_CALL ((BYTE *) 0x0041f751)
#define BNE_202_MASTER_SEED_TARGET ((BYTE *) 0x00479870)
#define BNE_202_SYNC_RANDOM_TARGET ((BYTE *) 0x004534c0)
#define BNE_202_ASYNC_RANDOM_TARGET ((BYTE *) 0x00479820)
#define BNE_202_IDLE_CALL ((BYTE *) 0x0040b058)
#define BNE_202_IDLE_TARGET ((BYTE *) 0x0040ad30)
#define BNE_202_PROJECTILE_CALL ((BYTE *) 0x00409f14)
#define BNE_202_PROJECTILE_TARGET ((BYTE *) 0x0040fb10)
#define BNE_202_INTERNAL_GIVE_ORDER ((BYTE *) 0x004513d0)
#define BNE_202_GIVE_ORDER ((BYTE *) 0x00451070)
#define BNE_202_ORDER_FUNCTIONS ((void **) 0x00495fcc)
/* 0x4368b0 is not in ORDER_FUNCTIONS. It is the order-15 installer
 * 0x0D stand-ground reaches: push 0x0f; call 0x453130. */
#define BNE_202_STAND_GROUND_ORDER ((BYTE *) 0x004368b0)
/* 0x15 production inner apply: cdecl (unit, type, mode) with mode 0=train. */
#define BNE_202_PRODUCTION_APPLY ((BYTE *) 0x0040e2a0)
#define BNE_202_SYNC_DISPATCH_CALL ((BYTE *) 0x0047800b)
#define BNE_202_SYNC_DISPATCH_TARGET ((BYTE *) 0x004782a0)
#define BNE_202_STORM_OPEN_ARCHIVE_IAT ((void **) 0x00490360)
#define BNE_202_STORM_OPEN_FILE_IAT ((void **) 0x00490368)

#define BNE_202_RANDOM_SEED ((DWORD *) 0x004a48dc)
#define BNE_202_ASYNC_RANDOM_SEED ((DWORD *) 0x004d40ec)
#define BNE_202_MASTER_RANDOM_SEED ((DWORD *) 0x004d40f0)
#define BNE_202_MAIN_STATE ((WORD *) 0x004ae480)
#define BNE_202_OPTION_FLAGS ((DWORD *) 0x004d6b50)
#define BNE_202_OPTION_SHOW_TIPS 0x00000100UL
#define BNE_202_CAMPAIGN_SELECTOR ((WORD *) 0x004ad350)
#define BNE_202_CAMPAIGN_RESOURCE ((WORD *) 0x004abda2)
#define BNE_202_CAMPAIGN_ORC ((BYTE *) 0x004abb7c)
#define BNE_202_CUSTOM_GAME_FLAG ((BYTE *) 0x004acc2e)
#define BNE_202_LOCAL_PLAYER ((BYTE *) 0x004abf8c)
#define BNE_202_NETWORK_PLAYER_INDEX ((DWORD *) 0x004a70f0)
#define BNE_202_PLAYER_CONTROLLERS ((BYTE *) 0x004acbac)
#define BNE_202_PLAYER_GOLD ((DWORD *) 0x004abb18)
#define BNE_202_PLAYER_LUMBER ((DWORD *) 0x004acb6c)
#define BNE_202_PLAYER_OIL ((DWORD *) 0x004abbfc)
#define BNE_202_PLAYER_FOOD_LIMIT ((WORD *) 0x004adc6c)
#define BNE_202_PLAYER_ALL_UNITS ((WORD *) 0x004adacc)
#define BNE_202_PLAYER_ALL_BUILDINGS ((WORD *) 0x004ada04)
#define BNE_202_PLAYER_RESCUED_UNITS ((WORD *) 0x004acc30)
#define BNE_202_PLAYER_LOST_UNITS ((WORD *) 0x004ad3b8)
#define BNE_202_PLAYER_LOST_BUILDINGS ((WORD *) 0x004acbbc)
#define BNE_202_PLAYER_KILLS_UNITS ((WORD *) 0x004ad378)
#define BNE_202_PLAYER_KILLS_BUILDINGS ((WORD *) 0x004aced0)
#define BNE_202_PLAYER_ALLOWED_UNITS ((DWORD *) 0x004acb28)
#define BNE_202_PLAYER_ALLOWED_UPGRADES ((DWORD *) 0x004acef4)
#define BNE_202_PLAYER_ALLOWED_SPELLS ((DWORD *) 0x004acbec)
#define BNE_202_PLAYER_SPELLS_LEARNED ((DWORD *) 0x004abf4c)
#define BNE_202_PLAYER_UPGRADE_ARROWS ((BYTE *) 0x004abd90)
#define BNE_202_PLAYER_UPGRADE_SWORDS ((BYTE *) 0x004acea4)
#define BNE_202_PLAYER_UPGRADE_SHIELDS ((BYTE *) 0x004abf38)
#define BNE_202_PLAYER_UPGRADE_BOAT_ATTACK ((BYTE *) 0x004ace94)
#define BNE_202_PLAYER_UPGRADE_BOAT_ARMOR ((BYTE *) 0x004abb80)
#define BNE_202_PLAYER_UPGRADE_CATAPULT ((BYTE *) 0x004abd68)
#define BNE_202_PLAYER_UPGRADE_RANGER ((BYTE *) 0x004ace2c)
#define BNE_202_PLAYER_UPGRADE_MARKSMANSHIP ((BYTE *) 0x004acc64)
#define BNE_202_PLAYER_UPGRADE_LONGBOW ((BYTE *) 0x004ab9b8)
#define BNE_202_PLAYER_UPGRADE_SCOUTING ((BYTE *) 0x004acbdc)
#define BNE_202_UNIT_POOL_POINTER ((BYTE **) 0x004aec94)
#define BNE_202_UNIT_POOL_COUNT ((DWORD *) 0x004ae270)
#define BNE_202_BULLET_POOL_POINTER ((BYTE **) 0x004aec98)
#define BNE_202_BULLET_POOL_COUNT ((DWORD *) 0x004ae268)
#define BNE_202_MAP_SIZE ((WORD *) 0x004acc2c)
#define BNE_202_MAP_COMPONENTS_POINTER ((WORD **) 0x004ad650)
#define BNE_202_MAP_CELLS_POINTER ((WORD **) 0x004ad61c)
#define BNE_202_MAP_SQUARES_POINTER ((WORD **) 0x004ad610)
#define BNE_202_AI_PROFILE_IDS ((BYTE *) 0x004af100)
#define BNE_202_AI_PLAYER_STATE ((BYTE *) 0x004af118)
#define BNE_202_AI_PLAYER_STATE_BYTES 48
#define BNE_202_AI_BUILD_LIST_OFFSET 0x23
#define BNE_202_AI_AVAILABILITY ((BYTE *) 0x004bdd30)
#define BNE_202_AI_AVAILABILITY_BYTES 64
#define BNE_202_AI_BUILDING_TYPE_FIRST 58
#define BNE_202_AI_BUILDING_TYPE_COUNT 47
#define BNE_202_AI_BUILDING_COUNTS ((WORD *) 0x004b4a38)
#define BNE_202_FIND_NEAREST_GOLD_DEPOT ((BYTE *) 0x00439ce0)
#define BNE_202_FIND_NEAREST_HOSTILE ((BYTE *) 0x00427830)
#define BNE_202_FIND_AUTO_TARGET ((BYTE *) 0x00409ff0)
#define BNE_202_TARGET_SCORE ((BYTE *) 0x0040a4b0)
#define BNE_202_AI_HOME_INIT ((BYTE *) 0x00427130)
#define BNE_202_SET_AI_BEHAVIOR ((BYTE *) 0x004275b0)
#define BNE_202_FIND_NEARBY_SQUARE ((BYTE *) 0x00416a00)
#define BNE_202_CHECK_BUILD_SITE ((BYTE *) 0x00416c40)
#define BNE_202_SET_NO_BUILD ((BYTE *) 0x00438560)
#define BNE_202_CLEAR_NO_BUILD ((BYTE *) 0x00438610)
#define BNE_202_FIND_AI_WOOD ((BYTE *) 0x0044e0f0)
#define BNE_202_UNIT_REACT_COMPUTER ((BYTE *) 0x004cf024)
#define BNE_202_UNIT_REACT_PERSON ((BYTE *) 0x004cf170)
#define BNE_202_UNIT_TARGET_MASK ((BYTE *) 0x004cfaa4)
#define BNE_202_UNIT_SIGHT_RANGE ((BYTE *) 0x004cff40)
#define BNE_202_UNIT_PRIORITY ((BYTE *) 0x004ceacc)
#define BNE_202_UNIT_TYPE_FLAGS ((DWORD *) 0x004cf574)

/* The per-type target priority the reaction scan's scorer subtracts its
   squared distance from, at 0x0040a5b7 inside FUN_0040a4b0. Also .bss. */
#define BNE_202_UNIT_TYPE_PRIORITY ((BYTE *) 0x004ceacc)

/* The reaction-scan box half-width the search takes for a computer-owned
   attacker (0x0040a0be) and for a person-owned one (0x0040a0d5). Both .bss. */
#define BNE_202_REACT_RANGE_COMPUTER ((BYTE *) 0x004cf024)
#define BNE_202_REACT_RANGE_PERSON ((BYTE *) 0x004cf170)

/* The floor the attack action clamps a unit's animation timer up to, read at
   0x0040b361 into unit+0x7a and enforced at 0x0040b0f0. Also .bss. */
#define BNE_202_ANIMATION_FLOOR ((BYTE *) 0x004bb8ec)
#define BNE_202_UNIT_TILE_WIDTH ((WORD *) 0x004cee6c)
#define BNE_202_UNIT_TILE_HEIGHT ((WORD *) 0x004cee6e)

#define BNE_PLAYER_COUNT 16
#define BNE_CONTROLLER_NOBODY 3
#define BNE_UNIT_BYTES 152
#define BNE_UNIT_LIMIT 1600
#define BNE_BULLET_BYTES 64
#define BNE_BULLET_LIMIT 400
#define BNE_MAP_LIMIT 128
#define BNE_MAP_TILE_LIMIT (BNE_MAP_LIMIT * BNE_MAP_LIMIT)

#define BNE_UNIT_X 24
#define BNE_UNIT_Y 26
#define BNE_UNIT_SEQUENCE 4
#define BNE_UNIT_SEQUENCE_FLAGS 6
#define BNE_UNIT_ANIMATION_TIMER 7
#define BNE_UNIT_ANIMATION 8
#define BNE_UNIT_FRAME 9
#define BNE_UNIT_FACE 10
#define BNE_UNIT_FLAGS3 30
#define BNE_UNIT_HP 34
#define BNE_UNIT_TYPE 39
#define BNE_UNIT_OWNER 44
#define BNE_UNIT_ORDER 46
#define BNE_UNIT_NEXT_ORDER 47
#define BNE_UNIT_ROUTE 48
#define BNE_UNIT_MOVEMENT_PATH 49
#define BNE_UNIT_ROUTE_INDEX 126
#define BNE_UNIT_AI_HOME_X 88
#define BNE_UNIT_AI_HOME_Y 90
#define BNE_UNIT_AI_BEHAVIOR 94
#define BNE_UNIT_AI_MARKER 95
#define BNE_UNIT_TARGET 136
#define BNE_UNIT_ORDER_X 132
#define BNE_UNIT_ORDER_Y 134

#define BNE_UNIT_FREE 0x01
#define BNE_UNIT_DEAD 0x04
#define BNE_UNIT_HIDDEN 0x08

/* BNE's effect/projectile allocator tests bit 0 at byte 53 for a free slot.
 * Its stock single-player and multiplayer capacities are 200 and 400. */
#define BNE_BULLET_FLAGS 53
#define BNE_BULLET_FREE 0x01

static const char *bne_unit_type_name(unsigned int type) {
    static const char *const names[] = {
        "unit-footman", "unit-grunt", "unit-peasant", "unit-peon",
        "unit-ballista", "unit-catapult", "unit-knight", "unit-ogre",
        "unit-archer", "unit-axethrower", "unit-mage", "unit-death-knight",
        "unit-paladin", "unit-ogre-mage", "unit-dwarves", "unit-goblin-sappers",
        "unit-attack-peasant", "unit-attack-peon", "unit-ranger", "unit-berserker",
        "unit-female-hero", "unit-evil-knight", "unit-flying-angel", "unit-fad-man",
        "unit-white-mage", "unit-beast-cry", "unit-human-oil-tanker",
        "unit-orc-oil-tanker", "unit-human-transport", "unit-orc-transport",
        "unit-human-destroyer", "unit-orc-destroyer", "unit-battleship",
        "unit-ogre-juggernaught", "unit-unused-34", "unit-fire-breeze",
        "unit-unused-36", "unit-unused-37", "unit-human-submarine",
        "unit-orc-submarine", "unit-balloon", "unit-zeppelin",
        "unit-gryphon-rider", "unit-dragon", "unit-knight-rider",
        "unit-eye-of-vision", "unit-arthor-literios", "unit-quick-blade",
        "unit-unused-48", "unit-double-head", "unit-wise-man", "unit-ice-bringer",
        "unit-man-of-light", "unit-sharp-axe", "unit-unused-54", "unit-skeleton",
        "unit-daemon", "unit-critter", "unit-farm", "unit-pig-farm",
        "unit-human-barracks", "unit-orc-barracks", "unit-church",
        "unit-altar-of-storms", "unit-human-watch-tower", "unit-orc-watch-tower",
        "unit-stables", "unit-ogre-mound", "unit-inventor", "unit-alchemist",
        "unit-gryphon-aviary", "unit-dragon-roost", "unit-human-shipyard",
        "unit-orc-shipyard", "unit-town-hall", "unit-great-hall",
        "unit-elven-lumber-mill", "unit-troll-lumber-mill", "unit-human-foundry",
        "unit-orc-foundry", "unit-mage-tower", "unit-temple-of-the-damned",
        "unit-human-blacksmith", "unit-orc-blacksmith", "unit-human-refinery",
        "unit-orc-refinery", "unit-human-oil-platform", "unit-orc-oil-platform",
        "unit-keep", "unit-stronghold", "unit-castle", "unit-fortress",
        "unit-gold-mine", "unit-oil-patch", "unit-human-start-location",
        "unit-orc-start-location", "unit-human-guard-tower", "unit-orc-guard-tower",
        "unit-human-cannon-tower", "unit-orc-cannon-tower", "unit-circle-of-power",
        "unit-dark-portal", "unit-runestone", "unit-human-wall", "unit-orc-wall"
    };
    if (type >= sizeof(names) / sizeof(names[0])) {
        return "unit-unknown";
    }
    return names[type];
}

/* Translate BNE's order byte into the Java trace's coarse action vocabulary. */
static const char *bne_order_name(unsigned int order) {
    if (order == 1) return "DYING";
    /*
     * 14 is the terminal idle action used by the four armed tower types,
     * not a combat attack.  Across the sealed 52-campaign corpus it appears
     * only on types 96--99, including towers with no goal or target, and is
     * retained indefinitely.  A newly placed tower commonly starts at 2
     * with 14 as its next action before settling here.  Calling it ATTACK
     * manufactured hundreds of cycle-one parity failures for towers which
     * were doing exactly the same thing in both engines.
     */
    if (order == 2 || order == 14 || order == 32
            || order == 33 || order == 58 || order == 60) return "STILL";
    /*
     * 13 is the hold-still that 0x4368b0's order-15 opening becomes after
     * its three animation ticks. Same 0x40b010 tick as idle Still, but the
     * flag word is 0x0082 -- no 0x1000 -- so a person does not take the
     * 0x4368c0 chase. Calling it STILL made a stand-ground click look
     * idle at the window.
     */
    if (order == 13 || order == 15) return "STAND_GROUND";
    if (order == 3 || order == 36 || order == 59) return "MOVE";
    if (order == 4 || order == 5) return "PATROL";
    if (order == 6 || order == 7) return "FOLLOW";
    /* 16 is the stationary attack/firing substate.  It carries animation 4,
     * the live target pointer, and the target square; all 15,996 occurrences
     * in the sealed campaign corpus are combat units executing that animation.
     */
    if ((order >= 8 && order <= 12) || order == 16
            || (order >= 19 && order <= 21)) {
        return "ATTACK";
    }
    if (order == 15) return "STAND_GROUND";
    if (order == 17) return "ATTACK_GROUND";
    if (order == 18) return "ATTACK_MOVE";
    /*
     * 28 is the builder's long-lived walk-to-site state, not the building's
     * under-construction state.  Campaign workers retain it for hundreds of
     * cycles while crossing the map, with their target coordinates already
     * set to the future building site.
     */
    if (order == 22 || order == 28 || order == 37) return "BUILD";
    /*
     * 25 and 26 are not transport orders.  They are the resource action's
     * final approach and inside-resource substates: every type observed in
     * 25 is a peasant, peon, or oil tanker, and 26 is a hidden tanker inside
     * its platform.  Keeping the old BOARD/UNLOAD guesses turned ordinary
     * gathering into false semantic divergences.
     */
    if (order == 23 || order == 25 || order == 26
            || order == 30 || order == 31) return "HARVEST";
    if (order == 24) return "RETURN_GOODS";
    if (order == 34) return "BOARD";
    if (order == 29 || order == 35) return "UNLOAD";
    if (order == 27) return "REPAIR";
    if (order >= 38 && order <= 57) return "SPELL_CAST";
    return "BNE_UNKNOWN";
}

#endif
