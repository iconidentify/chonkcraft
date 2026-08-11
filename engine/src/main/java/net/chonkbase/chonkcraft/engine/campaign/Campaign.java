package net.chonkbase.chonkcraft.engine.campaign;

import java.util.ArrayList;
import java.util.List;

/**
 * One campaign, as its script declares it.
 *
 * <p>Warcraft II ships four: the human and orc campaigns of Tides of Darkness
 * and the two of Beyond the Dark Portal. Each is a flat running order of title
 * cards, missions, cutscenes and an ending.
 *
 * @param name  the campaign's identifier, such as {@code human}
 * @param steps its running order
 */
public record Campaign(String name, List<CampaignStep> steps) {

    public Campaign {
        steps = List.copyOf(steps);
    }

    /** Only the missions, which is what a player counts as progress. */
    public List<CampaignStep> missions() {
        List<CampaignStep> missions = new ArrayList<>();
        for (CampaignStep step : steps) {
            if (step.kind() == CampaignStep.Kind.MAP) {
                missions.add(step);
            }
        }
        return missions;
    }

    /**
     * The cutscene and title card that come before a mission, in order.
     *
     * <p>A campaign is a flat sequence, and a mission's introduction is
     * whatever non-mission steps sit between it and the mission before. Act
     * four of the human campaign is a title card and then a cutscene, both
     * belonging to the mission that follows.
     *
     * @param number the mission, counting from one
     */
    public List<CampaignStep> introducing(int number) {
        List<CampaignStep> missions = missions();
        if (number < 1 || number > missions.size()) {
            return List.of();
        }
        CampaignStep target = missions.get(number - 1);
        int at = steps.indexOf(target);
        List<CampaignStep> before = new ArrayList<>();
        for (int i = at - 1; i >= 0; i--) {
            if (steps.get(i).kind() == CampaignStep.Kind.MAP) {
                break;
            }
            before.add(steps.get(i));
        }
        java.util.Collections.reverse(before);
        return before;
    }

    /**
     * Everything after the last mission, in order.
     *
     * <p>The counterpart of {@link #introducing}, and the reason it was needed:
     * that method gathers what comes before a mission, so a step sitting after
     * the final map is unreachable through it by construction. Every campaign
     * has two such steps -- a closing cutscene and the ending itself -- and
     * they were read out of the scripts, kept, and never asked for.
     */
    public List<CampaignStep> ending() {
        int last = -1;
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).kind() == CampaignStep.Kind.MAP) {
                last = i;
            }
        }
        return last < 0 ? List.of() : List.copyOf(steps.subList(last + 1, steps.size()));
    }

    /**
     * The step to run at a given position, or null once the campaign is over.
     *
     * <p>Position counts every step, not only the missions, because the title
     * cards and cutscenes are part of the sequence and a saved campaign has to
     * come back to the right one.
     */
    public CampaignStep step(int position) {
        return position >= 0 && position < steps.size() ? steps.get(position) : null;
    }
}
