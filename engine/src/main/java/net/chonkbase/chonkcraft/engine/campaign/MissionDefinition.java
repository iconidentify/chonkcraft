package net.chonkbase.chonkcraft.engine.campaign;

import java.util.List;
import java.util.Map;
import net.chonkbase.chonkcraft.engine.ai.AiAssignment;
import net.chonkbase.chonkcraft.engine.trigger.TriggerSystem;

/** One versioned, interpreter-free campaign mission declaration. */
public record MissionDefinition(
        String path,
        String background,
        List<String> voices,
        Map<String, String> allowFlags,
        Map<Integer, AiLabel> aiLabels,
        List<TriggerSystem.ProgramSpec> triggers) {

    /** Historical mission label retained only for diagnostics. */
    public record AiLabel(String requested, AiAssignment.Origin origin) {}

    public MissionDefinition {
        voices = List.copyOf(voices);
        allowFlags = Map.copyOf(allowFlags);
        aiLabels = Map.copyOf(aiLabels);
        triggers = List.copyOf(triggers);
    }

    /** Labels the old wrapper assigned, without executing its personality. */
    public Map<Integer, String> requestedAi() {
        java.util.Map<Integer, String> labels = new java.util.LinkedHashMap<>();
        aiLabels.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> labels.put(entry.getKey(), entry.getValue().requested()));
        return Map.copyOf(labels);
    }

    /** Where each historical label came from, retained for exact diagnostics. */
    public Map<Integer, AiAssignment.Origin> aiOrigins() {
        java.util.Map<Integer, AiAssignment.Origin> origins = new java.util.LinkedHashMap<>();
        aiLabels.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> origins.put(entry.getKey(), entry.getValue().origin()));
        return Map.copyOf(origins);
    }
}
