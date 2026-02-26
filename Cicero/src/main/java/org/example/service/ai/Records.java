package org.example.service.ai;
import java.util.List;

public class Records {
    /**
     * L'objet exact que l'Agent Analyste DOIT nous retourner.
     */
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
    public record MatchAnalysisResult(
            List<AnalystAdjustment> adjustments
    ) {}
}
