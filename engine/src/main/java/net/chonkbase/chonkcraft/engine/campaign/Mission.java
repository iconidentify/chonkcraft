package net.chonkbase.chonkcraft.engine.campaign;

import java.util.List;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.trigger.TriggerSystem;
import net.chonkbase.chonkcraft.engine.upgrade.AllowState;

/**
 * A campaign mission, ready to play.
 *
 * <p>A mission is two halves that live in different places. The map is a PUD
 * inside {@code maindat.war}; the rules are a {@code .sms} script shipped with
 * ChonkCraft, holding the briefing, the victory and defeat conditions, and the
 * list of what this mission lets the player build. Neither half is playable
 * without the other, which is why they are loaded together.
 *
 * @param source    the map as it came out of the archive, which the renderer
 *                  and the player setup both still need
 * @param world     the world, populated from the map
 * @param triggers  the victory and defeat conditions, already armed
 * @param dependencies the technology prerequisites declared in this mission's interpreter
 * @param allowed   what the mission permits
 * @param title     the mission title from the script, or null
 * @param objectives the objectives text from the script, or null
 * @param briefing  the briefing prose, read from the archive
 * @param background the picture the briefing is read off, as the script names
 *                  it: one of ten illustrated pages, not the menu's scroll
 * @param voices    the briefing voice-over files the script names
 * @param ai        the personality each computer slot ended up running. Most
 *                  of these missions write their own opponent, and it is
 *                  worth being able to see whether the one they wrote is the
 *                  one that is playing
 */
public record Mission(net.chonkbase.chonkcraft.data.map.PudMap source, World world,
        TriggerSystem triggers,
        net.chonkbase.chonkcraft.engine.upgrade.DependencyRules dependencies,
        AllowState allowed,
        String title, String objectives, String briefing, String background,
        List<String> voices,
        List<net.chonkbase.chonkcraft.engine.ai.AiAssignment> ai) {

    public Mission {
        voices = voices == null ? List.of() : List.copyOf(voices);
        ai = ai == null ? List.of() : List.copyOf(ai);
    }

    /** Runs one cycle of the world and its triggers. */
    public void tick() {
        // Triggers first: the game loop handles them before the units act
        // so a trigger's orders -- level08h's
        // opening diplomacy -- are in force for the same cycle's thinking.
        triggers.tick();
        world.tick();
    }

    /** Whether the mission has been won, lost, or is still running. */
    public TriggerSystem.Outcome outcome() {
        return triggers.outcome();
    }
}
