package net.chonkbase.chonkcraft.engine;

import java.util.ArrayList;
import java.util.List;
import net.chonkbase.chonkcraft.engine.ai.AiPlayer;
import net.chonkbase.chonkcraft.engine.missile.Missile;
import net.chonkbase.chonkcraft.engine.missile.MissileClass;
import net.chonkbase.chonkcraft.engine.missile.MissileType;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import net.chonkbase.chonkcraft.engine.upgrade.UpgradeState;

/**
 * A shot from the cycle it is constructed to the cycle it lands.
 *
 * <p>Implements {@code src/missile} and of retail BNE's projectile constructor
 * {@code FUN_0040fb10}, its fixed and splash impact resolution
 * ({@code FUN_00410520} / {@code FUN_00410680}), and the asynchronous-stream
 * debits those take. The missile list itself stays on {@link World}, which
 * owns the simulation state; what lives here is the order things happen in,
 * because that order is what the asynchronous RNG stream records.
 */
final class BattleNetProjectileSystem {

    private final World world;

    BattleNetProjectileSystem(World world) {
        this.world = world;
    }

    /**
     * Resolves the presentation-ahead half of an interrupted mobile shot.
     *
     * <p>Before BNE's attack opcode ten, Java may already have allocated a
     * missile for presentation. That placeholder is not yet a retail
     * projectile. If the attack order dies first, retaining it freezes it in
     * the global missile pool forever. A constructor-debited shot has crossed
     * the native boundary, so it is armed instead of erased.</p>
     */
    void interruptPendingAttack(Unit attacker) {
        if (attacker == null) {
            return;
        }
        Missile pending = world.battleNetPendingProjectileShots.remove(attacker);
        world.battleNetCycleEndConstructorDebit.removeIf(unit -> unit == attacker);
        if (pending == null) {
            return;
        }
        if (pending.battleNetConstructorDrawn()) {
            prepareBattleNetProjectile(pending, attacker.canMove());
        } else {
            discardPresentationPlaceholder(pending);
        }
        world.battleNetPendingProjectileQueuedCycle.remove(pending);
        world.missileSnapshot = List.copyOf(world.missiles);
    }

    /**
     * Installs the sole presentation-ahead shot owned by an attack order.
     *
     * <p>Retail has no projectile before attack opcode ten. ChonkCraft creates
     * one early solely so its independent presentation animation has
     * something to draw. Replacing the owner-map entry without removing its
     * old value turns that old sprite into an ordinary global missile: it
     * remains at the abandoned muzzle until a later missile pass makes it
     * fly. This is the only insertion path and also repairs duplicate records
     * restored from saves written while that bug existed.</p>
     */
    void queuePendingAttack(Unit attacker, Missile shot, long queuedCycle) {
        if (attacker == null || shot == null) {
            return;
        }
        Missile previous = world.battleNetPendingProjectileShots.get(attacker);
        if (previous != null && previous != shot) {
            // A second presentation callback for the same swing is not a
            // second retail shot. Keep the order's original placeholder and
            // erase the just-allocated duplicate. This is especially
            // important after an early constructor debit: arming the first
            // and retaining the second would manufacture two live missiles.
            discardPresentationPlaceholder(shot);
            world.battleNetPendingProjectileQueuedCycle.remove(shot);
            world.missileSnapshot = List.copyOf(world.missiles);
            return;
        }

        // A broken save records an overwritten placeholder as pending=false,
        // because only the newer value remains in the owner map. It is still
        // recognizable: same source, no constructor, no motion and no start.
        for (Missile candidate : new ArrayList<>(world.missiles)) {
            if (candidate == shot || candidate.source() != attacker
                    || candidate.battleNetConstructorDrawn()
                    || candidate.battleNetMotion()
                    || world.battleNetProjectileStartCycles.containsKey(candidate)
                    || !world.battleNetPendingProjectileQueuedCycle.containsKey(candidate)) {
                continue;
            }
            discardPresentationPlaceholder(candidate);
            world.battleNetPendingProjectileQueuedCycle.remove(candidate);
        }

        world.battleNetPendingProjectileShots.put(attacker, shot);
        if (queuedCycle >= 0) {
            world.battleNetPendingProjectileQueuedCycle.put(shot, queuedCycle);
        }
        world.missileSnapshot = List.copyOf(world.missiles);
    }

    /** Removes an object that never crossed BNE's projectile constructor. */
    private void discardPresentationPlaceholder(Missile pending) {
        world.missiles.remove(pending);
        world.battleNetProjectileStartCycles.remove(pending);
        world.battleNetProjectileCausalOrdinals.remove(pending);
        world.freeBattleNetProjectileSlot(pending.battleNetPoolSlot());
        pending.setBattleNetPoolSlot(-1);
    }

    /** Cancels placeholders whose owning attack order was replaced directly. */
    void discardInterruptedPlaceholders() {
        for (Unit attacker : new ArrayList<>(world.battleNetPendingProjectileShots.keySet())) {
            Missile pending = world.battleNetPendingProjectileShots.get(attacker);
            Unit.Order order = attacker.order();
            boolean ownsAttack = order == Unit.Order.ATTACK
                    || order == Unit.Order.ATTACK_MOVE
                    || order == Unit.Order.STAND_GROUND
                    || order == Unit.Order.ATTACK_GROUND
                    || (order == Unit.Order.STILL && !attacker.canMove());
            boolean ownsTarget = pending == null || pending.target() == null
                    || pending.target() == attacker.target();
            if (!attacker.isAlive() || !ownsAttack || !ownsTarget) {
                interruptPendingAttack(attacker);
            }
        }
    }


    /**
     * Spends queued presentation-ahead constructor draws after the unit loop.
     */
    void flushBattleNetCycleEndConstructorDebit() {
        if (world.battleNetCycleEndConstructorDebit.isEmpty()
                && world.battleNetCycleEndProjectileArm.isEmpty()) {
            return;
        }
        if (World.BNE_PEND_TRACE) {
            System.err.printf("JBNEPEND flush-begin cycle=%d queued=%d%n",
                    world.cycle, world.battleNetCycleEndConstructorDebit.size());
        }
        for (Unit attacker : world.battleNetCycleEndConstructorDebit) {
            Missile pend = world.battleNetPendingProjectileShots.get(attacker);
            if (pend != null && !pend.battleNetConstructorDrawn()) {
                long queued = world.battleNetPendingProjectileQueuedCycle
                        .getOrDefault(pend, -1L);
                world.logBattleNetPend("flush-debit", attacker, pend.target(), pend,
                        "cycle-end-flush", queued);
                debitBattleNetProjectileConstructor(pend, true);
            } else if (World.BNE_PEND_TRACE) {
                System.err.printf("JBNEPEND flush-skip cycle=%d attacker=%d "
                                + "pend=%s drawn=%s%n",
                        world.cycle, attacker == null ? -1 : attacker.id(),
                        pend == null ? "null"
                                : Integer.toHexString(System.identityHashCode(
                                        pend)),
                        pend == null ? "n/a"
                                : Boolean.toString(
                                        pend.battleNetConstructorDrawn()));
            }
        }
        world.battleNetCycleEndConstructorDebit.clear();
        // Stand-ground attack presentation is completed after HandleEachCycle
        // has walked the unit table.  The projectile is nevertheless present
        // in the same cycle-end pool snapshot.  Running its constructor here
        // preserves both facts: XHuman 4's axethrower shot is born on fixture
        // 48, while the later critter slots consume their idle draws before
        // the shot's damage and aim draws, just as retail does.
        for (Missile shot : world.battleNetCycleEndProjectileArm) {
            prepareBattleNetProjectile(shot, true);
        }
        world.battleNetCycleEndProjectileArm.clear();
        if (World.BNE_PEND_TRACE) {
            System.err.printf("JBNEPEND flush-end cycle=%d%n", world.cycle);
        }
    }


    /**
     * FUN_0040fb10 damage and aim-jitter draws without arming flight.
     *
     * <p>Presentation can take these three async draws while the Attack wait
     * is still above one; opcode ten then only enables motion so the shot
     * does not spend flight steps three cycles early.
     */
    void debitBattleNetProjectileConstructor(Missile shot,
            boolean mobileShot) {
        if (shot.battleNetConstructorDrawn()) {
            return;
        }
        Unit attacker = shot.source();
        Unit target = shot.target();
        long queued = world.battleNetPendingProjectileQueuedCycle
                .getOrDefault(shot, -1L);
        world.logBattleNetPend("ctor-debit", attacker, target, shot,
                mobileShot ? "mobile" : "fixed", queued);
        if (attacker != null && target != null) {
            shot.setDamage(battleNetProjectileDamage(attacker, target,
                    shot.type()));
        }
        if (mobileShot) {
            // FUN_0040fb10 measures from/to at the constructor boundary. The
            // presentation Attack frame can allocate the Missile one call
            // earlier with then-current pixels; OP10 only ran the three draws
            // and motion arm on that stale geometry. XHuman 12 archer→grunt
            // 152 walked east between those visits: rem 134 vs native 131 and
            // free fixture 36 vs 35 (arrow damage one cycle late). Refresh
            // live pixels before the two aim-jitter draws.
            if (attacker != null && attacker.isAlive()) {
                shot.setBattleNetMuzzle(
                        attacker.pixelX() + attacker.residualX()
                                + World.battleNetCentreOffset(attacker.type(), true),
                        attacker.pixelY() + attacker.residualY()
                                + World.battleNetCentreOffset(attacker.type(), false));
            }
            if (target != null && target.isAlive() && target.isOnMap()) {
                shot.setBattleNetAim(
                        target.pixelX() + target.residualX()
                                + World.battleNetCentreOffset(target.type(), true),
                        target.pixelY() + target.residualY()
                                + World.battleNetCentreOffset(target.type(), false));
            }
            int offsetX = (world.battleNetRand() & 7) - 3;
            int offsetY = (world.battleNetRand() & 7) - 3;
            shot.applyBattleNetAimJitter(offsetX, offsetY);
        }
        shot.setBattleNetConstructorDrawn(true);
    }


    /**
     * Completes the part of BNE's projectile constructor deferred to opcode ten.
     *
     * <p>Package-visible so same-package tests can arm a pending presentation
     * shot the way Attack OP10 does, without driving a full sequence tick.
     */
    void prepareBattleNetProjectile(Missile shot, boolean mobileShot) {
        // Towers and same-cycle OP10 take the full constructor here. A
        // presentation-ahead debit may already have spent the three draws;
        // only remaining distance and motion arming are left.
        boolean presentationAhead = shot.battleNetConstructorDrawn();
        if (!presentationAhead) {
            debitBattleNetProjectileConstructor(shot, mobileShot);
        }
        // Remaining distance and the 0x00429fa0 direction state are part of the
        // same constructor boundary. Without them a ChonkCraft arrow at speed 32
        // reaches Human 13's knight on fixture cycle 14 while the native type
        // 15 shot at speed 12 is still four tiles short.
        shot.enableBattleNetMotion(
                battleNetMissileSpeed(shot.type()),
                battleNetMissileMinFlight(shot.type()));
        // Residual-open clears so a later swing can mid-visit-collapse again.
        // Native spends 0041025A on every first flight step after construction
        // (diag-xh12-async-ledger-c50). Residual-open building silence and
        // presentation-ahead first-motion silence used to steal those draws:
        // free@35 lost idle seed 9998 into a motion slot and splash took
        // 20970 (tower 84) instead of 6888 (tower 92), and free@47 tower-axe
        // damage used motion seed 5959 (6 HP) instead of 22029 (4 HP). Pool
        // low→high walk places free splash after the live traveler prefix;
        // both silences stay off so the stream matches native.
        Unit source = shot.source();
        if (source != null && source.battleNetRangedResidualOpen()) {
            source.setBattleNetRangedResidualOpen(false);
        }
        // Presentation-ahead building debit (queued cycle) is native's
        // construction cycle: rem is banked then, first 0041025A motion on
        // the next projectile pass (XHuman 12 axe→tower: rem 146@33, 134@34
        // with a stream draw, free@47). Using the OP10 cycle as start free'd
        // at fixture 48, so start is back-dated to the debit cycle. The
        // first motion still spends the stream -- native cycle 34 ends with
        // four 0041025A draws (…3029, 16580); silencing the presentation
        // shot left only three and pushed 16580 into free-cycle idle, which
        // is why splash sat on 20970 instead of 6888.
        long startCycle = world.cycle;
        if (presentationAhead) {
            long queued = world.battleNetPendingProjectileQueuedCycle
                    .getOrDefault(shot, -1L);
            if (queued >= 0 && queued < world.cycle) {
                startCycle = queued;
            }
        }
        world.battleNetProjectileStartCycles.put(shot, startCycle);
        recordProjectileCreate(shot);
    }

    /** Numeric identities proved against BNE's type byte for corpus weapons. */
    private static int battleNetProjectileType(MissileType type) {
        if (type == null || type.ident() == null) {
            return -1;
        }
        return switch (type.ident()) {
            case "missile-catapult-rock" -> 13;
            case "missile-ballista-bolt" -> 14;
            case "missile-arrow", "missile-arrow-super" -> 15;
            case "missile-axe" -> 16;
            case "missile-impact" -> 21;
            case "missile-small-cannon", "missile-small-cannon-super" -> 24;
            default -> -1;
        };
    }

    /** Records the native constructor boundary, never its earlier placeholder. */
    private void recordProjectileCreate(Missile missile) {
        long creation = world.battleNetProjectileCausalOrdinals.computeIfAbsent(
                missile, ignored -> world.nextBattleNetProjectileCausalOrdinal++);
        Unit source = missile.source();
        Unit target = missile.target();
        world.causalTrace.event(world.cycle, "projectile.create",
                source == null ? null : source.id(),
                "fixture_cycle", Math.max(0, world.cycle - 2),
                "creation_ordinal", creation,
                "pool_slot", missile.battleNetPoolSlot(),
                "type", battleNetProjectileType(missile.type()),
                "type_ident", missile.type() == null ? null : missile.type().ident(),
                "source", source == null ? -1 : source.id(),
                "target", target == null ? -1 : target.id(),
                "remaining", missile.battleNetRemaining());
    }

    /** Records removal before the pool slot and creation identity are cleared. */
    private void recordProjectileFree(Missile missile) {
        Long creation = world.battleNetProjectileCausalOrdinals.remove(missile);
        if (creation == null) {
            return;
        }
        Unit source = missile.source();
        Unit target = missile.target();
        world.causalTrace.event(world.cycle, "projectile.free",
                source == null ? null : source.id(),
                "fixture_cycle", Math.max(0, world.cycle - 2),
                "creation_ordinal", creation,
                "pool_slot", missile.battleNetPoolSlot(),
                "type", battleNetProjectileType(missile.type()),
                "type_ident", missile.type() == null ? null : missile.type().ident(),
                "source", source == null ? -1 : source.id(),
                "target", target == null ? -1 : target.id(),
                "remaining", missile.battleNetRemaining());
    }


    /**
     * Native pixels per projectile update from table {@code 0x00494e0c}.
     *
     * <p>ChonkCraft {@code missiles.legacy-declaration} gives arrows speed 32; BNE type 15 and
     * type 16 (axe) are 12, and types 13/14 (rocks and bolts) are 8. Only the
     * identities that fire in the current campaign corpus are listed. Unknown
     * types keep their scripted speed so a hand-built test still moves.
     */
    static int battleNetMissileSpeed(MissileType type) {
        if (type == null || type.ident() == null) {
            return 12;
        }
        return switch (type.ident()) {
            case "missile-arrow", "missile-axe" -> 12;
            // Table 0x00494e0c type 24 is 16; ChonkCraft missiles.legacy-declaration used 22 and
            // delivered XHuman 10 cannon bolts several ticks early.
            case "missile-small-cannon" -> 16;
            case "missile-catapult-rock", "missile-ballista-bolt" -> 8;
            default -> Math.max(1, type.speed());
        };
    }


    /**
     * Minimum remaining flight in pixels from table {@code 0x00494e6c}.
     *
     * <p>The table stores a factor that the constructors shift left by five
     * ({@code * 32}). Type 13 and 14 demand 96; type 15 arrows demand nothing
     * beyond the max-axis distance itself. Type 24 (small cannon) factor 2
     * becomes 64.
     */
    static int battleNetMissileMinFlight(MissileType type) {
        if (type == null || type.ident() == null) {
            return 0;
        }
        return switch (type.ident()) {
            case "missile-catapult-rock", "missile-ballista-bolt" -> 96;
            case "missile-small-cannon" -> 64;
            default -> 0;
        };
    }


    /** Damage byte written by BNE's ordinary and mobile projectile constructors. */
    int battleNetProjectileDamage(Unit attacker, Unit target,
            MissileType missile) {
        UpgradeState attackerUpgrades = world.upgrades(attacker.player());
        UpgradeState targetUpgrades = world.upgrades(target.player());
        int basic = attackerUpgrades.basicDamage(attacker.type());
        int piercing = attackerUpgrades.piercingDamage(attacker.type());
        if (attacker.hasBuff(Unit.Buff.BLOODLUST)) {
            basic *= 2;
            piercing *= 2;
        }
        int armor = targetUpgrades.armor(target.type());
        // BNE projectile constructors floor basic-armor at 0 (not the melee
        // floor of 1). Equal-armor tower vs ogre is therefore piercing only
        // (12). With the half-band below, native seed result 8100 stores 7
        // (6 + 8100%7); flooring basic-armor at 1 made maximum 13 and 11.
        if (missile.splashes()) {
            // BNE's siege projectile types take the fixed/max arm at
            // 0x0040fbe0: weapon basic+piercing only, no target armor. Armor
            // is applied per splash victim in resolveBattleNetSplash. Storing
            // basic-armor here double-taxed XHuman 2's barracks (armor 20):
            // constructor kept 30, splash subtracted 20 again for a 5-10
            // outer-looking roll of 8 while native stores 50 and deals 28.
            // basic/piercing already include bloodlust doubling above.
            return Math.min(0xff, Math.max(0, basic) + piercing);
        }
        int maximum = Math.max(basic - armor, 0) + piercing;
        // 0x004182b0/0x00418370: half + async remainder in [0, half].
        int half = (maximum + 1) / 2;
        if (half <= 0) {
            return 0;
        }
        int rolled = half + world.battleNetRand() % (half + 1);
        return Math.min(0xff, rolled);
    }


    /** The projectile a unit fires, or null when it strikes directly. */
    MissileType missileFor(Unit attacker) {
        if (world.missileTypes == null || attacker.type() == null || !attacker.type().firesMissile()) {
            return null;
        }
        return world.missileTypes.get(attacker.type().missile());
    }


    /**
     * Puts a projectile in the air, aimed where the target stands now.
     *
     * <p>From the firer's own middle to the target's own middle, both measured
     * across the whole footprint. Both ends used to be the middle of the
     * top-left tile, which for anything larger than one square is a corner: an
     * arrow left a ballista's north-west corner and a catapult shot at a keep
     * landed on the corner of it rather than in the middle. That last part
     * mattered beyond looks, because the splash is measured from where the
     * shot lands.
     *
     * <p>The firer's position comes from its pixel coordinates rather than its
     * tile, so a unit part way through a step fires from where it is drawn
     * instead of from the square it is heading for.
     */
    Missile launch(Unit attacker, Unit target, MissileType type) {
        // BNE constructors aim at raw unit pixel (IX/IY) plus the native type
        // centre table (FUN_00450f20), not the settled map-tile centre and not
        // the ChonkCraft gameplay footprint. XOrc 4's zeppelin is 2x2 in ChonkCraft
        // but one tile in the projectile table, so the old centre (+32,+32)
        // aimed at 1248,160 and landed one cycle late; native +16,+16 lands
        // and stores damage at fixture cycle 20.
        int toX = target.pixelX() + target.residualX()
                + World.battleNetCentreOffset(target.type(), true);
        int toY = target.pixelY() + target.residualY()
                + World.battleNetCentreOffset(target.type(), false);
        // Used to aim back by one land Move opcode (3px) because this implementation
        // walked the new step on the commit cycle and led native for the
        // whole drain (XHuman 10: 2521,2745 vs native 2518,2742). Cold-commit
        // removed that lead -- peon/grunt pixels match native -- so the
        // compensation now aimed *behind* retail: cannon 99→grunt 100 remaining
        // 141 vs native-matching 138 under the old lead, one extra parabolic
        // flight step, and splash damage 6 vs 7. Do not restore the 3px aim
        // pull without re-measuring the drain phase.
        return world.spawn(new Missile(type, attacker, target,
                // GetMapPixelPosCenter reads upstream's raw IX/IY. This implementation
                // splits that displacement into the drawn offset and a
                // residual bank so idle wiggles cannot masquerade as a live
                // step; the muzzle must put the two halves back together.
                // levelx11o's destroyer fires with IX/IY 32,1, making a
                // five-cycle flight where the tile corner alone made six.
                attacker.pixelX() + attacker.residualX()
                        + World.battleNetCentreOffset(attacker.type(), true),
                attacker.pixelY() + attacker.residualY()
                        + World.battleNetCentreOffset(attacker.type(), false),
                toX, toY));
    }


    /**
     * Advances everything in the air and resolves what has landed.
     *
     * <p>Iterated over a copy: resolving an impact can spawn the type's
     * impact missile, and adding to a list while walking it is the kind of
     * fault that only shows up once an explosion has an explosion.
     */
    void stepMissiles() {
        discardInterruptedPlaceholders();
        if (world.missiles.isEmpty()) {
            return;
        }
        // Native walks the fixed projectile pool low slot to high
        // (0x00420520 capacity 200; free byte-53 bit 0). Ambient 0–2 never
        // free; long-lived rocks keep mid slots while later axes recycle
        // lower ones, so free splash runs after two live travelers on
        // XHuman 12@35 (seed 6888) without a traveler-count reorder.
        List<Missile> flying = new ArrayList<>(world.missiles);
        flying.sort((a, b) -> {
            int sa = a.battleNetPoolSlot();
            int sb = b.battleNetPoolSlot();
            if (sa < 0 && sb < 0) {
                return 0;
            }
            if (sa < 0) {
                return 1;
            }
            if (sb < 0) {
                return -1;
            }
            return Integer.compare(sa, sb);
        });
        // Per-missile motion RNG, step, and impact resolve. Parabolic motion
        // takes one or two async draws per FUN_00410260.
        List<Missile> finishedNow = new ArrayList<>();
        for (Missile missile : flying) {
            preparePersistentEffect(missile);
            Long started = world.battleNetProjectileStartCycles.get(missile);
            if (started == null
                    && world.battleNetPendingProjectileShots.containsValue(missile)) {
                // Mobile BNE weapon shots can exist in the list one call
                // before script.bin reaches opcode ten. They must not spend a
                // flight step before that constructor boundary runs. A spell
                // missile has no BNE attack-opcode handoff at all, however;
                // its absence from battleNetProjectileStartCycles is not a
                // reason to freeze it forever.
                continue;
            }
            if (started != null && world.cycle <= started) {
                // Construction cycle stores the muzzle position only.
                // Native 0x004101f0 first runs on the following update.
                continue;
            }
            if (started != null && !missile.battleNetPendingImpact()) {
                if (missile.battleNetSkipNextMotionDraw()) {
                    missile.setBattleNetSkipNextMotionDraw(false);
                } else if (missile.type().missileClass()
                        == MissileClass.PARABOLIC) {
                    int draws = missile.battleNetParabolicDrawsOnNextStep();
                    for (int draw = 0; draw < draws; draw++) {
                        world.battleNetRand();
                    }
                } else if (missile.type().missileClass()
                        == MissileClass.POINT_TO_POINT) {
                    world.battleNetRand();
                }
            }
            missile.step();
            if (missile.consumePeriodicHit()) {
                resolvePersistentPulse(missile);
            }
            if (missile.consumeHit()) {
                boolean returnsLife = missile.type().missileClass() == MissileClass.DEATH_COIL
                        && missile.source() != null && missile.source().isAlive()
                        && missile.target() != null && missile.target().isAlive();
                resolve(missile);
                if (returnsLife && missile.source().isAlive()) {
                    Unit source = missile.source();
                    source.setHitPoints(Math.min(source.type().hitPoints(),
                            source.hitPoints() + missile.damage()));
                }
            }
            if (missile.hasArrived()) {
                finishedNow.add(missile);
            }
        }
        world.missiles.removeAll(finishedNow);
        for (Missile missile : finishedNow) {
            recordProjectileFree(missile);
            world.battleNetProjectileStartCycles.remove(missile);
            world.freeBattleNetProjectileSlot(missile.battleNetPoolSlot());
            missile.setBattleNetPoolSlot(-1);
        }
        // Last, after anything an impact added, so the renderer never
        // sees a half-built cycle.
        world.missileSnapshot = List.copyOf(world.missiles);
    }

    /** World-aware setup for runes and the roaming Whirlwind. */
    private void preparePersistentEffect(Missile missile) {
        MissileClass kind = missile.type().missileClass();
        if (kind == MissileClass.LAND_MINE) {
            for (Unit unit : List.copyOf(world.units)) {
                if (!unit.isAlive() || unit.isDying() || !unit.isOnMap()
                        || unit.type().moveType() == UnitType.Movement.FLY
                        || unit == missile.source() && !missile.type().canHitOwner()) {
                    continue;
                }
                if (unit.distanceTo(missile.tileX(), missile.tileY()) == 0) {
                    missile.triggerImpact();
                    return;
                }
            }
        } else if (kind == MissileClass.WHIRLWIND
                && missile.timeToLive() > 0 && missile.timeToLive() % 100 == 0) {
            int tileX;
            int tileY;
            do {
                tileX = missile.tileX() + world.syncRand(5) - 2;
                tileY = missile.tileY() + world.syncRand(5) - 2;
            } while (!world.map.contains(tileX, tileY));
            missile.redirect(tileX * Unit.TILE_PIXELS + Unit.TILE_PIXELS / 2.0,
                    tileY * Unit.TILE_PIXELS + Unit.TILE_PIXELS / 2.0);
        }
    }

    /** Damage beat for Flame Shield's ring and the roaming Whirlwind. */
    private void resolvePersistentPulse(Missile missile) {
        Unit source = missile.source();
        if (source == null || !source.isAlive()) {
            return;
        }
        MissileClass kind = missile.type().missileClass();
        Unit protectedUnit = kind == MissileClass.FLAME_SHIELD ? missile.target() : null;
        int centreX = protectedUnit != null ? protectedUnit.tileX() : missile.tileX();
        int centreY = protectedUnit != null ? protectedUnit.tileY() : missile.tileY();
        int radius = Math.max(1, missile.type().range());
        for (Unit candidate : List.copyOf(world.units)) {
            if (!candidate.isAlive() || candidate.isDying() || !candidate.isOnMap()
                    || candidate == protectedUnit
                    || candidate == source && !missile.type().canHitOwner()
                    || candidate.distanceTo(centreX, centreY) > radius) {
                continue;
            }
            world.combat.applyDamage(source, candidate, 1, missile);
        }
    }


    /**
     * BNE fixed/splash impact resolution ({@code FUN_00410520} /
     * {@code FUN_00410680}).
     *
     * <p>Impact-tile cache first (center primary), then the rest of the 7x7
     * in y-outer/x-inner order. De-duplication is before the metric test.
     * Acceptance is {@code max(dx^2,dy^2) < 0x700} on pixel centers; above
     * {@code 0x1ff} the stored damage is quartered before armor. Each
     * accepted target draws {@code battleNetRand}. XHuman 2's northern ogre
     * must not take the first roll when the rock frees on the footman tile.
     */
    void resolveBattleNetSplash(Missile missile) {
        Unit source = missile.source();
        int stored = missile.damage();
        int impactX = (int) Math.round(missile.x());
        int impactY = (int) Math.round(missile.y());
        // Collect every candidate that survives the pixel metric, then hit in
        // native cache-grid order so the battleNetRand stream matches.
        record SplashHit(Unit unit, int metric) {}
        java.util.ArrayList<SplashHit> hits = new java.util.ArrayList<>();
        java.util.LinkedHashSet<Unit> seen = new java.util.LinkedHashSet<>();
        List<Unit> roster = new ArrayList<>(world.units.size() + world.pending.size());
        roster.addAll(world.units);
        roster.addAll(world.pending);
        for (Unit candidate : roster) {
            if (candidate == null || candidate == source || !candidate.isAlive()
                    || candidate.isDying() || !candidate.isOnMap()
                    || candidate.type() == null) {
                continue;
            }
            if (!seen.add(candidate)) {
                continue;
            }
            if (!world.targets.canTarget(source, candidate)) {
                continue;
            }
            // Native FUN_00410680: raw_pixel (IX/IY) + type centre offset.
            // Drawn offset and residual bank recombine into that raw pair, as
            // the projectile constructors do for aim points.
            int centerX = candidate.pixelX() + candidate.residualX()
                    + World.centreOffset(candidate.type(), true);
            int centerY = candidate.pixelY() + candidate.residualY()
                    + World.centreOffset(candidate.type(), false);
            int dx = impactX - centerX;
            int dy = impactY - centerY;
            int metric = Math.max(dx * dx, dy * dy);
            if (metric >= 0x700) {
                continue;
            }
            hits.add(new SplashHit(candidate, metric));
        }
        if (World.BNE_SPLASH_TRACE) {
            System.err.printf("JBNESPLASH cycle=%d impact=%d,%d tile=%d,%d stored=%d "
                            + "type=%s source=%d hits=%d%n",
                    world.cycle, impactX, impactY,
                    Math.floorDiv(impactX, World.TILE_SIZE),
                    Math.floorDiv(impactY, World.TILE_SIZE),
                    stored,
                    missile.type() == null ? "-" : missile.type().ident(),
                    source == null ? -1 : source.id(), hits.size());
            for (SplashHit probe : hits) {
                Unit u = probe.unit();
                int armor = world.upgrades(u.player()) != null
                        ? world.upgrades(u.player()).armor(u.type())
                        : u.type().armor();
                int maximum = stored;
                if (probe.metric() > 0x1ff) {
                    maximum >>= 2;
                }
                maximum -= armor;
                int bneCx = u.pixelX() + u.residualX()
                        + World.battleNetCentreOffset(u.type(), true);
                int bneCy = u.pixelY() + u.residualY()
                        + World.battleNetCentreOffset(u.type(), false);
                int bneMetric = Math.max(
                        (impactX - bneCx) * (impactX - bneCx),
                        (impactY - bneCy) * (impactY - bneCy));
                System.err.printf("JBNESPLASH-HIT cycle=%d unit=%d type=%s "
                                + "tile=%d,%d metric=%d bneMetric=%d armor=%d "
                                + "maxAfter=%d hp=%d%n",
                        world.cycle, u.id(), u.type().ident(),
                        u.tileX(), u.tileY(), probe.metric(), bneMetric,
                        armor, maximum, u.hitPoints());
            }
        }
        // Native FUN_00410520 walks the impact tile's cache first (center
        // primary), then the rest of the 7x7 in y-outer/x-inner order.
        // A pure ascending tileY sort used to hit XHuman 2's ogre at 61,66
        // before the footman on the impact tile at 60,68: with the same two
        // async rolls that yields footman 41 / ogre 8 instead of native
        // footman 57 / ogre 12 (stored 80, armor 2/4, outer quarter).
        int impactTileX = Math.floorDiv(impactX, World.TILE_SIZE);
        int impactTileY = Math.floorDiv(impactY, World.TILE_SIZE);
        hits.sort((a, b) -> {
            boolean aCenter = a.unit().tileX() == impactTileX
                    && a.unit().tileY() == impactTileY;
            boolean bCenter = b.unit().tileX() == impactTileX
                    && b.unit().tileY() == impactTileY;
            if (aCenter != bCenter) {
                return aCenter ? -1 : 1;
            }
            int ay = a.unit().tileY();
            int by = b.unit().tileY();
            if (ay != by) {
                return Integer.compare(ay, by);
            }
            int ax = a.unit().tileX();
            int bx = b.unit().tileX();
            if (ax != bx) {
                return Integer.compare(ax, bx);
            }
            if (a.metric() != b.metric()) {
                return Integer.compare(a.metric(), b.metric());
            }
            return Integer.compare(a.unit().id(), b.unit().id());
        });
        for (SplashHit hit : hits) {
            Unit candidate = hit.unit();
            // Native FUN_00410680 quarters only when metric > 0x1ff. There is
            // no separate bystander rule; a near secondary still takes the
            // full stored band. XHuman 10's second shell lands at 2512,2736
            // with unit 1500's live centre near 2537,2761 so metric 1024 is
            // outer by distance alone.
            int maximum = stored;
            if (hit.metric() > 0x1ff) {
                maximum >>= 2;
            }
            UpgradeState targetUpgrades = world.upgrades(candidate.player());
            int armor = targetUpgrades != null
                    ? targetUpgrades.armor(candidate.type())
                    : candidate.type().armor();
            maximum -= armor;
            if (maximum < 1) {
                continue;
            }
            if (candidate.type().indestructible()
                    || candidate.hasBuff(Unit.Buff.UNHOLY_ARMOR)) {
                continue;
            }
            int half = (maximum + 1) / 2;
            int damage = half + world.battleNetRand() % (half + 1);
            if (damage <= 0) {
                continue;
            }
            world.combat.noteAttacked(source, candidate);
            // Lethal splash keeps last living HP on the corpse (melee path
            // in BattleNetCombatSystem; XHuman 10 footman 1492 DYING@42).
            int before = candidate.hitPoints();
            if (before - damage <= 0) {
                // HitUnit help before death. Person melee Still brothers
                // answer a lethal splash (knight 1489); non-lethal hits must
                // not recruit (Human 13@21 REG).
                AiPlayer targetAi = world.ais.get(candidate.player());
                if (targetAi != null && source != null
                        && (candidate.type() == null
                                || !candidate.type().wall())) {
                    targetAi.helpMe(world, source, candidate);
                }
                if (source != null && (candidate.type() == null
                        || !candidate.type().wall())) {
                    world.battleNetSpatialHelpReactPlusOne(source, candidate);
                    world.battleNetPersonMeleeHelpOnSplash(source, candidate);
                }
                world.kill(candidate, source);
            } else {
                candidate.setHitPoints(before - damage);
                // Splash never calls applyDamage; OP0-damage bulk hold still
                // needs the marker (Human 13 knight 1490 catapult splash).
                world.combat.noteBattleNetAttackOp0Damage(candidate);
            }
        }
    }


    /**
     * Applies a landed missile's damage.
     *
     * <p>A single-target missile hurts what it hit. One with a range spreads,
     * and the falloff is upstream's: damage divided by the distance in tiles
     * times the type's splash factor, with the square struck taking it whole.
     * That is what makes a catapult worth building and worth keeping away from
     * your own army.
     */
    void resolve(Missile missile) {
        MissileType type = missile.type();
        Unit source = missile.source();
        if (type.missileClass()
                == net.chonkbase.chonkcraft.engine.missile.MissileClass.FIRE) {
            // A fire that has gone out has not landed on anything. Its source
            // is the building it was burning, and resolving it would have that
            // building hit itself the moment it was repaired.
            return;
        }
        // Both of these come before the ownerless early return below, which is
        // where upstream puts them (above the "no owner -
        // green-cross ..." guard). That ordering is the reason a crater is
        // still drawn where a sourceless explosion goes off.
        impactSound(missile);
        impactMissile(missile);
        if (source == null) {
            return;
        }
        // Retail BNE cannon/catapult splash: pixel Chebyshev metric, stored
        // damage as per-target maximum, battleNetRand per accepted target.
        // ChonkCraft tile falloff + damageFor treating stored 50 as a spell
        // override selected six units for 16 each on XHuman 10; native hits
        // four for 10/7/7/8. Flight/impact coordinate are already correct.
        if (type.splashes() && missile.damage() != 0
                && world.battleNetProjectileStartCycles.containsKey(missile)) {
            resolveBattleNetSplash(missile);
            return;
        }
        // Upstream's "if (!mtype.Range)": a shot with no range hits the unit it
        // was aimed at and nothing else. One with a range of one hits whatever
        // is standing where it landed, which is not the same question and is
        // the only thing a blizzard shard ever asks -- it is aimed at a patch
        // of ground and has no unit target at all.
        if (type.range() <= 0) {
            Unit target = missile.target();
            if (target != null && target.isAlive() && !target.isDying()) {
                world.combat.applyDamage(source, target, 1, missile);
            } else {
                // A shot with no range and nothing left to hit goes into the
                // scenery: MissileHit falls through to MissileHitsWall for the
                // square it landed on. This is how an arrow or an axe chips a
                // wall, and how attack-ground works for a weapon that does not
                // splash.
                world.hitWall(missile, missile.tileX(), missile.tileY(), 1);
            }
            return;
        }
        int centreX = missile.tileX();
        int centreY = missile.tileY();
        int radius = type.range();
        // Who the blast reaches, in the order upstream reaches them. It does
        // not walk the unit list: it calls {@code Select} over the blast box
        // and {@code Select} sweeps the
        // map square by square -- y outer, x inner -- taking each square's
        // {@code UnitCache} as it comes ({@code include/unit_find.h:240-272}),
        // so a unit is met at the first tile of its footprint the sweep
        // reaches.
        //
        // The order is not decoration. Every blow draws its own damage and
        // each is divided by the target's armour, so which draw a unit gets
        // depends on how many bodies the sweep passed first. On
        // {@code maps/demo/demo03} a destroyer's shell catches five units at
        // 8,3 on cycle 58: upstream deals 5, 11, 6, 7 and 7 going along the
        // rows, and this implementation, walking the units in the order they were
        // created, dealt the same five draws in a different order -- 9 to a
        // knight upstream hit for 5.
        int boxLeft = Math.max(0, centreX - (radius - 1));
        int boxTop = Math.max(0, centreY - (radius - 1));
        List<Unit> caught = new ArrayList<>();
        // Select reads the live tile caches. A unit spawned earlier in this
        // same UnitActions pass is already in those caches even though the
        // port defers adding it to the action table until the tick ends. In
        // levelx10h cycle 288 a footman's death creates a vision marker
        // before a catapult rock lands; upstream's blast selects the fresh
        // marker and spends its damage draw, while walking {@code units}
        // alone skipped it and shifted the damage of four living soldiers.
        List<Unit> selectable = new ArrayList<>(world.units.size() + world.pending.size());
        selectable.addAll(world.units);
        selectable.addAll(world.pending);
        for (Unit unit : selectable) {
            if (!unit.isAlive() || unit.isDying() || !unit.isOnMap()) {
                continue;
            }
            if (unit == source && !type.canHitOwner()) {
                continue;
            }
            // Measured to the footprint, as MissileHit does: upstream selects
            // everything whose tiles fall inside the blast box, so a four-by-
            // four Town Hall struck dead centre is nought squares away and
            // takes the blow whole. Measured to its top-left tile instead it
            // reported two or three and was skipped outright, which is why
            // catapults barely scratched large buildings.
            int distance = unit.distanceTo(centreX, centreY);
            if (distance >= radius) {
                continue;
            }
            // A rock cannot splash a gryphon the thrower could never have
            // aimed at. The filter is the firer's own CanTarget, not the
            // missile's, exactly as upstream writes it.
            if (!world.targets.canTarget(source, unit)) {
                continue;
            }
            if (!splashReaches(missile, unit)) {
                continue;
            }
            // Past those, splash does not ask whose side anyone is on, which
            // is the whole risk of the weapon.
            caught.add(unit);
        }
        caught.sort((left, right) -> {
            int leftX = Math.max(left.tileX(), boxLeft);
            int leftY = Math.max(left.tileY(), boxTop);
            int rightX = Math.max(right.tileX(), boxLeft);
            int rightY = Math.max(right.tileY(), boxTop);
            long a = (long) leftY * world.map.width() + leftX;
            long b = (long) rightY * world.map.width() + rightX;
            if (a != b) {
                return Long.compare(a, b);
            }
            // Select copies each tile's CMapField::UnitCache in its actual
            // insertion order. IDs are only creation order, and a moving
            // unit is removed from its old cache and appended to its new
            // one. levelx10h puts a dying grunt, its freshly spawned vision
            // marker and then a lower-numbered knight on 81,90. Sorting by ID
            // handed the knight the indestructible marker's damage draw and
            // cost it thirty-three extra hit points at cycle 120.
            List<Unit> cached = world.unitCache.get((int) a);
            int leftIndex = cached == null ? -1 : cached.indexOf(left);
            int rightIndex = cached == null ? -1 : cached.indexOf(right);
            if (leftIndex >= 0 && rightIndex >= 0 && leftIndex != rightIndex) {
                return Integer.compare(leftIndex, rightIndex);
            }
            return Integer.compare(left.id(), right.id());
        });
        for (Unit unit : caught) {
            world.combat.applyDamage(source, unit, type.falloffAt(unit.distanceTo(centreX, centreY)), missile);
        }
        // "Missile hits ground". The same box the units were
        // selected from -- upstream writes it as an offset of Range with a loop
        // that runs from one to Range*2-1, which is pos plus or minus Range-1 --
        // and every square of it that holds a wall takes the falloff a unit
        // standing there would.
        for (int dx = -(radius - 1); dx <= radius - 1; dx++) {
            for (int dy = -(radius - 1); dy <= radius - 1; dy++) {
                int distance = Math.max(Math.abs(dx), Math.abs(dy));
                world.hitWall(missile, centreX + dx, centreY + dy, type.falloffAt(distance));
            }
        }
    }


    /**
     * Plays the sound a shot makes where it lands.
     *
     * <p>{@code PlayMissileSound(*this, mtype.ImpactSound.Sound)}, the first
     * thing {@code Missile::MissileHit} does. Fifteen of the shipped missile
     * types name one -- {@code explosion} for a catapult, a ballista, a cannon
     * and both naval torpedoes, {@code bow hit} for an arrow and an axe,
     * {@code fireball hit} for dragon breath, a gryphon's hammer and a fireball
     * -- and the field was parsed and never read, so the only noise a battle
     * made was the attacker's own swing and the dying voice.
     *
     * <p>The event is hung on the unit nearest the impact so the interface has
     * somewhere to place it: the thing that was struck, or the firer when the
     * shot was aimed at a square.
     */
    void impactSound(Missile missile) {
        Unit heardAt = missile.target() != null ? missile.target() : missile.source();
        world.announceNamed(heardAt, missile.type().impactSound());
    }


    /**
     * Puts the crater, flash or fireball where a shot landed.
     *
     * <p>{@code Missile::MissileHit}. Eleven types
     * name one: a catapult rock and a ballista bolt leave
     * {@code missile-impact}, the three cannons leave a cannon-tower
     * explosion, and dragon breath, a gryphon's hammer, a fireball and a rune
     * all leave {@code missile-explosion}. The field was parsed and had no
     * readers, so every shot in the game simply vanished on arrival.
     *
     * <p>It is made at the impact point and goes nowhere -- these are all
     * {@code missile-class-stay}, which stands still and runs its animation
     * once. Upstream hands it the firer only when the impact missile itself
     * declares damage, and none in Warcraft II does; passing no source is what
     * keeps a picture a picture, because {@link #resolve} returns before its
     * splash loop when there is nobody to credit the damage to.
     */
    void impactMissile(Missile missile) {
        String ident = missile.type().impactMissile();
        if (ident == null || ident.isEmpty() || world.missileTypes == null) {
            return;
        }
        MissileType impact = world.missileTypes.get(ident);
        if (impact == null || impact.isNone()) {
            return;
        }
        Missile effect = world.spawn(new Missile(impact,
                impact.declaresDamage() ? missile.source() : null, null,
                missile.x(), missile.y(), missile.x(), missile.y()));
        recordProjectileCreate(effect);
        // The native fixed-pool pass is not a snapshot. If an impact takes a
        // slot above the projectile currently being resolved, the ascending
        // walk reaches and advances it later in this same cycle. If it reuses
        // a lower slot the cursor has already passed it, so its first action
        // waits for the next cycle. Human 13 proves both halves: slot 3 rock
        // creates impact slot 5 at fixture 35 (same-pass action, free@49),
        // while slot 4 rock creates impact slot 3 at 42 (free@57).
        if (effect.battleNetPoolSlot() > missile.battleNetPoolSlot()) {
            effect.step();
        }
    }


    /**
     * Whether {@code CorrectSphashDamage} lets this shot reach a bystander.
     *
     * <p>The flag confines a blast to things that move the way its own target
     * does, so a land explosion cannot catch aircraft overhead. A shot with no
     * unit target -- attack-ground -- is confined to the firer's own medium
     * instead. No missile Warcraft II ships sets the flag, so this is parity
     * rather than a behaviour change; it is read because a shot that ignores
     * it is a shot doing something its own definition forbids.
     */
    static boolean splashReaches(Missile missile, Unit bystander) {
        if (!missile.type().correctSplashDamage()) {
            return true;
        }
        Unit aimedAt = missile.target();
        Unit reference = aimedAt != null ? aimedAt : missile.source();
        return reference != null && World.sameMedium(reference.type(), bystander.type());
    }
}
