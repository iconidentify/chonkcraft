package net.chonkbase.chonkcraft.engine.campaign;

import java.util.ArrayList;
import java.util.List;

/** The four shipped campaign running orders, without evaluating retired scripting language. */
public final class CampaignCatalog {
    private CampaignCatalog() {}

    /** Reconstructs the immutable GPL ChonkCraft-derived migration declarations. */
    public static List<Campaign> generated() {
        return List.of(human(), orc(), humanExpansion(), orcExpansion());
    }

    private static Campaign human() {
        List<CampaignStep> s = new ArrayList<>();
        picture(s, "campaigns/human/interface/Act_I_-_Shores_of_Lordareon.png", "sounds/human/act.wav", "Act I", "The Shores of Lordareon");
        maps(s, "campaigns/human/level", "h.smp", 1, 4);
        picture(s, "campaigns/human/interface/Act_II_-_Khaz_Modan.png", "sounds/human/act.wav", "Act II", "Khaz Modan");
        s.add(CampaignStep.video("videos/human-1.ogv")); maps(s, "campaigns/human/level", "h.smp", 5, 7);
        picture(s, "campaigns/human/interface/Act_III_-_The_Northlands.png", "sounds/human/act.wav", "Act III", "The Northlands");
        s.add(CampaignStep.video("videos/human-2.ogv")); maps(s, "campaigns/human/level", "h.smp", 8, 11);
        picture(s, "campaigns/human/interface/Act_IV_-_Return_to_Azeroth.png", "sounds/human/act.wav", "Act IV", "Return to Azeroth");
        s.add(CampaignStep.video("videos/human-3.ogv")); maps(s, "campaigns/human/level", "h.smp", 12, 14);
        s.add(CampaignStep.video("videos/human-4.ogv"));
        s.add(CampaignStep.victory("graphics/ui/human/The_End.png", "campaigns/human/victory.txt", List.of("campaigns/human/victory.wav")));
        return new Campaign("human", s);
    }

    private static Campaign orc() {
        List<CampaignStep> s = new ArrayList<>();
        picture(s, "campaigns/orc/interface/Act_I_-_Seas_of_Blood.png", "sounds/orc/act.wav", "Act I", "Seas of Blood");
        maps(s, "campaigns/orc/level", "o.smp", 1, 4);
        picture(s, "campaigns/orc/interface/Act_II_-_Khaz_Modan.png", "sounds/orc/act.wav", "Act II", "Khaz Modan");
        s.add(CampaignStep.video("videos/orc-1.ogv")); maps(s, "campaigns/orc/level", "o.smp", 5, 7);
        picture(s, "campaigns/orc/interface/Act_III_-_Quel'Thalas.png", "sounds/orc/act.wav", "Act III", "Quel'Thalas");
        s.add(CampaignStep.video("videos/orc-2.ogv")); maps(s, "campaigns/orc/level", "o.smp", 8, 11);
        picture(s, "campaigns/orc/interface/Act_IV_-_Tides_of_Darkness.png", "sounds/orc/act.wav", "Act IV", "Tides of Darkness");
        s.add(CampaignStep.video("videos/orc-3.ogv")); maps(s, "campaigns/orc/level", "o.smp", 12, 14);
        s.add(CampaignStep.video("videos/orc-4.ogv"));
        s.add(CampaignStep.victory("graphics/ui/orc/Smashing_of_Lordaeron_scroll.png", "campaigns/orc/victory.txt", List.of("campaigns/orc/victory.wav")));
        return new Campaign("orc", s);
    }

    private static Campaign humanExpansion() {
        List<CampaignStep> s = new ArrayList<>(); s.add(CampaignStep.video("videos/exp-1.ogv"));
        picture(s, "campaigns/human-exp/interface/Act_I_-_A_Time_for_Heroes.png", "sounds/human/act.wav", "Act I", "A Time for Heroes"); maps(s, "campaigns/human-exp/levelx", "h.smp", 1, 3);
        picture(s, "campaigns/human-exp/interface/Act_II_-_Draenor,_the_Red_World.png", "sounds/human/act.wav", "Act II", "Draenor, The Red World"); maps(s, "campaigns/human-exp/levelx", "h.smp", 4, 6);
        picture(s, "campaigns/human-exp/interface/Act_III_-_War_in_the_Shadows.png", "sounds/human/act.wav", "Act III", "War in the Shadows"); maps(s, "campaigns/human-exp/levelx", "h.smp", 7, 9);
        picture(s, "campaigns/human-exp/interface/Act_IV_-_The_Measure_of_Valor.png", "sounds/human/act.wav", "Act IV", "The Measure of Valor"); maps(s, "campaigns/human-exp/levelx", "h.smp", 10, 12);
        s.add(CampaignStep.video("videos/human-exp-2.ogv"));
        s.add(CampaignStep.victory("graphics/ui/human/The_End.png", "campaigns/human-exp/victory.txt", List.of("campaigns/human-exp/victory-1.wav", "campaigns/human-exp/victory-2.wav")));
        return new Campaign("human-exp", s);
    }

    private static Campaign orcExpansion() {
        List<CampaignStep> s = new ArrayList<>(); s.add(CampaignStep.video("videos/exp-1.ogv"));
        picture(s, "campaigns/orc-exp/interface/Act_I_-_Draenor,_the_Red_World.png", "sounds/orc/act.wav", "Act I", "Draenor, the Red World"); maps(s, "campaigns/orc-exp/levelx", "o.smp", 1, 3);
        picture(s, "campaigns/orc-exp/interface/Act_II_-_The_Burning_of_Azeroth.png", "sounds/orc/act.wav", "Act II", "The Burning of Azeroth"); maps(s, "campaigns/orc-exp/levelx", "o.smp", 4, 6);
        picture(s, "campaigns/orc-exp/interface/Act_III_-_The_Great_Sea.png", "sounds/orc/act.wav", "Act III", "The Great Sea"); maps(s, "campaigns/orc-exp/levelx", "o.smp", 7, 9);
        picture(s, "campaigns/orc-exp/interface/Act_IV_-_Prelude_to_New_Worlds.png", "sounds/orc/act.wav", "Act IV", "Prelude to New Worlds"); maps(s, "campaigns/orc-exp/levelx", "o.smp", 10, 12);
        s.add(CampaignStep.video("videos/orc-exp-2.ogv"));
        s.add(CampaignStep.victory("graphics/ui/orc/Smashing_of_Lordaeron_scroll.png", "campaigns/orc-exp/victory.txt", List.of("campaigns/orc-exp/victory-1.wav", "campaigns/orc-exp/victory-2.wav", "campaigns/orc-exp/victory-3.wav")));
        return new Campaign("orc-exp", s);
    }

    private static void picture(List<CampaignStep> s, String path, String sound, String title, String subtitle) { s.add(CampaignStep.picture(path, sound, title, subtitle)); }
    private static void maps(List<CampaignStep> s, String prefix, String suffix, int first, int last) {
        for (int n = first; n <= last; n++) s.add(CampaignStep.map(prefix + String.format("%02d", n) + suffix));
    }
}
