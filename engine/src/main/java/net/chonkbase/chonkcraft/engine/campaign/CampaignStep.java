package net.chonkbase.chonkcraft.engine.campaign;

import java.util.List;

/**
 * One entry in a campaign's running order.
 *
 * <p>Sealed from the legacy {@code campaign_steps} tables into
 * {@link CampaignCatalog}, which is where the whole of Warcraft II's
 * presentation order now lives:
 *
 * <pre>
 *   campaign_steps = {
 *     CreatePictureStep("campaigns/human/interface/Act_I_-_Shores_of_Lordareon.png",
 *                       "sounds/human/act.wav", _("Act I"), _("The Shores of Lordareon")),
 *     CreateMapStep("campaigns/human/level01h.smp"),
 *     ...
 *   }
 * </pre>
 *
 * <p>Upstream's constructors returned closures that drove the menu system.
 * The migration differential recorded those calls in order; the runtime now
 * consumes the resulting native declarations directly.
 *
 * @param kind      what the step does
 * @param path      the map, picture or video it names
 * @param sound     a sound to play with it, or null
 * @param title     the act title, or null
 * @param subtitle  the act subtitle, or null
 * @param textPath  for a victory step, the text to show
 * @param voices    for a victory step, the voice-over files
 */
public record CampaignStep(Kind kind, String path, String sound, String title,
        String subtitle, String textPath, List<String> voices) {

    /** What a step does when the campaign reaches it. */
    public enum Kind {
        /** An act title card. */
        PICTURE,
        /** A mission: the point of the whole thing. */
        MAP,
        /** A cutscene. */
        VIDEO,
        /** The ending, once every mission is behind you. */
        VICTORY
    }

    public CampaignStep {
        voices = voices == null ? List.of() : List.copyOf(voices);
    }

    public static CampaignStep picture(String path, String sound, String title, String subtitle) {
        return new CampaignStep(Kind.PICTURE, path, sound, title, subtitle, null, List.of());
    }

    public static CampaignStep map(String path) {
        return new CampaignStep(Kind.MAP, path, null, null, null, null, List.of());
    }

    public static CampaignStep video(String path) {
        return new CampaignStep(Kind.VIDEO, path, null, null, null, null, List.of());
    }

    public static CampaignStep victory(String path, String textPath, List<String> voices) {
        return new CampaignStep(Kind.VICTORY, path, null, null, null, textPath, voices);
    }

    /**
     * The archive path of the map this step runs.
     *
     * <p>The scripts name a {@code .smp} file, which is what {@code wartool}
     * would have written out. The implementation reads the PUD from the archive instead,
     * and the two differ only by that suffix.
     */
    public String mapArchivePath() {
        if (kind != Kind.MAP || path == null) {
            return null;
        }
        return path.endsWith(".smp") ? path.substring(0, path.length() - 4) : path;
    }
}
