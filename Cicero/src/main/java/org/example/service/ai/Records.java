package org.example.service.ai;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

public class Records {
    /**
     * L'objet exact que l'Agent Analyste DOIT nous retourner.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AnalystAdjustment(
            String champion,
            String role,
            int math_score,

            String timeline_audit,
            String stat_padding_check,
            String external_context_used,

            int adjusted_score,
            String analyst_reasoning
    ) {}

    /**
     * L'objet complet qui contiendra la liste des 10 joueurs analysés.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MatchAnalysisResult(
            List<AnalystAdjustment> adjustments
    ) {}
}
