package net.chonkbase.chonkcraft.engine;

import net.chonkbase.chonkcraft.engine.unit.Unit;

/**
 * Banks points for kills, so the top bar's score slot has a figure to show.
 *
 * <p>Implements {@code HitUnit_IncreaseScoreForKill}: every unit type carries a
 * {@code Points} value and the side that kills it banks that value. The layout
 * scripts declare a slot for the figure -- {@code UI.Resources[ScoreCost]} --
 * and until now nothing filled it in.
 *
 * <p>Upstream raises two counts alongside the score in the same three lines,
 * {@code TotalKills} and {@code TotalRazings}, and this kept only the points.
 * They are cheap to keep and impossible to reconstruct afterwards: a game that
 * starts counting when something finally displays them has nothing to show for
 * the mission that has already been played.
 *
 * <p>Driven from the game loop rather than from inside the simulation, and
 * that is the one place this departs from upstream. Upstream credits the unit
 * that struck the last blow, because it is standing right there when the blow
 * lands; nothing in this implementation records who killed what, so a death is credited
 * to every active player that counted the dead unit as an enemy. In a two
 * sided game -- which every campaign mission and every skirmish is -- the two
 * rules give the same answer. In a three sided one an ally is credited for a
 * kill it did not make.
 *
 * <p>A unit is counted when it starts dying rather than when its corpse is
 * cleared away, because that is the moment it stops being a unit, and it is
 * counted once: the identifiers already banked are remembered.
 */
public final class ScoreKeeper {

    private final World world;

    /** The units already paid for, by identifier. */
    private final java.util.Set<Integer> counted = new java.util.HashSet<>();

    public ScoreKeeper(World world) {
        this.world = world;
    }

    /**
     * Looks over the field and banks anything newly dead.
     *
     * <p>Called once per simulation advance. Calling it more often is
     * harmless -- a unit is only ever counted once -- and calling it less
     * often loses nothing either, because a corpse lingers for many cycles
     * before it is taken off the list.
     */
    public void update() {
        for (Unit unit : world.unitsSnapshot()) {
            if (!isDead(unit) || !counted.add(unit.id())) {
                continue;
            }
            // Whoever struck the last blow, and nobody else. This used to
            // pay every player who counted the dead unit as an enemy, which
            // is identical in a two-sided game -- all 52 campaign missions --
            // and wrong the moment there are three. HitUnit_IncreaseScoreForKill
            // credits one player: the attacker.
            //
            // Minus one is a death nobody caused: a building cancelled, a
            // summon timing out, cargo going down with its transport. Upstream
            // has the same null-attacker path and pays nobody for it.
            int killer = unit.killedBy();
            if (killer < 0) {
                continue;
            }
            Player scorer = world.player(killer);
            if (scorer == null || !scorer.isActive()
                    || !world.isEnemyPlayer(killer, unit.player())) {
                continue;
            }
            int points = unit.type() == null ? 0 : unit.type().points();
            if (points > 0) {
                scorer.addScore(points);
            }
            // Counted even when the kill was worth nothing. Upstream raises
            // the tally outside its own points line, so a sheep or a seal --
            // neither of which carries any Points -- still shows up in the
            // number of things you destroyed. Skipping them because they are
            // worth no score would be reading the two figures as one.
            scorer.addKill(unit.type() != null && unit.type().building());
        }
    }

    /** How many deaths have been paid for, for a test to check against. */
    public int counted() {
        return counted.size();
    }

    private static boolean isDead(Unit unit) {
        return unit.order() == Unit.Order.DYING || unit.hitPoints() <= 0;
    }
}
