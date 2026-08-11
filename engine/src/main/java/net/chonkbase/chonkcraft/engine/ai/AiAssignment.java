package net.chonkbase.chonkcraft.engine.ai;

/**
 * Which retail AI profile a computer slot runs and its historical map label.
 *
 * <p>{@link #requested} is the old ChonkCraft-compatible label decoded from the
 * map or mission wrapper. It is retained as provenance only. {@link #attached}
 * names the authenticated {@code ai.bin} profile that actually executes.
 *
 * @param player    the slot
 * @param requested the historical map/mission label, or null
 * @param attached  the retail {@code ai.bin} profile that actually runs
 * @param origin    where the request came from
 */
public record AiAssignment(int player, String requested, String attached, Origin origin) {

    /** Where a slot's personality was chosen. */
    public enum Origin {
        /** The PUD's {@code AIPL} byte, mapped through the name table. */
        MAP,
        /** A {@code SetAiType} call in the mission script, which overrides the map. */
        MISSION_SCRIPT,
    }

    @Override
    public String toString() {
        return "player " + player + " " + requested + " (" + origin + ") -> " + attached;
    }
}
